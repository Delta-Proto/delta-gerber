package com.deltaproto.deltagerber.model.drill;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;

/**
 * A routed slot from one point to another.
 */
public class DrillSlot extends DrillOperation {

    private final double startX;
    private final double startY;
    private final double endX;
    private final double endY;

    public DrillSlot(Tool tool, double startX, double startY, double endX, double endY) {
        super(tool);
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
    }

    public double getStartX() {
        return startX;
    }

    public double getStartY() {
        return startY;
    }

    public double getEndX() {
        return endX;
    }

    public double getEndY() {
        return endY;
    }

    @Override
    public BoundingBox getBoundingBox() {
        double r = tool.getDiameter() / 2;
        BoundingBox bbox = new BoundingBox();
        bbox.extend(startX - r, startY - r);
        bbox.extend(startX + r, startY + r);
        bbox.extend(endX - r, endY - r);
        bbox.extend(endX + r, endY + r);
        return bbox;
    }

    @Override
    public String toSvg() {
        // Render slot as a path with M/L commands (line with round caps)
        return String.format(java.util.Locale.US,
            "<path d=\"M %.6f %.6f L %.6f %.6f\" " +
            "stroke=\"currentColor\" stroke-width=\"%.6f\" stroke-linecap=\"round\" fill=\"none\"/>",
            startX, startY, endX, endY, tool.getDiameter());
    }

    @Override
    public String toString() {
        return String.format("DrillSlot[%.4f,%.4f -> %.4f,%.4f, T%d]",
            startX, startY, endX, endY, tool.getNumber());
    }
}
