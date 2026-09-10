package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import com.deltaproto.deltagerber.dfm.geometry.Capsule;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry.Feature;
import com.deltaproto.deltagerber.dfm.geometry.CopperNets;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimum spacing between copper of different nets on one layer — issue #9.
 *
 * <p>The gap between two nets is the smallest distance between any object of one and any object of
 * the other, so no union is ever built: every boundary entry of the layer's {@link CopperGeometry}
 * is compared with the entries within the cutoff whose net differs, and the distance between two
 * entries is one {@link Capsule#distance} — exact for round apertures, exact to the half-micrometre
 * flattening for arcs. Which net an object belongs to is {@link CopperNets}' answer, and two pieces
 * the file calls by one name — a via and the pad it joins on another layer — are not measured
 * against each other.
 *
 * <p>Clears are where the copper's real edge is not an object's edge: a pad sitting in an antipad
 * in a plane is nearest the plane along the antipad's boundary. A clear's edges are in the grid
 * too, and a gap measured to one is credited to whatever copper survives just there
 * ({@link CopperGeometry#copperAt}); a gap measured to a dark edge that a later clear has erased is
 * discarded. Both rules can only make the check miss a candidate, never invent one, so a reported
 * gap is a real gap.
 */
@Beta("not yet validated against an external DFM tool")
public final class ClearanceDetector {

    /** Gaps wider than this (mm) are not measured. */
    public static final double DEFAULT_CUTOFF_MM = 1.0;

    private ClearanceDetector() {}

    public static ClearanceResult detect(GerberDocument document) {
        return detect(CopperGeometry.of(document), document.getFileName(), DEFAULT_CUTOFF_MM);
    }

    public static ClearanceResult detect(CopperGeometry geometry, String fileName, double cutoffMm) {
        return detect(CopperNets.of(geometry), fileName, cutoffMm);
    }

    public static ClearanceResult detect(CopperNets nets, String fileName, double cutoffMm) {
        CopperGeometry g = nets.geometry();
        Map<Long, Clearance> best = new HashMap<>();
        int entries = g.entryCount();
        for (int e = 0; e < entries; e++) {
            Feature fe = g.feature(g.entryFeature(e));
            if (fe.clear) {
                continue;
            }
            int netE = nets.netOf(fe.index);
            Capsule ce = g.entryCapsule(e);
            final int entry = e;
            g.forEachEntryAlong(ce, cutoffMm, f -> {
                Feature ff = g.feature(g.entryFeature(f));
                Capsule cf = g.entryCapsule(f);
                if (ff.clear) {
                    measureToClear(nets, fe, netE, ce, ff, cf, cutoffMm, best);
                    return;
                }
                if (f <= entry || ff.index == fe.index) {
                    return;
                }
                int netF = nets.netOf(ff.index);
                if (netF == netE || nets.sameName(netE, netF)) {
                    return;
                }
                double d = ce.distance(cf);
                if (d >= cutoffMm || !improves(best, netE, netF, d)) {
                    return;
                }
                double[] p = ce.closestSurfacePoints(cf);
                if (g.erasedAt(fe, p[0], p[1]) || g.erasedAt(ff, p[2], p[3])) {
                    return;
                }
                record(best, nets, netE, netF, d, p, fe, ff);
            });
        }
        List<Clearance> list = new ArrayList<>(best.values());
        list.sort(Comparator.comparingDouble(Clearance::distanceMm));
        List<String> warnings = new ArrayList<>(g.warnings());
        warnings.addAll(nets.warnings());
        return new ClearanceResult(fileName, cutoffMm, nets.netCount(), list, warnings);
    }

    /**
     * A gap from dark entry {@code ce} to the edge of clear {@code clear}: it counts when the copper
     * just across that edge is another net's.
     */
    private static void measureToClear(CopperNets nets, Feature fe, int netE, Capsule ce,
                                       Feature clear, Capsule edge, double cutoffMm, Map<Long, Clearance> best) {
        CopperGeometry g = nets.geometry();
        double d = ce.distance(edge);
        if (d >= cutoffMm) {
            return;
        }
        double[] p = ce.closestSurfacePoints(edge);
        int behind = g.copperAt(p[2], p[3]);
        if (behind < 0 || behind > clear.index) {
            return;     // laminate, or copper drawn after the clear — its own edges are measured directly
        }
        Feature fb = g.feature(behind);
        int netB = nets.netOf(behind);
        if (netB == netE || nets.sameName(netE, netB) || !improves(best, netE, netB, d)) {
            return;
        }
        if (g.erasedAt(fe, p[0], p[1])) {
            return;
        }
        record(best, nets, netE, netB, d, p, fe, fb);
    }

    private static boolean improves(Map<Long, Clearance> best, int netA, int netB, double d) {
        Clearance current = best.get(key(netA, netB));
        return current == null || d < current.distanceMm();
    }

    private static void record(Map<Long, Clearance> best, CopperNets nets, int netA, int netB, double d,
                               double[] p, Feature fa, Feature fb) {
        best.put(key(netA, netB), new Clearance(d, (p[0] + p[2]) / 2, (p[1] + p[3]) / 2,
                nets.nameOf(netA), nets.nameOf(netB), fa.describe(), fb.describe()));
    }

    private static long key(int a, int b) {
        return a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
    }
}
