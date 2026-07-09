package com.deltaproto.deltagerber.model.gerber;

/**
 * Exact axis-aligned bounds of a circular arc.
 *
 * <p>The naive bound {@code center ± radius} is correct only for a full circle; for any partial
 * sweep it over-estimates, in the worst case by a full radius on three sides. This class instead
 * takes the two endpoints and adds only those cardinal points (0°, 90°, 180°, 270°) that the
 * sweep actually passes through.
 *
 * <p>Angles follow the Gerber convention: counter-clockwise is increasing angle in a
 * right-handed coordinate system.
 */
public final class ArcBounds {

    /** Endpoints closer than this are the same point, i.e. the arc closes into a full circle. */
    private static final double CLOSURE_EPSILON = 1e-9;
    private static final double ANGLE_EPSILON = 1e-12;
    private static final double TWO_PI = 2 * Math.PI;

    private ArcBounds() {
    }

    /**
     * Bounds of the arc centreline — the swept path itself, with no aperture/stroke width.
     *
     * @param clockwise sweep direction from start to end (G02 rather than G03)
     */
    public static BoundingBox of(double startX, double startY,
                                 double endX, double endY,
                                 double centerX, double centerY,
                                 boolean clockwise) {
        BoundingBox bounds = new BoundingBox();
        bounds.includePoint(startX, startY);
        bounds.includePoint(endX, endY);

        double radius = Math.hypot(startX - centerX, startY - centerY);
        if (radius <= 0) {
            return bounds;
        }

        // A Gerber full circle is written with coincident endpoints, which carries no sweep
        // information — every cardinal point is on it.
        boolean fullCircle = Math.hypot(endX - startX, endY - startY) < CLOSURE_EPSILON;
        double startAngle = Math.atan2(startY - centerY, startX - centerX);
        double endAngle = Math.atan2(endY - centerY, endX - centerX);

        for (int quadrant = 0; quadrant < 4; quadrant++) {
            double angle = quadrant * Math.PI / 2;
            if (fullCircle || sweepReaches(angle, startAngle, endAngle, clockwise)) {
                bounds.includePoint(centerX + radius * Math.cos(angle),
                                    centerY + radius * Math.sin(angle));
            }
        }
        return bounds;
    }

    /** Whether sweeping from {@code startAngle} to {@code endAngle} passes through {@code angle}. */
    private static boolean sweepReaches(double angle, double startAngle, double endAngle, boolean clockwise) {
        double toAngle = clockwise ? normalize(startAngle - angle) : normalize(angle - startAngle);
        double toEnd = clockwise ? normalize(startAngle - endAngle) : normalize(endAngle - startAngle);
        return toAngle <= toEnd + ANGLE_EPSILON;
    }

    /** Reduce an angle to [0, 2π). */
    private static double normalize(double angle) {
        double reduced = angle % TWO_PI;
        return reduced < 0 ? reduced + TWO_PI : reduced;
    }
}
