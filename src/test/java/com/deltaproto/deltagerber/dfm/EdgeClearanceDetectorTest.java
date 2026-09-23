package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.dfm.geometry.BoardProfile;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Copper to the board's edge: measured to the router's path, and to where the copper really ends. */
class EdgeClearanceDetectorTest {

    private static GerberDocument gerber(String body) {
        return new GerberParser().parse("%FSLAX46Y46*%\n%MOMM*%\n" + body + "M02*\n");
    }

    /** A 20 x 10 mm board drawn with a 0.2 mm brush — the brush does not move the edge. */
    private static final BoardProfile BOARD = BoardProfile.of(List.of(gerber("%ADD10C,0.2*%\nD10*\n"
            + "X0Y0D02*\nX20000000Y0D01*\nX20000000Y10000000D01*\nX0Y10000000D01*\nX0Y0D01*\n")));

    private static String rect(double x0, double y0, double x1, double y1) {
        return String.format("G36*\nX%dY%dD02*\nX%dY%dD01*\nX%dY%dD01*\nX%dY%dD01*\nX%dY%dD01*\nG37*\n",
                Math.round(x0 * 1e6), Math.round(y0 * 1e6), Math.round(x1 * 1e6), Math.round(y0 * 1e6),
                Math.round(x1 * 1e6), Math.round(y1 * 1e6), Math.round(x0 * 1e6), Math.round(y1 * 1e6),
                Math.round(x0 * 1e6), Math.round(y0 * 1e6));
    }

    private static EdgeClearanceResult measure(String copper) {
        return EdgeClearanceDetector.detect(CopperGeometry.of(gerber(copper)), BOARD, "top.gbr", 1.0);
    }

    @Test
    void aTraceIsMeasuredFromItsEdgeToTheProfilesCentreline() {
        // A 0.2 mm track whose centreline runs 0.5 mm inside the bottom edge.
        EdgeClearanceResult r = measure("%ADD11C,0.2*%\nD11*\nX5000000Y500000D02*\nX15000000Y500000D01*\n");
        assertEquals(0.4, r.getMinMm(), 1e-9);
        assertEquals(0.4, r.getMin().yMm(), 1e-9);
    }

    @Test
    void aPourCutBackByALaterClearIsMeasuredToTheClearsEdge() {
        // A pour to the board's edge, then a clear 0.3 mm frame over its rim.
        EdgeClearanceResult r = measure("G36*\nX0Y0D02*\nX20000000Y0D01*\nX20000000Y10000000D01*\nX0Y10000000D01*\nX0Y0D01*\nG37*\n"
                + "%LPC*%\nG36*\nX0Y0D02*\nX20000000Y0D01*\nX20000000Y300000D01*\nX0Y300000D01*\nX0Y0D01*\nG37*\n%LPD*%\n");
        assertEquals(0.0, r.getMinMm(), 1e-9, "the pour still reaches the three uncleared edges");
        EdgeClearanceResult framed = measure("G36*\nX0Y0D02*\nX20000000Y0D01*\nX20000000Y10000000D01*\nX0Y10000000D01*\nX0Y0D01*\nG37*\n"
                + "%LPC*%\n" + rect(-1, -1, 21, 0.3) + rect(-1, 9.7, 21, 11) + rect(-1, -1, 0.3, 11) + rect(19.7, -1, 21, 11)
                + "%LPD*%\n");
        assertEquals(0.3, framed.getMinMm(), 1e-6, "cut back all round, the copper ends 0.3 mm in");
    }

    @Test
    void copperOffTheBoardIsNotTheBoards() {
        EdgeClearanceResult r = measure("%ADD11C,0.2*%\nD11*\nX5000000Y-700000D02*\nX15000000Y-700000D01*\n");
        assertNull(r.getMinMm());
    }

    @Test
    void copperWellInsideIsNotMeasured() {
        assertNull(measure("%ADD11C,0.2*%\nD11*\nX5000000Y5000000D02*\nX15000000Y5000000D01*\n").getMinMm());
    }
}
