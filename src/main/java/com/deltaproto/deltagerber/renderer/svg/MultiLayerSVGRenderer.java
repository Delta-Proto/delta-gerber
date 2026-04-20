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
            String fillColor = layer.getColor() != null ? layer.getColor() : "#000000";

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

        if (outlineLayer == null || !outlineLayer.isGerber()) {
            throw new IllegalArgumentException(
                "Realistic rendering requires a Gerber layer with LayerType.OUTLINE");
        }

        // Use outline bounding box for viewBox (content is clipped to outline anyway)
        BoundingBox globalBounds = outlineLayer.getBoundingBox();
        if (!globalBounds.isValid()) {
            // Fall back to union of all layers
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

        // Extract board outline path for clipPath and soldermask mask base
        SvgOptions outlineOptions = svgOptions.copy().setFlipY(flipY);
        String outlinePath = extractOutlinePath(outlineLayer.getGerberDoc(), outlineOptions);
        boolean hasOutlinePath = outlinePath != null && !outlinePath.isBlank();

        if (hasOutlinePath) {
            svg.append("  <clipPath id=\"board-outline\">\n");
            svg.append(String.format("    <path d=\"%s\"/>\n", outlinePath));
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
                svg.append(String.format("    <path d=\"%s\" fill=\"white\"/>\n", outlinePath));
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
     * Draw/Arc endpoints into a closed path.
     */
    private String extractOutlinePath(GerberDocument outlineDoc, SvgOptions options) {
        List<GraphicsObject> objects = outlineDoc.getObjects();

        // Prefer regions — they're already filled closed paths
        StringBuilder regionPaths = new StringBuilder();
        for (GraphicsObject obj : objects) {
            if (obj instanceof Region) {
                Region region = (Region) obj;
                for (Contour contour : region.getContours()) {
                    if (regionPaths.length() > 0) regionPaths.append(" ");
                    regionPaths.append(contour.toSvgPath(options));
                }
            }
        }
        if (regionPaths.length() > 0) {
            return regionPaths.toString();
        }

        // Fall back: chain Draw/Arc endpoints into a filled path
        // Board outlines are typically a single closed loop of draws/arcs
        StringBuilder path = new StringBuilder();
        double lastEndX = Double.NaN;
        double lastEndY = Double.NaN;
        double tolerance = 0.001;

        for (GraphicsObject obj : objects) {
            double startX, startY, endX, endY;
            boolean isArc = false;
            Arc arc = null;

            if (obj instanceof Draw) {
                Draw draw = (Draw) obj;
                startX = draw.getStartX();
                startY = draw.getStartY();
                endX = draw.getEndX();
                endY = draw.getEndY();
            } else if (obj instanceof Arc) {
                arc = (Arc) obj;
                startX = arc.getStartX();
                startY = arc.getStartY();
                endX = arc.getEndX();
                endY = arc.getEndY();
                isArc = true;
            } else {
                continue; // Skip flashes and other objects
            }

            // Check if this segment connects to the previous one
            boolean connected = !Double.isNaN(lastEndX)
                && Math.abs(startX - lastEndX) < tolerance
                && Math.abs(startY - lastEndY) < tolerance;

            if (!connected) {
                // Close previous loop if any
                if (!Double.isNaN(lastEndX)) {
                    path.append(" Z");
                }
                path.append(String.format(Locale.US, " M %.6f %.6f", startX, startY));
            }

            if (isArc) {
                double radius = arc.getRadius();
                double sa = Math.atan2(arc.getStartY() - arc.getCenterY(),
                    arc.getStartX() - arc.getCenterX());
                double ea = Math.atan2(arc.getEndY() - arc.getCenterY(),
                    arc.getEndX() - arc.getCenterX());
                double sweep;
                if (arc.isClockwise()) {
                    sweep = sa - ea;
                    if (sweep <= 0) sweep += 2 * Math.PI;
                } else {
                    sweep = ea - sa;
                    if (sweep <= 0) sweep += 2 * Math.PI;
                }
                int largeArcFlag = sweep > Math.PI ? 1 : 0;
                int sweepFlag;
                if (options.isFlipY()) {
                    sweepFlag = arc.isClockwise() ? 0 : 1;
                } else {
                    sweepFlag = arc.isClockwise() ? 1 : 0;
                }
                path.append(String.format(Locale.US, " A %.6f %.6f 0 %d %d %.6f %.6f",
                    radius, radius, largeArcFlag, sweepFlag, endX, endY));
            } else {
                path.append(String.format(Locale.US, " L %.6f %.6f", endX, endY));
            }

            lastEndX = endX;
            lastEndY = endY;
        }

        if (!Double.isNaN(lastEndX)) {
            path.append(" Z");
        }

        return path.toString().trim();
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
     * Sanitize a filename to be used as an SVG element ID.
     */
    private String sanitizeId(String name) {
        // Replace spaces and special characters that are invalid in SVG IDs
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String createEmptySvg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"></svg>";
    }
}
