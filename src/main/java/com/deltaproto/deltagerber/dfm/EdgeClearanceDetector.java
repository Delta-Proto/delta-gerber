package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import com.deltaproto.deltagerber.dfm.geometry.BoardProfile;
import com.deltaproto.deltagerber.dfm.geometry.Capsule;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry.Feature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Copper-to-board-edge clearance: how close each copper layer's copper comes to the router's path.
 *
 * <p>The same machinery as {@link ClearanceDetector}, with the board's {@link BoardProfile} in
 * place of the second net. Each profile segment is walked through the layer's boundary grid, and the
 * distance to an entry is one {@link Capsule#distance}. Clears are handled the same way too: a
 * pour drawn to the edge and cut back by a later clear border is measured to the border's inner
 * edge, where its copper really ends, and a gap measured to a dark edge that a later clear erased is
 * dropped. So a reported gap is a real one.
 *
 * <p>Copper lying wholly outside the profile's extent — a title block, a coupon beside the board —
 * is not the board's and is not measured.
 */
@Beta("validated against HQDFM on one board")
public final class EdgeClearanceDetector {

    /** Gaps wider than this (mm) are not measured. */
    public static final double DEFAULT_CUTOFF_MM = 1.0;

    private EdgeClearanceDetector() {}

    public static EdgeClearanceResult detect(CopperGeometry g, BoardProfile profile, String fileName,
                                             double cutoffMm) {
        Map<Integer, EdgeClearance> best = new HashMap<>();
        if (profile != null && !profile.isEmpty()) {
            for (Capsule edge : profile.segments()) {
                g.forEachEntryAlong(edge, cutoffMm, e -> {
                    Feature f = g.feature(g.entryFeature(e));
                    Capsule ce = g.entryCapsule(e);
                    if (edge.distance(ce) >= cutoffMm) {
                        return;
                    }
                    for (double[] q : candidates(edge, ce)) {
                        // q is on the entry's surface, facing the edge; d is its gap to the edge.
                        double d = edge.distanceTo(q[0], q[1]);
                        if (d >= cutoffMm) {
                            continue;
                        }
                        Feature copper = f;
                        if (f.clear) {
                            // A clear's edge is a copper edge only where copper drawn before it survives.
                            int behind = g.copperAt(q[0], q[1]);
                            if (behind < 0 || behind > f.index) {
                                continue;
                            }
                            copper = g.feature(behind);
                        } else if (g.erasedAt(f, q[0], q[1])) {
                            continue;
                        }
                        if (!onBoard(copper, profile)) {
                            continue;
                        }
                        EdgeClearance current = best.get(copper.index);
                        if (current == null || d < current.distanceMm()) {
                            best.put(copper.index, new EdgeClearance(Math.max(0, d), q[0], q[1], copper.describe()));
                        }
                    }
                });
            }
        }
        List<EdgeClearance> list = new ArrayList<>(best.values());
        list.sort(Comparator.comparingDouble(EdgeClearance::distanceMm));
        return new EdgeClearanceResult(fileName, cutoffMm, list);
    }

    /**
     * Points on an entry's surface to measure from: its point nearest the edge, and — because an
     * entry often runs parallel to the edge, where "nearest" is a whole stretch and the one point
     * picked for it can sit on a corner another object covers — the points along its axis nearest
     * each end of the edge and at its own quarters. Each is moved out to the surface, towards the
     * edge, by the entry's radius.
     */
    private static List<double[]> candidates(Capsule edge, Capsule entry) {
        List<double[]> axis = new ArrayList<>();
        double[] p = edge.closestAxisPoints(entry);
        axis.add(new double[]{p[2], p[3]});
        axis.add(entry.nearestOnAxis(edge.x1, edge.y1));
        axis.add(entry.nearestOnAxis(edge.x2, edge.y2));
        for (double t : new double[]{0, 0.25, 0.5, 0.75, 1}) {
            axis.add(new double[]{entry.x1 + (entry.x2 - entry.x1) * t, entry.y1 + (entry.y2 - entry.y1) * t});
        }
        List<double[]> out = new ArrayList<>(axis.size());
        for (double[] a : axis) {
            double[] n = edge.nearestOnAxis(a[0], a[1]);
            double dx = n[0] - a[0], dy = n[1] - a[1], len = Math.hypot(dx, dy);
            double step = len == 0 ? 0 : Math.min(entry.r, len) / len;
            out.add(new double[]{a[0] + dx * step, a[1] + dy * step});
        }
        return out;
    }

    private static boolean onBoard(Feature f, BoardProfile profile) {
        var b = profile.bounds();
        double cx = f.bounds.getCenterX(), cy = f.bounds.getCenterY();
        return cx >= b.getMinX() && cx <= b.getMaxX() && cy >= b.getMinY() && cy <= b.getMaxY();
    }
}
