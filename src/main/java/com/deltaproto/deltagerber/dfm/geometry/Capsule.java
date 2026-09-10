package com.deltaproto.deltagerber.dfm.geometry;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;

/**
 * A line segment swept by a disc: the copper a round aperture lays down along a draw, an obround
 * pad, or — with zero length — a round pad. With {@code r == 0} it is a bare edge of a polygon.
 *
 * <p>This is the one primitive every distance in the DFM geometry reduces to. The distance between
 * two capsules is the distance between their axes less both radii, which is exact for round
 * apertures with no flattening at all; a polygon contributes its edges as zero-radius capsules, so
 * a polygon-to-pad distance is the same computation. Everything here is in millimetres.
 */
public final class Capsule {

    public final double x1;
    public final double y1;
    public final double x2;
    public final double y2;
    public final double r;

    public Capsule(double x1, double y1, double x2, double y2, double r) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.r = r;
    }

    /** A round pad: a capsule of zero length. */
    public static Capsule disc(double cx, double cy, double r) {
        return new Capsule(cx, cy, cx, cy, r);
    }

    public double length() {
        return Math.hypot(x2 - x1, y2 - y1);
    }

    public BoundingBox bounds() {
        return new BoundingBox(Math.min(x1, x2) - r, Math.min(y1, y2) - r,
                Math.max(x1, x2) + r, Math.max(y1, y2) + r);
    }

    /** Point on the axis nearest to {@code (px, py)}, as {@code {x, y}}. */
    public double[] nearestOnAxis(double px, double py) {
        double vx = x2 - x1;
        double vy = y2 - y1;
        double l2 = vx * vx + vy * vy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * vx + (py - y1) * vy) / l2));
        return new double[]{x1 + t * vx, y1 + t * vy};
    }

    /** Distance from {@code (px, py)} to the axis. */
    public double axisDistanceTo(double px, double py) {
        double[] q = nearestOnAxis(px, py);
        return Math.hypot(px - q[0], py - q[1]);
    }

    /** Distance from {@code (px, py)} to the solid body; zero inside it. */
    public double distanceTo(double px, double py) {
        return Math.max(0, axisDistanceTo(px, py) - r);
    }

    /** Whether the point lies strictly inside the solid body — never for a bare edge. */
    public boolean contains(double px, double py) {
        return r > 0 && axisDistanceTo(px, py) < r;
    }

    /** Distance between the two axes: zero when they cross. */
    public double axisDistance(Capsule o) {
        double[] c = closestAxisPoints(o);
        return Math.hypot(c[0] - c[2], c[1] - c[3]);
    }

    /** Distance between the two solid bodies: zero when they overlap. */
    public double distance(Capsule o) {
        return Math.max(0, axisDistance(o) - r - o.r);
    }

    /**
     * The closest pair of points on the two axes, {@code {x, y}} on this one followed by
     * {@code {x, y}} on the other. Two crossing axes give their crossing point twice; otherwise the
     * minimum is attained at an endpoint of one of them, so the four endpoint candidates suffice.
     */
    public double[] closestAxisPoints(Capsule o) {
        double[] x = axisIntersection(o);
        if (x != null) {
            return new double[]{x[0], x[1], x[0], x[1]};
        }
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        double[][] candidates = {
                pair(x1, y1, o.nearestOnAxis(x1, y1)),
                pair(x2, y2, o.nearestOnAxis(x2, y2)),
                flip(o.x1, o.y1, nearestOnAxis(o.x1, o.y1)),
                flip(o.x2, o.y2, nearestOnAxis(o.x2, o.y2)),
        };
        for (double[] c : candidates) {
            double d = Math.hypot(c[0] - c[2], c[1] - c[3]);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /**
     * The closest pair of points on the two <em>surfaces</em>, in the same layout as
     * {@link #closestAxisPoints}: each axis point pushed toward the other by its radius. Where the
     * bodies overlap the two points are the same — the middle of the overlap along the connector,
     * which is a point inside both.
     */
    public double[] closestSurfacePoints(Capsule o) {
        double[] c = closestAxisPoints(o);
        double dx = c[2] - c[0];
        double dy = c[2 + 1] - c[1];
        double d = Math.hypot(dx, dy);
        if (d == 0) {
            return c;
        }
        double ux = dx / d;
        double uy = dy / d;
        if (d <= r + o.r) {
            double mid = (r + (d - o.r)) / 2;
            double mx = c[0] + ux * mid;
            double my = c[1] + uy * mid;
            return new double[]{mx, my, mx, my};
        }
        return new double[]{c[0] + ux * r, c[1] + uy * r, c[2] - ux * o.r, c[3] - uy * o.r};
    }

    /**
     * Where the two axes cross, or {@code null} when they do not — including the collinear and
     * touching cases, which the callers treat by containment instead.
     */
    public double[] axisIntersection(Capsule o) {
        double d1 = cross(x2 - x1, y2 - y1, o.x1 - x1, o.y1 - y1);
        double d2 = cross(x2 - x1, y2 - y1, o.x2 - x1, o.y2 - y1);
        double d3 = cross(o.x2 - o.x1, o.y2 - o.y1, x1 - o.x1, y1 - o.y1);
        double d4 = cross(o.x2 - o.x1, o.y2 - o.y1, x2 - o.x1, y2 - o.y1);
        if (!((d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0))) {
            return null;
        }
        double t = d1 / (d1 - d2);
        return new double[]{o.x1 + t * (o.x2 - o.x1), o.y1 + t * (o.y2 - o.y1)};
    }

    private static double cross(double ax, double ay, double bx, double by) {
        return ax * by - ay * bx;
    }

    private static double[] pair(double px, double py, double[] q) {
        return new double[]{px, py, q[0], q[1]};
    }

    private static double[] flip(double px, double py, double[] q) {
        return new double[]{q[0], q[1], px, py};
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "Capsule[(%.4f,%.4f)-(%.4f,%.4f) r=%.4f]", x1, y1, x2, y2, r);
    }
}
