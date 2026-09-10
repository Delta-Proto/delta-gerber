package com.deltaproto.deltagerber.dfm.geometry;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * A uniform grid over the boundary of a whole copper layer: every stroke, every pad and every
 * polygon edge is one <em>entry</em>, bucketed into the cells its bounds cover. A query walks the
 * cells around a point or box and hands back each entry once.
 *
 * <p>Cells are sized to the distances that matter — a fraction of a millimetre — so a neighbourhood
 * query costs the entries actually nearby rather than the layer. The index is compressed sparse
 * rows over the cells, built once; it holds ints, so a 300 000-edge layer costs a few megabytes.
 */
public final class EdgeGrid {

    private final double cellMm;
    private final double minX;
    private final double minY;
    private final int cols;
    private final int rows;
    private final int[] cellStart;
    private final int[] cellEntries;
    private final int entryCount;
    private final int[] stamp;
    private int query;

    private EdgeGrid(double cellMm, double minX, double minY, int cols, int rows,
                     int[] cellStart, int[] cellEntries, int entryCount) {
        this.cellMm = cellMm;
        this.minX = minX;
        this.minY = minY;
        this.cols = cols;
        this.rows = rows;
        this.cellStart = cellStart;
        this.cellEntries = cellEntries;
        this.entryCount = entryCount;
        this.stamp = new int[entryCount];
    }

    /** Build over {@code bounds} for entries whose bounds are given by {@code entryBounds}. */
    public static EdgeGrid build(BoundingBox bounds, double cellMm, List<BoundingBox> entryBounds) {
        double minX = bounds.getMinX() - cellMm;
        double minY = bounds.getMinY() - cellMm;
        int cols = (int) Math.ceil((bounds.getWidth() + 2 * cellMm) / cellMm) + 1;
        int rows = (int) Math.ceil((bounds.getHeight() + 2 * cellMm) / cellMm) + 1;
        int[] counts = new int[cols * rows + 1];
        int n = entryBounds.size();
        for (int e = 0; e < n; e++) {
            BoundingBox b = entryBounds.get(e);
            forCells(b, minX, minY, cellMm, cols, rows, c -> counts[c + 1]++);
        }
        for (int c = 0; c < cols * rows; c++) {
            counts[c + 1] += counts[c];
        }
        int[] entries = new int[counts[cols * rows]];
        int[] fill = new int[cols * rows];
        for (int e = 0; e < n; e++) {
            final int id = e;
            forCells(entryBounds.get(e), minX, minY, cellMm, cols, rows,
                    c -> entries[counts[c] + fill[c]++] = id);
        }
        return new EdgeGrid(cellMm, minX, minY, cols, rows, counts, entries, n);
    }

    public int entryCount() {
        return entryCount;
    }

    /** Every entry whose bounds come within {@code reachMm} of {@code box}, each once. */
    public void forEachNear(BoundingBox box, double reachMm, IntConsumer consumer) {
        forEachNear(box.getMinX() - reachMm, box.getMinY() - reachMm,
                box.getMaxX() + reachMm, box.getMaxY() + reachMm, consumer);
    }

    /** Every entry whose bounds come within {@code reachMm} of the point, each once. */
    public void forEachNear(double px, double py, double reachMm, IntConsumer consumer) {
        forEachNear(px - reachMm, py - reachMm, px + reachMm, py + reachMm, consumer);
    }

    /**
     * Every entry within {@code reachMm} of the segment, each once. Unlike a query by the segment's
     * bounds this walks the cells along it, so a long diagonal edge costs its length in cells, not
     * its bounding square.
     */
    public void forEachAlong(double ax, double ay, double bx, double by, double reachMm, IntConsumer consumer) {
        double len = Math.hypot(bx - ax, by - ay);
        if (len <= cellMm) {
            forEachNear(Math.min(ax, bx) - reachMm, Math.min(ay, by) - reachMm,
                    Math.max(ax, bx) + reachMm, Math.max(ay, by) + reachMm, consumer);
            return;
        }
        int q = nextQuery();
        int[] hits = new int[64];
        int n = 0;
        int steps = (int) Math.ceil(len / (cellMm / 2));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = ax + t * (bx - ax), py = ay + t * (by - ay);
            n = collect(px - reachMm - cellMm / 2, py - reachMm - cellMm / 2,
                    px + reachMm + cellMm / 2, py + reachMm + cellMm / 2, q, hits, n);
            hits = hitsRef;
        }
        deliver(hits, n, consumer);
    }

    private int nextQuery() {
        int q = ++query;
        if (q == Integer.MAX_VALUE) {
            java.util.Arrays.fill(stamp, 0);
            query = q = 1;
        }
        return q;
    }

    /**
     * The hits are gathered first and handed over afterwards. A consumer routinely runs queries of
     * its own on the same grid, and those re-stamp entries; delivering during the walk would let an
     * entry the outer walk had already seen be seen again from the next cell.
     */
    private void forEachNear(double x0, double y0, double x1, double y1, IntConsumer consumer) {
        int[] hits = new int[64];
        int n = collect(x0, y0, x1, y1, nextQuery(), hits, 0);
        deliver(hitsRef, n, consumer);
    }

    private int[] hitsRef;

    private int collect(double x0, double y0, double x1, double y1, int q, int[] hits, int n) {
        int cx0 = clampCol(x0), cx1 = clampCol(x1), cy0 = clampRow(y0), cy1 = clampRow(y1);
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                int c = cy * cols + cx;
                for (int i = cellStart[c]; i < cellStart[c + 1]; i++) {
                    int e = cellEntries[i];
                    if (stamp[e] != q) {
                        stamp[e] = q;
                        if (n == hits.length) {
                            hits = java.util.Arrays.copyOf(hits, n * 2);
                        }
                        hits[n++] = e;
                    }
                }
            }
        }
        hitsRef = hits;
        return n;
    }

    private static void deliver(int[] hits, int n, IntConsumer consumer) {
        for (int i = 0; i < n; i++) {
            consumer.accept(hits[i]);
        }
    }

    private int clampCol(double x) {
        return Math.max(0, Math.min(cols - 1, (int) ((x - minX) / cellMm)));
    }

    private int clampRow(double y) {
        return Math.max(0, Math.min(rows - 1, (int) ((y - minY) / cellMm)));
    }

    private static void forCells(BoundingBox b, double minX, double minY, double cell, int cols, int rows,
                                 IntConsumer sink) {
        int cx0 = Math.max(0, Math.min(cols - 1, (int) ((b.getMinX() - minX) / cell)));
        int cx1 = Math.max(0, Math.min(cols - 1, (int) ((b.getMaxX() - minX) / cell)));
        int cy0 = Math.max(0, Math.min(rows - 1, (int) ((b.getMinY() - minY) / cell)));
        int cy1 = Math.max(0, Math.min(rows - 1, (int) ((b.getMaxY() - minY) / cell)));
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                sink.accept(cy * cols + cx);
            }
        }
    }
}
