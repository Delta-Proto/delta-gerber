package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillOperation;
import com.deltaproto.deltagerber.model.drill.Tool;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;

/**
 * Renders Excellon drill documents to SVG format.
 */
public class DrillSVGRenderer {

    private String drillColor = "#000000";
    private String slotColor = "#000000";
    private String backgroundColor = null;
    private boolean flipY = true;
    private double margin = 0;

    public DrillSVGRenderer() {
    }

    public DrillSVGRenderer setDrillColor(String color) {
        this.drillColor = color;
        return this;
    }

    public DrillSVGRenderer setSlotColor(String color) {
        this.slotColor = color;
        return this;
    }

    public DrillSVGRenderer setBackgroundColor(String color) {
        this.backgroundColor = color;
        return this;
    }

    public DrillSVGRenderer setFlipY(boolean flip) {
        this.flipY = flip;
        return this;
    }

    public DrillSVGRenderer setMargin(double margin) {
        this.margin = margin;
        return this;
    }

    public String render(DrillDocument doc) {
        BoundingBox bounds = doc.getBoundingBox();
        if (!bounds.isValid()) {
            return createEmptySvg();
        }

        double minX = bounds.getMinX() - margin;
        double minY = bounds.getMinY() - margin;
        double width = bounds.getWidth() + 2 * margin;
        double height = bounds.getHeight() + 2 * margin;

        StringBuilder svg = new StringBuilder();

        // SVG header
        svg.append(String.format(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "viewBox=\"%.6f %.6f %.6f %.6f\" " +
            "width=\"%.6fmm\" height=\"%.6fmm\">\n",
            minX, minY, width, height, width, height));

        // Style definitions
        svg.append("<style>\n");
        svg.append(String.format("  .drill { fill: %s; }\n", drillColor));
        svg.append(String.format("  .slot { stroke: %s; fill: none; }\n", slotColor));
        svg.append("</style>\n");

        // Tool definitions (for reference)
        svg.append("<defs>\n");
        for (Tool tool : doc.getTools().values()) {
            String def = tool.toSvgDef("tool" + tool.getNumber());
            svg.append("  ").append(def).append("\n");
        }
        svg.append("</defs>\n");

        // Apply Y flip if needed
        if (flipY) {
            svg.append(String.format(
                "<g transform=\"translate(0, %.6f) scale(1,-1)\">\n",
                minY + height + minY));
        }

        // Background rectangle
        if (backgroundColor != null) {
            svg.append(String.format(
                "<rect x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"%s\"/>\n",
                minX, minY, width, height, backgroundColor));
        }

        // Render all operations
        for (DrillOperation op : doc.getOperations()) {
            String opSvg = op.toSvg();
            if (opSvg != null && !opSvg.isEmpty()) {
                svg.append("  ").append(opSvg).append("\n");
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
