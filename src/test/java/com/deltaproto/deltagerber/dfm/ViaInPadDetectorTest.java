package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.Tool;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Unit;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ViaInPadDetector}: a drilled hole whose centre lands inside a solder-paste
 * opening is a via in pad. Fixtures are synthetic — paste pads flashed at known millimetre
 * coordinates and drill hits placed by hand — so each assertion pins one geometric case.
 */
class ViaInPadDetectorTest {

    private static final Tool VIA = new Tool(1, 0.3);

    private static GerberDocument paste(String body) {
        return new GerberParser().parse("%FSLAX46Y46*%\n%MOMM*%\n" + body + "M02*\n");
    }

    /** Top paste: a 1×1 mm rectangle pad at (10,10) and a ⌀0.8 mm round pad at (20,20). */
    private static GerberDocument topPasteTwoPads() {
        return paste("""
            %ADD10R,1.000000X1.000000*%
            %ADD11C,0.800000*%
            D10*
            X10000000Y10000000D03*
            D11*
            X20000000Y20000000D03*
            """);
    }

    private static DrillDocument drill(double[]... holes) {
        DrillDocument doc = new DrillDocument();
        doc.setUnit(Unit.MM);
        doc.addTool(VIA);
        for (double[] h : holes) {
            doc.addOperation(new DrillHit(VIA, h[0], h[1]));
        }
        return doc;
    }

    @Test
    void holeInsideRectAndCirclePadsAreVias() {
        DrillDocument d = drill(new double[]{10, 10}, new double[]{20, 20});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topPasteTwoPads()), List.of(), List.of(d));
        assertTrue(r.hasViaInPad());
        assertEquals(2, r.getCount());
        assertTrue(r.isOnTop());
        assertFalse(r.isOnBottom());
    }

    @Test
    void holeOutsideEveryPadIsNotAVia() {
        DrillDocument d = drill(new double[]{30, 30}, new double[]{10, 12});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topPasteTwoPads()), List.of(), List.of(d));
        assertFalse(r.hasViaInPad());
        assertEquals(0, r.getCount());
    }

    @Test
    void holeJustOutsideTheCircleRadiusIsNotAVia() {
        // ⌀0.8 pad → radius 0.4 mm; a hole 0.5 mm away in X is clear of it.
        DrillDocument d = drill(new double[]{20.5, 20});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topPasteTwoPads()), List.of(), List.of(d));
        assertFalse(r.hasViaInPad());
    }

    @Test
    void carriesTheHoleDiameter() {
        DrillDocument d = drill(new double[]{10, 10});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topPasteTwoPads()), List.of(), List.of(d));
        assertEquals(1, r.getCount());
        assertEquals(0.3, r.getViaInPads().get(0).getHoleDiameterMm(), 1e-9);
    }

    @Test
    void bottomPastePadIsReportedOnTheBottom() {
        GerberDocument bottom = paste("""
            %ADD10C,1.000000*%
            D10*
            X5000000Y5000000D03*
            """);
        DrillDocument d = drill(new double[]{5, 5});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(), List.of(bottom), List.of(d));
        assertTrue(r.hasViaInPad());
        assertTrue(r.isOnBottom());
        assertFalse(r.isOnTop());
        assertTrue(r.getViaInPads().get(0).isBottom());
    }

    @Test
    void aPadOnBothSidesMarksTheHoleBothWays() {
        GerberDocument top = paste("""
            %ADD10C,1.000000*%
            D10*
            X8000000Y8000000D03*
            """);
        GerberDocument bottom = paste("""
            %ADD10C,1.000000*%
            D10*
            X8000000Y8000000D03*
            """);
        DrillDocument d = drill(new double[]{8, 8});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(top), List.of(bottom), List.of(d));
        assertEquals(1, r.getCount());
        ViaInPad v = r.getViaInPads().get(0);
        assertTrue(v.isTop());
        assertTrue(v.isBottom());
    }

    @Test
    void regionPadCatchesAHoleInside() {
        // A 2 mm square painted as a region (G36/G37), corners (10,10)-(12,12).
        GerberDocument region = paste("""
            G36*
            X10000000Y10000000D02*
            X12000000Y10000000D01*
            X12000000Y12000000D01*
            X10000000Y12000000D01*
            X10000000Y10000000D01*
            G37*
            """);
        DrillDocument in = drill(new double[]{11, 11});
        DrillDocument out = drill(new double[]{13, 11});
        assertTrue(ViaInPadDetector.detect(List.of(region), List.of(), List.of(in)).hasViaInPad());
        assertFalse(ViaInPadDetector.detect(List.of(region), List.of(), List.of(out)).hasViaInPad());
    }

    @Test
    void noPasteOrNoDrillYieldsAnEmptyResult() {
        DrillDocument d = drill(new double[]{10, 10});
        assertFalse(ViaInPadDetector.detect(List.of(), List.of(), List.of(d)).hasViaInPad());
        assertFalse(ViaInPadDetector.detect(List.of(topPasteTwoPads()), List.of(), List.of()).hasViaInPad());
    }

    @Test
    void detectAlignedRecoversADrillExportedOnAnotherOrigin() {
        // Copper pads coincide with the paste openings; the alignment needs at least three holes to
        // match a pad before it trusts the recovered offset.
        GerberDocument copper = new GerberParser().parse("""
            %FSLAX46Y46*%
            %MOMM*%
            %ADD10C,0.050000*%
            %ADD11C,1.200000*%
            D10*
            X0Y0D02*
            X40000000Y0D01*
            X40000000Y30000000D01*
            X0Y30000000D01*
            X0Y0D01*
            D11*
            X10000000Y10000000D03*
            X20000000Y20000000D03*
            X30000000Y10000000D03*
            M02*
            """);
        GerberDocument pasteThree = paste("""
            %ADD10C,1.000000*%
            D10*
            X10000000Y10000000D03*
            X20000000Y20000000D03*
            X30000000Y10000000D03*
            """);
        // Drill shifted +100,+80 mm → entirely off the board until re-aligned.
        DrillDocument shifted = drill(new double[]{110, 90}, new double[]{120, 100}, new double[]{130, 90});
        ViaInPadResult r = ViaInPadDetector.detectAligned(
                List.of(pasteThree), List.of(), List.of(copper), List.of(shifted));
        assertTrue(r.hasViaInPad());
        assertEquals(3, r.getCount());
    }

    // ------------------------------------------------------------------------
    // Grouping by pad, and the filled-and-capped verdict that follows from it
    // ------------------------------------------------------------------------

    /** Top paste: a 3×3 mm thermal land at (10,10) — 9 mm² of paste. */
    private static GerberDocument thermalLand() {
        return paste("""
            %ADD10R,3.000000X3.000000*%
            D10*
            X10000000Y10000000D03*
            """);
    }

    /** Top paste: a ⌀0.8 mm signal land at (20,20) — 0.503 mm², too little to spare any. */
    private static GerberDocument signalLand() {
        return paste("""
            %ADD11C,0.800000*%
            D11*
            X20000000Y20000000D03*
            """);
    }

    @Test
    void holesInOnePadCollapseIntoOneGroupCarryingItsAreaAndViaSize() {
        // Four vias inside the one 3×3 land — the classic QFN thermal via field.
        DrillDocument d = drill(new double[]{9.5, 9.5}, new double[]{10.5, 9.5},
                new double[]{9.5, 10.5}, new double[]{10.5, 10.5});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(thermalLand()), List.of(), List.of(d));

        assertEquals(4, r.getCount());
        assertEquals(1, r.getGroups().size());
        ViaInPadGroup g = r.getGroups().get(0);
        assertEquals(4, g.getViaCount());
        assertEquals(9.0, g.getPadAreaMm2(), 1e-9);
        assertEquals(0.3, g.getViaDiameterMm(), 1e-9);
        assertEquals(10, g.getPadCenterX(), 1e-9);
        assertEquals("R", g.getPadShape());
        assertTrue(g.isTop());
        assertFalse(g.isBottom());
    }

    @Test
    void aViaFieldIsThermalAndNeedsNoFill() {
        DrillDocument d = drill(new double[]{9.5, 9.5}, new double[]{10.5, 10.5});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(thermalLand()), List.of(), List.of(d));
        assertTrue(r.hasViaInPad());              // the holes are in a pad ...
        assertFalse(r.requiresFilledAndCapped()); // ... but two of them make the pad a heat spreader
        assertTrue(r.getGroups().get(0).isLikelyThermal());
        assertEquals(1, r.getThermalGroups().size());
        assertEquals(0, r.getFilledAndCappedGroups().size());
    }

    @Test
    void oneViaInALandFarLargerThanItIsAlsoThermal() {
        // 9 mm² of paste against 0.071 mm² of hole — ratio 127, so the joint is not starved.
        DrillDocument d = drill(new double[]{10, 10});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(thermalLand()), List.of(), List.of(d));
        ViaInPadGroup g = r.getGroups().get(0);
        assertEquals(1, g.getViaCount());
        assertEquals(9.0 / (Math.PI * 0.15 * 0.15), g.getPadToViaAreaRatio(), 1e-6);
        assertFalse(r.requiresFilledAndCapped());
    }

    @Test
    void oneViaInASmallLandForcesFilledAndCapped() {
        DrillDocument d = drill(new double[]{20, 20});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(signalLand()), List.of(), List.of(d));
        ViaInPadGroup g = r.getGroups().get(0);
        assertEquals(Math.PI * 0.4 * 0.4, g.getPadAreaMm2(), 1e-9);
        assertFalse(g.isLikelyThermal());
        assertTrue(g.requiresFilledAndCapped());
        assertTrue(r.requiresFilledAndCapped());
    }

    @Test
    void oneOffendingLandAmongThermalOnesStillForcesTheProcess() {
        DrillDocument d = drill(new double[]{9.5, 9.5}, new double[]{10.5, 10.5}, new double[]{20, 20});
        ViaInPadResult r = ViaInPadDetector.detect(
                List.of(thermalLand(), signalLand()), List.of(), List.of(d));
        assertEquals(2, r.getGroups().size());
        assertTrue(r.requiresFilledAndCapped());
        assertEquals(1, r.getFilledAndCappedGroups().size());
        assertEquals(1, r.getThermalGroups().size());
        // The land that forces the process is the small round one, not the heat spreader.
        assertEquals(Math.PI * 0.4 * 0.4, r.getFilledAndCappedGroups().get(0).getPadAreaMm2(), 1e-9);
    }

    @Test
    void aPolicyMovesWhereTheLineSits() {
        // The 3×3 land with a single via is thermal by default; demanding a 200× ratio flips it.
        DrillDocument d = drill(new double[]{10, 10});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(thermalLand()), List.of(), List.of(d));
        assertFalse(r.requiresFilledAndCapped());
        assertTrue(r.requiresFilledAndCapped(new ViaInPadPolicy(2, 200.0, 2.0)));

        // And a policy that will not call any pad thermal below 20 mm² does the same.
        assertTrue(r.requiresFilledAndCapped(new ViaInPadPolicy(2, 25.0, 20.0)));
    }

    @Test
    void aPadOnBothSidesYieldsOneHoleInTwoGroups() {
        GerberDocument top = paste("""
            %ADD10C,1.000000*%
            D10*
            X8000000Y8000000D03*
            """);
        GerberDocument bottom = paste("""
            %ADD10C,1.000000*%
            D10*
            X8000000Y8000000D03*
            """);
        DrillDocument d = drill(new double[]{8, 8});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(top), List.of(bottom), List.of(d));
        assertEquals(1, r.getCount());
        assertEquals(2, r.getGroups().size());
        assertTrue(r.getGroups().get(0).isTop());
        assertTrue(r.getGroups().get(1).isBottom());
        // The same hit object in both groups, not a copy per side.
        assertSame(r.getViaInPads().get(0), r.getGroups().get(0).getVias().get(0));
        assertSame(r.getViaInPads().get(0), r.getGroups().get(1).getVias().get(0));
    }

    @Test
    void padAreaFollowsTheApertureShapeNotItsBoundingBox() {
        // An obround 2×1 is a stadium: 2·1 less the (4−π)r² the rounded ends cut away.
        GerberDocument obround = paste("""
            %ADD12O,2.000000X1.000000*%
            D12*
            X30000000Y30000000D03*
            """);
        DrillDocument d = drill(new double[]{30, 30});
        ViaInPadGroup g = ViaInPadDetector.detect(List.of(obround), List.of(), List.of(d))
                .getGroups().get(0);
        assertEquals(2.0 - (4 - Math.PI) * 0.25, g.getPadAreaMm2(), 1e-9);
        assertEquals("O", g.getPadShape());
    }

    @Test
    void aRegionPadIsMeasuredByItsOutline() {
        GerberDocument region = paste("""
            G36*
            X10000000Y10000000D02*
            X12000000Y10000000D01*
            X12000000Y12000000D01*
            X10000000Y12000000D01*
            X10000000Y10000000D01*
            G37*
            """);
        DrillDocument d = drill(new double[]{11, 11});
        ViaInPadGroup g = ViaInPadDetector.detect(List.of(region), List.of(), List.of(d))
                .getGroups().get(0);
        assertEquals(4.0, g.getPadAreaMm2(), 1e-9);   // 2 mm square
        assertEquals("region", g.getPadShape());
        assertEquals(11, g.getPadCenterX(), 1e-9);
        assertTrue(g.isLikelyThermal());              // 4 mm² against a 0.3 mm hole
    }

    @Test
    void noViaInPadMeansNoGroupsAndNoProcess() {
        DrillDocument d = drill(new double[]{30, 30});
        ViaInPadResult r = ViaInPadDetector.detect(List.of(thermalLand()), List.of(), List.of(d));
        assertTrue(r.getGroups().isEmpty());
        assertFalse(r.requiresFilledAndCapped());
        assertFalse(ViaInPadResult.empty().requiresFilledAndCapped());
    }
}
