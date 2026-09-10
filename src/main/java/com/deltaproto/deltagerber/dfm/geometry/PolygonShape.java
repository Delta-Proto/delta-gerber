package com.deltaproto.deltagerber.dfm.geometry;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * A filled polygon of one or more rings — a region, a rectangular or polygonal pad, a flattened
 * macro — with every ring wound so that <b>material lies to the left</b> of each edge: outer rings
 * counter-clockwise, holes clockwise, decided by nesting parity at construction.
 *
 * <p>That single orientation rule is what lets a width measurement tell copper from air without a
 * boolean: the copper-side normal of an edge is its left normal, on every ring, in every polygon.
 *
 * <p>Point containment is by ray parity over a band index — the edges bucketed by the horizontal
 * strip they cross — so a query against a 40 000-edge plane touches a few hundred edges, not all
 * of them. A point within {@link #EDGE_EPSILON_MM} of an edge is never "strictly inside".
 */
public final class PolygonShape {

    /** A point closer than this to the boundary is on it, not inside. */
    public static final double EDGE_EPSILON_MM = 1e-7;

    private final double[][] rings;
    private final BoundingBox bounds;
    private final int edgeCount;

    // Band index: edges (ring, k) encoded as edgeId, listed per horizontal band they cross.
    private final int bandCount;
    private final double bandY0;
    private final double bandHeight;
    private final int[] bandStart;
    private final int[] bandEdges;
    private final int[] edgeRing;
    private final int[] edgeIndex;

    private PolygonShape(double[][] rings) {
        this.rings = rings;
        BoundingBox b = new BoundingBox();
        int edges = 0;
        for (double[] ring : rings) {
            for (int k = 0; k < ring.length; k += 2) {
                b.includePoint(ring[k], ring[k + 1]);
            }
            edges += ring.length / 2;
        }
        this.bounds = b;
        this.edgeCount = edges;
        this.edgeRing = new int[edges];
        this.edgeIndex = new int[edges];
        int id = 0;
        for (int r = 0; r < rings.length; r++) {
            for (int k = 0; k < rings[r].length / 2; k++) {
                edgeRing[id] = r;
                edgeIndex[id] = k;
                id++;
            }
        }

        this.bandCount = Math.max(1, Math.min(4096, (int) Math.sqrt(edges)));
        this.bandY0 = b.getMinY();
        double height = Math.max(b.getHeight(), 1e-9);
        this.bandHeight = height / bandCount;
        int[] counts = new int[bandCount + 1];
        for (int e = 0; e < edges; e++) {
            int lo = bandOf(Math.min(y1(e), y2(e)));
            int hi = bandOf(Math.max(y1(e), y2(e)));
            for (int band = lo; band <= hi; band++) {
                counts[band + 1]++;
            }
        }
        for (int band = 0; band < bandCount; band++) {
            counts[band + 1] += counts[band];
        }
        this.bandStart = counts;
        this.bandEdges = new int[counts[bandCount]];
        int[] fill = new int[bandCount];
        for (int e = 0; e < edges; e++) {
            int lo = bandOf(Math.min(y1(e), y2(e)));
            int hi = bandOf(Math.max(y1(e), y2(e)));
            for (int band = lo; band <= hi; band++) {
                bandEdges[bandStart[band] + fill[band]++] = e;
            }
        }
    }

    /**
     * Build from rings given as {@code x0, y0, x1, y1, …} (not repeating the first point), in any
     * winding. Degenerate rings (fewer than three points, or zero area) are dropped; the winding of
     * the rest is normalised by nesting parity.
     */
    public static PolygonShape of(List<double[]> input) {
        List<double[]> kept = new ArrayList<>();
        for (double[] raw : input) {
            double[] ring = withoutRepeats(raw);
            if (ring.length >= 6 && Math.abs(signedArea(ring)) > 0) {
                kept.add(ring);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        double[][] rings = new double[kept.size()][];
        for (int i = 0; i < rings.length; i++) {
            double[] ring = kept.get(i);
            int depth = 0;
            for (int j = 0; j < kept.size(); j++) {
                if (j != i && rayParityInside(kept.get(j), ring[0], ring[1])) {
                    depth++;
                }
            }
            boolean wantCcw = depth % 2 == 0;
            boolean isCcw = signedArea(ring) > 0;
            rings[i] = isCcw == wantCcw ? ring : reversed(ring);
        }
        return new PolygonShape(rings);
    }

    public int ringCount() {
        return rings.length;
    }

    /** Number of vertices (and edges) of ring {@code r}. */
    public int ringSize(int r) {
        return rings[r].length / 2;
    }

    public int edgeCount() {
        return edgeCount;
    }

    public double x(int ring, int k) {
        return rings[ring][2 * k];
    }

    public double y(int ring, int k) {
        return rings[ring][2 * k + 1];
    }

    /** Edge {@code k} of ring {@code r}, from vertex {@code k} to vertex {@code k + 1}, as a bare capsule. */
    public Capsule edge(int r, int k) {
        double[] ring = rings[r];
        int n = ring.length / 2;
        int j = (k + 1) % n;
        return new Capsule(ring[2 * k], ring[2 * k + 1], ring[2 * j], ring[2 * j + 1], 0);
    }

    public BoundingBox bounds() {
        return bounds;
    }

    /**
     * Length of the boundary path from the end of edge {@code from} to the start of edge {@code to}
     * of ring {@code r}, in ring order, or from the end of {@code to} to the start of {@code from} —
     * whichever is shorter. Zero for consecutive edges. This is how far apart two edges are as
     * boundary, which is what tells a corner (short) from a neck (long).
     */
    public double pathBetween(int r, int from, int to) {
        double[] ring = rings[r];
        int n = ring.length / 2;
        double forward = 0, backward = 0;
        for (int k = (from + 1) % n; k != to; k = (k + 1) % n) {
            forward += edgeLength(ring, n, k);
        }
        for (int k = (to + 1) % n; k != from; k = (k + 1) % n) {
            backward += edgeLength(ring, n, k);
        }
        return Math.min(forward, backward);
    }

    private static double edgeLength(double[] ring, int n, int k) {
        int j = (k + 1) % n;
        return Math.hypot(ring[2 * j] - ring[2 * k], ring[2 * j + 1] - ring[2 * k + 1]);
    }

    public double area() {
        double sum = 0;
        for (double[] ring : rings) {
            sum += signedArea(ring);
        }
        return sum;
    }

    /** Whether the point is strictly inside the material — on an edge counts as outside. */
    public boolean contains(double px, double py) {
        if (px < bounds.getMinX() || px > bounds.getMaxX() || py < bounds.getMinY() || py > bounds.getMaxY()) {
            return false;
        }
        int band = bandOf(py);
        int crossings = 0;
        for (int i = bandStart[band]; i < bandStart[band + 1]; i++) {
            int e = bandEdges[i];
            double ax = x1(e), ay = y1(e), bx = x2(e), by = y2(e);
            if (nearEdge(px, py, ax, ay, bx, by)) {
                return false;
            }
            if ((ay > py) != (by > py)) {
                double x = ax + (py - ay) / (by - ay) * (bx - ax);
                if (x > px) {
                    crossings++;
                }
            }
        }
        return (crossings & 1) == 1;
    }

    /** Distance from the point to the nearest edge, over every edge: linear in the polygon. */
    public double boundaryDistance(double px, double py) {
        double best = Double.MAX_VALUE;
        for (int e = 0; e < edgeCount; e++) {
            best = Math.min(best, pointSegment(px, py, x1(e), y1(e), x2(e), y2(e)));
        }
        return best;
    }

    private static boolean nearEdge(double px, double py, double ax, double ay, double bx, double by) {
        if (px < Math.min(ax, bx) - EDGE_EPSILON_MM || px > Math.max(ax, bx) + EDGE_EPSILON_MM
                || py < Math.min(ay, by) - EDGE_EPSILON_MM || py > Math.max(ay, by) + EDGE_EPSILON_MM) {
            return false;
        }
        return pointSegment(px, py, ax, ay, bx, by) < EDGE_EPSILON_MM;
    }

    static double pointSegment(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay;
        double l2 = vx * vx + vy * vy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (py - ay) * vy) / l2));
        return Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }

    private int bandOf(double y) {
        int band = (int) ((y - bandY0) / bandHeight);
        return Math.max(0, Math.min(bandCount - 1, band));
    }

    private double x1(int e) {
        return rings[edgeRing[e]][2 * edgeIndex[e]];
    }

    private double y1(int e) {
        return rings[edgeRing[e]][2 * edgeIndex[e] + 1];
    }

    private double x2(int e) {
        double[] ring = rings[edgeRing[e]];
        int j = (edgeIndex[e] + 1) % (ring.length / 2);
        return ring[2 * j];
    }

    private double y2(int e) {
        double[] ring = rings[edgeRing[e]];
        int j = (edgeIndex[e] + 1) % (ring.length / 2);
        return ring[2 * j + 1];
    }

    static double signedArea(double[] ring) {
        double sum = 0;
        int n = ring.length / 2;
        for (int k = 0; k < n; k++) {
            int j = (k + 1) % n;
            sum += ring[2 * k] * ring[2 * j + 1] - ring[2 * j] * ring[2 * k + 1];
        }
        return sum / 2;
    }

    private static boolean rayParityInside(double[] ring, double px, double py) {
        int n = ring.length / 2;
        boolean inside = false;
        for (int k = 0; k < n; k++) {
            int j = (k + 1) % n;
            double ax = ring[2 * k], ay = ring[2 * k + 1], bx = ring[2 * j], by = ring[2 * j + 1];
            if ((ay > py) != (by > py) && px < ax + (py - ay) / (by - ay) * (bx - ax)) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** The ring without a point that repeats its predecessor (or, at the end, the first point). */
    private static double[] withoutRepeats(double[] ring) {
        int n = ring.length / 2;
        double[] out = new double[ring.length];
        int m = 0;
        for (int k = 0; k < n; k++) {
            double x = ring[2 * k], y = ring[2 * k + 1];
            if (m > 0 && out[2 * (m - 1)] == x && out[2 * (m - 1) + 1] == y) {
                continue;
            }
            out[2 * m] = x;
            out[2 * m + 1] = y;
            m++;
        }
        if (m > 1 && out[0] == out[2 * (m - 1)] && out[1] == out[2 * (m - 1) + 1]) {
            m--;
        }
        return m == n ? ring : java.util.Arrays.copyOf(out, 2 * m);
    }

    private static double[] reversed(double[] ring) {
        int n = ring.length / 2;
        double[] out = new double[ring.length];
        for (int k = 0; k < n; k++) {
            out[2 * k] = ring[2 * (n - 1 - k)];
            out[2 * k + 1] = ring[2 * (n - 1 - k) + 1];
        }
        return out;
    }
}
