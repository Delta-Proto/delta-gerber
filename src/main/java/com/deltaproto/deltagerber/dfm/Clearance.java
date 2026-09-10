package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Locale;

/**
 * The tightest gap found between two nets on one copper layer: how wide, where, and between what.
 *
 * <p>A distance of zero means the two pieces of copper touch or overlap while nothing in the file
 * joins them into one net — a short, or a net the file names twice.
 *
 * @param distanceMm  the gap, in millimetres
 * @param xMm         where, in the Gerber frame: the middle of the connector between the two
 * @param yMm         where
 * @param netA        one net — its {@code .N} name when the file has one, else {@code net#k}
 * @param netB        the other
 * @param featureA    the object on net A the gap was measured from
 * @param featureB    the object on net B the gap was measured to
 */
@Beta("not yet validated against an external DFM tool")
public record Clearance(double distanceMm, double xMm, double yMm,
                        String netA, String netB, String featureA, String featureB) {

    @Override
    public String toString() {
        return String.format(Locale.US, "%.4f mm between %s and %s at (%.3f, %.3f)",
                distanceMm, netA, netB, xMm, yMm);
    }
}
