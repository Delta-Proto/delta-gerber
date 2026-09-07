package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;
import org.apache.batik.parser.AWTPathProducer;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the loops found on a profile layer into the board's <em>material</em>, as one resolved
 * area.
 *
 * <p>The loops cannot simply be handed to a fill rule. Even-odd is right for loops that
 * <b>nest</b> — a window drawn inside the board edge is a cut-out — and wrong for loops that
 * merely <b>overlap</b>, which is how EDA tools routinely draw one feature: Altium emits an
 * L- or C-shaped routed slot as two or three overlapping rectangles, and under even-odd their
 * corners are covered twice and flip back to material, leaving the slot with its corners filled
 * in. It is also wrong for a profile emitted twice, where the doubled loop cancels the board
 * away entirely. So the loops are resolved geometrically instead: identical loops collapse, loops
 * at even nesting depth are <b>unioned</b> into material and loops at odd depth are subtracted
 * from it. Overlap at the same depth then means what a fabricator means by it — one bigger
 * opening — and nesting still means a cut-out.
 *
 * <p>An <b>open</b> chain gets a second reading. It is not a loop at all, so the area it appears
 * to enclose is not a shape anyone drew; what the router removes is the width of its own tool
 * along it. So an open chain contributes its <em>stroked</em> path — a slot as wide as the
 * aperture that drew it — rather than the polygon you get by joining its ends. Force-closing one
 * is what turned a C-shaped slot into a filled wedge.
 *
 * <p>Two things a profile layer cannot tell you on its own, {@linkplain CopperProbe the copper}
 * can. A cut-out has no copper in it — nothing is routed over a hole — so a nested loop full of
 * copper is not a cut-out but a second outline of a board that is already there: the individual
 * board outlines an Altium panel draws inside the panel edge, or the same profile repeated on a
 * second mechanical file. Those are kept as material rather than punched out. Without copper to
 * consult, nothing is vetoed and nesting alone decides.
 *
 * @see MultiLayerSVGRenderer#resolveBoardOutline(java.util.List)
 */
final class OutlineResolver {

    /**
     * Copper points that must fall inside a loop before it is disqualified as a cut-out. More
     * than one, because a pour that was not pulled back from the routed edge can leave a stray
     * sliver inside a genuine opening; far below what any real board section carries.
     */
    private static final int COPPER_VETO_MIN_POINTS = 4;

    /**
     * Cap on the copper points sampled for the veto. The test only has to answer "is there
     * copper in here", which a few thousand points spread over the board settle as well as a
     * million do.
     */
    private static final int COPPER_SAMPLE_LIMIT = 20_000;

    /** Two loops are the same loop when what is in one but not the other is smaller than this (mm²). */
    private static final double SAME_LOOP_AREA_MM2 = 1e-4;

    /** Coincidence tolerance (mm) when comparing two loops' bounding boxes. */
    private static final double SAME_BOX_MM = 0.001;

    /**
     * Fraction of a loop that must lie inside a bigger one to count as nested in it. Not all of
     * it, because a cut-out is allowed to breach the edge it is cut into — an edge notch or a
     * mouse bite is drawn straddling the board outline, and it is still a cut-out and not a
     * separate piece of material bolted to the side of the board.
     */
    private static final double ENCLOSED_FRACTION = 0.6;

    private OutlineResolver() {
    }

    /**
     * One loop chained off a profile layer, on its way to becoming material or a cut-out.
     *
     * @param svgPath     the subpath in millimetres, Y up
     * @param closed      whether the chain met its own start; an open one is stroked, not filled
     * @param strokeWidth the width of the aperture that drew it, used when it is open
     * @param region      whether it came from a G36/G37 region rather than from chained strokes
     */
    record Loop(String svgPath, boolean closed, double strokeWidth, boolean region) {
    }

    /**
     * Copper sampled down to bare points, which is all the cut-out veto needs: a loop with copper
     * inside it is board, not a hole.
     */
    static final class CopperProbe {
        private final double[] xs;
        private final double[] ys;

        private CopperProbe(double[] xs, double[] ys) {
            this.xs = xs;
            this.ys = ys;
        }

        /** True when this probe has no copper to consult, so nothing can be vetoed. */
        boolean isEmpty() {
            return xs.length == 0;
        }

        /** Number of sampled copper points inside {@code area}, counted no further than {@code limit}. */
        int countInside(Area area, int limit) {
            Rectangle2D box = area.getBounds2D();
            int hits = 0;
            for (int i = 0; i < xs.length; i++) {
                if (!box.contains(xs[i], ys[i])) continue;
                if (area.contains(xs[i], ys[i]) && ++hits >= limit) return hits;
            }
            return hits;
        }

        /**
         * Fraction of the copper that lies inside {@code area}, over a bounded sample. Returns
         * {@code 0} when there is no copper to ask about.
         */
        double fractionInside(Area area) {
            if (xs.length == 0) return 0;
            int step = Math.max(1, xs.length / PROFILE_SAMPLE_POINTS);
            int tested = 0, hits = 0;
            Rectangle2D box = area.getBounds2D();
            for (int i = 0; i < xs.length; i += step) {
                tested++;
                if (box.contains(xs[i], ys[i]) && area.contains(xs[i], ys[i])) hits++;
            }
            return tested == 0 ? 0 : (double) hits / tested;
        }

        /** Sample the copper documents down to points, subsampling to stay bounded. */
        static CopperProbe of(List<GerberDocument> coppers) {
            List<double[]> pts = new ArrayList<>();
            for (GerberDocument doc : coppers) {
                if (doc == null) continue;
                for (GraphicsObject obj : doc.getObjects()) {
                    if (obj instanceof Flash f) {
                        pts.add(new double[]{f.getX(), f.getY()});
                    } else if (obj instanceof Draw d) {
                        pts.add(new double[]{(d.getStartX() + d.getEndX()) / 2,
                                             (d.getStartY() + d.getEndY()) / 2});
                    } else if (obj instanceof Arc a) {
                        pts.add(new double[]{a.getStartX(), a.getStartY()});
                    } else if (obj instanceof Region r) {
                        for (Contour c : r.getContours()) {
                            pts.add(new double[]{c.getStartX(), c.getStartY()});
                            for (Contour.ContourSegment s : c.getSegments()) {
                                pts.add(new double[]{s.getX(), s.getY()});
                            }
                        }
                    }
                }
            }
            int step = Math.max(1, (pts.size() + COPPER_SAMPLE_LIMIT - 1) / COPPER_SAMPLE_LIMIT);
            int n = (pts.size() + step - 1) / step;
            double[] xs = new double[n];
            double[] ys = new double[n];
            for (int i = 0, k = 0; i < pts.size() && k < n; i += step, k++) {
                xs[k] = pts.get(i)[0];
                ys[k] = pts.get(i)[1];
            }
            return new CopperProbe(xs, ys);
        }
    }

    /**
     * How much less of the copper a profile candidate may hold than the best candidate does and
     * still be considered. The comparison is deliberately relative: copper layers carry ink that
     * is genuinely off the board — KiCad strokes fabrication text beside it, and one board here
     * puts 95% of its copper draws there — so no fixed fraction separates a real edge from a
     * note layer. What does separate them is that the real edge holds *more* copper than
     * anything else on offer.
     */
    private static final double PROFILE_COPPER_MARGIN = 0.01;

    /** Copper points sampled when asking whether a candidate profile holds the copper. */
    private static final int PROFILE_SAMPLE_POINTS = 2_000;

    /**
     * How much tighter one candidate profile must be than another to displace it. Two files
     * carrying the same board edge do not agree to the last decimal, and it does not matter
     * which of them is quoted, so anything inside this counts as the same answer.
     */
    private static final double PROFILE_TIE_FRACTION = 0.01;

    /**
     * Combine the loops of several profile layers into one set, or {@code null} when none of them
     * looks like a board edge.
     *
     * <p>The board is the <b>tightest</b> candidate that holds as much of the copper as any of
     * them does. Holding the copper is what disqualifies a note or a dimension detail drawn on a
     * mechanical layer; being tightest is what disqualifies the drawing sheet drawn <em>around</em>
     * the board, which holds the copper just as well and is not the edge.
     *
     * <p>From the layers that lost, only <b>regions</b> sitting inside the winner are taken. A G36
     * region states an area outright, which is what a cut-out is and what Altium writes its routed
     * slots as. A closed run of strokes states nothing of the kind — on a mechanical layer it is
     * just as likely to be the box drawn around a component or a detail on a fab drawing, and one
     * real set here has 178 of them. Reading those as routing would punch holes through a board
     * that renders correctly today, which is a worse failure than the missing cut-out this is here
     * to fix, so the benefit of the doubt goes the other way.
     */
    static List<Loop> merge(List<List<Loop>> perLayer, CopperProbe copper) {
        Area[] hulls = new Area[perLayer.size()];
        double[] held = new double[perLayer.size()];
        double bestHeld = -1;
        for (int i = 0; i < perLayer.size(); i++) {
            hulls[i] = hull(perLayer.get(i));
            if (hulls[i] == null || hulls[i].isEmpty()) continue;
            held[i] = copper.fractionInside(hulls[i]);
            bestHeld = Math.max(bestHeld, held[i]);
        }
        if (bestHeld < 0) return null;

        int primary = -1;
        double primarySize = Double.MAX_VALUE;
        for (int i = 0; i < perLayer.size(); i++) {
            if (hulls[i] == null || hulls[i].isEmpty()) continue;
            if (held[i] < bestHeld - PROFILE_COPPER_MARGIN) continue;
            double size = areaOf(hulls[i]);
            // Tightest wins, and a tie goes to the first — two files carrying the same board edge
            // are the same answer, so which one is quoted does not matter.
            if (size < primarySize * (1 - PROFILE_TIE_FRACTION)) {
                primary = i;
                primarySize = size;
            }
        }
        if (primary < 0) return null;

        // Merged loops are judged against the winner's *material*, not its outer edge. A tool that
        // writes the profile to two files often describes the same cut-out on both — Altium
        // strokes the routed slot on one and fills the identical shape as regions on the other —
        // and the two descriptions are not the same loop, so nothing collapses them: each sits
        // inside the other and the second reads as an island of material in the hole the first
        // cut, filling the slot back in. Land already cut away is not somewhere a cut-out can be,
        // so a loop that falls in one is the same feature said twice and is dropped.
        Area primaryMaterial = resolve(perLayer.get(primary), copper);
        if (primaryMaterial == null || primaryMaterial.isEmpty()) return null;

        List<Loop> out = new ArrayList<>(perLayer.get(primary));
        for (int i = 0; i < perLayer.size(); i++) {
            if (i == primary) continue;
            for (Loop loop : perLayer.get(i)) {
                if (!loop.region()) continue;
                Area a = toArea(loop);
                if (a == null || a.isEmpty()) continue;
                double size = areaOf(a);
                Area inside = new Area(a);
                inside.intersect(primaryMaterial);
                if (size > 0 && areaOf(inside) >= ENCLOSED_FRACTION * size) out.add(loop);
            }
        }
        return out;
    }

    /**
     * A candidate profile's <b>outer</b> boundary — its outermost loops unioned, with whatever
     * they contain ignored. Candidates are compared on this rather than on their material,
     * because material rewards a layer for punching holes: a mechanical sheet that draws a box
     * around every component would resolve to less area than the plain edge next to it and win
     * on being "tighter", when what it is really doing is riddling the board.
     */
    private static Area hull(List<Loop> loops) {
        List<Area> areas = new ArrayList<>();
        for (Loop loop : loops) {
            Area a = toArea(loop);
            if (a != null && !a.isEmpty()) areas.add(a);
        }
        if (areas.isEmpty()) return null;
        areas = dropDuplicates(areas);
        int[] depth = depths(areas);
        Area hull = new Area();
        for (int i = 0; i < areas.size(); i++) {
            if (depth[i] == 0) hull.add(areas.get(i));
        }
        return hull;
    }

    /**
     * Resolve the loops into the material they describe, or {@code null} when they describe
     * nothing at all (every loop cancelled, or none could be read).
     */
    static Area resolve(List<Loop> loops, CopperProbe copper) {
        List<Area> areas = new ArrayList<>();
        for (Loop loop : loops) {
            Area a = toArea(loop);
            if (a != null && !a.isEmpty()) areas.add(a);
        }
        if (areas.isEmpty()) return null;

        areas = dropDuplicates(areas);

        // Nesting depth, re-derived after each veto: dropping a loop re-parents everything it
        // contained, which is the whole point — the cut-outs an Altium panel draws inside one of
        // its individual board outlines have to come back up to depth 1 once that outline is
        // recognised as board rather than hole.
        int[] depth;
        while (true) {
            depth = depths(areas);
            int vetoed = veto(areas, depth, copper);
            if (vetoed < 0) break;
            areas.remove(vetoed);
        }

        int maxDepth = 0;
        for (int d : depth) maxDepth = Math.max(maxDepth, d);

        Area material = new Area();
        for (int d = 0; d <= maxDepth; d++) {
            Area level = new Area();
            for (int i = 0; i < areas.size(); i++) {
                if (depth[i] == d) level.add(areas.get(i));
            }
            if (level.isEmpty()) continue;
            if (d % 2 == 0) {
                material.add(level);
            } else {
                material.subtract(level);
            }
        }
        return material.isEmpty() ? null : material;
    }

    /**
     * Index of the first loop that is nested but full of copper — a board section drawn inside
     * another outline of itself, not a cut-out — or {@code -1} when none is.
     */
    private static int veto(List<Area> areas, int[] depth, CopperProbe copper) {
        if (copper == null || copper.isEmpty()) return -1;
        for (int i = 0; i < areas.size(); i++) {
            if (depth[i] % 2 == 0) continue;
            if (copper.countInside(areas.get(i), COPPER_VETO_MIN_POINTS) >= COPPER_VETO_MIN_POINTS) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Collapse loops that describe the same shape. A tool that emits its profile on two
     * mechanical files, or twice on one, would otherwise have each copy enclose the other and
     * both would count as nested — cancelling the board away.
     */
    private static List<Area> dropDuplicates(List<Area> areas) {
        List<Area> out = new ArrayList<>();
        for (Area a : areas) {
            boolean dup = false;
            for (Area k : out) {
                if (!sameBox(a.getBounds2D(), k.getBounds2D())) continue;
                Area diff = new Area(a);
                diff.exclusiveOr(k);
                if (areaOf(diff) < SAME_LOOP_AREA_MM2) { dup = true; break; }
            }
            if (!dup) out.add(a);
        }
        return out;
    }

    private static boolean sameBox(Rectangle2D a, Rectangle2D b) {
        return Math.abs(a.getMinX() - b.getMinX()) <= SAME_BOX_MM
            && Math.abs(a.getMinY() - b.getMinY()) <= SAME_BOX_MM
            && Math.abs(a.getMaxX() - b.getMaxX()) <= SAME_BOX_MM
            && Math.abs(a.getMaxY() - b.getMaxY()) <= SAME_BOX_MM;
    }

    /**
     * How many other loops enclose each loop. Only a strictly bigger loop can enclose a smaller
     * one, which makes the relation a hierarchy no pair can sit on both sides of; bounding boxes
     * prune the exact test down to the pairs that could possibly overlap.
     */
    private static int[] depths(List<Area> areas) {
        int n = areas.size();
        int[] depth = new int[n];
        Rectangle2D[] boxes = new Rectangle2D[n];
        double[] size = new double[n];
        for (int i = 0; i < n; i++) {
            boxes[i] = areas.get(i).getBounds2D();
            size[i] = areaOf(areas.get(i));
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j || size[i] <= size[j] || !boxes[i].intersects(boxes[j])) continue;
                Area inside = new Area(areas.get(j));
                inside.intersect(areas.get(i));
                if (areaOf(inside) >= ENCLOSED_FRACTION * size[j]) depth[j]++;
            }
        }
        return depth;
    }

    /** The area of a loop, for comparing two of them. */
    private static double areaOf(Area area) {
        double sum = 0;
        double[] c = new double[6];
        double sx = 0, sy = 0, px = 0, py = 0;
        for (PathIterator it = area.getPathIterator(null, 0.001); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> { sx = px = c[0]; sy = py = c[1]; }
                case PathIterator.SEG_LINETO -> {
                    sum += px * c[1] - c[0] * py;
                    px = c[0];
                    py = c[1];
                }
                case PathIterator.SEG_CLOSE -> {
                    sum += px * sy - sx * py;
                    px = sx;
                    py = sy;
                }
                default -> { }
            }
        }
        return Math.abs(sum) / 2.0;
    }

    /** One loop as an area: filled when it closed, stroked by its own aperture when it did not. */
    private static Area toArea(Loop loop) {
        Shape shape;
        try {
            shape = AWTPathProducer.createShape(new StringReader(loop.svgPath()),
                PathIterator.WIND_NON_ZERO);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read outline subpath", e);
        }
        if (shape == null) return null;
        if (!loop.closed()) {
            double w = Math.max(loop.strokeWidth(), 0.001);
            shape = new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                .createStrokedShape(shape);
        }
        return new Area(shape);
    }

    /** Serialise a resolved area back to an SVG path, in millimetres with Y up. */
    static String toSvgPath(Area area) {
        StringBuilder sb = new StringBuilder();
        double[] c = new double[6];
        for (PathIterator it = area.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO ->
                    sb.append(sb.length() > 0 ? " M " : "M ").append(fmt(c[0], c[1]));
                case PathIterator.SEG_LINETO -> sb.append(" L ").append(fmt(c[0], c[1]));
                case PathIterator.SEG_QUADTO -> sb.append(" Q ").append(fmt(c[0], c[1]))
                    .append(" ").append(fmt(c[2], c[3]));
                case PathIterator.SEG_CUBICTO -> sb.append(" C ").append(fmt(c[0], c[1]))
                    .append(" ").append(fmt(c[2], c[3])).append(" ").append(fmt(c[4], c[5]));
                case PathIterator.SEG_CLOSE -> sb.append(" Z");
                default -> { }
            }
        }
        return sb.toString();
    }

    private static String fmt(double x, double y) {
        return String.format(Locale.US, "%.6f %.6f", x, y);
    }
}
