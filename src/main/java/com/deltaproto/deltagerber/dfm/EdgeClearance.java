package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Locale;

/**
 * How close one piece of copper comes to the board's edge.
 *
 * @param distanceMm the gap from the copper to the router's path, in millimetres; zero when the
 *                   copper reaches or crosses the edge
 * @param xMm        where, in the Gerber frame: the point of the copper nearest the edge
 * @param yMm        where
 * @param feature    the object whose copper it is
 */
@Beta("validated against HQDFM on one board")
public record EdgeClearance(double distanceMm, double xMm, double yMm, String feature) {

    @Override
    public String toString() {
        return String.format(Locale.US, "%.4f mm from the edge at (%.3f, %.3f): %s", distanceMm, xMm, yMm, feature);
    }
}
