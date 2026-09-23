package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Locale;

/**
 * The wall-to-wall gap between two drilled holes — what is left of the laminate between them.
 *
 * @param distanceMm the gap in millimetres; zero or negative when the holes touch or overlap (a
 *                   duplicate hit, or a slot drilled as a row of holes)
 * @param xMm        where, in the Gerber frame: the middle of the gap, which is the point HQDFM
 *                   reports
 * @param yMm        where
 * @param a          one hole
 * @param b          the other
 */
@Beta("validated against HQDFM on one board")
public record HoleSpacing(double distanceMm, double xMm, double yMm, DrilledHole a, DrilledHole b) {

    @Override
    public String toString() {
        return String.format(Locale.US, "%.4f mm between the ⌀%.3f at (%.3f, %.3f) and the ⌀%.3f at (%.3f, %.3f)",
                distanceMm, a.diameterMm(), a.xMm(), a.yMm(), b.diameterMm(), b.xMm(), b.yMm());
    }
}
