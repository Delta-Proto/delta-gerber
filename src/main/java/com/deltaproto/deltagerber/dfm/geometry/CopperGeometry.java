package com.deltaproto.deltagerber.dfm.geometry;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.ImagePolarity;
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
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * The copper of one layer as measurable geometry: every object the file draws, in file order, as
 * either a list of {@link Capsule}s (round-aperture strokes, round and obround pads) or a
 * {@link PolygonShape} (regions, rectangular and polygonal pads, macros, rectangular-aperture
 * strokes), plus a {@link EdgeGrid} over all of their boundaries.
 *
 * <p>Nothing here is unioned. A round trace is its centreline and a radius, exactly; a pad is its
 * outline with the flash transform applied; a region is its contour. Arcs are the one thing that
 * has to become chords — region contours, arc strokes' centrelines, macro circles — and they are
 * flattened at {@link #FLATNESS_MM}, a sagitta of half a micrometre, chosen so the error is two
 * orders below anything a fabricator quotes. This is what distinguishes it from
 * {@code renderer.svg.GerberShapes}, which flattens at 0.05 mm for drawing and boxes every
 * non-round pad.
 *
 * <p>Polarity is kept, not applied: a clear object is a {@link Feature} with {@link Feature#clear}
 * set, and every dark feature knows the clears drawn after it that overlap it
 * ({@link Feature#laterClears}). Whether a given point of a dark feature still exists is then a
 * question ({@link #erasedAt}) rather than a boolean operation — and the one boolean that was
 * measured, subtracting a plane's 1 600 antipads with {@code java.awt.geom.Area}, took 15 seconds
 * and grew quadratically. Clear features are always polygons, so their boundary can be walked.
 *
 * <p>A copper layer routinely carries a thin trace along the board edge that is not copper of the
 * board's; pass the (shrunk) board outline as {@code insideOnly} and strokes with an endpoint
 * outside it are left out, the same rule {@code PcbAnalyzer.minTrackWidthUm} applies.
 */
public final class CopperGeometry {

    /** Chord sagitta (mm) when an arc has to become straight edges. */
    public static final double FLATNESS_MM = 0.0005;

    /** Never split one arc into more chords than this, whatever its radius. */
    private static final int MAX_CHORDS_PER_ARC = 4096;

    /** Cell size (mm) of the boundary grid — the scale of the distances being measured. */
    public static final double CELL_MM = 0.5;

    /** Cell size (mm) of the coarser grid over whole features. */
    private static final double FEATURE_CELL_MM = 2.0;

    /** What a dark feature is, which decides which measurements apply to it. */
    public enum Kind {
        /** A draw or arc: has an aperture width. */
        STROKE,
        /** A flash: a pad, whose size is not a conductor width. */
        PAD,
        /** A G36 region: a pour, whose own outline can neck. */
        REGION
    }

    /** One drawn object as geometry. Exactly one of {@link #solids} and {@link #polygon} is set. */
    public static final class Feature {
        public final int index;
        public final GraphicsObject source;
        public final boolean clear;
        public final Kind kind;
        /** Capsules for a round stroke (one per chord of an arc) or a round/obround pad. */
        public final List<Capsule> solids;
        /** Polygon for everything else, and for every clear. */
        public final PolygonShape polygon;
        public final BoundingBox bounds;
        /** Aperture width across the stroke for a {@link Kind#STROKE}; NaN otherwise. */
        public final double strokeWidthMm;
        /** The {@code .N} net attribute, or null. */
        public final String net;
        int[] laterClears = new int[0];

        Feature(int index, GraphicsObject source, boolean clear, Kind kind, List<Capsule> solids,
                PolygonShape polygon, double strokeWidthMm) {
            this.index = index;
            this.source = source;
            this.clear = clear;
            this.kind = kind;
            this.solids = solids == null ? List.of() : List.copyOf(solids);
            this.polygon = polygon;
            this.strokeWidthMm = strokeWidthMm;
            String n = source.getNet();
            this.net = n == null || n.isBlank() || n.equalsIgnoreCase("N/C") ? null : n;
            BoundingBox b = new BoundingBox();
            if (polygon != null) {
                b.include(polygon.bounds());
            }
            for (Capsule c : this.solids) {
                b.include(c.bounds());
            }
            this.bounds = b;
        }

        /** Whether the point is strictly inside this feature's own footprint, clears ignored. */
        public boolean contains(double px, double py) {
            if (polygon != null) {
                return polygon.contains(px, py);
            }
            for (Capsule c : solids) {
                if (c.contains(px, py)) {
                    return true;
                }
            }
            return false;
        }

        /** Indices of the clear features drawn after this one whose bounds overlap it. */
        public int[] laterClears() {
            return laterClears;
        }

        /** A short human-readable identity for a report. */
        public String describe() {
            BoundingBox b = bounds;
            String at = String.format(Locale.US, "(%.3f, %.3f)", b.getCenterX(), b.getCenterY());
            if (source instanceof Flash f) {
                return "D" + f.getAperture().getDCode() + " flash at " + at;
            }
            if (source instanceof Draw d) {
                return "D" + d.getAperture().getDCode() + " draw at " + at;
            }
            if (source instanceof Arc a) {
                return "D" + a.getAperture().getDCode() + " arc at " + at;
            }
            if (source instanceof Region) {
                return "region at " + at;
            }
            return source.getClass().getSimpleName() + " at " + at;
        }
    }

    private final List<Feature> features;
    private final EdgeGrid edgeGrid;
    private final EdgeGrid featureGrid;
    private final int[] entryFeature;
    private final Capsule[] entryCapsule;
    private final int[] entryRing;
    private final int[] entryEdge;
    private final BoundingBox bounds;
    private final List<String> warnings;

    private CopperGeometry(List<Feature> features, List<String> warnings) {
        this.features = Collections.unmodifiableList(features);
        this.warnings = Collections.unmodifiableList(warnings);
        BoundingBox b = new BoundingBox();
        List<BoundingBox> featureBounds = new ArrayList<>(features.size());
        for (Feature f : features) {
            b.include(f.bounds);
            featureBounds.add(f.bounds);
        }
        this.bounds = b;

        int count = 0;
        for (Feature f : features) {
            count += f.polygon != null ? f.polygon.edgeCount() : f.solids.size();
        }
        entryFeature = new int[count];
        entryCapsule = new Capsule[count];
        entryRing = new int[count];
        entryEdge = new int[count];
        List<BoundingBox> entryBounds = new ArrayList<>(count);
        int id = 0;
        for (Feature f : features) {
            if (f.polygon != null) {
                for (int r = 0; r < f.polygon.ringCount(); r++) {
                    for (int k = 0; k < f.polygon.ringSize(r); k++) {
                        Capsule edge = f.polygon.edge(r, k);
                        entryFeature[id] = f.index;
                        entryCapsule[id] = edge;
                        entryRing[id] = r;
                        entryEdge[id] = k;
                        entryBounds.add(edge.bounds());
                        id++;
                    }
                }
            } else {
                for (Capsule c : f.solids) {
                    entryFeature[id] = f.index;
                    entryCapsule[id] = c;
                    entryRing[id] = -1;
                    entryEdge[id] = -1;
                    entryBounds.add(c.bounds());
                    id++;
                }
            }
        }
        BoundingBox gridBounds = b.isValid() ? b : new BoundingBox(0, 0, 1, 1);
        this.edgeGrid = EdgeGrid.build(gridBounds, CELL_MM, entryBounds);
        this.featureGrid = EdgeGrid.build(gridBounds, FEATURE_CELL_MM, featureBounds);

        // Every dark feature learns the clears drawn after it that could erase part of it.
        List<List<Integer>> later = new ArrayList<>(features.size());
        for (int i = 0; i < features.size(); i++) {
            later.add(null);
        }
        for (Feature c : features) {
            if (!c.clear) {
                continue;
            }
            featureGrid.forEachNear(c.bounds, 0, i -> {
                Feature d = features.get(i);
                if (d.clear || d.index > c.index || !overlaps(d.bounds, c.bounds)) {
                    return;
                }
                if (later.get(i) == null) {
                    later.set(i, new ArrayList<>());
                }
                later.get(i).add(c.index);
            });
        }
        for (int i = 0; i < features.size(); i++) {
            List<Integer> list = later.get(i);
            if (list != null) {
                features.get(i).laterClears = list.stream().mapToInt(Integer::intValue).toArray();
            }
        }
    }

    // ------------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------------

    /** Every object of the document. */
    public static CopperGeometry of(GerberDocument document) {
        return of(document, null);
    }

    /**
     * @param insideOnly when given, a stroke (draw or arc) with an endpoint outside this box is
     *                   left out — the board-edge trace stamped on copper layers
     */
    public static CopperGeometry of(GerberDocument document, BoundingBox insideOnly) {
        List<Feature> features = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (document.getImagePolarity() == ImagePolarity.NEGATIVE) {
            warnings.add("negative image polarity (%IPNEG%) is not measured: the copper is the background");
            return new CopperGeometry(features, warnings);
        }
        Builder builder = new Builder(features, warnings, insideOnly);
        for (GraphicsObject obj : document.getObjects()) {
            builder.add(obj, obj.getPolarity() == Polarity.CLEAR);
        }
        return new CopperGeometry(features, warnings);
    }

    private static final class Builder {
        private final List<Feature> features;
        private final List<String> warnings;
        private final BoundingBox insideOnly;
        private int blockDepth;

        Builder(List<Feature> features, List<String> warnings, BoundingBox insideOnly) {
            this.features = features;
            this.warnings = warnings;
            this.insideOnly = insideOnly;
        }

        void add(GraphicsObject obj, boolean clear) {
            if (obj instanceof Flash flash) {
                addFlash(flash, clear);
            } else if (obj instanceof Draw draw) {
                if (excluded(draw.getStartX(), draw.getStartY(), draw.getEndX(), draw.getEndY())) {
                    return;
                }
                addDraw(draw, clear);
            } else if (obj instanceof Arc arc) {
                if (excluded(arc.getStartX(), arc.getStartY(), arc.getEndX(), arc.getEndY())) {
                    return;
                }
                addArc(arc, clear);
            } else if (obj instanceof Region region) {
                addRegion(region, clear);
            }
        }

        private boolean excluded(double x1, double y1, double x2, double y2) {
            return insideOnly != null && !(inside(x1, y1) && inside(x2, y2));
        }

        private boolean inside(double x, double y) {
            return x > insideOnly.getMinX() && x < insideOnly.getMaxX()
                    && y > insideOnly.getMinY() && y < insideOnly.getMaxY();
        }

        private void addFlash(Flash flash, boolean clear) {
            Aperture ap = flash.getAperture();
            GraphicsTransform t = new GraphicsTransform(flash.getX(), flash.getY(), flash.getRotation(),
                    flash.getScale(), flash.isMirrorX(), flash.isMirrorY());
            double scale = flash.getScale();
            if (ap instanceof BlockAperture block) {
                if (blockDepth > 8) {
                    warnings.add("block aperture nested deeper than 8: D" + ap.getDCode() + " skipped");
                    return;
                }
                blockDepth++;
                for (GraphicsObject inner : block.getObjects()) {
                    boolean innerClear = (inner.getPolarity() == Polarity.CLEAR) != clear;
                    add(inner.transform(t), innerClear);
                }
                blockDepth--;
                return;
            }
            if (ap instanceof CircleAperture circle) {
                Capsule disc = Capsule.disc(flash.getX(), flash.getY(), circle.getRadius() * scale);
                emit(flash, clear, Kind.PAD, List.of(disc), null, Double.NaN);
                return;
            }
            if (ap instanceof ObroundAperture obround) {
                double w = obround.getWidth(), h = obround.getHeight();
                double r = Math.min(w, h) / 2;
                double half = Math.abs(w - h) / 2;
                double ax = w >= h ? -half : 0, ay = w >= h ? 0 : -half;
                double bx = -ax, by = -ay;
                Capsule c = new Capsule(t.applyX(ax, ay), t.applyY(ax, ay), t.applyX(bx, by), t.applyY(bx, by), r * scale);
                emit(flash, clear, Kind.PAD, List.of(c), null, Double.NaN);
                return;
            }
            List<double[]> rings = new ArrayList<>();
            if (ap instanceof RectangleAperture rect) {
                double hw = rect.getWidth() / 2, hh = rect.getHeight() / 2;
                rings.add(transformed(t, new double[]{-hw, -hh, hw, -hh, hw, hh, -hw, hh}));
            } else if (ap instanceof PolygonAperture poly) {
                int n = poly.getNumVertices();
                double r = poly.getOuterDiameter() / 2;
                double rot = Math.toRadians(poly.getRotation());
                double[] ring = new double[2 * n];
                for (int i = 0; i < n; i++) {
                    double a = rot + 2 * Math.PI * i / n;
                    ring[2 * i] = r * Math.cos(a);
                    ring[2 * i + 1] = r * Math.sin(a);
                }
                rings.add(transformed(t, ring));
            } else if (ap instanceof MacroAperture macro) {
                Shape shape = macro.getShape();
                AffineTransform at = new AffineTransform();
                at.translate(flash.getX(), flash.getY());
                at.scale(flash.isMirrorX() ? -1 : 1, flash.isMirrorY() ? -1 : 1);
                at.rotate(Math.toRadians(flash.getRotation()));
                at.scale(scale, scale);
                rings.addAll(flatten(shape, at));
            } else {
                BoundingBox b = ap.getBoundingBox();
                if (!b.isValid()) {
                    return;
                }
                rings.add(transformed(t, new double[]{b.getMinX(), b.getMinY(), b.getMaxX(), b.getMinY(),
                        b.getMaxX(), b.getMaxY(), b.getMinX(), b.getMaxY()}));
            }
            PolygonShape polygon = PolygonShape.of(rings);
            if (polygon != null) {
                emit(flash, clear, Kind.PAD, null, polygon, Double.NaN);
            }
        }

        private void addDraw(Draw draw, boolean clear) {
            Aperture ap = draw.getAperture();
            double scale = draw.getStrokeScale();
            double x1 = draw.getStartX(), y1 = draw.getStartY(), x2 = draw.getEndX(), y2 = draw.getEndY();
            if (ap instanceof CircleAperture circle) {
                double r = circle.getRadius() * scale;
                emit(draw, clear, Kind.STROKE, List.of(new Capsule(x1, y1, x2, y2, r)), null, 2 * r);
                return;
            }
            // A rectangular brush swept along a line inks the convex hull of the rectangle at both
            // ends. Any other aperture is swept as its bounding rectangle, which can only overstate.
            BoundingBox b = ap.getBoundingBox();
            if (!b.isValid()) {
                return;
            }
            double hw = b.getWidth() / 2 * scale, hh = b.getHeight() / 2 * scale;
            double[] pts = {x1 - hw, y1 - hh, x1 + hw, y1 - hh, x1 + hw, y1 + hh, x1 - hw, y1 + hh,
                    x2 - hw, y2 - hh, x2 + hw, y2 - hh, x2 + hw, y2 + hh, x2 - hw, y2 + hh};
            PolygonShape polygon = PolygonShape.of(List.of(convexHull(pts)));
            if (polygon != null) {
                emit(draw, clear, Kind.STROKE, null, polygon, Math.min(2 * hw, 2 * hh));
            }
        }

        private void addArc(Arc arc, boolean clear) {
            Aperture ap = arc.getAperture();
            double scale = arc.getStrokeScale();
            double r;
            if (ap instanceof CircleAperture circle) {
                r = circle.getRadius() * scale;
            } else {
                BoundingBox b = ap.getBoundingBox();
                if (!b.isValid()) {
                    return;
                }
                r = Math.min(b.getWidth(), b.getHeight()) / 2 * scale;
            }
            List<double[]> pts = new ArrayList<>();
            pts.add(new double[]{arc.getStartX(), arc.getStartY()});
            appendArc(pts, arc.getStartX(), arc.getStartY(), arc.getEndX(), arc.getEndY(),
                    arc.getCenterX(), arc.getCenterY(), arc.isClockwise());
            List<Capsule> chords = new ArrayList<>(pts.size());
            for (int i = 1; i < pts.size(); i++) {
                double[] a = pts.get(i - 1), b = pts.get(i);
                chords.add(new Capsule(a[0], a[1], b[0], b[1], r));
            }
            if (chords.isEmpty()) {
                chords.add(Capsule.disc(arc.getStartX(), arc.getStartY(), r));
            }
            emit(arc, clear, Kind.STROKE, chords, null, 2 * r);
        }

        private void addRegion(Region region, boolean clear) {
            List<double[]> rings = new ArrayList<>();
            for (Contour contour : region.getContours()) {
                List<double[]> pts = new ArrayList<>();
                double cx = contour.getStartX(), cy = contour.getStartY();
                pts.add(new double[]{cx, cy});
                for (Contour.ContourSegment seg : contour.getSegments()) {
                    if (seg.isArc()) {
                        appendArc(pts, cx, cy, seg.getX(), seg.getY(), seg.getCenterX(), seg.getCenterY(),
                                seg.isClockwise());
                    } else {
                        pts.add(new double[]{seg.getX(), seg.getY()});
                    }
                    cx = seg.getX();
                    cy = seg.getY();
                }
                double[] first = pts.get(0), last = pts.get(pts.size() - 1);
                if (pts.size() > 1 && first[0] == last[0] && first[1] == last[1]) {
                    pts.remove(pts.size() - 1);
                }
                double[] ring = new double[2 * pts.size()];
                for (int i = 0; i < pts.size(); i++) {
                    ring[2 * i] = pts.get(i)[0];
                    ring[2 * i + 1] = pts.get(i)[1];
                }
                rings.add(ring);
            }
            PolygonShape polygon = PolygonShape.of(rings);
            if (polygon != null) {
                emit(region, clear, Kind.REGION, null, polygon, Double.NaN);
            }
        }

        /** A clear is always a polygon, so its boundary can be walked for witness points. */
        private void emit(GraphicsObject source, boolean clear, Kind kind, List<Capsule> solids,
                          PolygonShape polygon, double strokeWidth) {
            if (clear && polygon == null) {
                List<double[]> rings = new ArrayList<>();
                for (Capsule c : solids) {
                    rings.add(capsuleOutline(c));
                }
                polygon = PolygonShape.of(rings);
                if (polygon == null) {
                    return;
                }
                solids = null;
            }
            features.add(new Feature(features.size(), source, clear, kind, solids, polygon, strokeWidth));
        }
    }

    // ------------------------------------------------------------------------
    // Flattening
    // ------------------------------------------------------------------------

    /**
     * Append the points of the arc from {@code (sx, sy)} to {@code (ex, ey)} about the centre, not
     * repeating the start. A start equal to the end is a full circle, as in Gerber.
     */
    static void appendArc(List<double[]> pts, double sx, double sy, double ex, double ey,
                          double cx, double cy, boolean clockwise) {
        double r = Math.hypot(sx - cx, sy - cy);
        double startAngle = Math.atan2(sy - cy, sx - cx);
        double endAngle = Math.atan2(ey - cy, ex - cx);
        double sweep;
        if (clockwise) {
            sweep = startAngle - endAngle;
            if (sweep <= 1e-12) {
                sweep += 2 * Math.PI;
            }
            sweep = -sweep;
        } else {
            sweep = endAngle - startAngle;
            if (sweep <= 1e-12) {
                sweep += 2 * Math.PI;
            }
        }
        int steps = chordCount(r, Math.abs(sweep));
        for (int i = 1; i <= steps; i++) {
            double a = startAngle + sweep * i / steps;
            double x = i == steps ? ex : cx + r * Math.cos(a);
            double y = i == steps ? ey : cy + r * Math.sin(a);
            pts.add(new double[]{x, y});
        }
    }

    /** Chords needed so no chord sits more than {@link #FLATNESS_MM} from the arc. */
    static int chordCount(double radius, double sweep) {
        if (radius <= FLATNESS_MM) {
            return 1;
        }
        double perChord = 2 * Math.acos(1 - FLATNESS_MM / radius);
        int steps = (int) Math.ceil(sweep / perChord);
        return Math.max(1, Math.min(MAX_CHORDS_PER_ARC, steps));
    }

    /** The outline of a capsule as one ring, its two caps as chords. */
    static double[] capsuleOutline(Capsule c) {
        double dx = c.x2 - c.x1, dy = c.y2 - c.y1;
        double len = Math.hypot(dx, dy);
        double ux = len == 0 ? 1 : dx / len, uy = len == 0 ? 0 : dy / len;
        double nx = -uy, ny = ux;
        List<double[]> pts = new ArrayList<>();
        // Side along the left of the axis, then the far cap, the right side, the near cap.
        pts.add(new double[]{c.x1 + nx * c.r, c.y1 + ny * c.r});
        pts.add(new double[]{c.x2 + nx * c.r, c.y2 + ny * c.r});
        appendArc(pts, c.x2 + nx * c.r, c.y2 + ny * c.r, c.x2 - nx * c.r, c.y2 - ny * c.r, c.x2, c.y2, true);
        pts.add(new double[]{c.x1 - nx * c.r, c.y1 - ny * c.r});
        appendArc(pts, c.x1 - nx * c.r, c.y1 - ny * c.r, c.x1 + nx * c.r, c.y1 + ny * c.r, c.x1, c.y1, true);
        double[] first = pts.get(0), last = pts.get(pts.size() - 1);
        if (first[0] == last[0] && first[1] == last[1]) {
            pts.remove(pts.size() - 1);
        }
        double[] ring = new double[2 * pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            ring[2 * i] = pts.get(i)[0];
            ring[2 * i + 1] = pts.get(i)[1];
        }
        return ring;
    }

    private static List<double[]> flatten(Shape shape, AffineTransform at) {
        List<double[]> rings = new ArrayList<>();
        List<Double> current = null;
        double[] c = new double[6];
        for (PathIterator it = shape.getPathIterator(at, FLATNESS_MM); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                closeRing(rings, current);
                current = new ArrayList<>();
                current.add(c[0]);
                current.add(c[1]);
            } else if (type == PathIterator.SEG_LINETO && current != null) {
                current.add(c[0]);
                current.add(c[1]);
            } else if (type == PathIterator.SEG_CLOSE) {
                closeRing(rings, current);
                current = null;
            }
        }
        closeRing(rings, current);
        return rings;
    }

    private static void closeRing(List<double[]> rings, List<Double> current) {
        if (current == null || current.size() < 6) {
            return;
        }
        int n = current.size();
        if (current.get(0).equals(current.get(n - 2)) && current.get(1).equals(current.get(n - 1))) {
            n -= 2;
        }
        double[] ring = new double[n];
        for (int i = 0; i < n; i++) {
            ring[i] = current.get(i);
        }
        rings.add(ring);
    }

    private static double[] transformed(GraphicsTransform t, double[] local) {
        double[] out = new double[local.length];
        for (int i = 0; i < local.length; i += 2) {
            out[i] = t.applyX(local[i], local[i + 1]);
            out[i + 1] = t.applyY(local[i], local[i + 1]);
        }
        return out;
    }

    /** Andrew's monotone chain over {@code x0, y0, x1, y1, …}; result is counter-clockwise. */
    static double[] convexHull(double[] pts) {
        int n = pts.length / 2;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> {
            int c = Double.compare(pts[2 * a], pts[2 * b]);
            return c != 0 ? c : Double.compare(pts[2 * a + 1], pts[2 * b + 1]);
        });
        int[] hull = new int[2 * n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && turn(pts, hull[k - 2], hull[k - 1], order[i]) <= 0) {
                k--;
            }
            hull[k++] = order[i];
        }
        for (int i = n - 2, lower = k + 1; i >= 0; i--) {
            while (k >= lower && turn(pts, hull[k - 2], hull[k - 1], order[i]) <= 0) {
                k--;
            }
            hull[k++] = order[i];
        }
        k--;    // last point repeats the first
        double[] out = new double[2 * k];
        for (int i = 0; i < k; i++) {
            out[2 * i] = pts[2 * hull[i]];
            out[2 * i + 1] = pts[2 * hull[i] + 1];
        }
        return out;
    }

    private static double turn(double[] p, int a, int b, int c) {
        return (p[2 * b] - p[2 * a]) * (p[2 * c + 1] - p[2 * a + 1])
                - (p[2 * b + 1] - p[2 * a + 1]) * (p[2 * c] - p[2 * a]);
    }

    // ------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------

    public List<Feature> features() {
        return features;
    }

    public Feature feature(int index) {
        return features.get(index);
    }

    public BoundingBox bounds() {
        return bounds;
    }

    public List<String> warnings() {
        return warnings;
    }

    public boolean isEmpty() {
        return features.isEmpty();
    }

    // Boundary entries: one per capsule of a capsule feature, one per polygon edge otherwise.

    public int entryCount() {
        return entryFeature.length;
    }

    public int entryFeature(int entry) {
        return entryFeature[entry];
    }

    public Capsule entryCapsule(int entry) {
        return entryCapsule[entry];
    }

    /** Ring of a polygon edge entry, or -1 for a solid capsule. */
    public int entryRing(int entry) {
        return entryRing[entry];
    }

    /** Edge index within its ring for a polygon edge entry, or -1 for a solid capsule. */
    public int entryEdge(int entry) {
        return entryEdge[entry];
    }

    /** Every boundary entry within {@code reachMm} of the box, each once. */
    public void forEachEntryNear(BoundingBox box, double reachMm, IntConsumer consumer) {
        edgeGrid.forEachNear(box, reachMm, consumer);
    }

    public void forEachEntryNear(double px, double py, double reachMm, IntConsumer consumer) {
        edgeGrid.forEachNear(px, py, reachMm, consumer);
    }

    /** Every boundary entry within {@code reachMm} of the capsule's axis, each once. */
    public void forEachEntryAlong(Capsule c, double reachMm, IntConsumer consumer) {
        edgeGrid.forEachAlong(c.x1, c.y1, c.x2, c.y2, reachMm + c.r, consumer);
    }

    /** Every feature whose bounds come within {@code reachMm} of the box, each once. */
    public void forEachFeatureNear(BoundingBox box, double reachMm, IntConsumer consumer) {
        featureGrid.forEachNear(box, reachMm, consumer);
    }

    public void forEachFeatureNear(double px, double py, double reachMm, IntConsumer consumer) {
        featureGrid.forEachNear(px, py, reachMm, consumer);
    }

    /**
     * Whether a point of dark feature {@code dark} has been erased by a clear drawn after it — or,
     * with {@code after} given, by a clear drawn after index {@code after}. On a clear's boundary
     * counts as not erased, which is what makes a thermal spoke's crossing of its antipad a point
     * where plane and spoke still meet.
     */
    public boolean erasedAt(Feature dark, double px, double py) {
        return erasedAfter(dark.index, px, py);
    }

    /** Whether any clear with an index above {@code after} strictly contains the point. */
    public boolean erasedAfter(int after, double px, double py) {
        boolean[] hit = new boolean[1];
        featureGrid.forEachNear(px, py, 0, i -> {
            if (hit[0]) {
                return;
            }
            Feature c = features.get(i);
            if (c.clear && c.index > after && c.contains(px, py)) {
                hit[0] = true;
            }
        });
        return hit[0];
    }

    /**
     * The copper actually at a point: a dark feature containing it whose copper there survives every
     * later clear. Features drawn later win, so a pad drawn over an antipad answers the pad.
     *
     * @return the feature index, or -1 for bare laminate
     */
    public int copperAt(double px, double py) {
        int[] best = {-1};
        featureGrid.forEachNear(px, py, 0, i -> {
            Feature f = features.get(i);
            if (f.clear || f.index < best[0] || !f.contains(px, py)) {
                return;
            }
            if (!erasedAt(f, px, py)) {
                best[0] = f.index;
            }
        });
        return best[0];
    }

    /** Whether two boxes share any point. */
    public static boolean overlaps(BoundingBox a, BoundingBox b) {
        return a.getMinX() <= b.getMaxX() && b.getMinX() <= a.getMaxX()
                && a.getMinY() <= b.getMaxY() && b.getMinY() <= a.getMaxY();
    }
}
