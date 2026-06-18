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

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
    // When true, renderRealistic frames its viewBox to the board OUTLINE instead of the
    // union of all layers. Set by the raster thumbnail path (renderRealisticSidePngWithScale)
    // so off-board silkscreen/marks don't shrink the board into a corner of the image; the
    // interactive SVG path keeps union bounds so a separate paste overlay shares its frame.
    private boolean preferOutlineBoundsForViewBox = false;

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
     * Union bounding box across all layers (skipping any with invalid bounds). Both
     * {@link #render} and {@link #renderRealistic} size their viewBox to this same box,
     * so a layer rendered on its own over the same set shares their coordinate frame and
     * overlays pixel-perfectly. The returned box may be {@link BoundingBox#isValid()
     * invalid} when no layer has valid bounds — callers treat that as an empty render.
     */
    private static BoundingBox computeGlobalBounds(List<Layer> layers) {
        BoundingBox globalBounds = new BoundingBox();
        for (Layer layer : layers) {
            BoundingBox layerBounds = layer.getBoundingBox();
            if (layerBounds.isValid()) {
                globalBounds.extend(layerBounds);
            }
        }
        return globalBounds;
    }

    /**
     * Render multiple layers into a single SVG document.
     */
    public String render(List<Layer> layers) {
        if (layers == null || layers.isEmpty()) {
            return createEmptySvg();
        }

        BoundingBox globalBounds = computeGlobalBounds(layers);
        if (!globalBounds.isValid()) {
            return createEmptySvg();
        }

        double minX = globalBounds.getMinX() - margin;
        double minY = globalBounds.getMinY() - margin;
        double width = globalBounds.getWidth() + 2 * margin;
        double height = globalBounds.getHeight() + 2 * margin;

        StringBuilder svg = new StringBuilder();
        appendSvgHeader(svg, minX, minY, width, height);

        svg.append("<defs>\n");
        List<List<PolarityMaskHelper.PolarityGroup>> allLayerGroups = new ArrayList<>();
        emitLayerDefs(svg, layers, allLayerGroups, minX, minY, width, height);
        svg.append("</defs>\n");

        emitViewportGroup(svg, layers, allLayerGroups, minX, minY, width, height, null);

        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * Render the visible layers as holes knocked out of a solid sheet spanning the global
     * bounds, rather than as filled shapes. Shares {@link #render}'s viewBox / Y-flipped
     * coordinate frame (via {@link #computeGlobalBounds}), so an inverse paste overlay still
     * aligns pixel-for-pixel with the realistic view.
     *
     * <p>A {@code <mask>} is built from a white full-bounds rect (sheet shows) minus the
     * layer shapes painted black (sheet hidden → holes); a {@code sheetColor} rect is then
     * painted through it. Reducing the rendered element's opacity fades the sheet while the
     * holes — having no pixels — stay fully transparent.
     */
    public String renderInverse(List<Layer> layers, String sheetColor) {
        if (layers == null || layers.isEmpty()) {
            return createEmptySvg();
        }

        BoundingBox globalBounds = computeGlobalBounds(layers);
        if (!globalBounds.isValid()) {
            return createEmptySvg();
        }

        double minX = globalBounds.getMinX() - margin;
        double minY = globalBounds.getMinY() - margin;
        double width = globalBounds.getWidth() + 2 * margin;
        double height = globalBounds.getHeight() + 2 * margin;

        StringBuilder svg = new StringBuilder();
        appendSvgHeader(svg, minX, minY, width, height);

        svg.append("<defs>\n");
        List<List<PolarityMaskHelper.PolarityGroup>> allLayerGroups = new ArrayList<>();
        emitLayerDefs(svg, layers, allLayerGroups, minX, minY, width, height);
        svg.append(String.format(Locale.US,
            "  <mask id=\"layer-knockout\" maskUnits=\"userSpaceOnUse\" "
            + "x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\">\n",
            minX, minY, width, height));
        svg.append(String.format(Locale.US,
            "    <rect x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"white\"/>\n",
            minX, minY, width, height));
        // Layers forced to black so they fully punch through the white sheet.
        emitViewportGroup(svg, layers, allLayerGroups, minX, minY, width, height, "black");
        svg.append("  </mask>\n");
        svg.append("</defs>\n");

        svg.append(String.format(Locale.US,
            "<rect x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"%s\" "
            + "mask=\"url(#layer-knockout)\"/>\n",
            minX, minY, width, height, sanitizeColor(sheetColor)));
        svg.append("</svg>");
        return svg.toString();
    }

    private void appendSvgHeader(StringBuilder svg, double minX, double minY, double width, double height) {
        svg.append(String.format(Locale.US,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "viewBox=\"%.6f %.6f %.6f %.6f\" " +
            "preserveAspectRatio=\"xMidYMid meet\" " +
            "stroke-linecap=\"round\" stroke-linejoin=\"round\" " +
            "fill-rule=\"nonzero\">\n",
            minX, minY, width, height));
    }

    /**
     * Emit the aperture defs and clear-polarity mask defs for every gerber layer (into an
     * open {@code <defs>}), populating {@code allLayerGroups} with the per-layer polarity
     * groups that {@link #emitViewportGroup} consumes. Shared by {@link #render} and
     * {@link #renderInverse}.
     */
    private void emitLayerDefs(StringBuilder svg, List<Layer> layers,
                               List<List<PolarityMaskHelper.PolarityGroup>> allLayerGroups,
                               double minX, double minY, double width, double height) {
        // Mask base rect for clear polarity masks
        String maskRect = PolarityMaskHelper.createMaskRect(minX, minY, width, height, 1);

        int layerIndex = 0;
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
    }

    /**
     * Emit the Y-flipped {@code <g id="viewport">} with one {@code <g class="layer">} per
     * layer. When {@code colorOverride} is non-null every layer is drawn in that colour at
     * full opacity (used to paint the shapes black inside the knockout mask); otherwise each
     * layer keeps its own colour and opacity. Shared by {@link #render}/{@link #renderInverse}.
     */
    private void emitViewportGroup(StringBuilder svg, List<Layer> layers,
                                   List<List<PolarityMaskHelper.PolarityGroup>> allLayerGroups,
                                   double minX, double minY, double width, double height,
                                   String colorOverride) {
        // Viewport group with Y-flip transform and stroke-width="0" to prevent inherited strokes
        if (flipY) {
            svg.append(String.format(Locale.US,
                "<g id=\"viewport\" transform=\"translate(0, %.6f) scale(1,-1)\" stroke-width=\"0\">\n",
                minY + height + minY));
        } else {
            svg.append("<g id=\"viewport\" stroke-width=\"0\">\n");
        }

        int layerIndex = 0;
        for (Layer layer : layers) {
            String layerId = sanitizeId(layer.getName());
            String display = layer.isVisible() ? "inline" : "none";
            String fillColor = colorOverride != null ? colorOverride : sanitizeColor(layer.getColor());
            double opacity = colorOverride != null ? 1.0 : layer.getOpacity();

            svg.append(String.format(Locale.US,
                "  <g class=\"layer\" id=\"%s\" display=\"%s\" " +
                "color=\"%s\" fill=\"currentColor\" stroke=\"none\" stroke-width=\"0\" opacity=\"%.2f\">\n",
                layerId, display, fillColor, opacity));

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
     * Hard ceiling on either raster dimension, and on the total pixel count, handed to
     * Batik. Batik allocates {@code width * height * 4} bytes for the output raster, so an
     * unbounded dimension blows the heap: a board whose viewBox has a pathological aspect
     * ratio (e.g. no usable OUTLINE, so the viewBox falls back to the union of all layers'
     * bounds and a single stray flash or mis-scaled coordinate inflates one axis) makes the
     * derived dimension explode into millions of pixels — observed as a 3+ GB allocation
     * from a single thumbnail render. These caps clamp each side and scale the pair down
     * proportionally so a degenerate board yields a (possibly distorted) small thumbnail
     * instead of an OutOfMemoryError. {@value #MAX_RASTER_DIMENSION_PX}² ≈ 256 MB worst case;
     * the area cap keeps the typical worst case far lower.
     */
    private static final int MAX_RASTER_DIMENSION_PX = 8192;
    private static final long MAX_RASTER_PIXELS = 16_000_000L; // ~64 MB raster (×4 bytes)

    /**
     * Thumbnail-specific raster cap — deliberately far below {@link #MAX_RASTER_DIMENSION_PX}.
     * The full-render caps bound only the <em>final</em> raster ({@code width*height*4}), but a
     * realistic render's true Batik footprint is dominated by its offscreen mask/clip buffers
     * (board-outline clip, sm/cf/mech masks, per-layer polarity masks) — each one a full-canvas
     * ARGB image, several alive at once, so the working set runs ~6–10× the final raster. A
     * single masked side at 4096px (well under the full-render caps) already OOMs a ~768 MB
     * heap. Capping a thumbnail at {@value #MAX_THUMBNAIL_DIMENSION_PX}px keeps that masked
     * working set comfortably inside a ~512 MB–1 GB heap. {@link #rasterizeSvgToPng} still
     * applies the higher full-render cap as a final backstop.
     */
    private static final int MAX_THUMBNAIL_DIMENSION_PX = 1024;

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
        List<Layer> innerCopperLayers = new ArrayList<>();
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
                case COPPER_INNER:
                    // not drawn — inner pours only contribute to outline derivation
                    innerCopperLayers.add(layer);
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

        // viewBox bounds. The interactive SVG path uses the UNION of all layers so a
        // separately-rendered paste overlay (render()/renderInverse over the same set)
        // shares this exact frame and aligns. Raster thumbnails instead frame to the board
        // OUTLINE — content is clipped to the outline anyway, and off-board silkscreen/marks
        // would otherwise shrink the board into a corner of a much larger image. Falls back
        // to the union when there is no usable outline.
        BoundingBox globalBounds = (preferOutlineBoundsForViewBox && haveOutlineLayer
                && outlineLayer.getBoundingBox().isValid())
            ? outlineLayer.getBoundingBox()
            : computeGlobalBounds(layers);
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
            for (Layer l : innerCopperLayers) {
                if (l.isGerber() && l.getGerberDoc() != null) silhouetteDocs.add(l.getGerberDoc());
            }
            for (Layer l : soldermaskLayers) {
                if (l.isGerber() && l.getGerberDoc() != null) silhouetteDocs.add(l.getGerberDoc());
            }
            outlinePath = OutlineDeriver.deriveOutlineSvgPath(
                silhouetteDocs, DERIVED_OUTLINE_CLOSE_MM, DERIVED_OUTLINE_OUTSET_MM);
        }
        boolean hasOutlinePath = outlinePath != null && !outlinePath.isBlank();

        // Fill/clip rule for the board-outline path. A real profile layer can carry
        // genuine internal cut-outs — extractOutlinePath emits the board edge plus
        // region holes and relies on EVEN-ODD to subtract them. A DERIVED outline has
        // no real cut-outs: OutlineDeriver fills interior pockets and emits only the
        // same-wound "outer" silhouette contours (holes already dropped), which must be
        // UNIONED — the NONZERO rule. Under even-odd those same-wound pieces cancel
        // wherever one nests inside another (the morphological close/outset step can emit
        // concentric boundary loops over copper-dense zones), punching holes into the
        // clip that erase the copper there — traces vanish from the realistic view while
        // the board still looks like a full rectangle. See OutlineDeriver.toOuterSilhouettePath.
        String outlineFillRule = haveOutlineLayer ? "evenodd" : "nonzero";

        if (hasOutlinePath) {
            svg.append("  <clipPath id=\"board-outline\">\n");
            svg.append(String.format("    <path d=\"%s\" clip-rule=\"%s\"/>\n",
                outlinePath, outlineFillRule));
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
                svg.append(String.format("    <path d=\"%s\" fill=\"white\" fill-rule=\"%s\"/>\n",
                    outlinePath, outlineFillRule));
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
     * A rasterised realistic-view PNG together with the geometry needed to map between
     * its pixels and the board's real-world millimetres.
     * <p>
     * The image covers exactly the {@linkplain #minXmm mm rectangle} {@code [minXmm,
     * minYmm, widthMm, heightMm]} — the board outline plus the thumbnail margin. Because
     * the PNG is fitted with {@code preserveAspectRatio="xMidYMid meet"}, the board is
     * scaled uniformly by {@link #pxPerMm} and centred, occupying the pixel rectangle
     * {@code [contentOffsetXpx, contentOffsetYpx, contentWidthPx, contentHeightPx]}. For
     * an aspect-matched PNG (the usual single-dimension call) the offsets are 0 and the
     * content fills the image.
     * <p>
     * The {@code mmRect} ({@link #minXmm}, {@link #minYmm}, {@link #widthMm},
     * {@link #heightMm}) is in <em>gerber/drill coordinate space</em> (millimetres, Y-up,
     * the native datum of the files). Image pixels are top-left origin, Y-down, and the
     * bottom side may additionally be mirrored in X — so the gerber/drill datum
     * {@code (0,0)} is not at a fixed pixel. Its actual location is precomputed for you as
     * {@link #originXpx}/{@link #originYpx} (handling the Y-flip and any X-mirror), so you
     * can anchor measurements to the real origin:
     * <pre>
     *   // pixel position of gerber point (gxMm, gyMm), aligned to the datum:
     *   pxX = originXpx + gxMm * pxPerMm * (mirrored ? -1 : +1);
     *   pxY = originYpx - gyMm * pxPerMm;   // minus: gerber Y-up vs pixel Y-down
     * </pre>
     * To draw a 10 mm grid aligned to the datum, step {@code 10 * pxPerMm} pixels out from
     * {@code (originXpx, originYpx)} in both directions. ({@code originXpx}/{@code originYpx}
     * may fall outside {@code [0,widthPx]×[0,heightPx]} if the datum is off-image.)
     */
    public static final class PngWithScale {
        /** PNG bytes. */
        public final byte[] png;
        /** Actual pixel dimensions of the PNG. */
        public final int widthPx, heightPx;
        /** The mm rectangle the image covers, in gerber/drill space (Y-up): outline
         *  bounds plus the thumbnail margin. */
        public final double minXmm, minYmm, widthMm, heightMm;
        /** Uniform scale Batik applied (pixels per millimetre). */
        public final double pxPerMm;
        /** Pixel rectangle the board content occupies inside the PNG (letterbox-aware). */
        public final double contentOffsetXpx, contentOffsetYpx, contentWidthPx, contentHeightPx;
        /** Pixel location of the gerber/drill datum (0,0), Y-flip- and mirror-aware. */
        public final double originXpx, originYpx;
        /** Which board side this image shows. */
        public final Side side;
        /** Whether the X axis was mirrored (bottom shown as the real physical underside). */
        public final boolean mirrored;

        PngWithScale(byte[] png, int widthPx, int heightPx,
                      double minXmm, double minYmm, double widthMm, double heightMm,
                      Side side, boolean mirrored, boolean flipY) {
            this.png = png;
            this.side = side;
            this.mirrored = mirrored;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            this.minXmm = minXmm;
            this.minYmm = minYmm;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
            // xMidYMid meet: uniform scale is the smaller of the two axis ratios; the
            // shorter axis is letterboxed (centred) within the PNG.
            double sx = widthMm  > 0 ? widthPx  / widthMm  : 0;
            double sy = heightMm > 0 ? heightPx / heightMm : 0;
            this.pxPerMm = (sx > 0 && sy > 0) ? Math.min(sx, sy) : Math.max(sx, sy);
            this.contentWidthPx  = widthMm  * pxPerMm;
            this.contentHeightPx = heightMm * pxPerMm;
            this.contentOffsetXpx = (widthPx  - contentWidthPx)  / 2.0;
            this.contentOffsetYpx = (heightPx - contentHeightPx) / 2.0;
            // Pixel position of the gerber datum (0,0). In gerber space the rect spans
            // X∈[minXmm, minXmm+widthMm], Y∈[minYmm, minYmm+heightMm]. Without mirroring,
            // gerber X maps straight to viewBox X; mirroring reflects it about the rect's
            // centre. With flipY, the image top is gerber maxY, so pixel-Y runs downward
            // from there.
            this.originXpx = contentOffsetXpx
                + (mirrored ? (minXmm + widthMm) : -minXmm) * pxPerMm;
            this.originYpx = contentOffsetYpx
                + (flipY ? (minYmm + heightMm) : -minYmm) * pxPerMm;
        }

        /** Millimetres per pixel — convenience inverse of {@link #pxPerMm}. */
        public double mmPerPx() { return pxPerMm > 0 ? 1.0 / pxPerMm : 0; }
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
        PngWithScale r = renderRealisticSidePngWithScale(
            layers, side, widthPx, heightPx, mirrorBottom);
        return r == null ? null : r.png;
    }

    /**
     * Render a realistic-view PNG and return it together with the px↔mm scale and the
     * mm rectangle it covers, so a caller can overlay real-world measurements (e.g. a
     * 10 mm grid behind the board). Same rendering and caps as
     * {@link #renderRealisticSidePng(List, Side, int, int, boolean)}.
     *
     * @return a {@link PngWithScale}, or {@code null} if the side couldn't be rendered
     * @throws IllegalArgumentException if both dimensions are {@code <= 0}
     */
    public PngWithScale renderRealisticSidePngWithScale(List<Layer> layers, Side side,
            int widthPx, int heightPx, boolean mirrorBottom) {
        if (widthPx <= 0 && heightPx <= 0) {
            throw new IllegalArgumentException(
                "At least one of widthPx/heightPx must be positive");
        }
        // Thumbnail raster cap: clamp any explicitly-requested dimension to the thumbnail
        // ceiling so a caller asking for a large PNG can't drive Batik's masked working set
        // into OOM territory. The derived dimension below is clamped to the same ceiling.
        if (widthPx  > MAX_THUMBNAIL_DIMENSION_PX) widthPx  = MAX_THUMBNAIL_DIMENSION_PX;
        if (heightPx > MAX_THUMBNAIL_DIMENSION_PX) heightPx = MAX_THUMBNAIL_DIMENSION_PX;

        // Thumbnails want a more generous, visible margin than the default 0.5 mm
        // used for overlay/DRC work — scale with board size so small and large
        // boards both get visible breathing room around the outline.
        double prevMargin = this.margin;
        boolean prevPreferOutline = this.preferOutlineBoundsForViewBox;
        this.margin = computeThumbnailMargin(layers);
        // Frame the raster to the board outline, not the union of all layers.
        this.preferOutlineBoundsForViewBox = true;
        String svg;
        try {
            svg = renderRealisticSide(layers, side, mirrorBottom);
        } finally {
            this.margin = prevMargin;
            this.preferOutlineBoundsForViewBox = prevPreferOutline;
        }
        if (svg == null) return null;

        // The viewBox is the image's mm extent (board bounds + thumbnail margin) and is
        // the source of truth for the px↔mm scale below.
        double[] vb = parseViewBox(svg);

        // Derive the missing dimension from the SVG's viewBox so the PNG's
        // aspect ratio exactly matches the board's X/Y extent (plus margin).
        // Passing both dimensions explicitly avoids any ambiguity in how
        // Batik resolves a single KEY_WIDTH/KEY_HEIGHT hint.
        if (widthPx <= 0 || heightPx <= 0) {
            if (vb != null && vb[2] > 0 && vb[3] > 0) {
                double aspect = vb[2] / vb[3];
                // Compute in long and clamp: a pathological aspect ratio (e.g. a stray
                // element inflating the viewBox of an outline-less board) can otherwise
                // overflow the int cast and/or demand a multi-gigabyte raster from Batik.
                if (widthPx <= 0) {
                    long w = Math.round((double) heightPx * aspect);
                    widthPx = (int) Math.max(1, Math.min(w, MAX_THUMBNAIL_DIMENSION_PX));
                }
                if (heightPx <= 0) {
                    long h = Math.round((double) widthPx / aspect);
                    heightPx = (int) Math.max(1, Math.min(h, MAX_THUMBNAIL_DIMENSION_PX));
                }
            }
        }
        byte[] png = rasterizeSvgToPng(svg, widthPx, heightPx);
        // rasterizeSvgToPng only ever clamps *down* (8192/16 M-px backstop); for a
        // thumbnail (≤1024² ≈ 1 M px) it never fires, so widthPx/heightPx here are the
        // PNG's true dimensions and the scale below is exact.
        double minXmm = vb != null ? vb[0] : 0;
        double minYmm = vb != null ? vb[1] : 0;
        double widthMm  = vb != null ? vb[2] : 0;
        double heightMm = vb != null ? vb[3] : 0;
        boolean mirrored = mirrorBottom && side == Side.BOTTOM;
        PngWithScale result = new PngWithScale(
            png, widthPx, heightPx, minXmm, minYmm, widthMm, heightMm, side, mirrored, flipY);
        // Make the PNG self-describing: embed the scale (pHYs) and full mm geometry +
        // datum-origin + side (tEXt).
        byte[] withMeta = embedScaleMetadata(png, result.pxPerMm, minXmm, minYmm,
            widthMm, heightMm, result.contentOffsetXpx, result.contentOffsetYpx,
            result.originXpx, result.originYpx, side, mirrored);
        if (withMeta == png) return result; // not a PNG (shouldn't happen) — return as-is
        return new PngWithScale(withMeta, widthPx, heightPx,
            minXmm, minYmm, widthMm, heightMm, side, mirrored, flipY);
    }

    /** Color used for the annotation underlay in {@link #renderBoardOverviewPng}. */
    private static final String OVERVIEW_ANNOTATION_COLOR = "#303030";

    /**
     * Render a single "board overview" image: the realistic view of one side,
     * composited over an all-layers rendering of the <em>entire</em> Gerber set on a
     * white canvas sized to the union of every layer's bounds.
     * <p>
     * Rationale: drill charts, stackup tables and fab notes are usually drawn
     * <em>outside</em> the board outline (on legend/mechanical/assembly layers, or in
     * the unused area of any layer). The realistic view clips to the board outline and
     * drops those layers entirely, so that information is invisible. This composite
     * keeps the realistic board where the board is, and shows everything else around
     * it in dark gray — one image that a vision model can read end to end.
     * <p>
     * The realistic board is drawn opaquely on top of the underlay, so per-layer
     * clutter inside the board area is hidden; only annotation content outside the
     * outline remains visible. When no realistic view can be produced (no outline and
     * no copper to derive one from), the all-layers underlay alone is returned.
     *
     * @param layers  the full layer set with layer types assigned
     * @param side    which side to show realistically (annotations come from all layers)
     * @param widthPx target canvas width in pixels; height follows the union aspect ratio
     * @return PNG bytes
     */
    public byte[] renderBoardOverviewPng(List<Layer> layers, Side side, int widthPx) {
        if (layers == null || layers.isEmpty()) {
            throw new IllegalArgumentException("layers must not be empty");
        }

        // Union of every layer's bounds — the canvas extent.
        BoundingBox union = new BoundingBox();
        for (Layer l : layers) {
            BoundingBox bb = l.getBoundingBox();
            if (bb.isValid()) union.extend(bb);
        }
        if (!union.isValid()) {
            throw new IllegalArgumentException("layers contain no drawable content");
        }
        double unionMargin = 1.5;
        double uMinX = union.getMinX() - unionMargin;
        double uMaxY = union.getMaxY() + unionMargin;
        double uW = union.getWidth() + 2 * unionMargin;
        double uH = union.getHeight() + 2 * unionMargin;

        int canvasW = Math.max(200, Math.min(widthPx, MAX_RASTER_DIMENSION_PX));
        int canvasH = (int) Math.max(1, Math.round(canvasW * uH / uW));
        if ((long) canvasW * canvasH > MAX_RASTER_PIXELS) {
            double scale = Math.sqrt((double) MAX_RASTER_PIXELS / ((double) canvasW * canvasH));
            canvasW = Math.max(1, (int) Math.floor(canvasW * scale));
            canvasH = Math.max(1, (int) Math.floor(canvasH * scale));
        }
        double pxPerMm = canvasW / uW;

        try {
            // 1. Annotation underlay, rasterized PER LAYER and composited as bitmaps.
            //    One combined SVG of a dense multi-layer set builds a Batik GVT tree of
            //    the whole board (hundreds of MB); per-layer jobs bound peak memory by
            //    the largest single layer. Each layer is rendered in its own mm frame
            //    and placed on the canvas by the same px<->mm mapping as the board.
            BufferedImage underlay = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ug = underlay.createGraphics();
            try {
                ug.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                for (Layer l : layers) {
                    BoundingBox lb = l.getBoundingBox();
                    if (!lb.isValid()) continue;
                    Layer copy = l.isGerber()
                            ? new Layer(l.getName(), l.getGerberDoc())
                            : new Layer(l.getName(), l.getDrillDoc());
                    copy.setLayerType(l.getLayerType());
                    copy.setColor(OVERVIEW_ANNOTATION_COLOR);
                    copy.setOpacity(1.0);
                    copy.setVisible(true);

                    double layerMargin = 0.2; // keep edge strokes inside the frame
                    double lW = lb.getWidth() + 2 * layerMargin;
                    double lH = lb.getHeight() + 2 * layerMargin;
                    int lPxW = Math.max(1, (int) Math.round(lW * pxPerMm));
                    int lPxH = Math.max(1, (int) Math.round(lH * pxPerMm));

                    MultiLayerSVGRenderer layerRenderer = new MultiLayerSVGRenderer()
                            .setMargin(layerMargin)
                            .setFlipY(flipY);
                    byte[] layerPng;
                    try {
                        layerPng = rasterizeSvgToPng(
                                layerRenderer.render(List.of(copy)), lPxW, lPxH);
                    } catch (Exception | OutOfMemoryError e) {
                        continue; // skip a layer Batik can't handle; keep the rest
                    }
                    BufferedImage layerImg = ImageIO.read(new ByteArrayInputStream(layerPng));
                    if (layerImg == null) continue;
                    int dx = (int) Math.round((lb.getMinX() - layerMargin - uMinX) * pxPerMm);
                    int dy = (int) Math.round((uMaxY - (lb.getMaxY() + layerMargin)) * pxPerMm);
                    ug.drawImage(layerImg, dx, dy, lPxW, lPxH, null);
                }
            } finally {
                ug.dispose();
            }

            // 2. Realistic board for the requested side (never mirrored — annotation
            //    text must stay readable, so geometry orientation is preserved).
            PngWithScale board = null;
            try {
                board = renderRealisticSidePngWithScale(layers, side,
                        Math.min(canvasW, MAX_THUMBNAIL_DIMENSION_PX), 0, false);
            } catch (Exception | OutOfMemoryError e) {
                // No outline and none derivable — the overview degrades to the
                // underlay only; the annotation content is the point here.
            }

            // 3. Composite on white.
            BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, canvasW, canvasH);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (underlay != null) {
                    g.drawImage(underlay, 0, 0, canvasW, canvasH, null);
                }
                if (board != null && board.png != null) {
                    BufferedImage boardImg = ImageIO.read(new ByteArrayInputStream(board.png));
                    if (boardImg != null) {
                        // The board PNG covers the mm rect [minXmm..minXmm+widthMm] x
                        // [minYmm..minYmm+heightMm] (Y-up); content rect is letterbox-aware.
                        int dx1 = (int) Math.round((board.minXmm - uMinX) * pxPerMm);
                        int dy1 = (int) Math.round((uMaxY - (board.minYmm + board.heightMm)) * pxPerMm);
                        int dx2 = dx1 + (int) Math.round(board.widthMm * pxPerMm);
                        int dy2 = dy1 + (int) Math.round(board.heightMm * pxPerMm);
                        int sx1 = (int) Math.round(board.contentOffsetXpx);
                        int sy1 = (int) Math.round(board.contentOffsetYpx);
                        int sx2 = sx1 + (int) Math.round(board.contentWidthPx);
                        int sy2 = sy1 + (int) Math.round(board.contentHeightPx);
                        g.drawImage(boardImg, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, null);
                    }
                }
            } finally {
                g.dispose();
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(canvas, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("board overview rendering failed", e);
        }
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

    /** 8-byte PNG file signature. */
    private static final byte[] PNG_SIGNATURE =
        {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * Embed the px↔mm scale into a freshly-encoded PNG so the image is self-describing:
     * <ul>
     *   <li>a standard {@code pHYs} chunk recording pixels-per-metre (uniform on both
     *       axes) — image viewers and libraries read this as the image's resolution/DPI;</li>
     *   <li>{@code tEXt} chunks: {@code side} (\"top\"/\"bottom\"), {@code pxPerMm} (the
     *       exact scale as text), and {@code boardGeometryMm} (a JSON object with the side,
     *       mirror flag, the gerber-space mm rectangle the image covers, the letterbox
     *       content offset, and the pixel location of the gerber/drill datum origin), for
     *       callers that want to label the side and overlay real-world measurements such as
     *       a 10 mm grid anchored to the origin.</li>
     * </ul>
     * The new chunks replace any existing {@code pHYs} and are spliced in before the first
     * {@code IDAT} (as the spec requires). Returns the input unchanged if it isn't a PNG.
     */
    private static byte[] embedScaleMetadata(byte[] png, double pxPerMm,
            double minXmm, double minYmm, double widthMm, double heightMm,
            double contentOffsetXpx, double contentOffsetYpx,
            double originXpx, double originYpx, Side side, boolean mirrored) {
        if (png == null || png.length < 8 + 25) return png;
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (png[i] != PNG_SIGNATURE[i]) return png; // not a PNG — leave it alone
        }

        // pHYs: pixels-per-unit X/Y (big-endian, uint32) + unit specifier (1 = metre).
        long ppm = Math.round(pxPerMm * 1000.0); // px per mm -> px per metre
        if (ppm < 0) ppm = 0;
        byte[] phys = new byte[9];
        writeUInt32(phys, 0, ppm);
        writeUInt32(phys, 4, ppm);
        phys[8] = 1;

        String sideStr = side == Side.BOTTOM ? "bottom" : "top";
        String geometry = String.format(Locale.US,
            "{\"side\":\"%s\",\"mirrored\":%b,\"pxPerMm\":%.6f,"
            + "\"mmRect\":[%.6f,%.6f,%.6f,%.6f],"
            + "\"contentOffsetPx\":[%.3f,%.3f],\"originPx\":[%.3f,%.3f]}",
            sideStr, mirrored, pxPerMm, minXmm, minYmm, widthMm, heightMm,
            contentOffsetXpx, contentOffsetYpx, originXpx, originYpx);

        // Walk the chunk stream so we can (a) drop any pHYs Batik already wrote — leaving
        // two would make readers pick the wrong one — and (b) splice our chunks in just
        // before the first IDAT, satisfying the spec's "pHYs before IDAT" ordering.
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 128);
        out.write(png, 0, 8); // signature
        int pos = 8;
        boolean inserted = false;
        while (pos + 8 <= png.length) {
            long len = readUInt32(png, pos);
            int dataStart = pos + 8;
            int chunkEnd = (int) (dataStart + len + 4); // +4 CRC
            if (len < 0 || chunkEnd > png.length) break; // malformed — bail, keep original
            String type = new String(png, pos + 4, 4,
                java.nio.charset.StandardCharsets.US_ASCII);
            if ("pHYs".equals(type)) { pos = chunkEnd; continue; } // drop existing pHYs
            if (("IDAT".equals(type) || "IEND".equals(type)) && !inserted) {
                writeChunk(out, "pHYs", phys);
                writeChunk(out, "tEXt", textChunkData("side", sideStr));
                writeChunk(out, "tEXt", textChunkData("pxPerMm",
                    String.format(Locale.US, "%.6f", pxPerMm)));
                writeChunk(out, "tEXt", textChunkData("boardGeometryMm", geometry));
                inserted = true;
            }
            out.write(png, pos, chunkEnd - pos);
            pos = chunkEnd;
        }
        if (!inserted) return png; // never found IDAT/IEND — leave original untouched
        return out.toByteArray();
    }

    private static void writeUInt32(byte[] buf, int off, long v) {
        buf[off]     = (byte) ((v >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((v >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((v >>> 8) & 0xFF);
        buf[off + 3] = (byte) (v & 0xFF);
    }

    private static long readUInt32(byte[] buf, int off) {
        return ((long) (buf[off] & 0xFF) << 24)
             | ((buf[off + 1] & 0xFF) << 16)
             | ((buf[off + 2] & 0xFF) << 8)
             | (buf[off + 3] & 0xFF);
    }

    /** Latin-1 keyword + NUL separator + Latin-1 text, per the PNG {@code tEXt} format. */
    private static byte[] textChunkData(String keyword, String text) {
        byte[] k = keyword.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] t = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] data = new byte[k.length + 1 + t.length];
        System.arraycopy(k, 0, data, 0, k.length);
        data[k.length] = 0;
        System.arraycopy(t, 0, data, k.length + 1, t.length);
        return data;
    }

    /** Write a full PNG chunk: length(4) + type(4) + data + CRC32(type+data). */
    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] len = new byte[4];
        writeUInt32(len, 0, data.length);
        out.write(len, 0, 4);
        out.write(typeBytes, 0, 4);
        out.write(data, 0, data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        writeUInt32(crcBytes, 0, crc.getValue());
        out.write(crcBytes, 0, 4);
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

        // Clamp before handing to Batik — it allocates width*height*4 bytes for the raster,
        // so an unbounded dimension (from a pathological viewBox aspect ratio) OOMs the JVM.
        // Clamp each provided side, then, when both are known, scale the pair down
        // proportionally so the total stays under MAX_RASTER_PIXELS while preserving aspect.
        if (widthPx  > MAX_RASTER_DIMENSION_PX) widthPx  = MAX_RASTER_DIMENSION_PX;
        if (heightPx > MAX_RASTER_DIMENSION_PX) heightPx = MAX_RASTER_DIMENSION_PX;
        if (widthPx > 0 && heightPx > 0
                && (long) widthPx * heightPx > MAX_RASTER_PIXELS) {
            double scale = Math.sqrt((double) MAX_RASTER_PIXELS / ((double) widthPx * heightPx));
            widthPx  = Math.max(1, (int) Math.floor(widthPx  * scale));
            heightPx = Math.max(1, (int) Math.floor(heightPx * scale));
        }

        PNGTranscoder transcoder = new PNGTranscoder();
        if (widthPx > 0)  transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH,  (float) widthPx);
        if (heightPx > 0) transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) heightPx);
        // Backstop for the single-dimension callers: when only width (or height) is set,
        // Batik derives the other from the SVG's own aspect ratio, which can itself explode.
        // KEY_MAX_* bounds whatever Batik computes internally.
        transcoder.addTranscodingHint(PNGTranscoder.KEY_MAX_WIDTH,  (float) MAX_RASTER_DIMENSION_PX);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_MAX_HEIGHT, (float) MAX_RASTER_DIMENSION_PX);

        String batikSvg = makeBatikCompatible(svg);
        TranscoderInput input = new TranscoderInput(new StringReader(batikSvg));
        long buf = (long) Math.max(widthPx, heightPx) * 32;
        int initialBuf = (int) Math.min(Math.max(buf, 16384), 1 << 20);
        ByteArrayOutputStream out = new ByteArrayOutputStream(initialBuf);
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
            } else if (lt == LayerType.COPPER_INNER) {
                // side-agnostic; participates in outline derivation only
                out.add(layer);
                hasCopper = true;
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
