package com.deltaproto.deltagerber.dfm.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which objects are one piece of copper, with clears drawn over them. */
class CopperNetsTest {

    private static CopperNets nets(String body) {
        return CopperNets.of(CopperGeometry.of(CopperGeometryTest.parse(body)));
    }

    @Test
    void touchingTracesJoinAndSeparateOnesDoNot() {
        CopperNets n = nets("%ADD10C,0.2*%\nD10*\n"
                + "X0Y0D02*\nX5000000Y0D01*\n"           // 0: trace along y=0
                + "X5000000Y0D02*\nX5000000Y5000000D01*\n" // 1: continues up from its end
                + "X0Y2000000D02*\nX4000000Y2000000D01*\n"); // 2: apart from both
        assertEquals(2, n.netCount());
        assertEquals(n.netOf(0), n.netOf(1));
        assertNotEquals(n.netOf(0), n.netOf(2));
    }

    @Test
    void padInsideAPlaneIsThePlanesNet() {
        CopperNets n = nets("G36*\nX0Y0D02*\nX10000000Y0D01*\nX10000000Y10000000D01*\nX0Y10000000D01*\nX0Y0D01*\nG37*\n"
                + "%ADD10C,1.0*%\nD10*\nX5000000Y5000000D03*\n");
        assertEquals(1, n.netCount());
    }

    @Test
    void antipadSeparatesPadFromPlaneAndASpokeReconnectsIt() {
        String planePadAntipad = "G36*\nX0Y0D02*\nX10000000Y0D01*\nX10000000Y10000000D01*\nX0Y10000000D01*\nX0Y0D01*\nG37*\n"
                + "%ADD10C,2.0*%\n%ADD11C,1.0*%\n%ADD12C,0.3*%\n"
                + "%LPC*%\nD10*\nX5000000Y5000000D03*\n"      // antipad, 2 mm, cut from the plane
                + "%LPD*%\nD11*\nX5000000Y5000000D03*\n";     // pad, 1 mm, drawn after it
        CopperNets isolated = nets(planePadAntipad);
        assertEquals(2, isolated.netCount(), "the pad sits in the antipad, apart from the plane");

        CopperNets spoked = nets(planePadAntipad
                + "D12*\nX5000000Y5000000D02*\nX7000000Y5000000D01*\n");  // spoke out across the antipad edge
        assertEquals(1, spoked.netCount(), "the spoke leaves the antipad into the plane");
    }

    @Test
    void aPadEntirelyUnderALaterClearIsGone() {
        CopperNets n = nets("%ADD10C,1.0*%\n%ADD11C,2.0*%\nD10*\nX0Y0D03*\nX5000000Y0D03*\n"
                + "%LPC*%\nD11*\nX0Y0D03*\n%LPD*%\n"
                + "D10*\nX300000Y0D03*\n");   // a pad drawn after the clear, overlapping the erased one's place
        // The first pad was erased by the clear; the last pad is drawn on top and is its own net.
        assertNotEquals(n.netOf(0), n.netOf(3));
    }

    @Test
    void aProfileDrawnTwiceIsOnePiece() {
        String square = "G36*\nX0Y0D02*\nX10000000Y0D01*\nX10000000Y10000000D01*\nX0Y10000000D01*\nX0Y0D01*\nG37*\n";
        assertEquals(1, nets(square + square).netCount());
    }

    @Test
    void namesComeFromTheFileAndNeverJoinCopper() {
        CopperNets n = nets("%ADD10C,1.0*%\nD10*\n"
                + "%TO.N,+3V3*%\nX0Y0D03*\nX5000000Y0D03*\n%TD*%\n"
                + "X10000000Y0D03*\n");
        assertEquals(3, n.netCount(), "two +3V3 pads not touching stay two pieces");
        assertTrue(n.sameName(n.netOf(0), n.netOf(1)));
        assertFalse(n.sameName(n.netOf(0), n.netOf(2)));
        assertEquals("+3V3", n.nameOf(n.netOf(0)));
        assertTrue(n.nameOf(n.netOf(2)).startsWith("net#"));
    }

    @Test
    void twoNamesOnOnePieceIsAConflictNamedForTheMajority() {
        CopperNets n = nets("%ADD10C,1.0*%\nD10*\n"
                + "%TO.N,GND*%\nX0Y0D03*\nX500000Y0D03*\n%TD*%\n"
                + "%TO.N,SIG*%\nX1000000Y0D03*\n%TD*%\n");
        assertEquals(1, n.netCount());
        assertEquals("GND", n.nameOf(0));
        assertEquals(1, n.warnings().size());
        assertTrue(n.warnings().get(0).contains("SIG"));
    }
}
