package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.align.DrillGerberAlignment;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.DrillOperation;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.MacroAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.ObroundAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.PolygonAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.RectangleAperture;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures the <em>annular ring</em> of every drilled hole: the copper left between the wall of the
 * hole and the edge of the pad it sits in, on each copper layer the hole passes through.
 *
 * <p>This is the check a fabricator's capability table calls "min annular ring", and it needs both
 * halves of a data set at once — the drill program says where the holes are, the copper layers say
 * where the pads are, and neither file mentions the other. They are correlated here.
 *
 * <h2>What counts as a pad</h2>
 *
 * A pad is a <strong>flash</strong> or a <strong>stroke</strong> whose ink contains the hole's
 * centre. A flash is what every EDA tool emits for a pad; a stroke — an aperture swept along a
 * short line or arc — is how several tools (EAGLE among them) draw an oblong through-hole pad, and
 * leaving it out loses a third of the pads on such a board. A hole that merely <em>overlaps</em>
 * copper is not in a pad, so the test is on the hole's centre, not on its rim.
 *
 * <p>A <strong>region</strong> is not a pad. A poured plane swallows every hole that crosses it,
 * and calling that a pad would report an ample ring for a via that is only passing through. The
 * cost of that choice is a via connected to a plane with no pad flash of its own: it reports no pad
 * on that layer, and its ring is taken from the layers that do have one.
 *
 * <p>Copper is read in file order, so a <strong>clear</strong> flash — an antipad on a negative
 * plane, or any {@code %LPC*%} feature — erases what is under it: a hole inside one has no pad on
 * that layer, rather than a ring of zero. Without that, every via through a plane would report as
 * broken out. A clear <em>region</em> does not erase anything here, for the same reason a dark one
 * is not a pad: regions are not read at all. That is a pad drawn under a poured clearance, which
 * would leave the ring overstated — rare enough to state rather than solve.
 *
 * <p>Where several objects contain a hole — a pad plus the trace entering it — the ring is the
 * largest each single object allows. Copper is really their union, whose edge is at least as far
 * away, so the answer errs towards reporting <em>less</em> copper than there is, never more.
 *
 * <h2>What the number means</h2>
 *
 * The ring is the shortest distance from the hole's edge to the pad's edge over all directions,
 * against the <em>drilled</em> diameter — see {@link PadRing}. Pad geometry is exact for circles,
 * rectangles, obrounds, regular polygons and macros (a macro's real outline, not its bounding box);
 * a block aperture falls back to its bounds, and a stroke drawn with a non-round aperture to that
 * aperture's smaller dimension. An aperture's own hole parameter ({@code C,1.6X0.8}) is ignored:
 * it draws the drilled hole itself, and the ring is measured to the pad's outer edge.
 *
 * <p>Holes are matched to layers, not to a stack-up span: a blind or buried drill simply finds pads
 * on the layers it reaches and none on the others, which is the right answer without the span ever
 * being stated.
 *
 * <p><strong>A non-plated hole is not measured at all.</strong> There is no barrel to connect to a
 * pad, so it needs no ring, and a mounting hole punched through a plane would otherwise report as a
 * spectacular breakout. The drill file states this per tool ({@code ;TYPE=NON_PLATED}, see
 * {@link com.deltaproto.deltagerber.model.drill.Tool#getPlated()}), and a file that says nothing
 * has every hole measured — a padless hole then lands in
 * {@link AnnularRingResult#getHolesWithoutPad()} for the caller to judge.
 *
 * <p>The drill and the copper must share one coordinate frame. Both parsers normalise to mm, so
 * they do unless the drill was exported on a different origin (some Altium flows) — use
 * {@link #detectAligned} to correct that first.
 */
public final class AnnularRingDetector {

    /** Flattening tolerance in mm for a macro's curved boundary — 0.1 µm, far below any rule. */
    private static final double FLATNESS_MM = 1e-4;

    private AnnularRingDetector() {
    }

    /**
     * Measure the annular ring of every hole in {@code drills} against the pads on
     * {@code copperLayers}.
     *
     * <p>The drills must already share the copper's coordinate origin; see {@link #detectAligned}
     * when that is not guaranteed. Slots are skipped — a routed slot is not a drilled hole and has
     * no pad to be concentric with. A {@code null} collection is treated as empty.
     *
     * @return the rings found — {@link AnnularRingResult#empty()} when there is no copper or no
     *         hole to measure
     */
    public static AnnularRingResult detect(Collection<CopperLayer> copperLayers,
                                           Collection<DrillDocument> drills) {
        List<Hole> holes = holesOf(drills);
        if (holes.isEmpty() || copperLayers == null || copperLayers.isEmpty()) {
            return AnnularRingResult.empty();
        }

        HoleIndex index = new HoleIndex(holes);
        Map<Aperture, Shape> macroShapes = new IdentityHashMap<>();
        for (CopperLayer layer : copperLayers) {
            if (layer != null && !layer.getObjects().isEmpty()) {
                measureLayer(layer, holes, index, macroShapes);
            }
        }

        List<AnnularRing> rings = new ArrayList<>();
        List<AnnularRing> withoutPad = new ArrayList<>();
        for (Hole hole : holes) {
            AnnularRing ring = new AnnularRing(hole.x, hole.y, hole.diameter, hole.drillFile,
                    hole.plated, hole.pads == null ? List.of() : hole.pads);
            (ring.hasPad() ? rings : withoutPad).add(ring);
        }
        return new AnnularRingResult(rings, withoutPad);
    }

    /**
     * As {@link #detect}, but first moves the drills into the Gerber frame using the copper pads —
     * for the case where they were exported on a different origin than the copper. The drills are
     * resolved as a set, so a file carrying no hole centres of its own (a slot-only drill file)
     * takes the offset its siblings recovered. When the drills already sit on the board this is a
     * no-op, so it is safe to call unconditionally.
     */
    public static AnnularRingResult detectAligned(Collection<CopperLayer> copperLayers,
                                                  Collection<DrillDocument> drills) {
        if (drills == null || drills.isEmpty() || copperLayers == null || copperLayers.isEmpty()) {
            return detect(copperLayers, drills);
        }
        BoundingBox bounds = new BoundingBox();
        List<double[]> padCenters = new ArrayList<>();
        for (CopperLayer layer : copperLayers) {
            if (layer == null) {
                continue;
            }
            for (GraphicsObject obj : layer.getObjects()) {
                BoundingBox b = obj.getBoundingBox();
                if (b != null && b.isValid()) {
                    bounds.include(b);
                }
                if (obj instanceof Flash flash) {
                    padCenters.add(new double[]{flash.getX(), flash.getY()});
                }
            }
        }
        return detect(copperLayers,
                DrillGerberAlignment.alignedAll(new ArrayList<>(drills), bounds, padCenters));
    }

    // ------------------------------------------------------------------------
    // The per-layer pass
    // ------------------------------------------------------------------------

    /**
     * Walk one layer's artwork once, keeping for every hole the largest ring any single object
     * allows it. Objects are visited in file order so a clear feature erases what is under it.
     */
    private static void measureLayer(CopperLayer layer, List<Hole> holes, HoleIndex index,
                                     Map<Aperture, Shape> macroShapes) {
        int n = holes.size();
        double[] best = new double[n];
        double[] padX = new double[n];
        double[] padY = new double[n];
        String[] padShape = new String[n];
        Arrays.fill(best, Double.NaN);

        for (GraphicsObject obj : layer.getObjects()) {
            if (obj instanceof Region) {
                continue;       // a pour is not a pad — see the class documentation
            }
            BoundingBox bounds = obj.getBoundingBox();
            if (bounds == null || !bounds.isValid()) {
                continue;
            }
            for (int h : index.candidates(bounds)) {
                Hole hole = holes.get(h);
                double distance = insideDistance(obj, hole.x, hole.y, macroShapes);
                if (Double.isNaN(distance)) {
                    continue;
                }
                if (obj.getPolarity() != Polarity.DARK) {
                    // Clear artwork removes every layer of copper below it at this point.
                    best[h] = Double.NaN;
                    padShape[h] = null;
                    continue;
                }
                double ring = distance - hole.diameter / 2;
                if (Double.isNaN(best[h]) || ring > best[h]) {
                    best[h] = ring;
                    padShape[h] = shapeName(obj);
                    double[] center = center(obj);
                    padX[h] = center[0];
                    padY[h] = center[1];
                }
            }
        }

        for (int h = 0; h < n; h++) {
            if (Double.isNaN(best[h])) {
                continue;
            }
            Hole hole = holes.get(h);
            if (hole.pads == null) {
                hole.pads = new ArrayList<>(2);
            }
            hole.pads.add(new PadRing(layer.getName(), layer.getSide(), layer.getNumber(),
                    best[h], padShape[h], padX[h], padY[h],
                    Math.hypot(hole.x - padX[h], hole.y - padY[h])));
        }
    }

    // ------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------

    /**
     * How far {@code (x, y)} is from the edge of the copper this object inks, in mm, or
     * {@link Double#NaN} when the point is not inside it at all.
     */
    private static double insideDistance(GraphicsObject obj, double x, double y,
                                         Map<Aperture, Shape> macroShapes) {
        if (obj instanceof Flash flash) {
            return flashInsideDistance(flash, x, y, macroShapes);
        }
        if (obj instanceof Draw draw) {
            return sweptInsideDistance(draw.getAperture(), x, y,
                    draw.getStartX(), draw.getStartY(), draw.getEndX(), draw.getEndY());
        }
        if (obj instanceof Arc arc) {
            return arcInsideDistance(arc, x, y);
        }
        return Double.NaN;
    }

    /**
     * The point is mapped into the aperture's own frame by undoing the flash transform — whose
     * order is translate → mirror → rotate → scale — measured there, and the distance scaled back.
     * Mirroring and rotation preserve distances, so only the scale factor has to be undone.
     */
    private static double flashInsideDistance(Flash flash, double x, double y,
                                              Map<Aperture, Shape> macroShapes) {
        double lx = x - flash.getX();
        double ly = y - flash.getY();
        if (flash.isMirrorX()) {
            lx = -lx;
        }
        if (flash.isMirrorY()) {
            ly = -ly;
        }
        if (flash.getRotation() != 0) {
            double rad = -Math.toRadians(flash.getRotation());
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double rx = lx * cos - ly * sin;
            ly = lx * sin + ly * cos;
            lx = rx;
        }
        double scale = flash.getScale();
        if (scale <= 0) {
            return Double.NaN;
        }
        if (scale != 1.0) {
            lx /= scale;
            ly /= scale;
        }
        double distance = apertureInsideDistance(flash.getAperture(), lx, ly, macroShapes);
        return Double.isNaN(distance) ? Double.NaN : distance * scale;
    }

    /**
     * How far {@code (lx, ly)} — in the aperture's own centred frame — is from the aperture's outer
     * edge, or NaN when it is outside.
     *
     * <p>The aperture's optional hole parameter is deliberately ignored. That hole <em>is</em> the
     * drilled hole drawn into the pad, so measuring to it would report a ring of zero on exactly
     * the pads that state their ring most plainly.
     */
    private static double apertureInsideDistance(Aperture aperture, double lx, double ly,
                                                 Map<Aperture, Shape> macroShapes) {
        if (aperture instanceof CircleAperture circle) {
            double distance = circle.getRadius() - Math.hypot(lx, ly);
            return distance < 0 ? Double.NaN : distance;
        }
        if (aperture instanceof RectangleAperture rect) {
            double dx = rect.getWidth() / 2 - Math.abs(lx);
            double dy = rect.getHeight() / 2 - Math.abs(ly);
            return dx < 0 || dy < 0 ? Double.NaN : Math.min(dx, dy);
        }
        if (aperture instanceof ObroundAperture obround) {
            double hw = obround.getWidth() / 2;
            double hh = obround.getHeight() / 2;
            double r = Math.min(hw, hh);
            // Signed distance to a stadium: a rounded box whose corner radius is its short half-axis.
            double qx = Math.abs(lx) - hw + r;
            double qy = Math.abs(ly) - hh + r;
            double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0))
                    + Math.min(Math.max(qx, qy), 0) - r;
            return outside > 0 ? Double.NaN : -outside;
        }
        if (aperture instanceof PolygonAperture polygon) {
            return polygonInsideDistance(polygon, lx, ly);
        }
        if (aperture instanceof MacroAperture macro) {
            Shape shape = macroShapes.computeIfAbsent(macro, k -> macro.getShape());
            if (shape == null || !shape.contains(lx, ly)) {
                return Double.NaN;
            }
            return boundaryDistance(shape, lx, ly);
        }
        // Block aperture: no outline of its own here, so fall back to its bounds, which can only
        // overstate the copper. Rare enough on a drilled pad to be worth saying rather than solving.
        BoundingBox b = aperture.getBoundingBox();
        if (!b.isValid() || lx < b.getMinX() || lx > b.getMaxX() || ly < b.getMinY() || ly > b.getMaxY()) {
            return Double.NaN;
        }
        return Math.min(Math.min(lx - b.getMinX(), b.getMaxX() - lx),
                Math.min(ly - b.getMinY(), b.getMaxY() - ly));
    }

    private static double polygonInsideDistance(PolygonAperture polygon, double lx, double ly) {
        int n = polygon.getNumVertices();
        if (n < 3) {
            return Double.NaN;
        }
        double r = polygon.getOuterDiameter() / 2;
        double rot = Math.toRadians(polygon.getRotation());
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            double a = rot + 2 * Math.PI * i / n;
            xs[i] = r * Math.cos(a);
            ys[i] = r * Math.sin(a);
        }
        if (!inPolygon(xs, ys, lx, ly)) {
            return Double.NaN;
        }
        double min = Double.MAX_VALUE;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            min = Math.min(min, segmentDistance(lx, ly, xs[j], ys[j], xs[i], ys[i]));
        }
        return min;
    }

    /** A stroke: the aperture swept along a line, i.e. a capsule of the aperture's width. */
    private static double sweptInsideDistance(Aperture aperture, double x, double y,
                                              double x1, double y1, double x2, double y2) {
        double r = strokeRadius(aperture);
        if (r <= 0) {
            return Double.NaN;
        }
        double distance = r - segmentDistance(x, y, x1, y1, x2, y2);
        return distance < 0 ? Double.NaN : distance;
    }

    private static double arcInsideDistance(Arc arc, double x, double y) {
        double r = strokeRadius(arc.getAperture());
        if (r <= 0) {
            return Double.NaN;
        }
        double[][] path = flattenArc(arc);
        double min = Double.MAX_VALUE;
        for (int i = 1; i < path[0].length; i++) {
            min = Math.min(min, segmentDistance(x, y, path[0][i - 1], path[1][i - 1],
                    path[0][i], path[1][i]));
        }
        double distance = r - min;
        return distance < 0 ? Double.NaN : distance;
    }

    /**
     * Half the width a stroke lays down. A round aperture — which is what a stroke is supposed to
     * use — gives this exactly; anything else is taken at its smaller dimension, understating the
     * copper as the rest of the library does for a stroked non-round aperture.
     */
    private static double strokeRadius(Aperture aperture) {
        if (aperture == null) {
            return 0;
        }
        if (aperture instanceof CircleAperture circle) {
            return circle.getRadius();
        }
        BoundingBox b = aperture.getBoundingBox();
        return b.isValid() ? Math.min(b.getWidth(), b.getHeight()) / 2 : 0;
    }

    /** Shortest distance from an interior point to a shape's boundary, over its flattened path. */
    private static double boundaryDistance(Shape shape, double x, double y) {
        double[] coords = new double[6];
        double min = Double.MAX_VALUE;
        double cx = 0;
        double cy = 0;
        double startX = 0;
        double startY = 0;
        for (PathIterator it = shape.getPathIterator(null, FLATNESS_MM); !it.isDone(); it.next()) {
            switch (it.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO -> {
                    cx = startX = coords[0];
                    cy = startY = coords[1];
                }
                case PathIterator.SEG_LINETO -> {
                    min = Math.min(min, segmentDistance(x, y, cx, cy, coords[0], coords[1]));
                    cx = coords[0];
                    cy = coords[1];
                }
                case PathIterator.SEG_CLOSE -> {
                    min = Math.min(min, segmentDistance(x, y, cx, cy, startX, startY));
                    cx = startX;
                    cy = startY;
                }
                default -> {
                    // A flattening iterator emits no curves.
                }
            }
        }
        return min == Double.MAX_VALUE ? Double.NaN : min;
    }

    private static double segmentDistance(double px, double py,
                                          double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private static boolean inPolygon(double[] xs, double[] ys, double px, double py) {
        boolean odd = false;
        int n = xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if ((ys[i] > py) != (ys[j] > py)) {
                double xInt = xs[i] + (py - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i]);
                if (px < xInt) {
                    odd = !odd;
                }
            }
        }
        return odd;
    }

    private static double[][] flattenArc(Arc arc) {
        double r = Math.hypot(arc.getStartX() - arc.getCenterX(), arc.getStartY() - arc.getCenterY());
        double startAngle = Math.atan2(arc.getStartY() - arc.getCenterY(),
                arc.getStartX() - arc.getCenterX());
        double endAngle = Math.atan2(arc.getEndY() - arc.getCenterY(),
                arc.getEndX() - arc.getCenterX());
        double sweep;
        if (arc.isClockwise()) {
            sweep = startAngle - endAngle;
            if (sweep <= 0) {
                sweep += 2 * Math.PI;
            }
            sweep = -sweep;
        } else {
            sweep = endAngle - startAngle;
            if (sweep <= 0) {
                sweep += 2 * Math.PI;
            }
        }
        int steps = Math.max(2, (int) Math.ceil(Math.abs(sweep) * r / 0.02));
        double[] xs = new double[steps + 1];
        double[] ys = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double a = startAngle + sweep * i / steps;
            xs[i] = arc.getCenterX() + r * Math.cos(a);
            ys[i] = arc.getCenterY() + r * Math.sin(a);
        }
        return new double[][]{xs, ys};
    }

    private static String shapeName(GraphicsObject obj) {
        return obj instanceof Flash flash ? flash.getAperture().getTemplateCode() : "stroke";
    }

    /** Where the pad sits: a flash's own point, or the middle of a stroked one. */
    private static double[] center(GraphicsObject obj) {
        if (obj instanceof Flash flash) {
            return new double[]{flash.getX(), flash.getY()};
        }
        if (obj instanceof Draw draw) {
            return new double[]{(draw.getStartX() + draw.getEndX()) / 2,
                    (draw.getStartY() + draw.getEndY()) / 2};
        }
        BoundingBox b = obj.getBoundingBox();
        return new double[]{(b.getMinX() + b.getMaxX()) / 2, (b.getMinY() + b.getMaxY()) / 2};
    }

    // ------------------------------------------------------------------------
    // Holes
    // ------------------------------------------------------------------------

    private static List<Hole> holesOf(Collection<DrillDocument> drills) {
        List<Hole> holes = new ArrayList<>();
        if (drills == null) {
            return holes;
        }
        for (DrillDocument drill : drills) {
            if (drill == null) {
                continue;
            }
            for (DrillOperation op : drill.getOperations()) {
                if (!(op instanceof DrillHit hit) || hit.getTool() == null
                        || hit.getTool().getDiameter() <= 0) {
                    continue;   // slots are routed, not drilled into a pad
                }
                if (Boolean.FALSE.equals(hit.getTool().getPlated())) {
                    continue;   // no barrel, no ring — see the class documentation
                }
                holes.add(new Hole(hit.getX(), hit.getY(), hit.getTool().getDiameter(),
                        drill.getFileName(), hit.getTool().getPlated()));
            }
        }
        return holes;
    }

    /** One drilled hole, and the rings it collects as the layers are walked. */
    private static final class Hole {
        private final double x;
        private final double y;
        private final double diameter;
        private final String drillFile;
        private final Boolean plated;
        private List<PadRing> pads;

        Hole(double x, double y, double diameter, String drillFile, Boolean plated) {
            this.x = x;
            this.y = y;
            this.diameter = diameter;
            this.drillFile = drillFile;
            this.plated = plated;
        }
    }

    /**
     * A uniform grid over the hole centres, so each copper object is tested only against the holes
     * near it. Copper is the big side of this problem — a dense layer draws a million objects
     * against a few thousand holes — so the index goes over the holes and the artwork queries it.
     */
    private static final class HoleIndex {
        private final double cell;
        private final Map<Long, int[]> grid = new HashMap<>();

        HoleIndex(List<Hole> holes) {
            BoundingBox extent = new BoundingBox();
            for (Hole hole : holes) {
                extent.extend(hole.x, hole.y);
            }
            this.cell = cellSize(extent, holes.size());

            Map<Long, List<Integer>> buckets = new HashMap<>();
            for (int i = 0; i < holes.size(); i++) {
                buckets.computeIfAbsent(key(holes.get(i).x, holes.get(i).y),
                        k -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<Long, List<Integer>> e : buckets.entrySet()) {
                int[] indices = new int[e.getValue().size()];
                for (int i = 0; i < indices.length; i++) {
                    indices[i] = e.getValue().get(i);
                }
                grid.put(e.getKey(), indices);
            }
        }

        /** The holes whose centre falls in the cells this box covers; some may lie outside it. */
        int[] candidates(BoundingBox box) {
            int minCx = (int) Math.floor(box.getMinX() / cell);
            int maxCx = (int) Math.floor(box.getMaxX() / cell);
            int minCy = (int) Math.floor(box.getMinY() / cell);
            int maxCy = (int) Math.floor(box.getMaxY() / cell);
            if (minCx == maxCx && minCy == maxCy) {
                int[] one = grid.get(key(minCx, minCy));
                return one == null ? EMPTY : one;
            }
            int[] out = EMPTY;
            int size = 0;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cy = minCy; cy <= maxCy; cy++) {
                    int[] cellHoles = grid.get(key(cx, cy));
                    if (cellHoles == null) {
                        continue;
                    }
                    if (size + cellHoles.length > out.length) {
                        out = Arrays.copyOf(out, Math.max(8, (size + cellHoles.length) * 2));
                    }
                    System.arraycopy(cellHoles, 0, out, size, cellHoles.length);
                    size += cellHoles.length;
                }
            }
            return size == out.length ? out : Arrays.copyOf(out, size);
        }

        /** A cell of roughly one hole, so a pad-sized object touches only a handful of them. */
        private static double cellSize(BoundingBox extent, int count) {
            if (!extent.isValid() || count <= 0) {
                return 1.0;
            }
            double area = Math.max(extent.getWidth(), 1) * Math.max(extent.getHeight(), 1);
            return Math.min(Math.max(Math.sqrt(area / count), 0.5), 5.0);
        }

        private long key(double x, double y) {
            return key((int) Math.floor(x / cell), (int) Math.floor(y / cell));
        }

        private static long key(int cx, int cy) {
            return ((long) cx << 32) ^ (cy & 0xffffffffL);
        }

        private static final int[] EMPTY = new int[0];
    }
}
