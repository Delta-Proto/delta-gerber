package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillOperation;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.*;

/**
 * Renders multiple Gerber and drill documents into a single multi-layer SVG.
 *
 * This approach creates a single SVG with all layers sharing:
 * - A common viewBox (calculated from global bounding box)
 * - A shared defs section for apertures
 * - Individual layer groups that can be toggled via display attribute
 *
 * Structure:
 * <pre>
 * &lt;svg viewBox="..."&gt;
 *   &lt;defs&gt;...shared apertures...&lt;/defs&gt;
 *   &lt;g id="viewport" transform="scale(1,-1)"&gt;
 *     &lt;g class="layer" id="file1.GTL" display="inline"&gt;...&lt;/g&gt;
 *     &lt;g class="layer" id="file2.GBL" display="inline"&gt;...&lt;/g&gt;
 *   &lt;/g&gt;
 * &lt;/svg&gt;
 * </pre>
 */
public class MultiLayerSVGRenderer {

    /** Which side of the board to render for realistic/thumbnail output. */
    public enum Side { TOP, BOTTOM }

    private double margin = 0.5;
    private boolean flipY = true;
    private SvgOptions svgOptions = SvgOptions.exact();

    /**
     * A layer to be rendered, containing either a Gerber or Drill document.
     */
    public static class Layer {
        private final String name;
        private final GerberDocument gerberDoc;
        private final DrillDocument drillDoc;
        private String color = null;
        private double opacity = 0.75;
        private boolean visible = true;
        private LayerType layerType = LayerType.OTHER;

        public Layer(String name, GerberDocument doc) {
            this.name = name;
            this.gerberDoc = doc;
            this.drillDoc = null;
        }

        public Layer(String name, DrillDocument doc) {
            this.name = name;
            this.gerberDoc = null;
            this.drillDoc = doc;
        }

        public String getName() { return name; }
        public GerberDocument getGerberDoc() { return gerberDoc; }
        public DrillDocument getDrillDoc() { return drillDoc; }
        public boolean isGerber() { return gerberDoc != null; }
        public boolean isDrill() { return drillDoc != null; }

        public Layer setColor(String color) {
            this.color = color;
            return this;
        }

        public Layer setOpacity(double opacity) {
            this.opacity = opacity;
            return this;
        }

        public Layer setVisible(boolean visible) {
            this.visible = visible;
            return this;
        }

        public Layer setLayerType(LayerType layerType) {
            this.layerType = layerType;
            return this;
        }

        public String getColor() { return color; }
        public double getOpacity() { return opacity; }
        public boolean isVisible() { return visible; }
        public LayerType getLayerType() { return layerType; }

        public BoundingBox getBoundingBox() {
            if (gerberDoc != null) {
                return gerberDoc.getBoundingBox();
            } else if (drillDoc != null) {
                return drillDoc.getBoundingBox();
            }
            return new BoundingBox();
        }
    }

    public MultiLayerSVGRenderer() {
    }

    public MultiLayerSVGRenderer setMargin(double margin) {
        this.margin = margin;
        return this;
    }

    public MultiLayerSVGRenderer setFlipY(boolean flipY) {
        this.flipY = flipY;
        return this;
    }

    public MultiLayerSVGRenderer setSvgOptions(SvgOptions options) {
        this.svgOptions = options;
        return this;
    }

    /**
     * Render multiple layers into a single SVG document.
     */
    public String render(List<Layer> layers) {
        if (layers == null || layers.isEmpty()) {
            return createEmptySvg();
        }

        // Calculate global bounding box across all layers
        BoundingBox globalBounds = new BoundingBox();
        for (Layer layer : layers) {
            BoundingBox layerBounds = layer.getBoundingBox();
            if (layerBounds.isValid()) {
                globalBounds.extend(layerBounds);
            }
        }

        if (!globalBounds.isValid()) {
            return createEmptySvg();
        }

        // Add margin
        double minX = globalBounds.getMinX() - margin;
        double minY = globalBounds.getMinY() - margin;
        double width = globalBounds.getWidth() + 2 * margin;
        double height = globalBounds.getHeight() + 2 * margin;

        StringBuilder svg = new StringBuilder();

        // SVG header with shared viewBox
        svg.append(String.format(Locale.US,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "viewBox=\"%.6f %.6f %.6f %.6f\" " +
            "preserveAspectRatio=\"xMidYMid meet\" " +
            "stroke-linecap=\"round\" stroke-linejoin=\"round\" " +
            "fill-rule=\"nonzero\">\n",
            minX, minY, width, height));

        // Collect all apertures from all Gerber layers with unique prefixes
        // Use "currentColor" so apertures pick up the layer group's color property
        svg.append("<defs>\n");

        // Mask base rect for clear polarity masks
        String maskRect = PolarityMaskHelper.createMaskRect(minX, minY, width, height, 1);

        int layerIndex = 0;
        // Pre-compute polarity groups per layer (needed for both mask defs and rendering)
        List<List<PolarityMaskHelper.PolarityGroup>> allLayerGroups = new ArrayList<>();

        for (Layer layer : layers) {
            if (layer.isGerber() && layer.getGerberDoc() != null) {
                String aperturePrefix = "L" + layerIndex + "_ap";
                // Aperture defs don't include fill — fill is set on <use> elements
                svgOptions.setDarkColor("currentColor").setClearColor("currentColor").setFlipY(flipY);
                for (Aperture aperture : layer.getGerberDoc().getApertures().values()) {
                    String def = aperture.toSvgDef(aperturePrefix + aperture.getDCode(), svgOptions);
                    svg.append("  ").append(def).append("\n");
                }

                // Group objects by polarity and generate mask defs
                List<PolarityMaskHelper.PolarityGroup> groups =
                    PolarityMaskHelper.groupByPolarity(layer.getGerberDoc().getObjects());
                allLayerGroups.add(groups);

                // Generate masks for clear polarity groups (black = hidden in mask)
                String maskPrefix = "L" + layerIndex + "_cm";
                SvgOptions maskOptions = svgOptions.copy();
                maskOptions.setApertureIdPrefix(aperturePrefix);
                maskOptions.setDarkColor("black").setClearColor("black");
                PolarityMaskHelper.generateMaskDefs(svg, groups, maskPrefix, maskRect, maskOptions);
            } else {
                allLayerGroups.add(Collections.emptyList());
            }
            layerIndex++;
        }
        svg.append("</defs>\n");

        // Viewport group with Y-flip transform and stroke-width="0" to prevent inherited strokes
        if (flipY) {
            svg.append(String.format(Locale.US,
                "<g id=\"viewport\" transform=\"translate(0, %.6f) scale(1,-1)\" stroke-width=\"0\">\n",
                minY + height + minY));
        } else {
            svg.append("<g id=\"viewport\" stroke-width=\"0\">\n");
        }

        // Render each layer as a group
        layerIndex = 0;
        for (Layer layer : layers) {
            String layerId = sanitizeId(layer.getName());
            String display = layer.isVisible() ? "inline" : "none";
            String fillColor = sanitizeColor(layer.getColor());

            svg.append(String.format(Locale.US,
                "  <g class=\"layer\" id=\"%s\" display=\"%s\" " +
                "color=\"%s\" fill=\"currentColor\" stroke=\"none\" stroke-width=\"0\" opacity=\"%.2f\">\n",
                layerId, display, fillColor, layer.getOpacity()));

            // Render layer content
            if (layer.isGerber()) {
                String aperturePrefix = "L" + layerIndex + "_ap";
                String maskPrefix = "L" + layerIndex + "_cm";
                List<PolarityMaskHelper.PolarityGroup> groups = allLayerGroups.get(layerIndex);

                SvgOptions layerOptions = svgOptions.copy();
                layerOptions.setApertureIdPrefix(aperturePrefix);
                layerOptions.setDarkColor("currentColor").setClearColor("currentColor");

                PolarityMaskHelper.renderWithMasks(svg, groups, maskPrefix, layerOptions);
            } else if (layer.isDrill()) {
                renderDrillContent(svg, layer.getDrillDoc());
            }

            svg.append("  </g>\n");
            layerIndex++;
        }

        svg.append("</g>\n");
        svg.append("</svg>");

        return svg.toString();
    }

    // Outline-chain tolerance (mm). Altium/other EDA tools sometimes emit
    // straight-edge endpoints that don't exactly meet the adjacent arc's
    // tangent point — observed gaps up to ~50 µm. 0.1 mm is still well below
    // typical PCB outline feature sizes (drills, slots, tabs are ≥0.3 mm).
    private static final double OUTLINE_CHAIN_TOLERANCE_MM = 0.1;

    // A subpath that spans the overall bounds is treated as an outer panel frame (and
    // dropped) only when another subpath reaches at least this fraction of its area —
    // the actual board nested inside the frame. Below it, the spanning subpath is the
    // board itself and its smaller neighbours are holes/slots, so it is kept.
    private static final double FRAME_BOARD_MIN_FRACTION = 0.5;

    // Coincidence tolerance for collapsing duplicate (re-emitted) profile segments.
    // Far tighter than the chain tolerance so genuinely distinct short segments survive.
    private static final double DEDUPE_TOLERANCE_MM = 0.001;

    // When a set has no dedicated outline layer, the board edge is derived from the union
    // of the copper layers. Copper keeps roughly this clearance from the routed edge, so
    // the derived silhouette is outset by this much to approximate the true board edge.
    private static final double DERIVED_OUTLINE_OUTSET_MM = 0.2;

    // Morphological close radius for the derived silhouette: bridges copper-free seams
    // between separately-poured zones so the board comes out as a single piece.
    private static final double DERIVED_OUTLINE_CLOSE_MM = 0.6;

    // Default realistic PCB colors (matches typical PCB viewer rendering)
    private static final String FR4_COLOR = "#666666";           // Dark gray substrate
    private static final String COPPER_COLOR = "#cccccc";         // Silver/gray copper under soldermask
    private static final String COPPER_FINISH_COLOR = "#cc9933";  // Gold HASL/ENIG finish on exposed pads
    private static final String SOLDERMASK_GREEN = "#004200";     // Dark green soldermask
    private static final String SILKSCREEN_WHITE = "#ffffff";     // White silkscreen
    private static final double SOLDERMASK_DEFAULT_OPACITY = 0.75;

    /**
     * Render a realistic PCB view where layers are stacked as they appear on a real board.
     * <p>
     * Requires an OUTLINE layer to define the board boundary. The soldermask layer is
     * inverted: the board outline defines where the mask is present (green), and the
     * soldermask gerber objects define the openings where copper is exposed.
     * <p>
     * Layer stack (bottom to top):
     * <ol>
     *   <li>FR4 substrate (dark gray, clipped to board outline)</li>
     *   <li>Copper traces/pads (silver/gray, visible through semi-transparent soldermask)</li>
     *   <li>Copper finish (gold HASL/ENIG, only at soldermask openings — exposed pads)</li>
     *   <li>Soldermask (green, semi-transparent with holes) containing:
     *     <ul>
     *       <li>Silkscreen (white text/markings, only where soldermask is present)</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * Colors can be overridden via {@link Layer#setColor(String)}. Soldermask opacity
     * can be set via {@link Layer#setOpacity(double)} (default 0.75).
     *
     * @throws IllegalArgumentException if no OUTLINE layer is provided
     */
    public String renderRealistic(List<Layer> layers) {
        if (layers == null || layers.isEmpty()) {
            return createEmptySvg();
        }

        // Categorize layers by type
        Layer outlineLayer = null;
        List<Layer> copperLayers = new ArrayList<>();
        List<Layer> soldermaskLayers = new ArrayList<>();
        List<Layer> silkscreenLayers = new ArrayList<>();
        List<Layer> drillLayers = new ArrayList<>();

        for (Layer layer : layers) {
            switch (layer.getLayerType()) {
                case OUTLINE:
                    outlineLayer = layer;
                    break;
                case COPPER_TOP:
                case COPPER_BOTTOM:
                    copperLayers.add(layer);
                    break;
                case SOLDERMASK_TOP:
                case SOLDERMASK_BOTTOM:
                    soldermaskLayers.add(layer);
                    break;
                case SILKSCREEN_TOP:
                case SILKSCREEN_BOTTOM:
                    silkscreenLayers.add(layer);
                    break;
                case DRILL:
                case DRILL_PLATED:
                case DRILL_NON_PLATED:
                    drillLayers.add(layer);
                    break;
                default:
                    break;
            }
        }

        boolean haveOutlineLayer = outlineLayer != null && outlineLayer.isGerber();
        if (!haveOutlineLayer && copperLayers.isEmpty()) {
            throw new IllegalArgumentException(
                "Realistic rendering requires an OUTLINE layer, or copper layers to derive "
                + "the board edge from");
        }

        // Use outline bounding box for viewBox (content is clipped to outline anyway);
        // with no outline layer, fall back to the union of all layers' bounds.
        BoundingBox globalBounds = haveOutlineLayer
            ? outlineLayer.getBoundingBox() : new BoundingBox();
        if (!globalBounds.isValid()) {
            globalBounds = new BoundingBox();
            for (Layer layer : layers) {
                BoundingBox layerBounds = layer.getBoundingBox();
                if (layerBounds.isValid()) {
                    globalBounds.extend(layerBounds);
                }
            }
        }
        if (!globalBounds.isValid()) {
            return createEmptySvg();
        }

        double minX = globalBounds.getMinX() - margin;
        double minY = globalBounds.getMinY() - margin;
        double width = globalBounds.getWidth() + 2 * margin;
        double height = globalBounds.getHeight() + 2 * margin;

        StringBuilder svg = new StringBuilder();

        // SVG header
        svg.append(String.format(Locale.US,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "viewBox=\"%.6f %.6f %.6f %.6f\" " +
            "preserveAspectRatio=\"xMidYMid meet\" " +
            "stroke-linecap=\"round\" stroke-linejoin=\"round\" " +
            "fill-rule=\"nonzero\">\n",
            minX, minY, width, height));

        svg.append("<defs>\n");

        // Extract board outline path for clipPath and soldermask mask base. With a real
        // profile layer, chain it; otherwise derive the board edge from the copper.
        SvgOptions outlineOptions = svgOptions.copy().setFlipY(flipY);
        String outlinePath;
        if (haveOutlineLayer) {
            outlinePath = extractOutlinePath(outlineLayer.getGerberDoc(), outlineOptions);
        } else {
            List<GerberDocument> silhouetteDocs = new ArrayList<>();
            for (Layer l : copperLayers) {
                if (l.isGerber() && l.getGerberDoc() != null) silhouetteDocs.add(l.getGerberDoc());
            }
            for (Layer l : soldermaskLayers) {
                if (l.isGerber() && l.getGerberDoc() != null) silhouetteDocs.add(l.getGerberDoc());
            }
            outlinePath = OutlineDeriver.deriveOutlineSvgPath(
                silhouetteDocs, DERIVED_OUTLINE_CLOSE_MM, DERIVED_OUTLINE_OUTSET_MM);
        }
        boolean hasOutlinePath = outlinePath != null && !outlinePath.isBlank();

        if (hasOutlinePath) {
            svg.append("  <clipPath id=\"board-outline\">\n");
            svg.append(String.format("    <path d=\"%s\" clip-rule=\"evenodd\"/>\n", outlinePath));
            svg.append("  </clipPath>\n");
        }

        // Oversized rect covering the full viewbox (used for soldermask fill etc.)
        String fullRectAttrs = String.format(Locale.US,
            "x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\"",
            minX - 1, minY - 1, width + 2, height + 2);

        // Mask base rect for polarity masks
        String maskRect = PolarityMaskHelper.createMaskRect(minX, minY, width, height, 1);

        // Assign unique aperture prefixes and collect polarity groups for all gerber layers
        int layerIndex = 0;
        Map<Layer, String> aperturePrefixes = new LinkedHashMap<>();
        Map<Layer, Integer> layerIndexMap = new LinkedHashMap<>();
        Map<Layer, List<PolarityMaskHelper.PolarityGroup>> polarityGroups = new LinkedHashMap<>();

        List<Layer> gerberLayers = new ArrayList<>();
        gerberLayers.addAll(copperLayers);
        gerberLayers.addAll(soldermaskLayers);
        gerberLayers.addAll(silkscreenLayers);
        // Gerber X2 drill files (e.g. KiCad's *-PTH-drl.gbr) are Gerber-backed but
        // classified as DRILL layers. They still need aperture defs so the mech-mask
        // can reference their flashes via <use>.
        for (Layer drill : drillLayers) {
            if (drill.isGerber()) gerberLayers.add(drill);
        }

        for (Layer layer : gerberLayers) {
            if (!layer.isGerber()) continue;

            String apPrefix = "L" + layerIndex + "_ap";
            aperturePrefixes.put(layer, apPrefix);
            layerIndexMap.put(layer, layerIndex);

            // Aperture definitions
            SvgOptions apOptions = svgOptions.copy()
                .setDarkColor("currentColor").setClearColor("currentColor").setFlipY(flipY);
            for (Aperture aperture : layer.getGerberDoc().getApertures().values()) {
                String def = aperture.toSvgDef(apPrefix + aperture.getDCode(), apOptions);
                svg.append("  ").append(def).append("\n");
            }

            // Polarity groups
            List<PolarityMaskHelper.PolarityGroup> groups =
                PolarityMaskHelper.groupByPolarity(layer.getGerberDoc().getObjects());
            polarityGroups.put(layer, groups);

            layerIndex++;
        }

        // Polarity mask definitions for copper and silkscreen layers
        for (Layer layer : copperLayers) {
            generatePolarityMaskDefs(svg, layer, aperturePrefixes, layerIndexMap,
                polarityGroups, maskRect);
        }
        for (Layer layer : silkscreenLayers) {
            generatePolarityMaskDefs(svg, layer, aperturePrefixes, layerIndexMap,
                polarityGroups, maskRect);
        }

        // Soldermask masks (two per soldermask layer):
        // 1. sm-mask: soldermask presence (white = mask present, black = openings)
        // 2. cf-mask: copper finish (inverse — white = openings where pads are exposed)
        for (Layer layer : soldermaskLayers) {
            boolean isTop = layer.getLayerType() == LayerType.SOLDERMASK_TOP;
            String smMaskId = isTop ? "sm-top-mask" : "sm-bottom-mask";
            String cfMaskId = isTop ? "cf-top-mask" : "cf-bottom-mask";
            String apPrefix = aperturePrefixes.get(layer);

            SvgOptions smMaskOptions = svgOptions.copy()
                .setApertureIdPrefix(apPrefix).setFlipY(flipY);

            // sm-mask: board outline white, soldermask objects black = where mask IS present
            svg.append(String.format("  <mask id=\"%s\">\n", smMaskId));
            if (hasOutlinePath) {
                svg.append(String.format("    <path d=\"%s\" fill=\"white\" fill-rule=\"evenodd\"/>\n", outlinePath));
            } else {
                // No outline path — use full viewbox rect as mask base
                svg.append(String.format("    <rect %s fill=\"white\"/>\n", fullRectAttrs));
            }
            smMaskOptions.setDarkColor("black").setClearColor("white");
            for (GraphicsObject obj : layer.getGerberDoc().getObjects()) {
                String objSvg = obj.toSvg(smMaskOptions);
                if (objSvg != null && !objSvg.isEmpty()) {
                    svg.append("    ").append(objSvg).append("\n");
                }
            }
            svg.append("  </mask>\n");

            // cf-mask: black background, soldermask objects white = where pads are EXPOSED
            svg.append(String.format("  <mask id=\"%s\">\n", cfMaskId));
            svg.append(String.format("    <rect %s fill=\"black\"/>\n", fullRectAttrs));
            smMaskOptions.setDarkColor("white").setClearColor("black");
            for (GraphicsObject obj : layer.getGerberDoc().getObjects()) {
                String objSvg = obj.toSvg(smMaskOptions);
                if (objSvg != null && !objSvg.isEmpty()) {
                    svg.append("    ").append(objSvg).append("\n");
                }
            }
            svg.append("  </mask>\n");
        }

        // Drill hole mask (mech-mask): white background + drill holes in black
        // Applied to the outermost board group so holes punch through ALL layers
        // stroke-width="0" prevents the default 1-unit stroke from enlarging the holes
        boolean hasDrills = !drillLayers.isEmpty();
        if (hasDrills) {
            svg.append("  <mask id=\"mech-mask\">\n");
            svg.append(String.format("    <rect %s fill=\"white\"/>\n", fullRectAttrs));
            for (Layer layer : drillLayers) {
                if (layer.isDrill()) {
                    svg.append("    <g fill=\"black\" color=\"black\" stroke=\"none\" stroke-width=\"0\">\n");
                    renderDrillContent(svg, layer.getDrillDoc());
                    svg.append("    </g>\n");
                } else if (layer.isGerber()) {
                    // Gerber X2 drill layer — render its flashes as solid black into the mask.
                    svg.append("    <g fill=\"black\" color=\"black\" stroke=\"none\" stroke-width=\"0\">\n");
                    String apPrefix = aperturePrefixes.get(layer);
                    SvgOptions maskOpt = svgOptions.copy()
                        .setApertureIdPrefix(apPrefix)
                        .setDarkColor("black").setClearColor("black")
                        .setFlipY(flipY);
                    for (GraphicsObject obj : layer.getGerberDoc().getObjects()) {
                        String objSvg = obj.toSvg(maskOpt);
                        if (objSvg != null && !objSvg.isEmpty()) {
                            svg.append("      ").append(objSvg).append("\n");
                        }
                    }
                    svg.append("    </g>\n");
                }
            }
            svg.append("  </mask>\n");
        }

        svg.append("</defs>\n");

        // Viewport with Y-flip
        if (flipY) {
            svg.append(String.format(Locale.US,
                "<g id=\"viewport\" transform=\"translate(0, %.6f) scale(1,-1)\" stroke-width=\"0\">\n",
                minY + height + minY));
        } else {
            svg.append("<g id=\"viewport\" stroke-width=\"0\">\n");
        }

        // --- Layer stack (matches typical PCB viewer rendering) ---
        // All content is clipped to board outline (if available), with drill holes punching through
        String clipAttr = hasOutlinePath ? " clip-path=\"url(#board-outline)\"" : "";

        if (hasDrills) {
            svg.append(String.format("  <g mask=\"url(#mech-mask)\"%s>\n", clipAttr));
        } else {
            svg.append(String.format("  <g%s>\n", clipAttr));
        }

        // 1. FR4 substrate background
        svg.append(String.format("    <rect %s fill=\"%s\"/>\n", fullRectAttrs, FR4_COLOR));

        // 2. Copper layer(s) — gray/silver, visible through semi-transparent soldermask
        // Always use realistic colors (layer color is for the "all layers" overlay view)
        for (Layer layer : copperLayers) {
            String copperColor = COPPER_COLOR;
            String apPrefix = aperturePrefixes.get(layer);
            String maskPrefix = "L" + layerIndexMap.get(layer) + "_cm";
            List<PolarityMaskHelper.PolarityGroup> groups = polarityGroups.get(layer);

            svg.append(String.format(
                "    <g fill=\"%s\" color=\"%s\" stroke=\"none\" stroke-width=\"0\">\n",
                copperColor, copperColor));

            SvgOptions layerOptions = svgOptions.copy()
                .setApertureIdPrefix(apPrefix)
                .setDarkColor("currentColor").setClearColor("currentColor").setFlipY(flipY);
            PolarityMaskHelper.renderWithMasks(svg, groups, maskPrefix, layerOptions);

            svg.append("    </g>\n");
        }

        // 3. Copper finish — gold HASL/ENIG, same copper data but only at soldermask openings
        // Paired: each copper layer gets a cf-mask from its corresponding soldermask
        for (Layer copperLayer : copperLayers) {
            // Find matching soldermask for this copper side
            boolean isTop = copperLayer.getLayerType() == LayerType.COPPER_TOP;
            String cfMaskId = isTop ? "cf-top-mask" : "cf-bottom-mask";

            // Only render if the corresponding soldermask exists
            boolean hasMask = soldermaskLayers.stream().anyMatch(sm ->
                (isTop && sm.getLayerType() == LayerType.SOLDERMASK_TOP) ||
                (!isTop && sm.getLayerType() == LayerType.SOLDERMASK_BOTTOM));
            if (!hasMask) continue;

            String apPrefix = aperturePrefixes.get(copperLayer);
            String maskPrefix = "L" + layerIndexMap.get(copperLayer) + "_cm";
            List<PolarityMaskHelper.PolarityGroup> groups = polarityGroups.get(copperLayer);

            svg.append(String.format(
                "    <g fill=\"%s\" color=\"%s\" stroke=\"none\" stroke-width=\"0\" " +
                "mask=\"url(#%s)\">\n",
                COPPER_FINISH_COLOR, COPPER_FINISH_COLOR, cfMaskId));

            SvgOptions layerOptions = svgOptions.copy()
                .setApertureIdPrefix(apPrefix)
                .setDarkColor("currentColor").setClearColor("currentColor").setFlipY(flipY);
            PolarityMaskHelper.renderWithMasks(svg, groups, maskPrefix, layerOptions);

            svg.append("    </g>\n");
        }

        // 4. Soldermask (semi-transparent green with holes) + silkscreen inside
        // Silkscreen is nested inside the soldermask mask group so it only appears
        // where the soldermask is present (not over exposed pads)
        for (Layer smLayer : soldermaskLayers) {
            boolean isTop = smLayer.getLayerType() == LayerType.SOLDERMASK_TOP;
            String smMaskId = isTop ? "sm-top-mask" : "sm-bottom-mask";
            String smColor = SOLDERMASK_GREEN;
            // Always use the realistic default opacity for soldermask — the layer's
            // opacity is for the "all layers" overlay view, not the realistic view
            double smOpacity = SOLDERMASK_DEFAULT_OPACITY;

            svg.append(String.format("    <g mask=\"url(#%s)\">\n", smMaskId));

            // Soldermask fill
            svg.append(String.format(Locale.US,
                "      <rect %s fill=\"%s\" opacity=\"%.2f\"/>\n",
                fullRectAttrs, smColor, smOpacity));

            // Silkscreen inside soldermask (only renders where mask is present)
            for (Layer ssLayer : silkscreenLayers) {
                boolean ssIsTop = ssLayer.getLayerType() == LayerType.SILKSCREEN_TOP;
                if (ssIsTop != isTop) continue; // Match top/bottom sides

                String ssColor = SILKSCREEN_WHITE;
                String apPrefix = aperturePrefixes.get(ssLayer);
                String maskPrefix = "L" + layerIndexMap.get(ssLayer) + "_cm";
                List<PolarityMaskHelper.PolarityGroup> groups = polarityGroups.get(ssLayer);

                svg.append(String.format(
                    "      <g fill=\"%s\" color=\"%s\" stroke=\"none\" stroke-width=\"0\">\n",
                    ssColor, ssColor));

                SvgOptions layerOptions = svgOptions.copy()
                    .setApertureIdPrefix(apPrefix)
                    .setDarkColor(ssColor).setClearColor(ssColor).setFlipY(flipY);
                PolarityMaskHelper.renderWithMasks(svg, groups, maskPrefix, layerOptions);

                svg.append("      </g>\n");
            }

            svg.append("    </g>\n");
        }

        svg.append("  </g>\n"); // close board-outline clip + mech-mask group

        svg.append("</g>\n");
        svg.append("</svg>");

        return svg.toString();
    }

    /**
     * Generate polarity mask definitions for a layer using PolarityMaskHelper.
     */
    private void generatePolarityMaskDefs(StringBuilder svg, Layer layer,
            Map<Layer, String> aperturePrefixes, Map<Layer, Integer> layerIndexMap,
            Map<Layer, List<PolarityMaskHelper.PolarityGroup>> polarityGroups,
            String maskRect) {
        if (!layer.isGerber()) return;
        String apPrefix = aperturePrefixes.get(layer);
        String maskPrefix = "L" + layerIndexMap.get(layer) + "_cm";
        List<PolarityMaskHelper.PolarityGroup> groups = polarityGroups.get(layer);

        SvgOptions maskOptions = svgOptions.copy()
            .setApertureIdPrefix(apPrefix)
            .setDarkColor("black").setClearColor("black").setFlipY(flipY);
        PolarityMaskHelper.generateMaskDefs(svg, groups, maskPrefix, maskRect, maskOptions);
    }

    /**
     * Extract a filled SVG path from a board outline Gerber document.
     * <p>
     * Prefers Region objects (already filled paths). Falls back to chaining
     * Draw/Arc endpoints into one or more closed subpaths.
     * <p>
     * Some EDA tools (notably Altium) emit board outlines as D02/D01 pairs with
     * segments written in mixed directions — end-to-start linear chaining breaks
     * on the reversed ones and fragments the path into single-segment subpaths.
     * We chain bidirectionally: each new segment can match the running head on
     * either endpoint, reversing the segment's direction when its end matches.
     * <p>
     * Matching uses nearest-neighbor within {@link #OUTLINE_CHAIN_TOLERANCE_MM}
     * — Altium sometimes emits straight-edge endpoints that don't exactly meet
     * the tangent point of the adjacent corner arc (observed gaps up to ~50 µm).
     * The tolerance is well below typical PCB feature sizes so it can't fuse
     * distinct outline features together.
     */
    private String extractOutlinePath(GerberDocument outlineDoc, SvgOptions options) {
        List<GraphicsObject> objects = outlineDoc.getObjects();

        // Region contours (G36/G37) are already closed filled paths. Collect each
        // contour as a standalone subpath. Two layouts occur in the wild:
        //   1. The board profile is expressed *entirely* as regions (outer contour
        //      plus inner hole contours). With no stroked profile present these
        //      regions ARE the outline.
        //   2. The board profile is stroked (D02/D01 draws/arcs) and the regions
        //      are cutouts/holes punched inside it (e.g. Altium emits internal
        //      rounded-rectangle openings this way).
        // We keep the regions separate from the stroked chain and emit the combined
        // path under the evenodd fill rule (set on the clip path and mask base), so
        // regions inside the stroked outline subtract as holes rather than being the
        // only thing drawn. Returning regions alone (the previous behaviour) made the
        // board clip to just the holes — inverting the realistic view.
        List<String> regionSubpaths = new ArrayList<>();
        for (GraphicsObject obj : objects) {
            if (obj instanceof Region) {
                Region region = (Region) obj;
                for (Contour contour : region.getContours()) {
                    regionSubpaths.add(contour.toSvgPath(options));
                }
            }
        }

        List<Segment> segments = new ArrayList<>();
        for (GraphicsObject obj : objects) {
            if (obj instanceof Draw) {
                Draw d = (Draw) obj;
                segments.add(Segment.draw(d.getStartX(), d.getStartY(),
                    d.getEndX(), d.getEndY()));
            } else if (obj instanceof Arc) {
                Arc a = (Arc) obj;
                segments.add(Segment.arc(a.getStartX(), a.getStartY(),
                    a.getEndX(), a.getEndY(), a.getCenterX(), a.getCenterY(),
                    a.getRadius(), a.isClockwise()));
            }
        }
        if (segments.isEmpty()) {
            // No stroked profile — the regions (if any) are the entire outline.
            return String.join(" ", regionSubpaths).trim();
        }

        double toleranceSq = OUTLINE_CHAIN_TOLERANCE_MM * OUTLINE_CHAIN_TOLERANCE_MM;

        // Some tools (notably Altium) emit the profile twice — identical, overlapping
        // segments. Under the evenodd clip rule a doubled loop cancels itself and the
        // board disappears, so collapse exact duplicate segments first. This uses a
        // tight coincidence tolerance (not the chain tolerance): duplicates are bit-for-
        // bit re-emitted coordinates, and a loose tolerance would wrongly fuse distinct
        // sub-tolerance segments (mouse-bite teeth, arc-approx polylines).
        double dedupeTolSq = DEDUPE_TOLERANCE_MM * DEDUPE_TOLERANCE_MM;
        segments = dedupeSegments(segments, dedupeTolSq);

        // Overall bounding box of all (deduped) segments — used below to identify the
        // outer panel frame rectangle. Panels (e.g. flex-PCB production panels) carry an
        // outer rectangular frame in the outline layer in addition to the PCB outline;
        // under evenodd it would turn the board interior into a ring, so we detect it by
        // its bounding box matching the overall bounds and exclude it.
        double allMinX = Double.MAX_VALUE, allMinY = Double.MAX_VALUE;
        double allMaxX = -Double.MAX_VALUE, allMaxY = -Double.MAX_VALUE;
        for (Segment s : segments) {
            allMinX = Math.min(allMinX, Math.min(s.startX, s.endX));
            allMinY = Math.min(allMinY, Math.min(s.startY, s.endY));
            allMaxX = Math.max(allMaxX, Math.max(s.startX, s.endX));
            allMaxY = Math.max(allMaxY, Math.max(s.startY, s.endY));
        }

        // Outline / mechanical layers frequently carry more than the board edge —
        // stroked text ("Do not forget the cut-out"), dimension lines, internal slots.
        // Group the segments into connected components (shared endpoints or T-junctions)
        // and chain each component into a single subpath. Chaining a whole component at
        // once bridges small gaps in the edge (some tools omit a short closing edge)
        // instead of shattering the loop into overlapping force-closed pieces; the
        // component split then lets us keep the board and drop the decorative noise.
        List<List<Segment>> components = groupConnectedSegments(segments, toleranceSq);

        List<String> subpathList = new ArrayList<>();
        List<double[]> subpathBoundsList = new ArrayList<>();  // [minX, minY, maxX, maxY]
        List<Boolean> subpathHasArcList = new ArrayList<>();
        for (List<Segment> component : components) {
            chainComponent(component, options, toleranceSq,
                subpathList, subpathBoundsList, subpathHasArcList);
        }

        // Drop the outer panel frame: a non-arc subpath whose bounding box equals the
        // overall bounds AND that encloses another comparably large subpath — the real
        // board nested inside the frame. Keeping it would, under evenodd, turn the board
        // into a ring. The size test is what separates a true frame (board fills most of
        // it) from an ordinary board whose own edge defines the overall bounds and whose
        // other subpaths are merely small holes/slots — that board must be kept.
        double bbTol = OUTLINE_CHAIN_TOLERANCE_MM;
        // Keep every remaining component. The board edge, real internal cut-outs, and
        // any small stroked text or dimension marks the layer carries all go into the
        // clip. Stray marks render as harmless little filled features; keeping them is
        // more robust than guessing what is decorative, and it can never drop a genuine
        // but small board section (e.g. a second board on a panel).
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < subpathList.size(); i++) {
            double[] sb = subpathBoundsList.get(i);
            boolean spansOverall = !subpathHasArcList.get(i)
                    && Math.abs(sb[0] - allMinX) <= bbTol
                    && Math.abs(sb[1] - allMinY) <= bbTol
                    && Math.abs(sb[2] - allMaxX) <= bbTol
                    && Math.abs(sb[3] - allMaxY) <= bbTol;
            if (subpathList.size() > 1 && spansOverall && hasComparableInnerSubpath(
                    subpathBoundsList, i, FRAME_BOARD_MIN_FRACTION)) {
                continue; // outer panel frame rectangle — skip
            }
            if (path.length() > 0) path.append(" ");
            path.append(subpathList.get(i));
        }

        // Prepend region subpaths (holes/cutouts inside the stroked profile). Under
        // the evenodd fill rule of the clip path / mask base they punch through the
        // outline interior instead of being drawn as the only shape.
        StringBuilder combined = new StringBuilder();
        for (String regionSubpath : regionSubpaths) {
            if (combined.length() > 0) combined.append(" ");
            combined.append(regionSubpath);
        }
        if (path.length() > 0) {
            if (combined.length() > 0) combined.append(" ");
            combined.append(path);
        }

        return combined.toString().trim();
    }

    private static double distSq(double ax, double ay, double bx, double by) {
        double dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy;
    }

    /**
     * T-intersection detection: find the first unused linear segment whose
     * interior (strictly between its endpoints) contains the given head point
     * within {@link #OUTLINE_CHAIN_TOLERANCE_MM}. Returns {@code null} if none.
     */
    private static Segment findTIntersection(List<Segment> segments,
                                             double hx, double hy,
                                             double toleranceSq) {
        for (Segment s : segments) {
            if (s.used || s.isArc) continue;
            double dx = s.endX - s.startX;
            double dy = s.endY - s.startY;
            double lenSq = dx * dx + dy * dy;
            if (lenSq < 1e-12) continue;
            // Parameter t of the head's projection onto the segment line (0=start, 1=end)
            double t = ((hx - s.startX) * dx + (hy - s.startY) * dy) / lenSq;
            // Must be strictly interior — not near either endpoint
            double eps = Math.sqrt(toleranceSq / lenSq);
            if (t <= eps || t >= 1.0 - eps) continue;
            // Perpendicular distance from head to the segment line
            double px = s.startX + t * dx - hx;
            double py = s.startY + t * dy - hy;
            if (px * px + py * py <= toleranceSq) return s;
        }
        return null;
    }

    /** True if some subpath other than {@code frameIdx} has at least {@code fraction}
     * of the frame candidate's bounding-box area — i.e. a real board nested in a frame. */
    private static boolean hasComparableInnerSubpath(List<double[]> bounds, int frameIdx,
                                                     double fraction) {
        double[] f = bounds.get(frameIdx);
        double frameArea = (f[2] - f[0]) * (f[3] - f[1]);
        if (frameArea <= 0) return false;
        for (int j = 0; j < bounds.size(); j++) {
            if (j == frameIdx) continue;
            double[] b = bounds.get(j);
            double area = (b[2] - b[0]) * (b[3] - b[1]);
            if (area >= fraction * frameArea) return true;
        }
        return false;
    }

    /**
     * Remove exact duplicate segments (same endpoints in either direction, same
     * arc geometry) within {@link #OUTLINE_CHAIN_TOLERANCE_MM}. Some EDA tools emit
     * the board profile twice; left in place, a doubled loop cancels itself under the
     * evenodd clip rule and the board renders empty.
     */
    private static List<Segment> dedupeSegments(List<Segment> segments, double toleranceSq) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : segments) {
            boolean dup = false;
            for (Segment k : out) {
                if (s.isArc != k.isArc) continue;
                boolean sameDir = distSq(s.startX, s.startY, k.startX, k.startY) <= toleranceSq
                               && distSq(s.endX, s.endY, k.endX, k.endY) <= toleranceSq;
                boolean revDir = distSq(s.startX, s.startY, k.endX, k.endY) <= toleranceSq
                              && distSq(s.endX, s.endY, k.startX, k.startY) <= toleranceSq;
                if (sameDir || revDir) { dup = true; break; }
            }
            if (!dup) out.add(s);
        }
        return out;
    }

    /**
     * Group segments into connected components: two segments share a component when an
     * endpoint of one is within {@link #OUTLINE_CHAIN_TOLERANCE_MM} of an endpoint or
     * the interior (T-junction) of the other. The board edge, each text glyph, and each
     * floating mark each form their own component.
     */
    private static List<List<Segment>> groupConnectedSegments(List<Segment> segments,
                                                              double toleranceSq) {
        int n = segments.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (segmentsConnected(segments.get(i), segments.get(j), toleranceSq)) {
                    int ri = find(parent, i), rj = find(parent, j);
                    if (ri != rj) parent[ri] = rj;
                }
            }
        }
        Map<Integer, List<Segment>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(segments.get(i));
        }
        return new ArrayList<>(groups.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static boolean segmentsConnected(Segment a, Segment b, double toleranceSq) {
        if (distSq(a.startX, a.startY, b.startX, b.startY) <= toleranceSq
         || distSq(a.startX, a.startY, b.endX, b.endY) <= toleranceSq
         || distSq(a.endX, a.endY, b.startX, b.startY) <= toleranceSq
         || distSq(a.endX, a.endY, b.endX, b.endY) <= toleranceSq) {
            return true;
        }
        // Endpoint-on-interior (T-junctions) — arcs are treated as their chord here.
        return pointOnSegment(b, a.startX, a.startY, toleranceSq)
            || pointOnSegment(b, a.endX, a.endY, toleranceSq)
            || pointOnSegment(a, b.startX, b.startY, toleranceSq)
            || pointOnSegment(a, b.endX, b.endY, toleranceSq);
    }

    private static boolean pointOnSegment(Segment s, double px, double py, double toleranceSq) {
        double dx = s.endX - s.startX, dy = s.endY - s.startY;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1e-12) return false;
        double t = ((px - s.startX) * dx + (py - s.startY) * dy) / lenSq;
        if (t < 0 || t > 1) return false;
        double qx = s.startX + t * dx - px, qy = s.startY + t * dy - py;
        return qx * qx + qy * qy <= toleranceSq;
    }

    /**
     * Chain one connected component into closed subpath(s), appending each to the
     * given lists. Mirrors the greedy bidirectional chaining with T-junction handling,
     * but when the head gets stuck with segments still unused it bridges straight to
     * the nearest one (rather than abandoning the loop), so a board whose closing edge
     * is missing still yields one whole loop instead of overlapping fragments.
     */
    private void chainComponent(List<Segment> pool, SvgOptions options, double toleranceSq,
                                List<String> subpathList, List<double[]> subpathBoundsList,
                                List<Boolean> subpathHasArcList) {
        // Index-based so we can safely append near-half splits to the pool mid-iteration.
        for (int si = 0; si < pool.size(); si++) {
            Segment seed = pool.get(si);
            if (seed.used) continue;
            seed.used = true;

            StringBuilder subpath = new StringBuilder();
            double loopStartX = seed.startX;
            double loopStartY = seed.startY;
            subpath.append(String.format(Locale.US, "M %.6f %.6f", loopStartX, loopStartY));
            appendSegment(subpath, seed, false, options);
            double headX = seed.endX;
            double headY = seed.endY;

            double spMinX = Math.min(seed.startX, seed.endX);
            double spMinY = Math.min(seed.startY, seed.endY);
            double spMaxX = Math.max(seed.startX, seed.endX);
            double spMaxY = Math.max(seed.startY, seed.endY);
            boolean spHasArc = seed.isArc;

            // Chain greedily: at each step pick the best unused segment whose endpoint
            // meets the head within tolerance. Close only once no such continuation
            // exists that is at least as close as the loop start — this prevents two
            // failure modes:
            //   1. A short seed (< tolerance length) short-circuits loop closure on
            //      iteration 0 — e.g. mouse-bite teeth, V-score rails, arc-approx
            //      polylines. Must leave the tolerance ball before closure counts.
            //   2. A chain built from short segments hits a point one segment before
            //      the true close that happens to lie ≤ tolerance from the start —
            //      we must prefer extending if an unused segment continues the chain
            //      at least as well as snapping back to the start would.
            boolean leftToleranceBall = false;
            while (true) {
                Segment next = null;
                boolean reverse = false;
                double bestSq = toleranceSq;
                for (Segment s : pool) {
                    if (s.used) continue;
                    double d1 = distSq(s.startX, s.startY, headX, headY);
                    if (d1 < bestSq) {
                        bestSq = d1; next = s; reverse = false;
                    }
                    double d2 = distSq(s.endX, s.endY, headX, headY);
                    if (d2 < bestSq) {
                        bestSq = d2; next = s; reverse = true;
                    }
                }
                double headDistSq = distSq(headX, headY, loopStartX, loopStartY);
                if (leftToleranceBall && headDistSq <= toleranceSq
                        && (next == null || bestSq >= headDistSq)) {
                    break; // loop closed — no better continuation than snapping back
                }
                if (next == null) {
                    // T-intersection fallback: the head may lie on the interior of a
                    // collinear segment (not at its endpoints). This occurs in flex-PCB
                    // or panel outlines where adjacent rigid/flex boundary segments
                    // overlap — e.g. a top-tab left edge (y=79–91) and a flex-body
                    // left edge (y=70–84) share the y=79–84 range. Endpoint matching
                    // fails because neither endpoint of the lower segment is within
                    // tolerance of the chain head. We detect the T-junction, split the
                    // overlapping segment, continue toward the farther endpoint, and put
                    // the near-half back into the pool for later pickup.
                    Segment tSeg = findTIntersection(pool, headX, headY, toleranceSq);
                    if (tSeg != null) {
                        tSeg.used = true;
                        double d1sq = distSq(tSeg.startX, tSeg.startY, headX, headY);
                        double d2sq = distSq(tSeg.endX, tSeg.endY, headX, headY);
                        double farX, farY, nearX, nearY;
                        if (d1sq > d2sq) {
                            farX = tSeg.startX; farY = tSeg.startY;
                            nearX = tSeg.endX;  nearY = tSeg.endY;
                        } else {
                            farX = tSeg.endX;   farY = tSeg.endY;
                            nearX = tSeg.startX; nearY = tSeg.startY;
                        }
                        // Return the near half to the pool so it can be picked up later.
                        pool.add(Segment.draw(headX, headY, nearX, nearY));
                        subpath.append(String.format(Locale.US, " L %.6f %.6f", farX, farY));
                        headX = farX;
                        headY = farY;
                        spMinX = Math.min(spMinX, headX); spMinY = Math.min(spMinY, headY);
                        spMaxX = Math.max(spMaxX, headX); spMaxY = Math.max(spMaxY, headY);
                        if (!leftToleranceBall
                                && distSq(headX, headY, loopStartX, loopStartY) > toleranceSq) {
                            leftToleranceBall = true;
                        }
                        continue;
                    }
                    // Bridge an internal gap: jump straight to the nearest unused segment
                    // in this component and carry on. The component is one shape, so the
                    // nearest unused endpoint is the far side of the gap (e.g. the missing
                    // closing edge of a cut-out), not unrelated geometry.
                    Segment bridge = null;
                    boolean bridgeRev = false;
                    double bridgeSq = Double.MAX_VALUE;
                    for (Segment s : pool) {
                        if (s.used) continue;
                        double d1 = distSq(s.startX, s.startY, headX, headY);
                        if (d1 < bridgeSq) { bridgeSq = d1; bridge = s; bridgeRev = false; }
                        double d2 = distSq(s.endX, s.endY, headX, headY);
                        if (d2 < bridgeSq) { bridgeSq = d2; bridge = s; bridgeRev = true; }
                    }
                    if (bridge != null) {
                        double bx = bridgeRev ? bridge.endX : bridge.startX;
                        double by = bridgeRev ? bridge.endY : bridge.startY;
                        subpath.append(String.format(Locale.US, " L %.6f %.6f", bx, by));
                        spMinX = Math.min(spMinX, bx); spMinY = Math.min(spMinY, by);
                        spMaxX = Math.max(spMaxX, bx); spMaxY = Math.max(spMaxY, by);
                        bridge.used = true;
                        appendSegment(subpath, bridge, bridgeRev, options);
                        headX = bridgeRev ? bridge.startX : bridge.endX;
                        headY = bridgeRev ? bridge.startY : bridge.endY;
                        if (bridge.isArc) spHasArc = true;
                        spMinX = Math.min(spMinX, headX); spMinY = Math.min(spMinY, headY);
                        spMaxX = Math.max(spMaxX, headX); spMaxY = Math.max(spMaxY, headY);
                        if (!leftToleranceBall
                                && distSq(headX, headY, loopStartX, loopStartY) > toleranceSq) {
                            leftToleranceBall = true;
                        }
                        continue;
                    }
                    break; // open loop — emit Z anyway to let SVG fill it
                }
                next.used = true;
                appendSegment(subpath, next, reverse, options);
                headX = reverse ? next.startX : next.endX;
                headY = reverse ? next.startY : next.endY;
                spMinX = Math.min(spMinX, headX); spMinY = Math.min(spMinY, headY);
                spMaxX = Math.max(spMaxX, headX); spMaxY = Math.max(spMaxY, headY);
                if (next.isArc) spHasArc = true;
                if (!leftToleranceBall
                    && distSq(headX, headY, loopStartX, loopStartY) > toleranceSq) {
                    leftToleranceBall = true;
                }
            }
            subpath.append(" Z");

            subpathList.add(subpath.toString());
            subpathBoundsList.add(new double[]{spMinX, spMinY, spMaxX, spMaxY});
            subpathHasArcList.add(spHasArc);
        }
    }

    private void appendSegment(StringBuilder path, Segment s, boolean reverse,
                               SvgOptions options) {
        double ex = reverse ? s.startX : s.endX;
        double ey = reverse ? s.startY : s.endY;
        if (!s.isArc) {
            path.append(String.format(Locale.US, " L %.6f %.6f", ex, ey));
            return;
        }

        double sx = reverse ? s.endX : s.startX;
        double sy = reverse ? s.endY : s.startY;
        boolean cw = reverse ? !s.clockwise : s.clockwise;
        double sa = Math.atan2(sy - s.centerY, sx - s.centerX);
        double ea = Math.atan2(ey - s.centerY, ex - s.centerX);
        double sweep;
        if (cw) {
            sweep = sa - ea;
            if (sweep <= 0) sweep += 2 * Math.PI;
        } else {
            sweep = ea - sa;
            if (sweep <= 0) sweep += 2 * Math.PI;
        }
        int largeArcFlag = sweep > Math.PI ? 1 : 0;
        int sweepFlag;
        if (options.isFlipY()) {
            sweepFlag = cw ? 0 : 1;
        } else {
            sweepFlag = cw ? 1 : 0;
        }
        path.append(String.format(Locale.US, " A %.6f %.6f 0 %d %d %.6f %.6f",
            s.radius, s.radius, largeArcFlag, sweepFlag, ex, ey));
    }

    private static final class Segment {
        final boolean isArc;
        final double startX, startY, endX, endY;
        final double centerX, centerY, radius;
        final boolean clockwise;
        boolean used;

        private Segment(boolean isArc, double sx, double sy, double ex, double ey,
                        double cx, double cy, double r, boolean cw) {
            this.isArc = isArc;
            this.startX = sx; this.startY = sy;
            this.endX = ex;   this.endY = ey;
            this.centerX = cx; this.centerY = cy;
            this.radius = r;
            this.clockwise = cw;
        }

        static Segment draw(double sx, double sy, double ex, double ey) {
            return new Segment(false, sx, sy, ex, ey, 0, 0, 0, false);
        }

        static Segment arc(double sx, double sy, double ex, double ey,
                           double cx, double cy, double r, boolean cw) {
            return new Segment(true, sx, sy, ex, ey, cx, cy, r, cw);
        }
    }

    private void renderDrillContent(StringBuilder svg, DrillDocument doc) {
        if (doc == null) return;

        for (DrillOperation op : doc.getOperations()) {
            String opSvg = op.toSvg();
            if (opSvg != null && !opSvg.isEmpty()) {
                svg.append("    ").append(opSvg).append("\n");
            }
        }
    }

    /**
     * Render a single-side realistic SVG — the input layer list is automatically
     * filtered to the outline, drills, and the layers matching the requested side.
     * <p>
     * The caller can pass their full layer set (both top and bottom); this method
     * selects the appropriate subset. Returns {@code null} if no outline layer is
     * present or the filtered set would have no content.
     * <p>
     * Equivalent to {@link #renderRealisticSide(List, Side, boolean)} with
     * {@code mirrorBottom = false} — the bottom side is rendered from the
     * top-looking-down perspective (horizontally mirrored relative to the real
     * underside of the board). This is the natural orientation for viewers that
     * apply their own horizontal flip on display.
     */
    public String renderRealisticSide(List<Layer> layers, Side side) {
        return renderRealisticSide(layers, side, false);
    }

    /**
     * Render a single-side realistic SVG with optional horizontal mirroring of
     * the bottom side.
     *
     * @param layers       the full layer set (both sides may be present)
     * @param side         which side to render
     * @param mirrorBottom if {@code true} and {@code side == BOTTOM}, flip the
     *                     rendering horizontally so the output matches the real
     *                     physical underside of the board (as if you'd turned the
     *                     board over around its Y axis). Has no effect for
     *                     {@link Side#TOP}.
     * @return the SVG string, or {@code null} if no outline layer is present or
     *         the filtered layer set would have no content.
     */
    public String renderRealisticSide(List<Layer> layers, Side side, boolean mirrorBottom) {
        List<Layer> sideLayers = filterForSide(layers, side);
        if (sideLayers == null) return null;
        String svg = renderRealistic(sideLayers);
        if (mirrorBottom && side == Side.BOTTOM) {
            svg = applyHorizontalFlip(svg);
        }
        return svg;
    }

    /**
     * Render a realistic view of the given side rasterised as a PNG thumbnail
     * with height auto-derived from the SVG's aspect ratio.
     * <p>
     * Intended for project-list cards: for 20–100 projects, inlining full realistic
     * SVGs (tens of thousands of {@code <use>} elements each) is prohibitively
     * expensive in a browser — a fixed-size PNG is orders of magnitude smaller and
     * cheaper to decode.
     * <p>
     * The PNG is 8-bit RGBA with transparent background: areas outside the board
     * outline and through drill holes are fully transparent, so the thumbnail can
     * be composited onto any card/background colour.
     *
     * @param layers  the full layer set (both sides may be present; the side is
     *                selected automatically)
     * @param side    which side to render
     * @param widthPx target PNG width in pixels; height follows aspect ratio
     * @return PNG bytes, or {@code null} if the side couldn't be rendered (e.g.
     *         no outline layer)
     * @throws IllegalArgumentException if widthPx is not positive
     * @throws RuntimeException         if SVG rasterisation fails
     */
    public byte[] renderRealisticSidePng(List<Layer> layers, Side side, int widthPx) {
        return renderRealisticSidePng(layers, side, widthPx, 0);
    }

    /**
     * Render a realistic-view PNG at explicit dimensions.
     * <p>
     * When one of {@code widthPx}/{@code heightPx} is {@code <= 0} it is derived
     * from the other using the board's X/Y extent (outline bounds plus a
     * thumbnail-scaled margin) so the PNG's aspect ratio matches the board.
     * When both are given, the board is fitted into that box with
     * {@code preserveAspectRatio="xMidYMid meet"} — letterboxed to match.
     * <p>
     * The bottom side is mirrored horizontally by default so the PNG shows the
     * real physical underside of the board. Pass the 5-argument overload to
     * control this explicitly.
     *
     * @param layers   full layer set (both sides may be present)
     * @param side     which side to render
     * @param widthPx  target width in pixels, or {@code <= 0} to derive from height
     * @param heightPx target height in pixels, or {@code <= 0} to derive from width
     * @return PNG bytes, or {@code null} if the side couldn't be rendered
     * @throws IllegalArgumentException if both dimensions are {@code <= 0}
     */
    public byte[] renderRealisticSidePng(List<Layer> layers, Side side,
                                         int widthPx, int heightPx) {
        return renderRealisticSidePng(layers, side, widthPx, heightPx, true);
    }

    /**
     * Render a realistic-view PNG with explicit control over bottom-side mirroring.
     *
     * @param layers       full layer set (both sides may be present)
     * @param side         which side to render
     * @param widthPx      target width in pixels, or {@code <= 0} to derive from height
     * @param heightPx     target height in pixels, or {@code <= 0} to derive from width
     * @param mirrorBottom if {@code true} and {@code side == BOTTOM}, the bottom is
     *                     flipped horizontally so the PNG shows the real underside.
     *                     Has no effect for {@link Side#TOP}.
     * @return PNG bytes, or {@code null} if the side couldn't be rendered
     * @throws IllegalArgumentException if both dimensions are {@code <= 0}
     */
    public byte[] renderRealisticSidePng(List<Layer> layers, Side side,
                                         int widthPx, int heightPx, boolean mirrorBottom) {
        if (widthPx <= 0 && heightPx <= 0) {
            throw new IllegalArgumentException(
                "At least one of widthPx/heightPx must be positive");
        }
        // Thumbnails want a more generous, visible margin than the default 0.5 mm
        // used for overlay/DRC work — scale with board size so small and large
        // boards both get visible breathing room around the outline.
        double prevMargin = this.margin;
        this.margin = computeThumbnailMargin(layers);
        String svg;
        try {
            svg = renderRealisticSide(layers, side, mirrorBottom);
        } finally {
            this.margin = prevMargin;
        }
        if (svg == null) return null;

        // Derive the missing dimension from the SVG's viewBox so the PNG's
        // aspect ratio exactly matches the board's X/Y extent (plus margin).
        // Passing both dimensions explicitly avoids any ambiguity in how
        // Batik resolves a single KEY_WIDTH/KEY_HEIGHT hint.
        if (widthPx <= 0 || heightPx <= 0) {
            double[] vb = parseViewBox(svg);
            if (vb != null && vb[2] > 0 && vb[3] > 0) {
                double aspect = vb[2] / vb[3];
                if (widthPx <= 0)  widthPx  = Math.max(1, (int) Math.round(heightPx * aspect));
                if (heightPx <= 0) heightPx = Math.max(1, (int) Math.round(widthPx / aspect));
            }
        }
        return rasterizeSvgToPng(svg, widthPx, heightPx);
    }

    /** 3% of the max outline dimension, floored at 1.5 mm. */
    private static double computeThumbnailMargin(List<Layer> layers) {
        BoundingBox bb = null;
        for (Layer l : layers) {
            if (l.getLayerType() == LayerType.OUTLINE) {
                bb = l.getBoundingBox();
                break;
            }
        }
        if (bb == null || !bb.isValid()) return 1.5;
        double maxDim = Math.max(bb.getWidth(), bb.getHeight());
        return Math.max(1.5, maxDim * 0.03);
    }

    /**
     * Mirror the rendered SVG horizontally around the viewBox's vertical
     * centreline. Used to turn the top-looking-down bottom-side render into the
     * real underside view. Modifies the {@code #viewport} group's {@code transform}
     * so downstream rasterisation sees a properly flipped image.
     */
    private static String applyHorizontalFlip(String svg) {
        double[] vb = parseViewBox(svg);
        if (vb == null) return svg;
        // Mirror x around (minX + width/2): maps x -> 2*minX + width - x.
        double tx = 2 * vb[0] + vb[2];
        String mirror = String.format(Locale.US, "translate(%.6f,0) scale(-1,1)", tx);

        int vpStart = svg.indexOf("<g id=\"viewport\"");
        if (vpStart < 0) return svg;
        int vpEnd = svg.indexOf('>', vpStart);
        if (vpEnd < 0) return svg;
        String vpTag = svg.substring(vpStart, vpEnd);

        int txIdx = vpTag.indexOf("transform=\"");
        String newVpTag;
        if (txIdx >= 0) {
            int txStart = txIdx + 11;
            int txEnd = vpTag.indexOf('"', txStart);
            if (txEnd < 0) return svg;
            String existing = vpTag.substring(txStart, txEnd);
            newVpTag = vpTag.substring(0, txStart) + mirror + " " + existing + vpTag.substring(txEnd);
        } else {
            newVpTag = vpTag + " transform=\"" + mirror + "\"";
        }
        return svg.substring(0, vpStart) + newVpTag + svg.substring(vpEnd);
    }

    private static double[] parseViewBox(String svg) {
        int i = svg.indexOf("viewBox=\"");
        if (i < 0) return null;
        int start = i + 9;
        int end = svg.indexOf('"', start);
        if (end < 0) return null;
        String[] parts = svg.substring(start, end).trim().split("\\s+");
        if (parts.length != 4) return null;
        try {
            double[] out = new double[4];
            for (int k = 0; k < 4; k++) out[k] = Double.parseDouble(parts[k]);
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Rasterise an SVG string to a PNG. Either dimension can be {@code <= 0}
     * to mean "derive from the other via aspect ratio". The SVG's
     * {@code preserveAspectRatio} setting controls how the image fits when
     * both dimensions are given.
     */
    public static byte[] rasterizeSvgToPng(String svg, int widthPx, int heightPx) {
        if (widthPx <= 0 && heightPx <= 0) {
            throw new IllegalArgumentException(
                "At least one of widthPx/heightPx must be positive");
        }
        PNGTranscoder transcoder = new PNGTranscoder();
        if (widthPx > 0)  transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH,  (float) widthPx);
        if (heightPx > 0) transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) heightPx);
        String batikSvg = makeBatikCompatible(svg);
        TranscoderInput input = new TranscoderInput(new StringReader(batikSvg));
        int buf = Math.max(widthPx, heightPx) * 32;
        ByteArrayOutputStream out = new ByteArrayOutputStream(buf > 0 ? buf : 16384);
        TranscoderOutput output = new TranscoderOutput(out);
        try {
            transcoder.transcode(input, output);
        } catch (TranscoderException e) {
            throw new RuntimeException("SVG→PNG rasterisation failed", e);
        }
        return out.toByteArray();
    }

    /** Width-only convenience overload — height follows aspect ratio. */
    public static byte[] rasterizeSvgToPng(String svg, int widthPx) {
        return rasterizeSvgToPng(svg, widthPx, 0);
    }

    /**
     * Rewrite the SVG so Batik (which enforces SVG 1.1) accepts our SVG 2 output:
     * declare the xlink namespace on the root and swap bare {@code href=} on
     * {@code <use>} elements to {@code xlink:href=}. Browsers accept either, so
     * we only do this when handing the SVG to Batik.
     */
    private static String makeBatikCompatible(String svg) {
        // Add xmlns:xlink to root <svg> if not already present.
        String out = svg;
        if (!out.contains("xmlns:xlink=")) {
            int svgTagEnd = out.indexOf("<svg");
            if (svgTagEnd >= 0) {
                int insertAt = out.indexOf(' ', svgTagEnd);
                if (insertAt >= 0) {
                    out = out.substring(0, insertAt)
                        + " xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                        + out.substring(insertAt);
                }
            }
        }
        // Replace `href="#...` with `xlink:href="#...` inside <use> attrs. The
        // realistic/multi-layer SVGs only use href on <use> elements (aperture
        // references), so a global swap is safe.
        out = out.replace("<use href=\"", "<use xlink:href=\"");
        return out;
    }

    private static List<Layer> filterForSide(List<Layer> allLayers, Side side) {
        List<Layer> out = new ArrayList<>();
        boolean hasOutline = false;
        boolean hasCopper = false;
        for (Layer layer : allLayers) {
            LayerType lt = layer.getLayerType();
            if (lt == LayerType.OUTLINE) {
                out.add(layer);
                hasOutline = true;
            } else if (side == Side.TOP && (lt == LayerType.COPPER_TOP
                    || lt == LayerType.SOLDERMASK_TOP || lt == LayerType.SILKSCREEN_TOP)) {
                out.add(layer);
                if (lt == LayerType.COPPER_TOP) hasCopper = true;
            } else if (side == Side.BOTTOM && (lt == LayerType.COPPER_BOTTOM
                    || lt == LayerType.SOLDERMASK_BOTTOM || lt == LayerType.SILKSCREEN_BOTTOM)) {
                out.add(layer);
                if (lt == LayerType.COPPER_BOTTOM) hasCopper = true;
            } else if (lt == LayerType.DRILL || lt == LayerType.DRILL_PLATED
                    || lt == LayerType.DRILL_NON_PLATED) {
                out.add(layer);
            }
        }
        // Need either a real outline or copper to derive one from, plus some content.
        if ((!hasOutline && !hasCopper) || out.size() < 2) return null;
        return out;
    }

    /**
     * Sanitize a filename to be used as an SVG element ID.
     */
    private String sanitizeId(String name) {
        // Replace spaces and special characters that are invalid in SVG IDs
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // Accepts only well-formed CSS colors: #rgb/#rgba/#rrggbb/#rrggbbaa, rgb()/rgba(),
    // or a bare color keyword. The layer color is settable via the public Layer API, so an
    // untrusted value (e.g. '#000" onload="...') must not be able to break out of the
    // color="..." attribute and inject markup. Anything else falls back to black.
    private static final java.util.regex.Pattern SAFE_COLOR = java.util.regex.Pattern.compile(
        "#[0-9a-fA-F]{3,8}|[a-zA-Z]+|rgba?\\([0-9.,%\\s]+\\)");

    private String sanitizeColor(String color) {
        if (color != null && SAFE_COLOR.matcher(color).matches()) {
            return color;
        }
        return "#000000";
    }

    private String createEmptySvg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"></svg>";
    }
}
