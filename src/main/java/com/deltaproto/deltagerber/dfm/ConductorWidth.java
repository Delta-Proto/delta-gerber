package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Locale;

/**
 * One place where the copper of a layer is a certain width: a stroke's aperture or a neck in a pour.
 *
 * @param widthMm     the width, in millimetres
 * @param xMm         where, in the Gerber frame
 * @param yMm         where
 * @param kind        what kind of copper it is
 * @param feature     the object it was measured on
 */
@Beta("not yet validated against an external DFM tool")
public record ConductorWidth(double widthMm, double xMm, double yMm, Kind kind, String feature) {

    /** What was measured. */
    public enum Kind {
        /** A draw or arc: the aperture's width across the stroke. */
        STROKE,
        /** The narrowest place in a region's own outline, clears included — a pour's neck. */
        REGION_NECK
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%.4f mm %s at (%.3f, %.3f)", widthMm, kind, xMm, yMm);
    }
}
