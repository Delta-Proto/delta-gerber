package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import com.deltaproto.deltagerber.dfm.ConductorWidth.Kind;
import com.deltaproto.deltagerber.dfm.geometry.Capsule;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry.Feature;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimum conductor width from the copper's actual outline — issue #11 — beside, not instead of,
 * the aperture-table figure {@code PcbAnalyzer.minTrackWidthUm} gives a quote form.
 *
 * <p>The copper of a layer is a union of objects, and a union never narrows what it is made of.
 * So the width of the union has two sources, and each is measured on its own:
 * <ul>
 *   <li><b>Strokes</b>: a draw or arc is as wide as its aperture, whatever its shape — a
 *       rectangular aperture counts here where the quote figure only reads circles.
 *   <li><b>Region necks</b>: a pour's own outline can come back on itself — a plane split by a slot,
 *       a web between two antipads. For each boundary edge, the nearest facing edge of the same
 *       region (or of a clear cut into it) whose connector runs through copper is a neck.
 *       "Facing" is within 60° of antiparallel, and the connector must leave and arrive through
 *       copper at 30° or steeper, which is what keeps a rectangle's corner from reading as zero.
 *       Two edges joined by less boundary than the gap between them meet at a corner, however
 *       sharp, and a corner is not a neck; nor is a wedge, which reads a different width at each
 *       end of the edge where a neck reads one — so a conductor tapering by more than a quarter
 *       over one edge is not measured either; and the copper must carry on for at least the
 *       width to either side, or the place is a tip.
 * </ul>
 * A neck's two ends must be edges of the <em>union</em>, not just of the region: just outside each
 * there must be bare laminate. Altium's teardrops are regions whose tail lies along the pad they
 * join, a few micrometres wide, and without that rule every one of them reads as a hairline. Within
 * the pad's copper the tail is not a conductor of any width.
 *
 * <p>A pad's own size is not measured: a 0.3 mm square pad is 0.3 mm of copper, but not a
 * conductor. Nor is the sliver where two polygons overlap only slightly — a pad's outline drawn
 * again as a region crosses the pad hundreds of times, and no chord between crossings tells a
 * sliver from a shared edge.
 *
 * <p>Edges are sampled at both ends and the middle, so a neck between a long edge and a vertex is
 * found from the vertex's side to within a twentieth of that edge's length.
 */
@Beta("not yet validated against an external DFM tool")
public final class ConductorWidthDetector {

    /** Necks wider than this (mm) are not looked for. */
    public static final double DEFAULT_CUTOFF_MM = 1.0;

    /** Two edges face each other when their copper normals are at least this far apart (cos 120°). */
    private static final double FACING_COS = -0.5;

    /** The connector must leave/arrive through copper at 30° or steeper (sin 30°). */
    private static final double CONNECTOR_SIN = 0.5;

    /** Two coincident edges — a region's cut-in line — are a slit, not a neck. */
    private static final double SLIT_MM = 1e-6;

    /**
     * Edges shorter than this (mm) are not necks' sides. KiCad writes sub-micrometre zigzags where
     * a zone outline meets an arc, and a "neck" between two of those is a 3 µm notch in the outline,
     * not copper anyone will etch. Four times the flattening sagitta, so no real chord is this short.
     */
    private static final double MIN_EDGE_MM = 0.002;

    /** How far outside a neck's end to look for laminate — beyond the edge epsilon, below any feature. */
    private static final double OUTSIDE_MM = 1e-4;

    private static final double[] SAMPLES = {0.05, 0.5, 0.95};

    /**
     * A neck's width may vary along one edge by at most this factor; more is a wedge. Two facing
     * chords of flattened arcs read within a couple of percent of each other; a pour's pointed
     * corner nearly doubles over one chord near its tip.
     */
    private static final double WEDGE_RATIO = 1.25;

    private ConductorWidthDetector() {}

    public static ConductorWidthResult detect(GerberDocument document) {
        return detect(CopperGeometry.of(document), document.getFileName(), DEFAULT_CUTOFF_MM);
    }

    public static ConductorWidthResult detect(CopperGeometry g, String fileName, double cutoffMm) {
        List<ConductorWidth> out = new ArrayList<>();
        strokes(g, out);
        for (Feature f : g.features()) {
            if (!f.clear && f.kind == CopperGeometry.Kind.REGION && f.polygon != null) {
                ConductorWidth neck = regionNeck(g, f, cutoffMm);
                if (neck != null) {
                    out.add(neck);
                }
            }
        }
        out.sort(Comparator.comparingDouble(ConductorWidth::widthMm));
        return new ConductorWidthResult(fileName, cutoffMm, out, g.warnings());
    }

    /** One entry per distinct stroke width, at the first stroke drawn with it. */
    private static void strokes(CopperGeometry g, List<ConductorWidth> out) {
        Map<Double, ConductorWidth> byWidth = new LinkedHashMap<>();
        for (Feature f : g.features()) {
            if (f.clear || f.kind != CopperGeometry.Kind.STROKE || !(f.strokeWidthMm > 0)) {
                continue;
            }
            double key = Math.round(f.strokeWidthMm * 1e6) / 1e6;
            byWidth.computeIfAbsent(key, w -> new ConductorWidth(f.strokeWidthMm,
                    f.bounds.getCenterX(), f.bounds.getCenterY(), Kind.STROKE, f.describe()));
        }
        out.addAll(byWidth.values());
    }

    /**
     * Whether there is copper a quarter of {@code d} inside the boundary at both points {@code d}
     * along the edge's line from {@code (px, py)}. A quarter, because the boundary of a flattened
     * arc drifts from its chord's line by a few micrometres over that distance and a hundredth
     * would land in the air beside a hole's edge.
     */
    private static boolean continues(CopperGeometry g, double px, double py, double ux, double uy,
                                     double nx, double ny, double d) {
        double in = d / 4;
        return g.copperAt(px + ux * d + nx * in, py + uy * d + ny * in) >= 0
                && g.copperAt(px - ux * d + nx * in, py - uy * d + ny * in) >= 0;
    }

    /** The narrowest neck of one region, clears cut into it included; null when none under the cutoff. */
    static ConductorWidth regionNeck(CopperGeometry g, Feature region, double cutoffMm) {
        Set<Integer> clears = new HashSet<>();
        for (int c : region.laterClears()) {
            clears.add(c);
        }
        double[] best = {cutoffMm, 0, 0};
        // Own edges, copper to the left.
        for (int r = 0; r < region.polygon.ringCount(); r++) {
            for (int k = 0; k < region.polygon.ringSize(r); k++) {
                neckFrom(g, region, clears, region.polygon.edge(r, k), region.index, r, k, false, best);
            }
        }
        // Edges of the clears cut into it, copper to the right.
        for (int ci : clears) {
            Feature c = g.feature(ci);
            for (int r = 0; r < c.polygon.ringCount(); r++) {
                for (int k = 0; k < c.polygon.ringSize(r); k++) {
                    neckFrom(g, region, clears, c.polygon.edge(r, k), ci, r, k, true, best);
                }
            }
        }
        if (best[0] >= cutoffMm) {
            return null;
        }
        return new ConductorWidth(best[0], best[1], best[2], Kind.REGION_NECK, region.describe());
    }

    private static void neckFrom(CopperGeometry g, Feature region, Set<Integer> clears, Capsule e,
                                 int eFeature, int eRing, int eIndex, boolean eIsClear, double[] best) {
        double len = e.length();
        if (len < MIN_EDGE_MM) {
            return;
        }
        double ux = (e.x2 - e.x1) / len, uy = (e.y2 - e.y1) / len;
        // Copper-side normal: left of a region edge, right of a clear's edge.
        double nex = eIsClear ? uy : -uy, ney = eIsClear ? -ux : ux;
        Set<Integer> tried = new HashSet<>();
        for (double t : SAMPLES) {
            double px = e.x1 + ux * len * t, py = e.y1 + uy * len * t;
            g.forEachEntryNear(px, py, best[0], f -> {
                if (!tried.add(f)) {
                    return;
                }
                int fFeature = g.entryFeature(f);
                boolean fIsClear;
                if (fFeature == region.index) {
                    fIsClear = false;
                } else if (clears.contains(fFeature)) {
                    fIsClear = true;
                } else {
                    return;
                }
                Capsule cf = g.entryCapsule(f);
                boolean sameRing = fFeature == eFeature && g.entryRing(f) == eRing;
                if (sameRing) {
                    int n = g.feature(fFeature).polygon.ringSize(eRing);
                    int diff = Math.abs(g.entryEdge(f) - eIndex);
                    if (diff <= 1 || diff >= n - 1) {
                        return;     // the edge itself or its neighbours: a corner, not a neck
                    }
                }
                double flen = cf.length();
                if (flen < MIN_EDGE_MM) {
                    return;
                }
                double fux = (cf.x2 - cf.x1) / flen, fuy = (cf.y2 - cf.y1) / flen;
                double nfx = fIsClear ? fuy : -fuy, nfy = fIsClear ? -fux : fux;
                if (nex * nfx + ney * nfy > FACING_COS) {
                    return;
                }
                // The width is taken at every sample along e. A neck reads the same all along; a
                // wedge tapering to a point reads wide at one end and narrow at the other, and a
                // pour's pointed corner is not a conductor of the width at its tip.
                double[][] valid = new double[SAMPLES.length][];
                int count = 0;
                double narrowest = Double.MAX_VALUE, widest = 0;
                for (double ts : SAMPLES) {
                    double sx = e.x1 + ux * len * ts, sy = e.y1 + uy * len * ts;
                    double[] q = cf.nearestOnAxis(sx, sy);
                    double dx = q[0] - sx, dy = q[1] - sy;
                    double d = Math.hypot(dx, dy);
                    if (d <= SLIT_MM) {
                        return;
                    }
                    dx /= d;
                    dy /= d;
                    if (dx * nex + dy * ney < CONNECTOR_SIN || dx * nfx + dy * nfy > -CONNECTOR_SIN) {
                        continue;
                    }
                    double cx = (sx + q[0]) / 2, cy = (sy + q[1]) / 2;
                    if (!region.polygon.contains(cx, cy) || g.erasedAt(region, cx, cy)) {
                        continue;
                    }
                    // Both ends must be edges of the union: bare laminate just outside each.
                    if (g.copperAt(sx - nex * OUTSIDE_MM, sy - ney * OUTSIDE_MM) >= 0
                            || g.copperAt(q[0] - nfx * OUTSIDE_MM, q[1] - nfy * OUTSIDE_MM) >= 0) {
                        continue;
                    }
                    valid[count++] = new double[]{d, cx, cy, sx, sy, q[0], q[1]};
                    narrowest = Math.min(narrowest, d);
                    widest = Math.max(widest, d);
                }
                if (count < 2 || widest > WEDGE_RATIO * narrowest || narrowest >= best[0]) {
                    return;
                }
                // Two edges joined by less boundary than the gap between them meet at a corner —
                // an apex rounded off into a few micro-edges is still an apex, not a neck.
                if (sameRing && g.feature(fFeature).polygon.pathBetween(eRing, eIndex, g.entryEdge(f)) < narrowest) {
                    return;
                }
                // The copper must carry on along both edges for at least the width at the place
                // reported: a neck is a narrow place in a conductor, and a conductor that ends
                // within its own width of the place is a tip. Widths agree to a quarter here, so
                // the narrowest sample that is not a tip is the answer.
                java.util.Arrays.sort(valid, 0, count, java.util.Comparator.comparingDouble(v -> v[0]));
                for (int i = 0; i < count; i++) {
                    double[] v = valid[i];
                    if (v[0] < best[0] && continues(g, v[3], v[4], ux, uy, nex, ney, v[0])
                            && continues(g, v[5], v[6], fux, fuy, nfx, nfy, v[0])) {
                        best[0] = v[0];
                        best[1] = v[1];
                        best[2] = v[2];
                        return;
                    }
                }
            });
        }
    }
}
