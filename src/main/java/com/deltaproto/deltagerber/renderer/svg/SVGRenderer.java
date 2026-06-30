package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.ImagePolarity;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;

import java.util.List;
import java.util.Locale;

/**
 * Renders Gerber documents to SVG format.
 */
public class SVGRenderer {

    private String darkColor = "#000000";
    private String clearColor = "#ffffff";
    private String backgroundColor = null;
    private boolean flipY = true;
    private double margin = 0;
    private Double fixedViewBoxSize = null;  // If set, use a fixed square viewBox centered on content
    private SvgOptions svgOptions = SvgOptions.exact();  // Default to exact mode

    public SVGRenderer() {
    }

    /**
     * Set the SVG output options (exact or polygonized mode).
     * @param options the SVG options to use
     * @return this renderer for method chaining
     */
    public SVGRenderer setSvgOptions(SvgOptions options) {
        this.svgOptions = options;
        return this;
    }

    /**
     * Enable polygonized mode for geometry processing compatibility.
     * @return this renderer for method chaining
     */
    public SVGRenderer setPolygonizeMode() {
        this.svgOptions = SvgOptions.polygonized();
        return this;
    }

    /**
     * Enable exact mode (default) for maximum precision.
     * @return this renderer for method chaining
     */
    public SVGRenderer setExactMode() {
        this.svgOptions = SvgOptions.exact();
        return this;
    }

    public SVGRenderer setDarkColor(String color) {
        this.darkColor = color;
        return this;
    }

    public SVGRenderer setClearColor(String color) {
        this.clearColor = color;
        return this;
    }

    public SVGRenderer setBackgroundColor(String color) {
        this.backgroundColor = color;
        return this;
    }

    public SVGRenderer setFlipY(boolean flip) {
        this.flipY = flip;
        return this;
    }

    public SVGRenderer setMargin(double margin) {
        this.margin = margin;
        return this;
    }

    public SVGRenderer setIncludeBackground(boolean include) {
        if (include && backgroundColor == null) {
            backgroundColor = "#ffffff";
        } else if (!include) {
            backgroundColor = null;
        }
        return this;
    }

    /**
     * Sets a fixed square viewBox size. All renders will use this viewBox centered on the content.
     * This allows comparing sizes across different renders.
     * @param size The size of the square viewBox in mm, or null to use auto-fit
     */
    public SVGRenderer setFixedViewBoxSize(Double size) {
        this.fixedViewBoxSize = size;
        return this;
    }

    /**
     * Render the document and rasterize it to a PNG in one step.
     * <p>
     * All renderer settings (colors, margin, flipY, viewBox) apply. The background is
     * transparent unless {@link #setBackgroundColor(String)} or
     * {@link #setIncludeBackground(boolean)} is used — set a background when the PNG is
     * meant for human or vision-model reading (fab drawings, drill legends). Dimensions
     * are clamped by the same caps as
     * {@link MultiLayerSVGRenderer#rasterizeSvgToPng(String, int, int)}.
     *
     * @param doc     the parsed Gerber layer
     * @param widthPx target PNG width in pixels; height follows the layer's aspect ratio
     * @return PNG bytes
     */
    public byte[] renderPng(GerberDocument doc, int widthPx) {
        return renderPng(doc, widthPx, 0);
    }

    /**
     * Render the document and rasterize it to a PNG at explicit dimensions.
     * Pass {@code 0} for one dimension to derive it from the layer's aspect ratio.
     */
    public byte[] renderPng(GerberDocument doc, int widthPx, int heightPx) {
        // The SVG output carries a viewBox but no width/height attributes, so Batik
        // cannot derive a missing pixel dimension from it (it falls back to a 400px
        // default viewport). Derive it here from the document's own aspect ratio.
        if (widthPx <= 0 || heightPx <= 0) {
            double aspect = viewBoxAspect(doc);
            if (widthPx > 0) {
                heightPx = Math.max(1, (int) Math.round(widthPx / aspect));
            } else if (heightPx > 0) {
                widthPx = Math.max(1, (int) Math.round(heightPx * aspect));
            }
        }
        return MultiLayerSVGRenderer.rasterizeSvgToPng(render(doc), widthPx, heightPx);
    }

    /** Width/height ratio of the viewBox that {@link #render(GerberDocument)} will emit. */
    private double viewBoxAspect(GerberDocument doc) {
        if (fixedViewBoxSize != null) {
            return 1.0;
        }
        BoundingBox bounds = doc.getBoundingBox();
        if (!bounds.isValid()) {
            return 1.0; // createEmptySvg() uses a 1x1 viewBox
        }
        double width = bounds.getWidth() + 2 * margin;
        double height = bounds.getHeight() + 2 * margin;
        return height > 0 ? width / height : 1.0;
    }

    public String render(GerberDocument doc) {
        BoundingBox bounds = doc.getBoundingBox();
        if (!bounds.isValid()) {
            return createEmptySvg();
        }

        double minX, minY, width, height;

        if (fixedViewBoxSize != null) {
            // Use fixed viewBox centered on content
            double centerX = (bounds.getMinX() + bounds.getMaxX()) / 2;
            double centerY = (bounds.getMinY() + bounds.getMaxY()) / 2;
            double halfSize = fixedViewBoxSize / 2;
            minX = centerX - halfSize;
            minY = centerY - halfSize;
            width = fixedViewBoxSize;
            height = fixedViewBoxSize;
        } else {
            // Auto-fit to content with margin
            minX = bounds.getMinX() - margin;
            minY = bounds.getMinY() - margin;
            width = bounds.getWidth() + 2 * margin;
            height = bounds.getHeight() + 2 * margin;
        }

        StringBuilder svg = new StringBuilder();

        // SVG header
        svg.append(String.format(Locale.US,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "viewBox=\"%.6f %.6f %.6f %.6f\" " +
            "preserveAspectRatio=\"xMidYMid meet\">\n",
            minX, minY, width, height));

        // Set colors and flipY in svgOptions for direct fill attributes and arc direction
        svgOptions.setDarkColor(darkColor).setClearColor(clearColor).setFlipY(flipY);

        // Deprecated %IPNEG% inverts the whole image: a dark field that the image clears.
        boolean negative = doc.getImagePolarity() == ImagePolarity.NEGATIVE;
        String maskRect = PolarityMaskHelper.createMaskRect(minX, minY, width, height, 1);

        // Aperture definitions
        svg.append("<defs>\n");
        for (Aperture aperture : doc.getApertures().values()) {
            String def = aperture.toSvgDef("ap" + aperture.getDCode(), svgOptions);
            svg.append("  ").append(def).append("\n");
        }

        List<PolarityMaskHelper.PolarityGroup> groups = null;
        if (negative) {
            // Inversion mask: white (visible) where the positive image is ABSENT. Painting each
            // object in document order (dark→black, clear→white) reproduces the painter's model
            // directly inside the mask's luminance, so the dark field below shows through only
            // outside the image.
            SvgOptions negOpts = svgOptions.copy().setDarkColor("black").setClearColor("white");
            svg.append("  <mask id=\"ipneg\">\n");
            svg.append("    ").append(maskRect).append("\n");
            for (GraphicsObject obj : doc.getObjects()) {
                String objSvg = obj.toSvg(negOpts);
                if (objSvg != null && !objSvg.isEmpty()) {
                    svg.append("    ").append(objSvg).append("\n");
                }
            }
            svg.append("  </mask>\n");
        } else {
            // Group objects by polarity transitions and generate masks for clear groups
            groups = PolarityMaskHelper.groupByPolarity(doc.getObjects());
            SvgOptions maskOptions = svgOptions.copy();
            maskOptions.setDarkColor("black").setClearColor("black");
            PolarityMaskHelper.generateMaskDefs(svg, groups, "cm", maskRect, maskOptions);
        }
        svg.append("</defs>\n");

        // Apply Y flip if needed
        if (flipY) {
            svg.append(String.format(Locale.US,
                "<g transform=\"translate(0, %.6f) scale(1,-1)\">\n",
                minY + height + minY));
        }

        // Background rectangle
        if (backgroundColor != null) {
            svg.append(String.format(Locale.US,
                "<rect x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"%s\"/>\n",
                minX, minY, width, height, backgroundColor));
        }

        if (negative) {
            // The dark field, clipped to the image's negative by the inversion mask.
            svg.append(String.format(Locale.US,
                "<rect x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"%s\" mask=\"url(#ipneg)\"/>\n",
                minX, minY, width, height, darkColor));
        } else {
            // Render objects with mask wrapping for clear polarity groups
            PolarityMaskHelper.renderWithMasks(svg, groups, "cm", svgOptions);
        }

        if (flipY) {
            svg.append("</g>\n");
        }

        svg.append("</svg>");
        return svg.toString();
    }

    private String createEmptySvg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"></svg>";
    }
}
