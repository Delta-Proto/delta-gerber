package com.deltaproto.deltagerber.renderer.step;

import com.deltaproto.deltagerber.renderer.svg.BoardOutline;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.apache.batik.parser.AWTPathProducer;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extrudes the resolved board outline into a solid and writes it as an ISO 10303-21 (STEP)
 * exchange file — the board as a mechanical part, for an enclosure designer to drop into CAD.
 *
 * <p>It is a third consumer of {@link MultiLayerSVGRenderer#resolveBoardOutline}, alongside the
 * realistic view's clip path and mask base, and deliberately adds no outline logic of its own:
 * a set with a profile layer is extruded from the chained profile, cut-outs and all, and a set
 * without one from the copper silhouette. What the realistic view shows is what comes out here.
 *
 * <p>The solid keeps the Gerber frame's X and Y in millimetres — so it lines up with every other
 * coordinate the library reports — and occupies z ∈ [0, thickness], bottom copper at z=0.
 * Thickness defaults to {@value #DEFAULT_THICKNESS_MM} mm, the ordinary FR-4 board, and is the
 * one thing a caller has to state, because no Gerber file carries it. A set that ships a
 * {@code .gbrjob} or IPC-2581 stack-up does state it: pass
 * {@code spec.BoardSpecification.getBoardThicknessMm()} when you have one.
 *
 * <p>Everything the set drills is subtracted from that solid, so the model carries the mounting
 * holes and vias, and — because a hole is subtracted whether or not it lies inside the board —
 * the <b>mouse bites</b> on a break-off tab take their bite out of the board edge, the way the
 * fabricator's router leaves it. Turn it off with {@link #setIncludeDrillHoles(boolean)} for the
 * bare outline.
 *
 * <p>The two flat faces are labelled: the words {@code TOP} and {@code BOTTOM} are written on
 * them as engraving-style curves lying in each surface (the underside mirrored, so it reads from
 * below), because a bare board silhouette gives an enclosure designer nothing to tell one face
 * from the other by. They are annotation, not material — a separate wireframe representation
 * that leaves the solid untouched — and {@link #setLabelSides(boolean)} turns them off.
 *
 * <p>Curves are flattened. The outline arrives as an SVG path whose arcs are approximated to
 * {@value #FLATNESS_MM} mm, so the solid is a prism over a polygon: a rounded corner comes out
 * faceted, well inside any fabrication tolerance but visibly not a cylinder in CAD.
 *
 * <p>Usage:
 * <pre>{@code
 * String step = new StepExporter().setThicknessMm(1.0).export(layers);
 * }</pre>
 */
public class StepExporter {

    /** Board thickness (mm) used when the caller does not state one — standard FR-4. */
    public static final double DEFAULT_THICKNESS_MM = 1.6;

    /** Arc/curve flattening tolerance (mm) for the outline path. */
    private static final double FLATNESS_MM = 0.01;

    /** Points closer than this (mm) are the same point — a loop cannot have a zero-length edge. */
    private static final double WELD_MM = 1e-4;

    /**
     * Drop loops enclosing less than this (mm²). Flattening and chaining leave the occasional
     * zero-area sliver, and a face built on one is invalid; a real board feature — the smallest
     * mouse-bite tab or slot — is orders of magnitude larger.
     */
    private static final double MIN_LOOP_AREA_MM2 = 1e-3;

    /** How many of a loop's vertices vote on whether another loop encloses it. */
    private static final int NESTING_SAMPLES = 32;

    /** What the two faces are called, and how big the words are drawn. */
    private static final String TOP_LABEL = "TOP";
    private static final String BOTTOM_LABEL = "BOTTOM";
    private static final double LABEL_HEIGHT_FRACTION = 0.10;
    private static final double LABEL_MIN_HEIGHT_MM = 1.0;
    private static final double LABEL_MAX_HEIGHT_MM = 8.0;
    private static final double LABEL_MAX_WIDTH_FRACTION = 0.6;

    /** How far off a seam a vertex may sit and still count as lying on it (mm). */
    private static final double SEAM_EPS_MM = 1e-6;

    /** Cell size (mm) of the grid that finds the vertices sitting on an edge. */
    private static final double SEAM_GRID_MM = 1.0;

    /** Cell keys are {@code x * stride + y}; the stride outruns any board's cell count. */
    private static final long GRID_STRIDE = 1L << 32;

    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private double thicknessMm = DEFAULT_THICKNESS_MM;
    private boolean includeDrillHoles = true;
    private boolean labelSides = true;
    private String productName = "PCB";
    private Instant timestamp;

    /**
     * Finished board thickness in millimetres. Must be positive.
     *
     * @throws IllegalArgumentException if not a positive, finite number
     */
    public StepExporter setThicknessMm(double thicknessMm) {
        if (!Double.isFinite(thicknessMm) || thicknessMm <= 0) {
            throw new IllegalArgumentException("Board thickness must be positive, got " + thicknessMm);
        }
        this.thicknessMm = thicknessMm;
        return this;
    }

    public double getThicknessMm() {
        return thicknessMm;
    }

    /**
     * Whether the set's drilled holes are subtracted from the solid (default {@code true}).
     * Only {@link #export(List)} can honour this — an outline on its own says nothing about
     * what is drilled through it.
     */
    public StepExporter setIncludeDrillHoles(boolean includeDrillHoles) {
        this.includeDrillHoles = includeDrillHoles;
        return this;
    }

    public boolean isIncludeDrillHoles() {
        return includeDrillHoles;
    }

    /**
     * Whether {@code TOP} and {@code BOTTOM} are written on the board's two faces
     * (default {@code true}).
     */
    public StepExporter setLabelSides(boolean labelSides) {
        this.labelSides = labelSides;
        return this;
    }

    public boolean isLabelSides() {
        return labelSides;
    }

    /** Name carried in the file's {@code PRODUCT} entity — what CAD shows in its tree. */
    public StepExporter setProductName(String productName) {
        this.productName = productName == null || productName.isBlank() ? "PCB" : productName;
        return this;
    }

    /**
     * The creation timestamp written into the header. Defaults to the moment of export; set it
     * to make the output byte-for-byte reproducible for a given board.
     */
    public StepExporter setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    /**
     * Export a layer set: resolve its board outline, subtract what the set drills, then extrude.
     *
     * <p>The drill layers are origin-corrected first ({@link MultiLayerSVGRenderer#alignDrillLayers}),
     * so a drill program exported on its own origin still lands on the board — the same correction
     * the rendered views get, and a no-op for a set that never needed it.
     *
     * @throws IllegalArgumentException when no board edge can be resolved — the set has neither
     *         a profile layer that draws anything nor any copper to derive an edge from
     */
    public String export(List<MultiLayerSVGRenderer.Layer> layers) {
        List<MultiLayerSVGRenderer.Layer> aligned = MultiLayerSVGRenderer.alignDrillLayers(layers);
        return export(new MultiLayerSVGRenderer().resolveBoardOutline(aligned),
            includeDrillHoles ? DrillHoles.of(aligned) : null);
    }

    /**
     * Export an already-resolved outline, with nothing drilled through it.
     *
     * @throws IllegalArgumentException if the outline is empty, or carries no loop big enough
     *         to build a solid from
     */
    public String export(BoardOutline outline) {
        return export(outline, null);
    }

    private String export(BoardOutline outline, Area holes) {
        if (outline == null || outline.isEmpty()) {
            throw new IllegalArgumentException(
                "No board outline to export: the set has no profile layer and no copper to "
                + "derive the board edge from");
        }
        List<Loop> loops = flatten(outline, holes);
        if (loops.isEmpty()) {
            throw new IllegalArgumentException("Board outline encloses no area");
        }
        List<Loop> solids = nest(loops);
        if (solids.isEmpty()) {
            // Every loop came out a cut-out, which takes loops that enclose each other — a
            // profile layer emitted twice, say. There is no board to extrude from that.
            throw new IllegalArgumentException(
                "Board outline has no enclosing loop: every one of its " + loops.size()
                + " loops sits inside another");
        }
        return write(solids);
    }

    // --- outline path → closed loops -------------------------------------------------

    /**
     * A closed, simple polygon in millimetres, wound counter-clockwise seen from +Z when it
     * bounds material and clockwise when it bounds a cut-out — the winding STEP wants for an
     * outer bound and an inner bound of the same face.
     */
    private static final class Loop {
        final double[] x;
        final double[] y;
        double area;        // signed, +ve counter-clockwise; follows the winding
        final double minX, minY, maxX, maxY;
        final List<Loop> holes = new ArrayList<>();

        Loop(List<double[]> pts) {
            int n = pts.size();
            x = new double[n];
            y = new double[n];
            double lox = Double.MAX_VALUE, loy = Double.MAX_VALUE;
            double hix = -Double.MAX_VALUE, hiy = -Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                x[i] = pts.get(i)[0];
                y[i] = pts.get(i)[1];
                lox = Math.min(lox, x[i]); hix = Math.max(hix, x[i]);
                loy = Math.min(loy, y[i]); hiy = Math.max(hiy, y[i]);
            }
            minX = lox; maxX = hix; minY = loy; maxY = hiy;
            area = signedArea(x, y);
        }

        int size() {
            return x.length;
        }

        /** Reverse the winding in place — a loop is material or a cut-out, not both. */
        void reverse() {
            for (int i = 0, j = x.length - 1; i < j; i++, j--) {
                double tx = x[i]; x[i] = x[j]; x[j] = tx;
                double ty = y[i]; y[i] = y[j]; y[j] = ty;
            }
            area = -area;
        }

        void windCounterClockwise() {
            if (area < 0) reverse();
        }

        void windClockwise() {
            if (area > 0) reverse();
        }

        /**
         * Does this loop enclose {@code other}?
         *
         * <p>Decided by a vote of {@code other}'s own vertices rather than by one representative
         * interior point. Board geometry is grid-aligned and loops line up with each other — a
         * cut-out's edge and the board edge share coordinates all the time — so any single test
         * point stands a real chance of landing exactly on this loop's boundary, where a crossing
         * test answers arbitrarily. A vote is unmoved by a few such vertices: a cut-out has
         * essentially all of its vertices inside the board, and a board has essentially none of
         * its vertices inside the cut-out.
         */
        boolean encloses(Loop other) {
            if (other.minX < minX - WELD_MM || other.maxX > maxX + WELD_MM
                    || other.minY < minY - WELD_MM || other.maxY > maxY + WELD_MM) {
                return false;
            }
            int n = other.x.length;
            int step = Math.max(1, n / NESTING_SAMPLES);
            int inside = 0, tested = 0;
            for (int i = 0; i < n; i += step) {
                tested++;
                if (containsPoint(other.x[i], other.y[i])) inside++;
            }
            return inside * 2 > tested;
        }

        private boolean containsPoint(double px, double py) {
            boolean in = false;
            for (int i = 0, j = x.length - 1; i < x.length; j = i++) {
                if ((y[i] > py) != (y[j] > py)
                        && px < (x[j] - x[i]) * (py - y[i]) / (y[j] - y[i]) + x[i]) {
                    in = !in;
                }
            }
            return in;
        }
    }

    /**
     * Flatten the outline path — less what the set drills — into closed polygons.
     *
     * <p>The path is SVG, so Batik parses it — it is already on the classpath for rasterizing,
     * and its arc handling is the same one the browser applies to the realistic view. The
     * winding rule the shape is built under decides nothing here (the nesting pass below
     * re-derives which loops are cut-outs), but it is set from the outline anyway so the shape
     * means what the renderer means by it.
     */
    private List<Loop> flatten(BoardOutline outline, Area holes) {
        Shape shape;
        try {
            shape = AWTPathProducer.createShape(new StringReader(outline.getSvgPath()),
                outline.isFromProfileLayer() ? PathIterator.WIND_EVEN_ODD : PathIterator.WIND_NON_ZERO);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read board outline path", e);
        }
        if (holes != null && !holes.isEmpty()) {
            // One boolean for the whole drill program: a hole inside the board opens a cut-out,
            // and one that straddles the routed edge notches the edge itself.
            Area board = new Area(shape);
            board.subtract(holes);
            shape = board;
        }

        List<List<double[]>> rings = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        double[] coords = new double[6];
        for (PathIterator it = shape.getPathIterator(null, FLATNESS_MM); !it.isDone(); it.next()) {
            switch (it.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO -> {
                    addRing(rings, current);
                    current = new ArrayList<>();
                    current.add(new double[]{coords[0], coords[1]});
                }
                case PathIterator.SEG_LINETO -> current.add(new double[]{coords[0], coords[1]});
                case PathIterator.SEG_CLOSE -> {
                    addRing(rings, current);
                    current = new ArrayList<>();
                }
                default -> { } // flattened: no curve segments reach here
            }
        }
        addRing(rings, current);

        List<Loop> loops = new ArrayList<>();
        for (List<double[]> ring : weldSeams(rings)) {
            Loop loop = new Loop(ring);
            if (Math.abs(loop.area) >= MIN_LOOP_AREA_MM2) loops.add(loop);
        }
        return loops;
    }

    /**
     * Re-join rings that are two halves of one region.
     *
     * <p>{@link Area} is free to return a single connected region as several subpaths meeting
     * along a shared seam, and on a real board it does: subtract a few hundred drilled holes and
     * the board comes back sliced into horizontal bands. Extruded as they stand, those bands are
     * separate solids that happen to touch — one board arriving in CAD as four bodies.
     *
     * <p>A seam is recognisable without any tolerance games: the two sides traverse exactly the
     * same points in opposite directions, because both came out of the same crossing computation.
     * So cancel every directed edge that has an exact opposite twin, and re-chain what is left —
     * the seam disappears and the bands become one ring. Nothing cancels on a genuine boundary
     * (two disjoint board pieces, a cut-out, the outline of a set with no holes at all), so this
     * is a no-op wherever there was no seam to begin with.
     */
    private static List<List<double[]>> weldSeams(List<List<double[]>> rings) {
        rings = splitAtSharedVertices(rings);
        Map<String, Integer> multiplicity = new HashMap<>();
        for (List<double[]> ring : rings) {
            for (int i = 0; i < ring.size(); i++) {
                multiplicity.merge(edgeKey(ring.get(i), ring.get((i + 1) % ring.size())), 1, Integer::sum);
            }
        }
        Map<String, Integer> cancellable = new HashMap<>();
        for (Map.Entry<String, Integer> e : multiplicity.entrySet()) {
            Integer opposite = multiplicity.get(reverseKey(e.getKey()));
            if (opposite != null) cancellable.put(e.getKey(), Math.min(e.getValue(), opposite));
        }
        if (cancellable.isEmpty()) return rings;

        // Survivors, in scan order, so the welded rings come out deterministically.
        Map<String, Deque<double[][]>> outgoing = new LinkedHashMap<>();
        List<double[][]> survivors = new ArrayList<>();
        for (List<double[]> ring : rings) {
            for (int i = 0; i < ring.size(); i++) {
                double[] from = ring.get(i);
                double[] to = ring.get((i + 1) % ring.size());
                String key = edgeKey(from, to);
                Integer left = cancellable.get(key);
                if (left != null && left > 0) {
                    cancellable.put(key, left - 1);
                    continue;
                }
                double[][] edge = {from, to};
                survivors.add(edge);
                outgoing.computeIfAbsent(pointKey(from), k -> new ArrayDeque<>()).add(edge);
            }
        }

        List<List<double[]>> welded = new ArrayList<>();
        Set<double[][]> used = Collections.newSetFromMap(new IdentityHashMap<>());
        for (double[][] start : survivors) {
            if (used.contains(start)) continue;
            List<double[]> ring = new ArrayList<>();
            double[][] edge = start;
            while (edge != null && !used.contains(edge)) {
                used.add(edge);
                ring.add(edge[0]);
                Deque<double[][]> next = outgoing.get(pointKey(edge[1]));
                edge = null;
                while (next != null && !next.isEmpty()) {
                    double[][] candidate = next.poll();
                    if (!used.contains(candidate)) { edge = candidate; break; }
                }
            }
            if (ring.size() >= 3) welded.add(ring);
        }
        return welded;
    }

    /**
     * Give every ring the same vertices along a shared seam, so the two sides of it cancel.
     *
     * <p>The halves of a split region traverse the seam over the same points — but not always
     * the same <em>number</em> of them: a hole that reaches the seam puts a vertex on one side
     * only, and the long edge facing it then has no twin to cancel against. So every vertex that
     * lies on another ring's edge is inserted into that edge, and every point is replaced by one
     * canonical instance of its coordinates, after which twin edges match exactly.
     *
     * <p>Candidate vertices come from a coarse grid, so this stays near-linear in the number of
     * points rather than quadratic.
     */
    private static List<List<double[]>> splitAtSharedVertices(List<List<double[]>> rings) {
        Map<String, double[]> canonical = new LinkedHashMap<>();
        for (List<double[]> ring : rings) {
            for (double[] p : ring) canonical.putIfAbsent(pointKey(p), p);
        }
        Map<Long, List<double[]>> grid = new HashMap<>();
        for (double[] p : canonical.values()) {
            grid.computeIfAbsent(cell(p[0], p[1]), k -> new ArrayList<>()).add(p);
        }

        List<List<double[]>> split = new ArrayList<>(rings.size());
        List<double[]> hits = new ArrayList<>();
        for (List<double[]> ring : rings) {
            List<double[]> out = new ArrayList<>(ring.size());
            for (int i = 0; i < ring.size(); i++) {
                double[] a = canonical.get(pointKey(ring.get(i)));
                double[] b = canonical.get(pointKey(ring.get((i + 1) % ring.size())));
                out.add(a);
                double dx = b[0] - a[0], dy = b[1] - a[1];
                double lengthSq = dx * dx + dy * dy;
                if (lengthSq <= WELD_MM * WELD_MM) continue;
                hits.clear();
                long minCx = cellIndex(Math.min(a[0], b[0])), maxCx = cellIndex(Math.max(a[0], b[0]));
                long minCy = cellIndex(Math.min(a[1], b[1])), maxCy = cellIndex(Math.max(a[1], b[1]));
                for (long cx = minCx; cx <= maxCx; cx++) {
                    for (long cy = minCy; cy <= maxCy; cy++) {
                        List<double[]> bucket = grid.get(cx * GRID_STRIDE + cy);
                        if (bucket == null) continue;
                        for (double[] p : bucket) {
                            if (p == a || p == b) continue;
                            double t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lengthSq;
                            if (t <= 0 || t >= 1) continue;
                            double px = a[0] + t * dx - p[0], py = a[1] + t * dy - p[1];
                            if (px * px + py * py > SEAM_EPS_MM * SEAM_EPS_MM) continue;
                            hits.add(p);
                        }
                    }
                }
                if (hits.isEmpty()) continue;
                double[] from = a;
                hits.sort((p, q) -> Double.compare(
                    (p[0] - from[0]) * dx + (p[1] - from[1]) * dy,
                    (q[0] - from[0]) * dx + (q[1] - from[1]) * dy));
                out.addAll(hits);
            }
            split.add(out);
        }
        return split;
    }

    private static long cellIndex(double v) {
        return (long) Math.floor(v / SEAM_GRID_MM);
    }

    private static long cell(double x, double y) {
        return cellIndex(x) * GRID_STRIDE + cellIndex(y);
    }

    private static String pointKey(double[] p) {
        return String.format(Locale.US, "%.6f,%.6f", p[0], p[1]);
    }

    private static String edgeKey(double[] from, double[] to) {
        return pointKey(from) + '>' + pointKey(to);
    }

    private static String reverseKey(String key) {
        int split = key.indexOf('>');
        return key.substring(split + 1) + '>' + key.substring(0, split);
    }

    /**
     * Weld coincident points and close the ring. A subpath left open by the chainer is closed
     * here — an outline that does not close cannot bound a solid, and the closing edge is the
     * one the EDA tool omitted.
     */
    private static void addRing(List<List<double[]>> rings, List<double[]> pts) {
        List<double[]> welded = new ArrayList<>(pts.size());
        for (double[] p : pts) {
            if (welded.isEmpty() || !same(welded.get(welded.size() - 1), p)) welded.add(p);
        }
        while (welded.size() > 1 && same(welded.get(0), welded.get(welded.size() - 1))) {
            welded.remove(welded.size() - 1);
        }
        if (welded.size() >= 3) rings.add(welded);
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < WELD_MM && Math.abs(a[1] - b[1]) < WELD_MM;
    }

    private static double signedArea(double[] x, double[] y) {
        double sum = 0;
        for (int i = 0, n = x.length; i < n; i++) {
            int j = (i + 1) % n;
            sum += x[i] * y[j] - x[j] * y[i];
        }
        return sum / 2.0;
    }

    /**
     * Sort the loops into solids: a loop enclosed by an even number of other loops bounds
     * material and becomes its own solid; an odd one is a cut-out in the innermost loop that
     * encloses it. That is the even-odd rule a profile layer's cut-outs are drawn under, and it
     * is equally right for a derived silhouette, whose loops never nest at all.
     */
    private static List<Loop> nest(List<Loop> loops) {
        int n = loops.size();
        boolean[][] encloses = new boolean[n][n]; // encloses[i][j]: loop i contains loop j
        int[] depth = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j || !loops.get(i).encloses(loops.get(j))) continue;
                encloses[i][j] = true;
                depth[j]++;
            }
        }

        List<Loop> solids = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            Loop loop = loops.get(j);
            if (depth[j] % 2 == 0) {
                loop.windCounterClockwise();
                solids.add(loop);
                continue;
            }
            // The cut-out belongs to the innermost loop enclosing it — the deepest of its
            // enclosers, which is the piece of material it is actually a hole in.
            int parent = -1;
            for (int i = 0; i < n; i++) {
                if (encloses[i][j] && (parent < 0 || depth[i] > depth[parent])) parent = i;
            }
            loop.windClockwise();
            loops.get(parent).holes.add(loop);
        }
        return solids;
    }

    // --- STEP emission ----------------------------------------------------------------

    private String write(List<Loop> solids) {
        StepFile f = new StepFile();

        // Product structure: one part, one shape.
        int appContext = f.emit("APPLICATION_CONTEXT('automotive design')");
        f.emit("APPLICATION_PROTOCOL_DEFINITION('international standard','automotive_design',2000,#"
            + appContext + ")");
        int productContext = f.emit("PRODUCT_CONTEXT('',#" + appContext + ",'mechanical')");
        int product = f.emit("PRODUCT(" + StepFile.str(productName) + "," + StepFile.str(productName)
            + ",'',(#" + productContext + "))");
        int formation = f.emit("PRODUCT_DEFINITION_FORMATION('','',#" + product + ")");
        int definitionContext = f.emit("PRODUCT_DEFINITION_CONTEXT('part definition',#"
            + appContext + ",'design')");
        int definition = f.emit("PRODUCT_DEFINITION('design','',#" + formation + ",#"
            + definitionContext + ")");
        int shape = f.emit("PRODUCT_DEFINITION_SHAPE('','',#" + definition + ")");
        f.emit("PRODUCT_RELATED_PRODUCT_CATEGORY('part','',(#" + product + "))");

        // Units: millimetres, radians, and the tolerance a consumer should heal geometry within.
        int lengthUnit = f.emit("(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.))");
        int angleUnit = f.emit("(NAMED_UNIT(*)PLANE_ANGLE_UNIT()SI_UNIT($,.RADIAN.))");
        int solidAngleUnit = f.emit("(NAMED_UNIT(*)SI_UNIT($,.STERADIAN.)SOLID_ANGLE_UNIT())");
        int uncertainty = f.emit("UNCERTAINTY_MEASURE_WITH_UNIT(LENGTH_MEASURE(1.E-05),#"
            + lengthUnit + ",'distance_accuracy_value','confusion accuracy')");
        int context = f.emit("(GEOMETRIC_REPRESENTATION_CONTEXT(3)"
            + "GLOBAL_UNCERTAINTY_ASSIGNED_CONTEXT((#" + uncertainty + "))"
            + "GLOBAL_UNIT_ASSIGNED_CONTEXT((#" + lengthUnit + ",#" + angleUnit + ",#"
            + solidAngleUnit + "))REPRESENTATION_CONTEXT('','3D'))");

        int origin = f.share("CARTESIAN_POINT('',(0.,0.,0.))");
        int axisZ = f.share("DIRECTION('',(0.,0.,1.))");
        int axisX = f.share("DIRECTION('',(1.,0.,0.))");
        int placement = f.share("AXIS2_PLACEMENT_3D('',#" + origin + ",#" + axisZ + ",#" + axisX + ")");

        StringBuilder solidRefs = new StringBuilder("#").append(placement);
        for (Loop solid : solids) {
            solidRefs.append(",#").append(writeSolid(f, solid, axisZ, axisX));
        }

        int representation = f.emit("ADVANCED_BREP_SHAPE_REPRESENTATION(" + StepFile.str(productName)
            + ",(" + solidRefs + "),#" + context + ")");
        f.emit("SHAPE_DEFINITION_REPRESENTATION(#" + shape + ",#" + representation + ")");

        if (labelSides) {
            writeSideLabels(f, solids, context, representation);
        }

        return header(solids.size()) + f.body() + "ENDSEC;\nEND-ISO-10303-21;\n";
    }

    private String header(int solidCount) {
        String stamp = TIMESTAMP.format(timestamp != null ? timestamp : Instant.now());
        String description = String.format(Locale.US,
            "PCB outline extruded to %s mm (%d solid%s)",
            StepFile.num(thicknessMm), solidCount, solidCount == 1 ? "" : "s");
        return "ISO-10303-21;\n"
            + "HEADER;\n"
            + "FILE_DESCRIPTION((" + StepFile.str(description) + "),'2;1');\n"
            + "FILE_NAME(" + StepFile.str(productName + ".step") + "," + StepFile.str(stamp)
            + ",(''),(''),'Delta Gerber','Delta Gerber','');\n"
            + "FILE_SCHEMA(('AUTOMOTIVE_DESIGN { 1 0 10303 214 3 1 1 }'));\n"
            + "ENDSEC;\n"
            + "DATA;\n";
    }

    /**
     * Write {@code TOP} and {@code BOTTOM} across the board's two faces.
     *
     * <p>Annotation, not geometry: the strokes go into their own
     * {@code GEOMETRICALLY_BOUNDED_WIREFRAME_SHAPE_REPRESENTATION}, tied to the solid by a
     * {@code SHAPE_REPRESENTATION_RELATIONSHIP}. A CAD system that shows free curves draws the
     * words on the faces; one that does not simply shows the board, and either way the solid is
     * exactly what it would have been without them — no faces added, no material moved.
     *
     * <p>The words are centred on the board's extent and sized to it, and the underside's is
     * mirrored so it reads the right way round when the board is turned over.
     */
    private void writeSideLabels(StepFile f, List<Loop> solids, int context, int solidRepresentation) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Loop solid : solids) {
            minX = Math.min(minX, solid.minX);
            maxX = Math.max(maxX, solid.maxX);
            minY = Math.min(minY, solid.minY);
            maxY = Math.max(maxY, solid.maxY);
        }
        double width = maxX - minX, height = maxY - minY;
        double capHeight = Math.min(Math.max(Math.min(width, height) * LABEL_HEIGHT_FRACTION,
            LABEL_MIN_HEIGHT_MM), LABEL_MAX_HEIGHT_MM);
        // Never wider than the board it labels.
        double widest = Math.max(StrokeFont.width(TOP_LABEL, capHeight), StrokeFont.width(BOTTOM_LABEL, capHeight));
        if (widest > width * LABEL_MAX_WIDTH_FRACTION) {
            capHeight *= width * LABEL_MAX_WIDTH_FRACTION / widest;
        }
        double centreY = (minY + maxY) / 2 - capHeight / 2;

        List<Integer> curves = new ArrayList<>();
        addLabel(f, curves, TOP_LABEL, minX + (width - StrokeFont.width(TOP_LABEL, capHeight)) / 2,
            centreY, capHeight, thicknessMm, false);
        addLabel(f, curves, BOTTOM_LABEL, minX + (width - StrokeFont.width(BOTTOM_LABEL, capHeight)) / 2,
            centreY, capHeight, 0, true);
        if (curves.isEmpty()) return;

        int curveSet = f.emit("GEOMETRIC_CURVE_SET('side labels',(" + refs(curves) + "))");
        int wireframe = f.emit("GEOMETRICALLY_BOUNDED_WIREFRAME_SHAPE_REPRESENTATION('side labels',(#"
            + curveSet + "),#" + context + ")");
        f.emit("SHAPE_REPRESENTATION_RELATIONSHIP('side labels','',#" + solidRepresentation
            + ",#" + wireframe + ")");
    }

    private void addLabel(StepFile f, List<Integer> curves, String text,
                          double x, double y, double capHeight, double z, boolean mirrored) {
        for (double[][] stroke : StrokeFont.strokes(text, x, y, capHeight, mirrored)) {
            List<Integer> points = new ArrayList<>(stroke.length);
            for (double[] p : stroke) {
                points.add(f.share("CARTESIAN_POINT('',(" + StepFile.num(p[0]) + ","
                    + StepFile.num(p[1]) + "," + StepFile.num(z) + "))"));
            }
            curves.add(f.emit("POLYLINE('',(" + refs(points) + "))"));
        }
    }

    /**
     * One board piece as a closed shell: a bottom face at z=0, a top face at the board
     * thickness, and a wall for every edge of the outer loop and of each cut-out.
     *
     * <p>The bottom and top faces share their bounds' edges with the walls, which is what makes
     * the shell closed: every edge is used exactly twice, once in each direction. Both faces sit
     * on a plane whose normal is +Z; the bottom face declares {@code same_sense = .F.} so its
     * own normal points out of the solid, and its bounds are therefore traversed in reverse.
     */
    private int writeSolid(StepFile f, Loop outer, int axisZ, int axisX) {
        List<Integer> faces = new ArrayList<>();
        List<Integer> bottomBounds = new ArrayList<>();
        List<Integer> topBounds = new ArrayList<>();

        List<Loop> all = new ArrayList<>();
        all.add(outer);
        all.addAll(outer.holes);
        for (Loop loop : all) {
            boolean isOuter = loop == outer;
            LoopEdges edges = writeLoopEdges(f, loop, axisZ, faces);
            bottomBounds.add(f.emit(bound(isOuter) + "('',#" + edges.bottomLoop + ",.T.)"));
            topBounds.add(f.emit(bound(isOuter) + "('',#" + edges.topLoop + ",.T.)"));
        }

        int bottomOrigin = f.share("CARTESIAN_POINT('',(0.,0.,0.))");
        int topOrigin = f.share("CARTESIAN_POINT('',(0.,0.," + StepFile.num(thicknessMm) + "))");
        int bottomPlane = f.share("PLANE('',#" + f.share("AXIS2_PLACEMENT_3D('',#" + bottomOrigin
            + ",#" + axisZ + ",#" + axisX + ")") + ")");
        int topPlane = f.share("PLANE('',#" + f.share("AXIS2_PLACEMENT_3D('',#" + topOrigin
            + ",#" + axisZ + ",#" + axisX + ")") + ")");
        faces.add(f.emit("ADVANCED_FACE('bottom',(" + refs(bottomBounds) + "),#" + bottomPlane + ",.F.)"));
        faces.add(f.emit("ADVANCED_FACE('top',(" + refs(topBounds) + "),#" + topPlane + ",.T.)"));

        int shell = f.emit("CLOSED_SHELL('',(" + refs(faces) + "))");
        return f.emit("MANIFOLD_SOLID_BREP(" + StepFile.str(productName) + ",#" + shell + ")");
    }

    private static String bound(boolean outer) {
        return outer ? "FACE_OUTER_BOUND" : "FACE_BOUND";
    }

    /** The two horizontal edge loops of one polygon, the walls between them already emitted. */
    private record LoopEdges(int bottomLoop, int topLoop) { }

    /**
     * Emit one polygon's vertices, its bottom, top and vertical edges, and the wall face over
     * every edge; return the two horizontal edge loops for the caller's end faces.
     *
     * <p>Winding does all the orientation work. The loop arrives wound counter-clockwise seen
     * from +Z if it is material and clockwise if it is a cut-out, so for edge {@code i} the
     * outward normal — out of the board, or into the cut-out — is the edge direction rotated
     * a quarter turn clockwise in both cases, and one wall construction serves both.
     */
    private LoopEdges writeLoopEdges(StepFile f, Loop loop, int axisZ, List<Integer> faces) {
        int n = loop.size();
        int[] bottomVertex = new int[n];
        int[] topVertex = new int[n];
        int[] bottomPoint = new int[n];
        int[] topPoint = new int[n];
        for (int i = 0; i < n; i++) {
            String xy = "(" + StepFile.num(loop.x[i]) + "," + StepFile.num(loop.y[i]) + ",";
            bottomPoint[i] = f.share("CARTESIAN_POINT(''," + xy + "0.))");
            topPoint[i] = f.share("CARTESIAN_POINT(''," + xy + StepFile.num(thicknessMm) + "))");
            bottomVertex[i] = f.share("VERTEX_POINT('',#" + bottomPoint[i] + ")");
            topVertex[i] = f.share("VERTEX_POINT('',#" + topPoint[i] + ")");
        }

        int upVector = f.share("VECTOR('',#" + axisZ + ",1.)");
        int[] vertical = new int[n];
        for (int i = 0; i < n; i++) {
            int line = f.share("LINE('',#" + bottomPoint[i] + ",#" + upVector + ")");
            vertical[i] = f.emit("EDGE_CURVE('',#" + bottomVertex[i] + ",#" + topVertex[i]
                + ",#" + line + ",.T.)");
        }

        int[] bottomEdge = new int[n];
        int[] topEdge = new int[n];
        int[] wallPlane = new int[n];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double dx = loop.x[j] - loop.x[i];
            double dy = loop.y[j] - loop.y[i];
            double len = Math.hypot(dx, dy);
            dx /= len;
            dy /= len;
            int direction = f.share("DIRECTION('',(" + StepFile.num(dx) + "," + StepFile.num(dy) + ",0.))");
            int vector = f.share("VECTOR('',#" + direction + ",1.)");
            bottomEdge[i] = f.emit("EDGE_CURVE('',#" + bottomVertex[i] + ",#" + bottomVertex[j] + ",#"
                + f.share("LINE('',#" + bottomPoint[i] + ",#" + vector + ")") + ",.T.)");
            topEdge[i] = f.emit("EDGE_CURVE('',#" + topVertex[i] + ",#" + topVertex[j] + ",#"
                + f.share("LINE('',#" + topPoint[i] + ",#" + vector + ")") + ",.T.)");
            // Outward normal: the edge direction turned a quarter turn clockwise.
            int normal = f.share("DIRECTION('',(" + StepFile.num(dy) + "," + StepFile.num(-dx) + ",0.))");
            wallPlane[i] = f.share("PLANE('',#" + f.share("AXIS2_PLACEMENT_3D('',#" + bottomPoint[i]
                + ",#" + normal + ",#" + direction + ")") + ")");
        }

        // One wall per edge, wound counter-clockwise about its outward normal:
        // along the bottom, up, back along the top, down.
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            int wallLoop = f.emit("EDGE_LOOP('',("
                + "#" + f.emit("ORIENTED_EDGE('',*,*,#" + bottomEdge[i] + ",.T.)")
                + ",#" + f.emit("ORIENTED_EDGE('',*,*,#" + vertical[j] + ",.T.)")
                + ",#" + f.emit("ORIENTED_EDGE('',*,*,#" + topEdge[i] + ",.F.)")
                + ",#" + f.emit("ORIENTED_EDGE('',*,*,#" + vertical[i] + ",.F.)")
                + "))");
            faces.add(f.emit("ADVANCED_FACE('',(#"
                + f.emit("FACE_OUTER_BOUND('',#" + wallLoop + ",.T.)")
                + "),#" + wallPlane[i] + ",.T.)"));
        }

        // The top face looks along +Z, so it takes the loop as wound; the bottom face looks
        // along -Z and takes it reversed, which is the same traversal seen from below.
        StringBuilder top = new StringBuilder();
        StringBuilder bottom = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) top.append(',');
            top.append('#').append(f.emit("ORIENTED_EDGE('',*,*,#" + topEdge[i] + ",.T.)"));
        }
        for (int i = n - 1; i >= 0; i--) {
            if (i < n - 1) bottom.append(',');
            bottom.append('#').append(f.emit("ORIENTED_EDGE('',*,*,#" + bottomEdge[i] + ",.F.)"));
        }
        return new LoopEdges(f.emit("EDGE_LOOP('',(" + bottom + "))"),
                             f.emit("EDGE_LOOP('',(" + top + "))"));
    }

    private static String refs(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('#').append(ids.get(i));
        }
        return sb.toString();
    }
}
