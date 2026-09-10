package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #9: the tightest gap between two nets, measured on synthetic copper where the answer is arithmetic. */
class ClearanceDetectorTest {

    private static GerberDocument copper(String body) {
        GerberDocument doc = new GerberParser().parse("%FSLAX46Y46*%\n%MOMM*%\n" + body + "M02*\n");
        doc.setFileName("top.gbr");
        return doc;
    }

    private static final String PLANE = "G36*\nX0Y0D02*\nX10000000Y0D01*\nX10000000Y10000000D01*\nX0Y10000000D01*\nX0Y0D01*\nG37*\n";

    @Test
    void parallelTracesOfDifferentNetsMeasureEdgeToEdge() {
        ClearanceResult r = ClearanceDetector.detect(copper("%ADD10C,0.2*%\nD10*\n"
                + "X0Y0D02*\nX5000000Y0D01*\nX0Y500000D02*\nX5000000Y500000D01*\n"));
        assertEquals(0.3, r.getMinMm(), 1e-9);
        Clearance c = r.getMin();
        assertEquals(0.25, c.yMm(), 1e-9, "reported halfway across the gap");
        assertEquals(2, r.getNetCount());
        assertEquals(1, r.getClearances().size());
    }

    @Test
    void padInAnAntipadMeasuresToTheAntipadEdge() {
        ClearanceResult r = ClearanceDetector.detect(copper(PLANE
                + "%ADD10C,2.0*%\n%ADD11C,1.0*%\n"
                + "%LPC*%\nD10*\nX5000000Y5000000D03*\n"
                + "%LPD*%\nD11*\nX5000000Y5000000D03*\n"));
        assertEquals(0.5, r.getMinMm(), 1e-3, "1 mm pad in a 2 mm antipad: half a millimetre all round, to the flattening");
    }

    @Test
    void aSpokedPadIsTheSameNetAndMeasuresNothing() {
        ClearanceResult r = ClearanceDetector.detect(copper(PLANE
                + "%ADD10C,2.0*%\n%ADD11C,1.0*%\n%ADD12C,0.3*%\n"
                + "%LPC*%\nD10*\nX5000000Y5000000D03*\n"
                + "%LPD*%\nD11*\nX5000000Y5000000D03*\n"
                + "D12*\nX5000000Y5000000D02*\nX7000000Y5000000D01*\n"));
        assertEquals(1, r.getNetCount());
        assertNull(r.getMinMm());
    }

    @Test
    void twoPiecesTheFileNamesAlikeAreNotMeasuredAgainstEachOther() {
        ClearanceResult r = ClearanceDetector.detect(copper("%ADD10C,1.0*%\nD10*\n"
                + "%TO.N,+3V3*%\nX0Y0D03*\nX1200000Y0D03*\n%TD*%\n"
                + "%TO.N,SIG*%\nX0Y1500000D03*\n%TD*%\n"));
        assertEquals(0.5, r.getMinMm(), 1e-9, "+3V3 to SIG, not +3V3 to +3V3 (which would be 0.2)");
        assertEquals("+3V3", r.getMin().netA());
        assertEquals("SIG", r.getMin().netB());
    }

    @Test
    void gapsBeyondTheCutoffAreNotMeasured() {
        ClearanceResult r = ClearanceDetector.detect(copper("%ADD10C,1.0*%\nD10*\nX0Y0D03*\nX5000000Y0D03*\n"));
        assertNull(r.getMinMm());
        assertEquals(ClearanceDetector.DEFAULT_CUTOFF_MM, r.getCutoffMm(), 1e-12);
        assertEquals(2, r.getNetCount());
    }

    @Test
    void anErasedEdgeIsNotACopperEdge() {
        // Two 1 mm pads 0.4 apart; a clear then wipes out the right half of the first (x from 0
        // to 0.8), so the real gap runs from the clear's edge at x = 0 to the second pad at 0.9 —
        // not from the first pad's outline, which no longer exists there.
        ClearanceResult r = ClearanceDetector.detect(copper("%ADD10C,1.0*%\n%ADD11R,0.8X2.0*%\nD10*\n"
                + "X0Y0D03*\nX1400000Y0D03*\n"
                + "%LPC*%\nD11*\nX400000Y0D03*\n%LPD*%\n"));
        assertEquals(0.9, r.getMinMm(), 1e-6);
        assertTrue(r.getMin().featureA().contains("flash") || r.getMin().featureB().contains("flash"));
    }
}
