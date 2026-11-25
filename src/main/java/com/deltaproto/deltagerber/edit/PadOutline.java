package com.deltaproto.deltagerber.edit;

import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;

import java.util.List;

/**
 * The exact inked boundary of one dark graphics object (or of one separate piece of it), with the
 * oriented box that fits it. See {@link PadOutlines#of}.
 */
public final class PadOutline {

    /** What the inked shape is, exactly. */
    public enum Kind {
        /** A disc: {@link #width()} = {@link #height()} = the diameter. */
        CIRCLE,
        /** A rectangle, possibly rotated by {@link #rotationDeg()}. */
        RECTANGLE,
        /** A stadium: a rectangle with semicircular ends on its short sides. */
        OBROUND,
        /** Anything else, boxed by its minimum-area bounding rectangle. */
        POLYGON,
        /** The band swept by a draw or arc. */
        STROKE
    }

    private final int objectIndex;
    private final GraphicsObject source;
    private final Kind kind;
    private final double centerX;
    private final double centerY;
    private final double width;
    private final double height;
    private final double rotationDeg;
    private final List<double[]> ring;
    private final double strokeWidth;
    private final String apertureFunction;
    private final String componentRef;

    PadOutline(int objectIndex, GraphicsObject source, Kind kind, double centerX, double centerY,
               double width, double height, double rotationDeg, List<double[]> ring,
               double strokeWidth, String apertureFunction, String componentRef) {
        this.objectIndex = objectIndex;
        this.source = source;
        this.kind = kind;
        this.centerX = centerX;
        this.centerY = centerY;
        this.width = width;
        this.height = height;
        this.rotationDeg = rotationDeg;
        this.ring = List.copyOf(ring);
        this.strokeWidth = strokeWidth;
        this.apertureFunction = apertureFunction;
        this.componentRef = componentRef;
    }

    /** Index of the source object in {@code doc.getObjects()}. */
    public int objectIndex() {
        return objectIndex;
    }

    public GraphicsObject source() {
        return source;
    }

    public Kind kind() {
        return kind;
    }

    /** Centre of the oriented bounding box, mm. */
    public double centerX() {
        return centerX;
    }

    public double centerY() {
        return centerY;
    }

    /** Size of the oriented box along its width axis (the axis at {@link #rotationDeg()}), mm. */
    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    /** Counter-clockwise angle of the box's width axis, in [0, 180); 0 when axis-aligned. */
    public double rotationDeg() {
        return rotationDeg;
    }

    /**
     * The outer boundary, absolute mm, counter-clockwise, without repeating the first point.
     * Curves are flattened to within {@link PadOutlines#FLATNESS_MM}. Holes are not part of it.
     */
    public List<double[]> ring() {
        return ring;
    }

    /** The drawing aperture's size for a {@link Kind#STROKE} (a circle's diameter), else 0. */
    public double strokeWidth() {
        return strokeWidth;
    }

    /** The {@code .AperFunction} value, fields joined by commas ({@code "FiducialPad,Global"}), or null. */
    public String apertureFunction() {
        return apertureFunction;
    }

    /** The {@code .C} object attribute (component reference), or null. */
    public String componentRef() {
        return componentRef;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "PadOutline[#%d %s %.4fx%.4f @(%.4f, %.4f) %.2f° %d pts]",
                objectIndex, kind, width, height, centerX, centerY, rotationDeg, ring.size());
    }
}
