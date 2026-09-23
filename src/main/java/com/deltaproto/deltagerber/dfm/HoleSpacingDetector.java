package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import com.deltaproto.deltagerber.model.drill.DrillDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hole-to-hole spacing: the laminate left between the walls of neighbouring drilled holes, over the
 * whole drill program — plated and non-plated files together, since the drill does not care which
 * file a hole came from.
 *
 * <p>Every pair is measured once, and each hole keeps only its nearest neighbour, so a row of
 * connector holes reports its pitch once per hole rather than once per pair. Holes are swept in x,
 * so the cost is the neighbourhood, not the square of the hole count.
 *
 * <p>Holes that touch or overlap are not a spacing at all: EAGLE drills the Arduino's DC-jack slots
 * as rows of ⌀1.3 mm holes 0.76 mm apart, and a duplicated hit lands on itself. They are listed
 * apart, on {@link HoleSpacingResult#getOverlapping()}, and never set the minimum — HQDFM passes the
 * Arduino's drill spacing for the same reason.
 */
@Beta("validated against HQDFM on one board")
public final class HoleSpacingDetector {

    /** Gaps wider than this (mm) are not measured. */
    public static final double DEFAULT_CUTOFF_MM = 1.0;

    private HoleSpacingDetector() {}

    /** @param drills in the Gerber frame, or at least all in one frame */
    public static HoleSpacingResult detect(Collection<DrillDocument> drills, double cutoffMm) {
        return detect(DrilledHole.of(drills), cutoffMm);
    }

    public static HoleSpacingResult detect(List<DrilledHole> holes, double cutoffMm) {
        List<DrilledHole> sorted = new ArrayList<>(holes);
        sorted.sort(Comparator.comparingDouble(DrilledHole::xMm));
        double maxRadius = 0;
        for (DrilledHole h : sorted) {
            maxRadius = Math.max(maxRadius, h.diameterMm() / 2);
        }
        Map<DrilledHole, HoleSpacing> nearest = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            DrilledHole a = sorted.get(i);
            double reach = a.diameterMm() / 2 + maxRadius + cutoffMm;
            for (int j = i + 1; j < sorted.size() && sorted.get(j).xMm() - a.xMm() < reach; j++) {
                DrilledHole b = sorted.get(j);
                double centres = Math.hypot(b.xMm() - a.xMm(), b.yMm() - a.yMm());
                double gap = centres - a.diameterMm() / 2 - b.diameterMm() / 2;
                if (gap >= cutoffMm) {
                    continue;
                }
                // The middle of the gap, along the line through the centres.
                double t = centres == 0 ? 0.5 : (a.diameterMm() / 2 + gap / 2) / centres;
                HoleSpacing s = new HoleSpacing(gap, a.xMm() + (b.xMm() - a.xMm()) * t,
                        a.yMm() + (b.yMm() - a.yMm()) * t, a, b);
                keep(nearest, a, s);
                keep(nearest, b, s);
            }
        }
        List<HoleSpacing> out = new ArrayList<>();
        List<HoleSpacing> overlapping = new ArrayList<>();
        for (HoleSpacing s : new java.util.LinkedHashSet<>(nearest.values())) {
            (s.distanceMm() > 0 ? out : overlapping).add(s);
        }
        out.sort(Comparator.comparingDouble(HoleSpacing::distanceMm));
        overlapping.sort(Comparator.comparingDouble(HoleSpacing::distanceMm));
        return new HoleSpacingResult(cutoffMm, out, overlapping);
    }

    private static void keep(Map<DrilledHole, HoleSpacing> nearest, DrilledHole hole, HoleSpacing s) {
        HoleSpacing current = nearest.get(hole);
        if (current == null || s.distanceMm() < current.distanceMm()) {
            nearest.put(hole, s);
        }
    }
}
