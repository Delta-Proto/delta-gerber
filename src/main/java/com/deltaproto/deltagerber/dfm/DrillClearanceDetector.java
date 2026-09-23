package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import com.deltaproto.deltagerber.dfm.geometry.Capsule;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry.Feature;
import com.deltaproto.deltagerber.dfm.geometry.CopperNets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Drill-to-copper clearance: how close each hole's wall comes to copper it must not touch on one
 * copper layer.
 *
 * <p>The hole's own copper is whatever survives at its centre — its pad, or a plane it connects to
 * — and everything of that net is its own. Anything else is foreign: a track of another net passing
 * a plated hole ("hole to trace"), the edge of the antipad a via passes through in a plane, any
 * copper near a non-plated hole. The hole is a disc, the distance one {@link Capsule#distance}, and
 * clears are handled as in {@link ClearanceDetector}: a gap to a dark edge a later clear erased is
 * dropped, and a gap to a clear's edge counts when foreign copper survives just across it. So a
 * reported gap is a real one.
 *
 * <p>Floating copper can be left out, as the clearance check leaves it out.
 */
@Beta("validated against HQDFM on one board")
public final class DrillClearanceDetector {

    /** Gaps wider than this (mm) are not measured. */
    public static final double DEFAULT_CUTOFF_MM = 1.0;

    private DrillClearanceDetector() {}

    /**
     * @param holes    in this layer's Gerber frame
     * @param floating measured on these same nets, whose pieces are left out; or null
     */
    public static DrillClearanceResult detect(CopperNets nets, String fileName, List<DrilledHole> holes,
                                              double cutoffMm, FloatingCopperResult floating) {
        CopperGeometry g = nets.geometry();
        if (floating != null && !floating.isOf(g)) {
            throw new IllegalArgumentException("floating copper was measured on a different geometry");
        }
        List<DrillClearance> out = new ArrayList<>();
        for (DrilledHole hole : holes) {
            Capsule disc = Capsule.disc(hole.xMm(), hole.yMm(), hole.diameterMm() / 2);
            int own = g.copperAt(hole.xMm(), hole.yMm());
            int ownNet = own < 0 ? -1 : nets.netOf(own);
            DrillClearance[] best = {null};
            g.forEachEntryNear(hole.xMm(), hole.yMm(), hole.diameterMm() / 2 + cutoffMm, e -> {
                Feature f = g.feature(g.entryFeature(e));
                Capsule ce = g.entryCapsule(e);
                double d = disc.distance(ce);
                if (d >= cutoffMm || best[0] != null && d >= best[0].distanceMm()) {
                    return;
                }
                double[] p = disc.closestSurfacePoints(ce);
                Feature copper = f;
                if (f.clear) {
                    int behind = g.copperAt(p[2], p[3]);
                    if (behind < 0 || behind > f.index) {
                        return;
                    }
                    copper = g.feature(behind);
                } else if (g.erasedAt(f, p[2], p[3])) {
                    return;
                }
                int net = nets.netOf(copper.index);
                if (net == ownNet || ownNet >= 0 && nets.sameName(net, ownNet)
                        || floating != null && floating.isFloatingFeature(copper.index)) {
                    return;
                }
                best[0] = new DrillClearance(d, (p[0] + p[2]) / 2, (p[1] + p[3]) / 2, hole, ownNet >= 0,
                        copper.describe());
            });
            if (best[0] != null) {
                out.add(best[0]);
            }
        }
        out.sort(Comparator.comparingDouble(DrillClearance::distanceMm));
        return new DrillClearanceResult(fileName, cutoffMm, out);
    }
}
