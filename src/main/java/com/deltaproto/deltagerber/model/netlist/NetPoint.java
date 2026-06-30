package com.deltaproto.deltagerber.model.netlist;

/**
 * A single coordinate in an IPC-D-356A netlist, in millimetres.
 *
 * <p>Like every other coordinate in the library, IPC-356 X/Y values are normalized to mm at
 * parse time (from the file's native 0.0001&nbsp;inch or 0.001&nbsp;mm grid), so a {@code NetPoint}
 * shares one coordinate space with {@code GerberDocument}/{@code DrillDocument} geometry.
 */
public record NetPoint(double x, double y) {

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "(%.4f, %.4f)", x, y);
    }
}
