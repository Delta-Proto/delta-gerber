package com.deltaproto.deltagerber.model.gerber;

/**
 * Axis-aligned bounding box for graphics objects.
 */
public class BoundingBox {

    private double minX = Double.POSITIVE_INFINITY;
    private double minY = Double.POSITIVE_INFINITY;
    private double maxX = Double.NEGATIVE_INFINITY;
    private double maxY = Double.NEGATIVE_INFINITY;

    public BoundingBox() {
    }

    public BoundingBox(double minX, double minY, double maxX, double maxY) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    /**
     * Include a point in the bounding box.
     */
    public void includePoint(double x, double y) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
    }

    /**
     * Extend the bounding box to include a point.
     * Alias for includePoint for convenience.
     */
    public void extend(double x, double y) {
        includePoint(x, y);
    }

    /**
     * Extend the bounding box to include another bounding box.
     * Alias for include for convenience.
     */
    public void extend(BoundingBox other) {
        include(other);
    }

    /**
     * Include another bounding box.
     */
    public void include(BoundingBox other) {
        if (other != null && other.isValid()) {
            minX = Math.min(minX, other.minX);
            minY = Math.min(minY, other.minY);
            maxX = Math.max(maxX, other.maxX);
            maxY = Math.max(maxY, other.maxY);
        }
    }

    /**
     * Expand the bounding box by a margin.
     */
    public void expand(double margin) {
        if (isValid()) {
            minX -= margin;
            minY -= margin;
            maxX += margin;
            maxY += margin;
        }
    }

    /**
     * Check if the bounding box is valid (has been initialized).
     */
    public boolean isValid() {
        return minX != Double.POSITIVE_INFINITY &&
               minY != Double.POSITIVE_INFINITY &&
               maxX != Double.NEGATIVE_INFINITY &&
               maxY != Double.NEGATIVE_INFINITY;
    }

    public double getMinX() {
        return minX;
    }

    public double getMinY() {
        return minY;
    }

    public double getMaxX() {
        return maxX;
    }

    public double getMaxY() {
        return maxY;
    }

    public double getWidth() {
        return isValid() ? maxX - minX : 0;
    }

    public double getHeight() {
        return isValid() ? maxY - minY : 0;
    }

    public double getCenterX() {
        return isValid() ? (minX + maxX) / 2 : 0;
    }

    public double getCenterY() {
        return isValid() ? (minY + maxY) / 2 : 0;
    }

    @Override
    public String toString() {
        if (!isValid()) {
            return "BoundingBox[invalid]";
        }
        return String.format("BoundingBox[%.4f,%.4f - %.4f,%.4f]",
            minX, minY, maxX, maxY);
    }
}
