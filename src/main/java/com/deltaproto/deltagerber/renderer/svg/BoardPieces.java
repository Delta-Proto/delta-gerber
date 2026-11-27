package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;

import java.awt.Shape;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the pieces of a resolved outline that are the board, and drops a stack-up table, title
 * block or drill legend drawn beside it.
 *
 * <p>Those drawings are closed shapes, so they resolve into material like any board piece: an
 * Altium profile layer with a "Board Layer Stack" table under a round board renders the table as
 * a second piece of green PCB, and a set without a profile derives an island from a title block
 * drawn in the soldermask. What tells them apart is the copper: a board carries <b>flashed</b>
 * copper — pads and vias — and a table carries text and rules, which are draws. So each island
 * (an outer loop with its holes) is scored by the copper flash centres inside it, and the board is
 * the best-scoring island together with every other island holding at least
 * {@link #PANEL_PIECE_FRACTION} of that — the further boards of a panel, which keep rendering. A
 * hole travels with its island. An edge derived from the copper is held to less: a board without a
 * pour derives as fragments, so there only an island with no flash at all is dropped.
 *
 * <p>Without a flash in any island there is nothing to tell them apart; picking the biggest would
 * be a guess, so nothing is dropped and the guess is only reported.
 */
final class BoardPieces {

    /** An island holding this share of the best island's flashes is another board of a panel. */
    static final double PANEL_PIECE_FRACTION = 0.2;

    /**
     * Islands smaller than this share of the largest are not counted as islands: a closed glyph or
     * a speck left over from the silhouette is not a piece anyone would call a second board. They
     * are still kept or dropped by their copper like any other island.
     */
    static final double MIN_ISLAND_AREA_FRACTION = 0.002;

    /** Flash centres sampled per set; whether an island holds copper needs no more than this. */
    private static final int MAX_FLASHES = 20_000;

    private static final double FLATNESS_MM = 0.02;

    private BoardPieces() {
    }

    /** Centres of the dark copper flashes in {@code coppers}, subsampled to a bounded count. */
    static List<double[]> flashCentres(List<GerberDocument> coppers) {
        List<double[]> all = new ArrayList<>();
        for (GerberDocument doc : coppers) {
            if (doc == null) continue;
            for (GraphicsObject obj : doc.getObjects()) {
                if (obj instanceof Flash f && obj.getPolarity() != Polarity.CLEAR) {
                    all.add(new double[]{f.getX(), f.getY()});
                }
            }
        }
        if (all.size() <= MAX_FLASHES) return all;
        List<double[]> sample = new ArrayList<>(MAX_FLASHES);
        double step = (double) all.size() / MAX_FLASHES;
        for (double i = 0; i < all.size(); i += step) sample.add(all.get((int) i));
        return sample;
    }

    /** One closed subpath: its curves as drawn, and a flattened copy to measure. */
    private record Ring(Path2D.Double path, Path2D.Double flat, double area, Rectangle2D box,
                        List<double[]> probes) {
    }

    /**
     * {@code outline} restricted to the board's pieces, carrying what was found. Returns it with
     * only the counts filled in when nothing is dropped.
     */
    static BoardOutline select(BoardOutline outline, List<double[]> flashes) {
        if (outline.isEmpty()) return outline;
        Shape shape = outline.toShape();
        if (shape == null) return outline;

        List<Ring> rings = rings(shape);
        if (rings.isEmpty()) return outline;

        // Depth: how many bigger rings contain each ring. Even depth is an island's outer edge,
        // odd depth a hole in the innermost island around it.
        int n = rings.size();
        int[] depth = new int[n];
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = -1;
            double parentArea = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (i == j || rings.get(j).area() <= rings.get(i).area()) continue;
                if (!encloses(rings.get(j), rings.get(i))) continue;
                depth[i]++;
                if (rings.get(j).area() < parentArea) {
                    parentArea = rings.get(j).area();
                    parent[i] = j;
                }
            }
        }

        List<Integer> islands = new ArrayList<>();
        double largest = 0;
        for (int i = 0; i < n; i++) {
            if (depth[i] % 2 == 0) {
                islands.add(i);
                largest = Math.max(largest, rings.get(i).area());
            }
        }
        int counted = 0;
        for (int i : islands) {
            if (rings.get(i).area() >= MIN_ISLAND_AREA_FRACTION * largest) counted++;
        }

        // Score: flash centres inside the island and outside its holes.
        int[] score = new int[n];
        int best = 0;
        for (int i : islands) {
            Ring outer = rings.get(i);
            for (double[] p : flashes) {
                if (!outer.box().contains(p[0], p[1]) || !outer.flat().contains(p[0], p[1])) continue;
                boolean inHole = false;
                for (int h = 0; h < n && !inHole; h++) {
                    inHole = parent[h] == i && depth[h] % 2 == 1
                            && rings.get(h).box().contains(p[0], p[1])
                            && rings.get(h).flat().contains(p[0], p[1]);
                }
                if (!inHole) score[i]++;
            }
            best = Math.max(best, score[i]);
        }

        if (best == 0) {
            // Nothing to tell the islands apart: keep everything, and say so when it mattered.
            return outline.withSelection(counted, counted, counted > 1);
        }

        // A profile layer's pieces are deliberate drawings, so a piece with a fraction of the
        // board's pads is a table that happens to cover one. A derived silhouette breaks a board
        // without a pour into fragments — a connector's pads apart from the rest — so there only
        // a piece with no pad at all is not board.
        double bar = outline.isFromProfileLayer() ? Math.max(1, PANEL_PIECE_FRACTION * best) : 1;
        boolean[] keep = new boolean[n];
        int kept = 0;
        for (int i : islands) {
            if (score[i] >= bar) {
                keep[i] = true;
                if (rings.get(i).area() >= MIN_ISLAND_AREA_FRACTION * largest) kept++;
            }
        }
        boolean dropped = false;
        for (int i = 0; i < n; i++) {
            if (depth[i] % 2 == 1) keep[i] = parent[i] >= 0 && keep[parent[i]];
            dropped |= !keep[i];
        }
        if (!dropped) return outline.withSelection(counted, kept, false);

        Path2D.Double out = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (int i = 0; i < n; i++) {
            if (keep[i]) out.append(rings.get(i).path(), false);
        }
        return new BoardOutline(OutlineResolver.toSvgPath(out), outline.isFromProfileLayer())
                .withSelection(counted, kept, false);
    }

    /** Split a shape into its closed subpaths, keeping curves as they are. */
    private static List<Ring> rings(Shape shape) {
        List<Path2D.Double> paths = new ArrayList<>();
        Path2D.Double cur = null;
        double[] c = new double[6];
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    cur = new Path2D.Double(Path2D.WIND_NON_ZERO);
                    paths.add(cur);
                    cur.moveTo(c[0], c[1]);
                }
                case PathIterator.SEG_LINETO -> { if (cur != null) cur.lineTo(c[0], c[1]); }
                case PathIterator.SEG_QUADTO -> { if (cur != null) cur.quadTo(c[0], c[1], c[2], c[3]); }
                case PathIterator.SEG_CUBICTO -> {
                    if (cur != null) cur.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                }
                case PathIterator.SEG_CLOSE -> { if (cur != null) cur.closePath(); }
                default -> { }
            }
        }
        List<Ring> rings = new ArrayList<>();
        for (Path2D.Double p : paths) {
            Path2D.Double flat = new Path2D.Double(Path2D.WIND_NON_ZERO);
            List<double[]> pts = new ArrayList<>();
            for (PathIterator it = new FlatteningPathIterator(p.getPathIterator(null), FLATNESS_MM);
                 !it.isDone(); it.next()) {
                int seg = it.currentSegment(c);
                if (seg == PathIterator.SEG_MOVETO) flat.moveTo(c[0], c[1]);
                else if (seg == PathIterator.SEG_LINETO) flat.lineTo(c[0], c[1]);
                else if (seg == PathIterator.SEG_CLOSE) flat.closePath();
                if (seg != PathIterator.SEG_CLOSE) pts.add(new double[]{c[0], c[1]});
            }
            if (pts.size() < 3) continue;
            flat.closePath();
            double area = 0;
            for (int i = 0; i < pts.size(); i++) {
                double[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
                area += a[0] * b[1] - b[0] * a[1];
            }
            area = Math.abs(area) / 2;
            if (area <= 0) continue;
            // A handful of the ring's own vertices, spread along it, to vote on containment.
            List<double[]> probes = new ArrayList<>();
            int step = Math.max(1, pts.size() / 7);
            for (int i = 0; i < pts.size() && probes.size() < 7; i += step) probes.add(pts.get(i));
            rings.add(new Ring(p, flat, area, flat.getBounds2D(), probes));
        }
        return rings;
    }

    /**
     * Whether {@code outer} contains {@code inner}, by a vote of {@code inner}'s vertices: rings
     * from one resolved outline never cross, but they share coordinates often enough that any
     * single vertex can land on the other's boundary.
     */
    private static boolean encloses(Ring outer, Ring inner) {
        if (!outer.box().contains(inner.box().getCenterX(), inner.box().getCenterY())
                && !outer.box().intersects(inner.box())) {
            return false;
        }
        int in = 0;
        for (double[] p : inner.probes()) {
            if (outer.flat().contains(p[0], p[1])) in++;
        }
        return in * 2 > inner.probes().size();
    }
}
