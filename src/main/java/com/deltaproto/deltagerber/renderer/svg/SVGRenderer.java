package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;

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

        // Style definitions (kept for backwards compatibility with any CSS-based elements)
        svg.append("<style>\n");
        svg.append(String.format("  .dark { fill: %s; stroke: %s; }\n", darkColor, darkColor));
        svg.append(String.format("  .clear { fill: %s; stroke: %s; }\n", clearColor, clearColor));
        svg.append("</style>\n");

        // Set colors and flipY in svgOptions for direct fill attributes and arc direction
        svgOptions.setDarkColor(darkColor).setClearColor(clearColor).setFlipY(flipY);

        // Aperture definitions
        svg.append("<defs>\n");
        for (Aperture aperture : doc.getApertures().values()) {
            String def = aperture.toSvgDef("ap" + aperture.getDCode(), svgOptions);
            svg.append("  ").append(def).append("\n");
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

        // Render all objects
        for (GraphicsObject obj : doc.getObjects()) {
            String objSvg = obj.toSvg(svgOptions);
            if (objSvg != null && !objSvg.isEmpty()) {
                svg.append("  ").append(objSvg).append("\n");
            }
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
