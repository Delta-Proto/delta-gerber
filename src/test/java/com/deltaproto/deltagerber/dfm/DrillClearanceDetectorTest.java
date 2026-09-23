package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.dfm.geometry.CopperNets;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** From a hole's wall to copper it must not touch: another net's, or any when it sits in none. */
class DrillClearanceDetectorTest {

    private static CopperNets nets(String body) {
        return CopperNets.of(CopperGeometry.of(
                new GerberParser().parse("%FSLAX46Y46*%\n%MOMM*%\n" + body + "M02*\n")));
    }

    private static DrillClearanceResult measure(String copper, DrilledHole... holes) {
        return DrillClearanceDetector.detect(nets(copper), "top.gbr", List.of(holes), 1.0, null);
    }

    @Test
    void aTrackOfAnotherNetPassingAPlatedHole() {
        // A ⌀1.0 pad with a ⌀0.6 hole; a 0.2 mm track of another net 1.0 mm from the centre.
        DrillClearanceResult r = measure("%ADD10C,1.0*%\n%ADD11C,0.2*%\nD10*\nX0Y0D03*\n"
                + "D11*\nX-2000000Y1000000D02*\nX2000000Y1000000D01*\n", new DrilledHole(0, 0, 0.6, true));
        // Wall at 0.3, track edge at 0.9: 0.6 mm.
        assertEquals(0.6, r.getMinMm(), 1e-9);
        assertTrue(r.getMin().onPad());
        assertEquals(0.6, r.getMin().yMm(), 1e-9, "the middle of the gap");
    }

    @Test
    void theHolesOwnTrackIsNotAClearance() {
        DrillClearanceResult r = measure("%ADD10C,1.0*%\n%ADD11C,0.2*%\nD10*\nX0Y0D03*\n"
                + "D11*\nX0Y0D02*\nX3000000Y0D01*\n", new DrilledHole(0, 0, 0.6, true));
        assertNull(r.getMinMm());
    }

    @Test
    void aViaInAnAntipadIsMeasuredToTheAntipadsEdge() {
        // A plane with a ⌀1.0 antipad; a ⌀0.3 via through it, touching no copper here.
        DrillClearanceResult r = measure("G36*\nX-5000000Y-5000000D02*\nX5000000Y-5000000D01*\nX5000000Y5000000D01*\n"
                + "X-5000000Y5000000D01*\nX-5000000Y-5000000D01*\nG37*\n%ADD10C,1.0*%\n%LPC*%\nD10*\nX0Y0D03*\n%LPD*%\n",
                new DrilledHole(0, 0, 0.3, true));
        assertEquals(0.35, r.getMinMm(), 1e-3, "to the flattening of the antipad");
        assertFalse(r.getMin().onPad());
    }

    @Test
    void floatingCopperCanBeLeftOut() {
        String copper = "%ADD10C,1.0*%\n%ADD12C,0.1*%\nD10*\nX0Y0D03*\nD12*\nX-1000000Y800000D02*\nX1000000Y800000D01*\n";
        CopperNets n = nets(copper);
        List<DrilledHole> hole = List.of(new DrilledHole(0, 0, 0.6, true));
        assertEquals(0.45, DrillClearanceDetector.detect(n, "top.gbr", hole, 1.0, null).getMinMm(), 1e-9);
        FloatingCopperResult floating = FloatingCopperDetector.detect(n, "top.gbr", null, true);
        assertNull(DrillClearanceDetector.detect(n, "top.gbr", hole, 1.0, floating).getMinMm());
    }
}
