package com.deltaproto.deltagerber.model.drill;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;

/**
 * A single drill hit at a specific location.
 */
public class DrillHit extends DrillOperation {

    private final double x;
    private final double y;

    public DrillHit(Tool tool, double x, double y) {
        super(tool);
        this.x = x;
        this.y = y;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    @Override
    public BoundingBox getBoundingBox() {
        double r = tool.getDiameter() / 2;
        return new BoundingBox(x - r, y - r, x + r, y + r);
    }

    @Override
    public String toSvg() {
        return String.format(java.util.Locale.US,
            "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"%.6f\" fill=\"currentColor\"/>",
            x, y, tool.getDiameter() / 2);
    }

    @Override
    public String toString() {
        return String.format("DrillHit[%.4f, %.4f, T%d]", x, y, tool.getNumber());
    }
}
