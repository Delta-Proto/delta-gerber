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
 * <p>Point containment is by ray parity over two axis indexes — the edges bucketed by the
 * horizontal band and the vertical column they cross — casting the ray in whichever direction
 * meets fewest edges, so a query against a 40 000-edge plane touches a handful of edges, not the
 * thousands packed along the board edge. A point within {@link #EDGE_EPSILON_MM} of an edge is never "strictly inside".
 */
public final class PolygonShape {

    /** A point closer than this to the boundary is on it, not inside. */
    public static final double EDGE_EPSILON_MM = 1e-7;

    private final double[][] rings;
    private final BoundingBox bounds;

    // Two axis indexes over the edges (ring, k), encoded as edge ids. Horizontal bands list every
    // edge whose y-range meets the band, columns every edge whose x-range meets the column, each
    // range grown by EDGE_EPSILON_MM so an edge near a query point is always in its list. Within a
    // band the ids are held twice, ordered by max x descending and by min x ascending, so a ray to
    // +x or -x visits only the edges on its side; the columns likewise for y.
    private final int edgeCount;
    private final int[] edgeRing;
    private final int[] edgeIndex;
    private final AxisIndex bands;
    private final AxisIndex columns;

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

        int cells = Math.max(1, Math.min(4096, (int) Math.sqrt(edges)));
        this.bands = new AxisIndex(this, true, b.getMinY(), b.getHeight(), cells);
        this.columns = new AxisIndex(this, false, b.getMinX(), b.getWidth(), cells);
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

    /**
     * Whether the point is strictly inside the material — on an edge counts as outside.
     *
     * <p>Ray parity, cast along whichever of the four axis directions crosses the fewest edges. A
     * board-sized plane clearance is tens of thousands of edges, most of them packed along the
     * board edge, so a horizontal ray from a point near the top edge would walk every edge of that
     * strip while the ray straight up meets a handful. Every direction gives the same parity for a
     * point off the boundary, and a point within {@link #EDGE_EPSILON_MM} of an edge is outside
     * whichever is cast: each direction's list holds every edge that near.
     */
    public boolean contains(double px, double py) {
        if (px < bounds.getMinX() || px > bounds.getMaxX() || py < bounds.getMinY() || py > bounds.getMaxY()) {
            return false;
        }
        int band = bands.cellOf(py);
        int column = columns.cellOf(px);
        int plusX = bands.countUp(band, px), minusX = bands.countDown(band, px);
        int plusY = columns.countUp(column, py), minusY = columns.countDown(column, py);
        int best = Math.min(Math.min(plusX, minusX), Math.min(plusY, minusY));
        if (best == plusX) {
            return cast(bands, band, plusX, true, true, px, py);
        } else if (best == minusX) {
            return cast(bands, band, minusX, true, false, px, py);
        } else if (best == plusY) {
            return cast(columns, column, plusY, false, true, px, py);
        }
        return cast(columns, column, minusY, false, false, px, py);
    }

    /**
     * Parity of a ray from the point along one axis direction, over the first {@code count} edges of
     * the cell's list for that direction — exactly the edges that reach the ray's side.
     */
    private boolean cast(AxisIndex index, int cell, int count, boolean horizontal, boolean up,
                         double px, double py) {
        int[] order = up ? index.byMaxDesc : index.byMinAsc;
        int start = index.start[cell];
        int crossings = 0;
        for (int i = start; i < start + count; i++) {
            int e = order[i];
            double ax = x1(e), ay = y1(e), bx = x2(e), by = y2(e);
            if (nearEdge(px, py, ax, ay, bx, by)) {
                return false;
            }
            if (horizontal) {
                if ((ay > py) != (by > py)) {
                    double x = ax + (py - ay) / (by - ay) * (bx - ax);
                    if (up ? x > px : x < px) {
                        crossings++;
                    }
                }
            } else if ((ax > px) != (bx > px)) {
                double y = ay + (px - ax) / (bx - ax) * (by - ay);
                if (up ? y > py : y < py) {
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

    /**
     * Edges bucketed along one axis — horizontal bands (by y) or vertical columns (by x) — and,
     * within each cell, ordered twice by their extent across the other axis: by its maximum
     * descending, so the edges reaching past a coordinate are a prefix, and by its minimum
     * ascending, so the edges reaching before it are a prefix too.
     */
    private static final class AxisIndex {
        private final double origin;
        private final double cellSize;
        private final int cellCount;
        private final int[] start;
        private final int[] byMaxDesc;
        private final int[] byMinAsc;
        private final double[] maxDesc;
        private final double[] minAsc;

        AxisIndex(PolygonShape shape, boolean bands, double origin, double extent, int cellCount) {
            this.origin = origin;
            this.cellCount = cellCount;
            this.cellSize = Math.max(extent, 1e-9) / cellCount;
            int edges = shape.edgeCount;
            int[] counts = new int[cellCount + 1];
            for (int e = 0; e < edges; e++) {
                int lo = cellOf(along(shape, e, bands, false) - EDGE_EPSILON_MM);
                int hi = cellOf(along(shape, e, bands, true) + EDGE_EPSILON_MM);
                for (int c = lo; c <= hi; c++) {
                    counts[c + 1]++;
                }
            }
            for (int c = 0; c < cellCount; c++) {
                counts[c + 1] += counts[c];
            }
            this.start = counts;
            int total = counts[cellCount];
            int[] members = new int[total];
            int[] fill = new int[cellCount];
            for (int e = 0; e < edges; e++) {
                int lo = cellOf(along(shape, e, bands, false) - EDGE_EPSILON_MM);
                int hi = cellOf(along(shape, e, bands, true) + EDGE_EPSILON_MM);
                for (int c = lo; c <= hi; c++) {
                    members[start[c] + fill[c]++] = e;
                }
            }
            this.byMaxDesc = new int[total];
            this.byMinAsc = new int[total];
            this.maxDesc = new double[total];
            this.minAsc = new double[total];
            for (int c = 0; c < cellCount; c++) {
                int from = start[c], to = start[c + 1];
                Integer[] ids = new Integer[to - from];
                for (int i = from; i < to; i++) {
                    ids[i - from] = members[i];
                }
                java.util.Arrays.sort(ids, (a, b) -> Double.compare(across(shape, b, bands, true), across(shape, a, bands, true)));
                for (int i = 0; i < ids.length; i++) {
                    byMaxDesc[from + i] = ids[i];
                    maxDesc[from + i] = across(shape, ids[i], bands, true);
                }
                java.util.Arrays.sort(ids, (a, b) -> Double.compare(across(shape, a, bands, false), across(shape, b, bands, false)));
                for (int i = 0; i < ids.length; i++) {
                    byMinAsc[from + i] = ids[i];
                    minAsc[from + i] = across(shape, ids[i], bands, false);
                }
            }
        }

        int cellOf(double v) {
            int c = (int) ((v - origin) / cellSize);
            return Math.max(0, Math.min(cellCount - 1, c));
        }

        /** Edges of the cell whose extent reaches {@code v - ε} or beyond: a ray towards +. */
        int countUp(int cell, double v) {
            double limit = v - EDGE_EPSILON_MM;
            int lo = start[cell], hi = start[cell + 1];
            while (lo < hi) {               // first index whose max is below the limit
                int mid = (lo + hi) >>> 1;
                if (maxDesc[mid] >= limit) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo - start[cell];
        }

        /** Edges of the cell whose extent reaches {@code v + ε} or before: a ray towards −. */
        int countDown(int cell, double v) {
            double limit = v + EDGE_EPSILON_MM;
            int lo = start[cell], hi = start[cell + 1];
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (minAsc[mid] <= limit) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo - start[cell];
        }

        /** The edge's extent along the indexed axis (y for bands, x for columns). */
        private static double along(PolygonShape s, int e, boolean bands, boolean max) {
            double a = bands ? s.y1(e) : s.x1(e);
            double b = bands ? s.y2(e) : s.x2(e);
            return max ? Math.max(a, b) : Math.min(a, b);
        }

        /** The edge's extent across it — the direction a ray in this index travels. */
        private static double across(PolygonShape s, int e, boolean bands, boolean max) {
            double a = bands ? s.x1(e) : s.y1(e);
            double b = bands ? s.x2(e) : s.y2(e);
            return max ? Math.max(a, b) : Math.min(a, b);
        }
    }
}
