package com.deltaproto.deltagerber.edit;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.BlockAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.MacroAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.ObroundAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.PolygonAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.RectangleAperture;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsTransform;
import com.deltaproto.deltagerber.model.gerber.operation.Region;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The exact inked outline of every dark object in a Gerber document — what a stencil editor needs
 * to scale, split or move a paste opening, and what {@code renderer.svg.GerberShapes} deliberately
 * does not give (it boxes every non-round aperture).
 *
 * <p>Rectangle, obround, polygon and macro apertures are outlined exactly, with the flash's
 * rotation, mirroring and scale applied ({@link GraphicsTransform}, the same placement the renderer
 * and block expansion use); circles and arcs are flattened to within {@link #FLATNESS_MM}; a draw
 * or arc is the band its aperture sweeps. Clear objects are skipped, and nothing is subtracted for
 * a later clear object: each entry is what its own object inks.
 *
 * <p>One entry per separate piece: a region of several disjoint contours, a thermal macro or a
 * block aperture gives one {@link PadOutline} per outer boundary, all with the same
 * {@link PadOutline#objectIndex()}. An aperture's hole is not part of the ring.
 */
public final class PadOutlines {

    /** Largest distance between a flattened curve and the true one, mm. */
    public static final double FLATNESS_MM = 0.005;

    private static final double EPS = 1e-9;
    private static final double ANGLE_EPS_DEG = 1e-6;
    private static final String APER_FUNCTION = ".AperFunction";

    private PadOutlines() {
    }

    /** Every dark object's outline, in document order. */
    public static List<PadOutline> of(GerberDocument doc) {
        List<PadOutline> out = new ArrayList<>();
        List<GraphicsObject> objects = doc.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            GraphicsObject o = objects.get(i);
            if (o.getPolarity() == Polarity.CLEAR) {
                continue;
            }
            out.addAll(outlinesOf(i, o));
        }
        return out;
    }

    /**
     * The outline(s) of one object regardless of its polarity — the shape it inks, dark or clear.
     * {@code objectIndex} is copied into the result.
     */
    public static List<PadOutline> outlinesOf(int objectIndex, GraphicsObject o) {
        String function = apertureFunction(o);
        String component = o.getComponentRef();

        if (o instanceof Flash f) {
            PadOutline exact = standardFlash(objectIndex, f, function, component);
            if (exact != null) {
                return List.of(exact);
            }
        }
        Area area = inked(o);
        if (area == null || area.isEmpty()) {
            return List.of();
        }
        boolean stroke = o instanceof Draw || o instanceof Arc;
        double strokeWidth = stroke ? strokeWidth(o) : 0;
        List<PadOutline> out = new ArrayList<>();
        List<List<double[]>> outer = outerRings(area);
        // A ring with a hole in it (an aperture hole, a cut-in) is not a plain rectangle, whatever
        // its outer boundary is.
        boolean holed = rings(area).size() > outer.size();
        for (List<double[]> ring : outer) {
            Box box = minimumAreaBox(ring);
            PadOutline.Kind kind = PadOutline.Kind.POLYGON;
            if (stroke) {
                kind = PadOutline.Kind.STROKE;
            } else if (!holed && isRectangle(ring, box)) {
                kind = PadOutline.Kind.RECTANGLE;
            }
            out.add(new PadOutline(objectIndex, o, kind, box.cx, box.cy, box.w, box.h, box.rot, ring,
                    strokeWidth, function, component));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Standard flashes: the shape is known, only the placement needs working out
    // ------------------------------------------------------------------------------------------

    private static PadOutline standardFlash(int index, Flash f, String function, String component) {
        Aperture a = f.getAperture();
        PadOutline.Kind kind;
        double w;
        double h;
        if (a instanceof CircleAperture c && !c.hasHole()) {
            kind = PadOutline.Kind.CIRCLE;
            w = h = c.getDiameter();
        } else if (a instanceof RectangleAperture r && !r.hasHole()) {
            kind = PadOutline.Kind.RECTANGLE;
            w = r.getWidth();
            h = r.getHeight();
        } else if (a instanceof ObroundAperture ob && !ob.hasHole()) {
            kind = ob.getWidth() == ob.getHeight() ? PadOutline.Kind.CIRCLE : PadOutline.Kind.OBROUND;
            w = ob.getWidth();
            h = ob.getHeight();
        } else {
            return null;
        }
        if (w <= 0 || h <= 0) {
            return null;
        }
        GraphicsTransform t = placement(f);
        List<double[]> ring = transform(t, localRing(a));
        if (ring.size() < 3) {
            return null;
        }
        ensureCounterClockwise(ring);
        double s = Math.abs(f.getScale());
        double rot = 0;
        if (kind != PadOutline.Kind.CIRCLE) {
            // Where the aperture's own width axis ends up; mirroring a symmetric shape changes nothing else.
            double ux = t.applyX(1, 0) - t.applyX(0, 0);
            double uy = t.applyY(1, 0) - t.applyY(0, 0);
            rot = normalize180(Math.toDegrees(Math.atan2(uy, ux)));
            if (Math.abs(rot - 90) < ANGLE_EPS_DEG) {
                double swap = w;
                w = h;
                h = swap;
                rot = 0;
            }
        }
        return new PadOutline(index, f, kind, f.getX(), f.getY(), w * s, h * s, rot, ring, 0, function, component);
    }

    private static GraphicsTransform placement(Flash f) {
        return new GraphicsTransform(f.getX(), f.getY(), f.getRotation(), f.getScale(), f.isMirrorX(), f.isMirrorY());
    }

    // ------------------------------------------------------------------------------------------
    // Inked areas
    // ------------------------------------------------------------------------------------------

    /** The area an object inks, absolute mm; null when it inks nothing we can outline. */
    static Area inked(GraphicsObject o) {
        if (o instanceof Flash f && f.getAperture() instanceof BlockAperture block) {
            // Place the contents first, as the parser expands a block flash, so curves are
            // flattened at their final size.
            return blockArea(block, placement(f));
        }
        if (o instanceof Flash f) {
            Area local = apertureArea(f.getAperture());
            if (local == null) {
                return null;
            }
            return local.createTransformedArea(affine(placement(f)));
        }
        if (o instanceof Draw d) {
            return strokeArea(d.getAperture(), d.getStrokeScale(),
                    List.of(new double[]{d.getStartX(), d.getStartY()}, new double[]{d.getEndX(), d.getEndY()}),
                    null);
        }
        if (o instanceof Arc a) {
            return arcArea(a);
        }
        if (o instanceof Region r) {
            Area area = new Area();
            for (Contour c : r.getContours()) {
                List<double[]> pts = contourPoints(c);
                if (pts.size() >= 3) {
                    area.add(new Area(polygon(pts, Path2D.WIND_EVEN_ODD)));
                }
            }
            return area;
        }
        return null;
    }

    /** What a block inks where it is flashed: its dark contents less its clear ones, in order. */
    private static Area blockArea(BlockAperture block, GraphicsTransform t) {
        Area area = new Area();
        for (GraphicsObject child : block.getObjects()) {
            Area piece = inked(child.transform(t));
            if (piece == null) {
                continue;
            }
            if (child.getPolarity() == Polarity.CLEAR) {
                area.subtract(piece);
            } else {
                area.add(piece);
            }
        }
        return area;
    }

    /** An aperture's inked area around its own origin, hole included. */
    private static Area apertureArea(Aperture a) {
        if (a instanceof MacroAperture m) {
            Shape shape = m.getShape();
            return shape == null ? null : new Area(shape);
        }
        List<double[]> ring = localRing(a);
        if (ring.size() < 3) {
            return null;
        }
        Area area = new Area(polygon(ring, Path2D.WIND_NON_ZERO));
        double hole = holeDiameter(a);
        if (hole > 0) {
            area.subtract(new Area(polygon(circle(0, 0, hole / 2), Path2D.WIND_NON_ZERO)));
        }
        return area;
    }

    /** A standard aperture's outer boundary around its origin; empty for macros and blocks. */
    private static List<double[]> localRing(Aperture a) {
        if (a instanceof CircleAperture c) {
            return c.getDiameter() > 0 ? circle(0, 0, c.getDiameter() / 2) : List.of();
        }
        if (a instanceof RectangleAperture r) {
            double hw = r.getWidth() / 2;
            double hh = r.getHeight() / 2;
            if (hw <= 0 || hh <= 0) {
                return List.of();
            }
            return new ArrayList<>(List.of(new double[]{-hw, -hh}, new double[]{hw, -hh},
                    new double[]{hw, hh}, new double[]{-hw, hh}));
        }
        if (a instanceof ObroundAperture o) {
            return obround(o.getWidth(), o.getHeight());
        }
        if (a instanceof PolygonAperture p) {
            int n = p.getNumVertices();
            double r = p.getOuterDiameter() / 2;
            if (n < 3 || r <= 0) {
                return List.of();
            }
            List<double[]> pts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double ang = Math.toRadians(p.getRotation() + 360.0 * i / n);
                pts.add(new double[]{r * Math.cos(ang), r * Math.sin(ang)});
            }
            return pts;
        }
        return List.of();
    }

    private static double holeDiameter(Aperture a) {
        if (a instanceof CircleAperture c) {
            return c.getHoleDiameter();
        }
        if (a instanceof RectangleAperture r) {
            return r.getHoleDiameter();
        }
        if (a instanceof ObroundAperture o) {
            return o.getHoleDiameter();
        }
        if (a instanceof PolygonAperture p) {
            return p.getHoleDiameter();
        }
        return 0;
    }

    /**
     * The band an aperture sweeps along a polyline. A circle sweeps capsules; any other aperture
     * sweeps the hull of itself at both ends of each segment, which is exact for a convex aperture.
     */
    private static Area strokeArea(Aperture a, double strokeScale, List<double[]> path, Area seed) {
        Area area = seed == null ? new Area() : seed;
        if (a instanceof CircleAperture c) {
            double r = c.getDiameter() * strokeScale / 2;
            if (r <= 0) {
                return area;
            }
            if (path.size() == 1) {
                area.add(new Area(polygon(circle(path.get(0)[0], path.get(0)[1], r), Path2D.WIND_NON_ZERO)));
            }
            for (int i = 0; i + 1 < path.size(); i++) {
                area.add(new Area(polygon(capsule(path.get(i), path.get(i + 1), r), Path2D.WIND_NON_ZERO)));
            }
            return area;
        }
        List<double[]> shape = new ArrayList<>();
        if (a instanceof MacroAperture m) {
            Shape s = m.getShape();
            if (s != null) {
                for (List<double[]> ring : rings(new Area(s))) {
                    shape.addAll(ring);
                }
            }
        } else {
            shape.addAll(localRing(a));
        }
        if (shape.isEmpty()) {
            return area;
        }
        for (double[] p : shape) {
            p[0] *= strokeScale;
            p[1] *= strokeScale;
        }
        for (int i = 0; i + 1 < path.size(); i++) {
            List<double[]> pts = new ArrayList<>();
            for (double[] end : List.of(path.get(i), path.get(i + 1))) {
                for (double[] p : shape) {
                    pts.add(new double[]{end[0] + p[0], end[1] + p[1]});
                }
            }
            area.add(new Area(polygon(convexHull(pts), Path2D.WIND_NON_ZERO)));
        }
        return area;
    }

    private static Area arcArea(Arc a) {
        double sx = a.getStartX() - a.getCenterX();
        double sy = a.getStartY() - a.getCenterY();
        double ex = a.getEndX() - a.getCenterX();
        double ey = a.getEndY() - a.getCenterY();
        double radius = Math.hypot(sx, sy);
        Aperture ap = a.getAperture();
        double width = ap instanceof CircleAperture c ? c.getDiameter() * a.getStrokeScale() : 0;
        double sweep = sweep(Math.atan2(sy, sx), Math.atan2(ey, ex), a.isClockwise(),
                Math.abs(a.getStartX() - a.getEndX()) < EPS && Math.abs(a.getStartY() - a.getEndY()) < EPS);

        if (!(ap instanceof CircleAperture)) {
            List<double[]> path = arcPoints(a.getCenterX(), a.getCenterY(), radius, Math.atan2(sy, sx), sweep,
                    Math.max(radius, FLATNESS_MM));
            return strokeArea(ap, a.getStrokeScale(), path, null);
        }
        double r = width / 2;
        if (r <= 0) {
            return new Area();
        }
        double outer = radius + r;
        double inner = radius - r;
        double start = Math.atan2(sy, sx);
        Area area = new Area();
        List<double[]> band = new ArrayList<>(arcPoints(a.getCenterX(), a.getCenterY(), outer, start, sweep, outer));
        if (inner > EPS) {
            List<double[]> in = arcPoints(a.getCenterX(), a.getCenterY(), inner, start, sweep, outer);
            java.util.Collections.reverse(in);
            band.addAll(in);
        } else {
            band.add(new double[]{a.getCenterX(), a.getCenterY()});
        }
        if (Math.abs(Math.abs(sweep) - 2 * Math.PI) < 1e-12) {
            // A full circle: an annulus, which a single polygon cannot state.
            area.add(new Area(polygon(circle(a.getCenterX(), a.getCenterY(), outer), Path2D.WIND_NON_ZERO)));
            if (inner > EPS) {
                area.subtract(new Area(polygon(circle(a.getCenterX(), a.getCenterY(), inner), Path2D.WIND_NON_ZERO)));
            }
            return area;
        }
        area.add(new Area(polygon(band, Path2D.WIND_NON_ZERO)));
        area.add(new Area(polygon(circle(a.getStartX(), a.getStartY(), r), Path2D.WIND_NON_ZERO)));
        area.add(new Area(polygon(circle(a.getEndX(), a.getEndY(), r), Path2D.WIND_NON_ZERO)));
        return area;
    }

    /** Signed sweep from start to end angle; a coincident start and end is a full circle. */
    private static double sweep(double start, double end, boolean clockwise, boolean full) {
        if (full) {
            return clockwise ? -2 * Math.PI : 2 * Math.PI;
        }
        double d = end - start;
        if (clockwise) {
            while (d >= 0) {
                d -= 2 * Math.PI;
            }
        } else {
            while (d <= 0) {
                d += 2 * Math.PI;
            }
        }
        return d;
    }

    /**
     * Points along an arc, both ends included, spaced so the chord stays within
     * {@link #FLATNESS_MM} of a circle of radius {@code flatRadius} (the largest one being drawn).
     */
    private static List<double[]> arcPoints(double cx, double cy, double r, double start, double sweep,
                                            double flatRadius) {
        // An even count puts a vertex on the sweep's midpoint, so a half-turn cap reaches its full extent.
        int n = Math.max(2, (int) Math.ceil(Math.abs(sweep) / stepAngle(flatRadius)));
        n += n % 2;
        List<double[]> pts = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i++) {
            double ang = start + sweep * i / n;
            pts.add(new double[]{cx + r * Math.cos(ang), cy + r * Math.sin(ang)});
        }
        return pts;
    }

    private static double stepAngle(double radius) {
        if (radius <= FLATNESS_MM) {
            return Math.PI / 4;
        }
        return Math.min(Math.PI / 4, 2 * Math.acos(1 - FLATNESS_MM / radius));
    }

    private static List<double[]> circle(double cx, double cy, double r) {
        // A multiple of four puts vertices on the axes, so the polygon spans the full diameter.
        int n = Math.max(8, (int) Math.ceil(2 * Math.PI / stepAngle(r)));
        n = (n + 3) / 4 * 4;
        List<double[]> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double ang = 2 * Math.PI * i / n;
            pts.add(new double[]{cx + r * Math.cos(ang), cy + r * Math.sin(ang)});
        }
        return pts;
    }

    private static List<double[]> obround(double w, double h) {
        if (w <= 0 || h <= 0) {
            return List.of();
        }
        if (Math.abs(w - h) < EPS) {
            return circle(0, 0, w / 2);
        }
        List<double[]> pts = new ArrayList<>();
        if (w > h) {
            double r = h / 2;
            double off = (w - h) / 2;
            pts.addAll(arcPoints(off, 0, r, -Math.PI / 2, Math.PI, r));
            pts.addAll(arcPoints(-off, 0, r, Math.PI / 2, Math.PI, r));
        } else {
            double r = w / 2;
            double off = (h - w) / 2;
            pts.addAll(arcPoints(0, off, r, 0, Math.PI, r));
            pts.addAll(arcPoints(0, -off, r, Math.PI, Math.PI, r));
        }
        return pts;
    }

    private static List<double[]> capsule(double[] a, double[] b, double r) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        if (Math.hypot(dx, dy) < EPS) {
            return circle(a[0], a[1], r);
        }
        double dir = Math.atan2(dy, dx);
        List<double[]> pts = new ArrayList<>(arcPoints(b[0], b[1], r, dir - Math.PI / 2, Math.PI, r));
        pts.addAll(arcPoints(a[0], a[1], r, dir + Math.PI / 2, Math.PI, r));
        return pts;
    }

    private static List<double[]> contourPoints(Contour c) {
        List<double[]> pts = new ArrayList<>();
        double px = c.getStartX();
        double py = c.getStartY();
        pts.add(new double[]{px, py});
        for (Contour.ContourSegment s : c.getSegments()) {
            if (s.isArc()) {
                double sx = px - s.getCenterX();
                double sy = py - s.getCenterY();
                double r = Math.hypot(sx, sy);
                double start = Math.atan2(sy, sx);
                double end = Math.atan2(s.getY() - s.getCenterY(), s.getX() - s.getCenterX());
                boolean full = Math.abs(px - s.getX()) < EPS && Math.abs(py - s.getY()) < EPS;
                List<double[]> arc = arcPoints(s.getCenterX(), s.getCenterY(), r, start,
                        sweep(start, end, s.isClockwise(), full), r);
                for (int i = 1; i < arc.size(); i++) {
                    pts.add(arc.get(i));
                }
                pts.get(pts.size() - 1)[0] = s.getX();
                pts.get(pts.size() - 1)[1] = s.getY();
            } else {
                pts.add(new double[]{s.getX(), s.getY()});
            }
            px = s.getX();
            py = s.getY();
        }
        return pts;
    }

    // ------------------------------------------------------------------------------------------
    // Rings and boxes
    // ------------------------------------------------------------------------------------------

    /** The area's outer boundaries (even nesting depth), counter-clockwise, without closing points. */
    static List<List<double[]>> outerRings(Area area) {
        List<List<double[]>> all = rings(area);
        List<List<double[]>> outer = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            List<double[]> ring = all.get(i);
            double[] probe = interiorProbe(ring);
            int depth = 0;
            for (int j = 0; j < all.size(); j++) {
                if (j != i && Math.abs(signedArea(all.get(j))) > Math.abs(signedArea(ring))
                        && contains(all.get(j), probe[0], probe[1])) {
                    depth++;
                }
            }
            if (depth % 2 == 0) {
                ensureCounterClockwise(ring);
                outer.add(ring);
            }
        }
        // Largest first; equal pieces (a thermal's spokes) left to right, then bottom to top, so the
        // order does not hang on floating-point noise.
        outer.sort(Comparator.comparingDouble((List<double[]> r) -> -Math.round(Math.abs(signedArea(r)) * 1e6) / 1e6)
                .thenComparingDouble(r -> Math.round(minOf(r, 0) * 1e6) / 1e6)
                .thenComparingDouble(r -> Math.round(minOf(r, 1) * 1e6) / 1e6));
        return outer;
    }

    private static List<List<double[]>> rings(Area area) {
        List<List<double[]>> rings = new ArrayList<>();
        List<double[]> current = null;
        double[] c = new double[6];
        for (PathIterator it = area.getPathIterator(null, FLATNESS_MM); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                current = new ArrayList<>();
                current.add(new double[]{c[0], c[1]});
            } else if (type == PathIterator.SEG_LINETO && current != null) {
                double[] last = current.get(current.size() - 1);
                if (Math.abs(last[0] - c[0]) > EPS || Math.abs(last[1] - c[1]) > EPS) {
                    current.add(new double[]{c[0], c[1]});
                }
            } else if (type == PathIterator.SEG_CLOSE && current != null) {
                double[] first = current.get(0);
                double[] last = current.get(current.size() - 1);
                if (current.size() > 1 && Math.abs(last[0] - first[0]) < EPS && Math.abs(last[1] - first[1]) < EPS) {
                    current.remove(current.size() - 1);
                }
                if (current.size() >= 3 && Math.abs(signedArea(current)) > EPS * EPS) {
                    rings.add(current);
                }
                current = null;
            }
        }
        return rings;
    }

    /** A point just inside the ring, off its edges: the midpoint of an edge nudged inwards. */
    private static double[] interiorProbe(List<double[]> ring) {
        double sign = Math.signum(signedArea(ring));
        double[] a = ring.get(0);
        double[] b = ring.get(1);
        double mx = (a[0] + b[0]) / 2;
        double my = (a[1] + b[1]) / 2;
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double len = Math.hypot(dx, dy);
        double nudge = Math.min(1e-6, len * 1e-3);
        // The interior of a counter-clockwise ring is to the left of each edge.
        return new double[]{mx - sign * dy / len * nudge, my + sign * dx / len * nudge};
    }

    private static boolean contains(List<double[]> ring, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double[] a = ring.get(i);
            double[] b = ring.get(j);
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double minOf(List<double[]> ring, int axis) {
        double m = Double.MAX_VALUE;
        for (double[] p : ring) {
            m = Math.min(m, p[axis]);
        }
        return m;
    }

    static double signedArea(List<double[]> ring) {
        double s = 0;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            s += ring.get(j)[0] * ring.get(i)[1] - ring.get(i)[0] * ring.get(j)[1];
        }
        return s / 2;
    }

    private static void ensureCounterClockwise(List<double[]> ring) {
        if (signedArea(ring) < 0) {
            java.util.Collections.reverse(ring);
        }
    }

    private record Box(double cx, double cy, double w, double h, double rot) {
    }

    /**
     * The minimum-area rectangle around the ring (rotating calipers over its hull). Its width axis
     * is reported in [0, 90): an angle of 90 or more is the same box with width and height swapped.
     */
    private static Box minimumAreaBox(List<double[]> ring) {
        List<double[]> hull = convexHull(ring);
        Box best = null;
        double bestArea = Double.MAX_VALUE;
        for (int i = 0; i < hull.size(); i++) {
            double[] a = hull.get(i);
            double[] b = hull.get((i + 1) % hull.size());
            double ang = Math.atan2(b[1] - a[1], b[0] - a[0]);
            Box box = boxAt(hull, ang);
            double area = box.w * box.h;
            if (area < bestArea - 1e-12) {
                bestArea = area;
                best = box;
            }
        }
        // Prefer the axis-aligned box when it is as small (a circle's hull has no favourite edge).
        Box aligned = boxAt(hull, 0);
        if (best == null || aligned.w * aligned.h <= bestArea * (1 + 1e-9)) {
            best = aligned;
        }
        return best;
    }

    private static Box boxAt(List<double[]> pts, double angle) {
        double ux = Math.cos(angle);
        double uy = Math.sin(angle);
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (double[] p : pts) {
            double u = p[0] * ux + p[1] * uy;
            double v = -p[0] * uy + p[1] * ux;
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        double cu = (minU + maxU) / 2;
        double cv = (minV + maxV) / 2;
        double cx = cu * ux - cv * uy;
        double cy = cu * uy + cv * ux;
        double w = maxU - minU;
        double h = maxV - minV;
        double deg = normalize180(Math.toDegrees(angle));
        if (deg >= 90 - ANGLE_EPS_DEG) {
            deg -= 90;
            double swap = w;
            w = h;
            h = swap;
        }
        if (Math.abs(deg) < ANGLE_EPS_DEG) {
            deg = 0;
        }
        return new Box(cx, cy, w, h, deg);
    }

    /** True when the ring, with collinear points dropped, is four right-angled corners filling its box. */
    private static boolean isRectangle(List<double[]> ring, Box box) {
        double tol = Math.max(1e-6, 1e-6 * Math.max(box.w, box.h));
        if (box.w <= tol || box.h <= tol) {
            return false;
        }
        double area = Math.abs(signedArea(ring));
        if (Math.abs(area - box.w * box.h) > tol * (box.w + box.h)) {
            return false;
        }
        // Filling its minimum box exactly leaves only a rectangle; the corner count guards against
        // flattening noise passing for one.
        int corners = 0;
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            double[] p = ring.get((i + n - 1) % n);
            double[] q = ring.get(i);
            double[] r = ring.get((i + 1) % n);
            double cross = (q[0] - p[0]) * (r[1] - q[1]) - (q[1] - p[1]) * (r[0] - q[0]);
            double len = Math.hypot(q[0] - p[0], q[1] - p[1]) * Math.hypot(r[0] - q[0], r[1] - q[1]);
            if (len > 0 && Math.abs(cross) / len > 1e-6) {
                corners++;
            }
        }
        return corners == 4;
    }

    static List<double[]> convexHull(List<double[]> points) {
        double[][] pts = points.toArray(new double[0][]);
        Arrays.sort(pts, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = pts.length;
        if (n < 3) {
            return new ArrayList<>(Arrays.asList(pts));
        }
        double[][] hull = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], pts[i]) <= 0) {
                k--;
            }
            hull[k++] = pts[i];
        }
        for (int i = n - 2, lower = k + 1; i >= 0; i--) {
            while (k >= lower && cross(hull[k - 2], hull[k - 1], pts[i]) <= 0) {
                k--;
            }
            hull[k++] = pts[i];
        }
        return new ArrayList<>(Arrays.asList(hull).subList(0, k - 1));
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static double normalize180(double deg) {
        double d = deg % 180;
        if (d < 0) {
            d += 180;
        }
        if (180 - d < ANGLE_EPS_DEG) {
            d = 0;
        }
        return d;
    }

    private static List<double[]> transform(GraphicsTransform t, List<double[]> pts) {
        List<double[]> out = new ArrayList<>(pts.size());
        for (double[] p : pts) {
            out.add(new double[]{t.applyX(p[0], p[1]), t.applyY(p[0], p[1])});
        }
        return out;
    }

    private static AffineTransform affine(GraphicsTransform t) {
        double tx = t.applyX(0, 0);
        double ty = t.applyY(0, 0);
        return new AffineTransform(t.applyX(1, 0) - tx, t.applyY(1, 0) - ty,
                t.applyX(0, 1) - tx, t.applyY(0, 1) - ty, tx, ty);
    }

    private static Path2D.Double polygon(List<double[]> pts, int windingRule) {
        Path2D.Double path = new Path2D.Double(windingRule, pts.size() + 1);
        path.moveTo(pts.get(0)[0], pts.get(0)[1]);
        for (int i = 1; i < pts.size(); i++) {
            path.lineTo(pts.get(i)[0], pts.get(i)[1]);
        }
        path.closePath();
        return path;
    }

    private static double strokeWidth(GraphicsObject o) {
        Aperture a = o instanceof Draw d ? d.getAperture() : ((Arc) o).getAperture();
        double s = o instanceof Draw d ? d.getStrokeScale() : ((Arc) o).getStrokeScale();
        if (a instanceof CircleAperture c) {
            return c.getDiameter() * s;
        }
        if (a instanceof RectangleAperture r) {
            return Math.min(r.getWidth(), r.getHeight()) * s;
        }
        if (a instanceof ObroundAperture ob) {
            return Math.min(ob.getWidth(), ob.getHeight()) * s;
        }
        if (a instanceof PolygonAperture p) {
            return p.getOuterDiameter() * s;
        }
        return 0;
    }

    private static String apertureFunction(GraphicsObject o) {
        List<String> values = null;
        if (o instanceof Region r) {
            values = r.getApertureAttributes().get(APER_FUNCTION);
        } else {
            Aperture a = o instanceof Flash f ? f.getAperture()
                    : o instanceof Draw d ? d.getAperture()
                    : o instanceof Arc arc ? arc.getAperture() : null;
            if (a != null) {
                values = a.getAttribute(APER_FUNCTION);
            }
        }
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }
}
