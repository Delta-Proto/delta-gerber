package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Locale;

/**
 * How close one hole's wall comes to copper it must not touch on one layer: copper of another net,
 * or any copper at all when the hole sits in no copper here.
 *
 * @param distanceMm the gap from the hole's wall to that copper, in millimetres
 * @param xMm        where, in the Gerber frame: the middle of the gap — the point HQDFM reports
 * @param yMm        where
 * @param hole       the hole
 * @param onPad      whether the hole passes through copper of its own on this layer (a pad, or a
 *                   plane it connects to) — a plated hole's "hole to trace", as against a hole in an
 *                   antipad or a non-plated hole's "hole to copper"
 * @param feature    the copper it comes close to
 */
@Beta("validated against HQDFM on one board")
public record DrillClearance(double distanceMm, double xMm, double yMm, DrilledHole hole, boolean onPad,
                             String feature) {

    @Override
    public String toString() {
        return String.format(Locale.US, "%.4f mm from the ⌀%.3f hole at (%.3f, %.3f) to %s",
                distanceMm, hole.diameterMm(), hole.xMm(), hole.yMm(), feature);
    }
}
