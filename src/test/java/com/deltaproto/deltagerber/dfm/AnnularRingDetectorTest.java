package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.DrillSlot;
import com.deltaproto.deltagerber.model.drill.Tool;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Unit;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AnnularRingDetector}: the copper left between a drilled hole and the edge of its
 * pad. Fixtures are synthetic — one pad flashed at a known millimetre coordinate, one hole placed by
 * hand — so each assertion pins one piece of geometry and the arithmetic can be checked by eye.
 */
class AnnularRingDetectorTest {

    private static GerberDocument copper(String body) {
        return new GerberParser().parse("%FSLAX46Y46*%\n%MOMM*%\n" + body + "M02*\n");
    }

    private static CopperLayer top(String body) {
        return CopperLayer.top("top.gbr", copper(body));
    }

    /** One drill file: {@code diameter} mm holes at each x,y pair. Plating unstated. */
    private static DrillDocument drill(double diameter, double... coordinates) {
        DrillDocument doc = new DrillDocument();
        doc.setUnit(Unit.MM);
        doc.setFileName("board-PTH.drl");
        Tool tool = new Tool(1, diameter);
        doc.addTool(tool);
        for (int i = 0; i < coordinates.length; i += 2) {
            doc.addOperation(new DrillHit(tool, coordinates[i], coordinates[i + 1]));
        }
        return doc;
    }

    private static AnnularRingResult measure(CopperLayer layer, DrillDocument drill) {
        return AnnularRingDetector.detect(List.of(layer), List.of(drill));
    }

    private static double ring(AnnularRingResult result) {
        assertEquals(1, result.getRings().size(), "expected exactly one measured hole");
        return result.getRings().get(0).getMinRingMm();
    }

    // ------------------------------------------------------------------------
    // The pad shapes
    // ------------------------------------------------------------------------

    @Test
    void aCentredHoleInARoundPadRingsHalfTheDifference() {
        // ⌀1.0 pad, ⌀0.6 hole → (1.0 − 0.6) / 2
        AnnularRingResult r = measure(
                top("%ADD10C,1.000000*%\nD10*\nX10000000Y10000000D03*\n"), drill(0.6, 10, 10));
        assertEquals(0.2, ring(r), 1e-9);
        assertFalse(r.hasBreakout());
    }

    @Test
    void anOffCentreHoleRingsTheNearSideOnly() {
        // The same ⌀1.0 pad, the hole pushed 0.1 mm along X: the near side loses exactly that.
        AnnularRingResult r = measure(
                top("%ADD10C,1.000000*%\nD10*\nX10000000Y10000000D03*\n"), drill(0.6, 10.1, 10));
        assertEquals(0.1, ring(r), 1e-9);
        assertEquals(0.1, r.getRings().get(0).getWorstPad().getOffsetMm(), 1e-9);
    }

    @Test
    void aRectangularPadIsMeasuredAcrossItsShortAxis() {
        // 2.0 × 1.0 pad, ⌀0.6 hole: the long axis leaves 0.7, the short one 0.2, and 0.2 is the ring.
        AnnularRingResult r = measure(
                top("%ADD10R,2.000000X1.000000*%\nD10*\nX10000000Y10000000D03*\n"), drill(0.6, 10, 10));
        assertEquals(0.2, ring(r), 1e-9);
    }

    @Test
    void anObroundPadIsMeasuredAcrossItsShortAxisToo() {
        // A 2.0 × 1.0 stadium: the flat sides are 0.5 from the centre, so a ⌀0.6 hole rings 0.2.
        AnnularRingResult r = measure(
                top("%ADD10O,2.000000X1.000000*%\nD10*\nX10000000Y10000000D03*\n"), drill(0.6, 10, 10));
        assertEquals(0.2, ring(r), 1e-9);
    }

    @Test
    void anObroundPadRingsMoreTowardsItsRoundedEnd() {
        // Pushed 0.4 mm along the long axis the hole is still 0.6 from the end cap, but only 0.2
        // from the flat side — the short axis keeps deciding, which is the point of the worst
        // direction rule.
        AnnularRingResult r = measure(
                top("%ADD10O,2.000000X1.000000*%\nD10*\nX10000000Y10000000D03*\n"),
                drill(0.6, 10.4, 10));
        assertEquals(0.2, ring(r), 1e-9);
    }

    @Test
    void anOctagonalPadIsMeasuredToItsFlats() {
        // A regular octagon of ⌀2.0 (circumradius 1.0): its flats sit at 1.0·cos(22.5°) = 0.9239,
        // so a ⌀0.6 hole rings that less its own radius. A bounding box would have said 1.0 − 0.3.
        AnnularRingResult r = measure(
                top("%ADD10P,2.000000X8*%\nD10*\nX10000000Y10000000D03*\n"), drill(0.6, 10, 10));
        assertEquals(Math.cos(Math.PI / 8) - 0.3, ring(r), 1e-6);
    }

    @Test
    void aMacroPadIsMeasuredToItsRealOutlineNotItsBoundingBox() {
        // A rounded rectangle 2.0 × 1.0 with 0.25 corners, built the way tools write one: a wide
        // bar, a tall bar and four corner circles. Its flat side is 0.5 from the centre, so the
        // ring is 0.2 — the bounding box would have agreed here, and the corner test below is what
        // separates them.
        AnnularRingResult r = measure(top(ROUNDED_RECT + "D10*\nX10000000Y10000000D03*\n"),
                drill(0.6, 10, 10));
        assertEquals(0.2, ring(r), 1e-3);
    }

    @Test
    void aHoleInAMacroPadsRoundedCornerRingsLessThanItsBoxWould() {
        // The hole pushed into the corner: the true outline curves away at radius 0.25 about
        // (10.75, 10.25), so the copper left is 0.25 − 0.1 − 0.3, while the pad's bounding box
        // would still have claimed 0.5 − 0.4 − 0.3 = a comfortable ring on the long side.
        AnnularRingResult r = measure(top(ROUNDED_RECT + "D10*\nX10000000Y10000000D03*\n"),
                drill(0.6, 10.8, 10.3));
        double corner = 0.25 - Math.hypot(0.05, 0.05) - 0.3;
        assertEquals(corner, ring(r), 2e-3);
        assertTrue(ring(r) < 0);
    }

    @Test
    void aRotatedRectangularPadTurnsWithItsFlash() {
        // The same 2.0 × 1.0 pad, and a hole 0.15 mm off along X. Unrotated, X is the long axis and
        // the short one still decides: ring 0.2.
        AnnularRingResult r = measure(
                top("%ADD10R,2.000000X1.000000*%\nD10*\nX10000000Y10000000D03*\n"),
                drill(0.6, 10.15, 10));
        assertEquals(0.2, ring(r), 1e-9);

        // Flashed at 90° the short axis is X, the offset now eats into it, and the ring drops to
        // 0.05 — the flash's own rotation, not just the aperture's dimensions.
        AnnularRingResult rotated = AnnularRingDetector.detect(
                List.of(top("%ADD10R,2.000000X1.000000*%\n%LR90*%\nD10*\nX10000000Y10000000D03*\n")),
                List.of(drill(0.6, 10.15, 10)));
        assertEquals(0.05, ring(rotated), 1e-9);
    }

    @Test
    void anAperturesOwnHoleIsNotTheEdgeTheRingIsMeasuredTo() {
        // C,1.6X0.8 draws a 1.6 pad with its 0.8 drill punched out. That hole is the drilled hole,
        // so the ring runs to the pad's outer edge: (1.6 − 0.8) / 2, not zero.
        AnnularRingResult r = measure(
                top("%ADD10C,1.600000X0.800000*%\nD10*\nX10000000Y10000000D03*\n"), drill(0.8, 10, 10));
        assertEquals(0.4, ring(r), 1e-9);
    }

    // ------------------------------------------------------------------------
    // What is and is not a pad
    // ------------------------------------------------------------------------

    @Test
    void anOblongPadDrawnAsASweptApertureIsAPad() {
        // How EAGLE writes an oval through-hole pad: a ⌀1.4 round aperture dragged 0.7 mm. The
        // narrow direction is the aperture's own width, so a ⌀0.85 hole at the middle rings 0.275.
        AnnularRingResult r = measure(
                top("%ADD10C,1.400000*%\nD10*\nX10000000Y10000000D02*\nX10000000Y10700000D01*\n"),
                drill(0.85, 10, 10.35));
        assertEquals(0.275, ring(r), 1e-9);
    }

    @Test
    void aPourIsNotAPad() {
        // A 20 × 20 region covering the whole board. The hole is deep inside it and finds nothing:
        // copper it merely passes through is not a pad, and calling it one would report a 10 mm ring.
        AnnularRingResult r = AnnularRingDetector.detect(
                List.of(top("%ADD10C,0.100000*%\nD10*\nG36*\n"
                        + "X0Y0D02*\nX20000000Y0D01*\nX20000000Y20000000D01*\nX0Y20000000D01*\nX0Y0D01*\n"
                        + "G37*\n")),
                List.of(drill(0.6, 10, 10)));
        assertTrue(r.getRings().isEmpty());
        assertEquals(1, r.getHolesWithoutPad().size());
        assertNull(r.getMinRingMm());
    }

    @Test
    void anAntipadOverAPadLeavesNoPadAtAll() {
        // A negative plane: a pad, then a clear flash over it. The copper is gone at the hole, so
        // the layer reports no pad — not a ring of zero, which is what would make every via on a
        // plane look broken out.
        AnnularRingResult r = measure(top("%ADD10C,1.000000*%\n%ADD11C,1.400000*%\n"
                + "D10*\nX10000000Y10000000D03*\n"
                + "%LPC*%\nD11*\nX10000000Y10000000D03*\n"), drill(0.6, 10, 10));
        assertTrue(r.getRings().isEmpty());
        assertEquals(1, r.getHolesWithoutPad().size());
    }

    @Test
    void copperReturningOverAnAntipadIsAPadAgain() {
        // ... and the order is what decides it: clear first, dark second, and the pad stands.
        AnnularRingResult r = measure(top("%ADD10C,1.000000*%\n%ADD11C,1.400000*%\n"
                + "%LPC*%\nD11*\nX10000000Y10000000D03*\n"
                + "%LPD*%\nD10*\nX10000000Y10000000D03*\n"), drill(0.6, 10, 10));
        assertEquals(0.2, ring(r), 1e-9);
    }

    @Test
    void aTraceCrossingTheHoleDoesNotShrinkThePadsRing() {
        // A ⌀1.0 pad with a 0.2 mm trace running through it. Both contain the hole; copper is their
        // union, so the pad's own ring stands rather than the trace's negative one.
        AnnularRingResult r = measure(top("%ADD10C,1.000000*%\n%ADD11C,0.200000*%\n"
                + "D10*\nX10000000Y10000000D03*\n"
                + "D11*\nX5000000Y10000000D02*\nX15000000Y10000000D01*\n"), drill(0.6, 10, 10));
        assertEquals(0.2, ring(r), 1e-9);
    }

    @Test
    void aHoleOutsideEveryPadHasNoRingAtAll() {
        AnnularRingResult r = measure(
                top("%ADD10C,1.000000*%\nD10*\nX10000000Y10000000D03*\n"), drill(0.6, 20, 20));
        assertTrue(r.getRings().isEmpty());
        assertEquals(1, r.getHolesWithoutPad().size());
        assertEquals(1, r.getHoleCount());
        assertFalse(r.isMeasured());
    }

    @Test
    void aSlotIsNotMeasured() {
        DrillDocument doc = new DrillDocument();
        doc.setUnit(Unit.MM);
        Tool tool = new Tool(1, 1.0);
        doc.addTool(tool);
        doc.addOperation(new DrillSlot(tool, 9, 10, 11, 10));
        AnnularRingResult r = measure(top("%ADD10C,3.000000*%\nD10*\nX10000000Y10000000D03*\n"), doc);
        assertEquals(0, r.getHoleCount());
    }

    // ------------------------------------------------------------------------
    // Breakout, and holes that need no ring
    // ------------------------------------------------------------------------

    @Test
    void aHoleWiderThanItsPadBreaksOut() {
        // A ⌀4.0 hole drilled into a ⌀3.2 pad — the copper is drilled away, not merely thinned.
        AnnularRingResult r = measure(
                top("%ADD10C,3.200000*%\nD10*\nX10000000Y10000000D03*\n"), drill(4.0, 10, 10));
        assertEquals(-0.4, ring(r), 1e-9);
        assertTrue(r.hasBreakout());
        assertTrue(r.getRings().get(0).isBreakout());
    }

    @Test
    void aPadDrawnToTheSizeOfItsHoleIsNotABreakout() {
        // Pad and hole the same ⌀1.0 to within the file's own resolution: the ring is nothing, and
        // nothing is not a hole outside its pad.
        AnnularRingResult r = measure(
                top("%ADD10C,0.999900*%\nD10*\nX10000000Y10000000D03*\n"), drill(1.0, 10, 10));
        assertEquals(-0.00005, ring(r), 1e-6);
        assertFalse(r.hasBreakout());
    }

    @Test
    void aNonPlatedHoleIsNotMeasured() {
        // A ⌀3.2 mounting hole through a plane's pad would report a spectacular breakout. It has no
        // barrel to connect to anything, so it is left out of the measurement entirely.
        DrillDocument doc = new DrillDocument();
        doc.setUnit(Unit.MM);
        Tool tool = new Tool(1, 3.2);
        tool.setPlated(Boolean.FALSE);
        doc.addTool(tool);
        doc.addOperation(new DrillHit(tool, 10, 10));
        AnnularRingResult r = measure(top("%ADD10C,1.000000*%\nD10*\nX10000000Y10000000D03*\n"), doc);
        assertEquals(0, r.getHoleCount());
        assertNull(r.getMinRingMm());
    }

    @Test
    void aPlatedHoleWithNoPadIsCalledOutSeparately() {
        DrillDocument doc = new DrillDocument();
        doc.setUnit(Unit.MM);
        Tool plated = new Tool(1, 0.6);
        plated.setPlated(Boolean.TRUE);
        doc.addTool(plated);
        doc.addOperation(new DrillHit(plated, 10, 10));     // on the pad
        doc.addOperation(new DrillHit(plated, 20, 20));     // nowhere near it
        AnnularRingResult r = measure(top("%ADD10C,1.000000*%\nD10*\nX10000000Y10000000D03*\n"), doc);

        assertEquals(1, r.getRings().size());
        assertEquals(1, r.getHolesWithoutPad().size());
        assertEquals(1, r.getPlatedHolesWithoutPad().size());
        assertEquals(20, r.getPlatedHolesWithoutPad().get(0).getX(), 1e-9);
    }

    @Test
    void aHoleWithNoStatedPlatingIsNotCalledAFault() {
        AnnularRingResult r = measure(
                top("%ADD10C,1.000000*%\nD10*\nX10000000Y10000000D03*\n"), drill(3.2, 20, 20));
        assertEquals(1, r.getHolesWithoutPad().size());
        assertTrue(r.getPlatedHolesWithoutPad().isEmpty());
        assertNull(r.getHolesWithoutPad().get(0).getPlated());
    }

    // ------------------------------------------------------------------------
    // Across the stack
    // ------------------------------------------------------------------------

    @Test
    void theRingIsReportedPerLayerAndTheWorstOneIsTheAnswer() {
        CopperLayer top = CopperLayer.top("top.gbr",
                copper("%ADD10C,1.000000*%\nD10*\nX10000000Y10000000D03*\n"));
        CopperLayer inner = CopperLayer.inner("in1.gbr", 1,
                copper("%ADD10C,0.800000*%\nD10*\nX10000000Y10000000D03*\n"));
        CopperLayer bottom = CopperLayer.bottom("bot.gbr",
                copper("%ADD10C,1.200000*%\nD10*\nX10000000Y10000000D03*\n"));

        AnnularRingResult r = AnnularRingDetector.detect(List.of(top, inner, bottom),
                List.of(drill(0.6, 10, 10)));

        AnnularRing hole = r.getRings().get(0);
        assertEquals(3, hole.getPads().size());
        assertEquals(0.1, hole.getMinRingMm(), 1e-9);                 // the inner layer is worst
        assertEquals(LayerSide.INNER, hole.getWorstPad().getSide());
        assertEquals(1, hole.getWorstPad().getLayerNumber());
        assertEquals(0.2, hole.getPads().get(0).getRingMm(), 1e-9);   // top
        assertEquals(0.3, hole.getPads().get(2).getRingMm(), 1e-9);   // bottom
    }

    @Test
    void aLayerAViaOnlyPassesThroughIsSimplyNotListed() {
        // The plane has an antipad where the via crosses it — no pad, no entry, and the ring comes
        // from the layers that do have one. This is the ordinary case, not a fault.
        CopperLayer top = CopperLayer.top("top.gbr",
                copper("%ADD10C,1.000000*%\nD10*\nX10000000Y10000000D03*\n"));
        CopperLayer plane = CopperLayer.inner("in1.gbr", 1,
                copper("%ADD10C,1.400000*%\n%LPC*%\nD10*\nX10000000Y10000000D03*\n"));

        AnnularRingResult r = AnnularRingDetector.detect(List.of(top, plane),
                List.of(drill(0.6, 10, 10)));
        assertEquals(1, r.getRings().get(0).getPads().size());
        assertEquals(0.2, r.getMinRingMm(), 1e-9);
    }

    @Test
    void aBuriedViaFindsPadsOnlyOnTheLayersItReaches() {
        // No span is stated anywhere and none is needed: the drill finds pads on the two inner
        // layers and nothing on the outer ones, which is the truth about a buried via.
        CopperLayer top = CopperLayer.top("top.gbr", copper("%ADD10C,1.000000*%\nD10*\nX0Y0D03*\n"));
        CopperLayer in1 = CopperLayer.inner("in1.gbr", 1,
                copper("%ADD10C,0.500000*%\nD10*\nX10000000Y10000000D03*\n"));
        CopperLayer in2 = CopperLayer.inner("in2.gbr", 2,
                copper("%ADD10C,0.500000*%\nD10*\nX10000000Y10000000D03*\n"));

        AnnularRingResult r = AnnularRingDetector.detect(List.of(top, in1, in2),
                List.of(drill(0.25, 10, 10)));
        AnnularRing via = r.getRings().get(0);
        assertEquals(2, via.getPads().size());
        assertEquals(LayerSide.INNER, via.getPads().get(0).getSide());
        assertEquals(0.125, via.getMinRingMm(), 1e-9);
    }

    // ------------------------------------------------------------------------
    // The policy
    // ------------------------------------------------------------------------

    @Test
    void thePolicyJudgesInnerAndOuterLayersByTheirOwnRule() {
        CopperLayer top = CopperLayer.top("top.gbr",
                copper("%ADD10C,0.900000*%\nD10*\nX10000000Y10000000D03*\n"));   // ring 0.15
        CopperLayer inner = CopperLayer.inner("in1.gbr", 1,
                copper("%ADD10C,0.800000*%\nD10*\nX10000000Y10000000D03*\n"));   // ring 0.10

        AnnularRingResult r = AnnularRingDetector.detect(List.of(top, inner),
                List.of(drill(0.6, 10, 10)));

        // The default asks 0.15 mm on both, and the inner layer misses it.
        assertFalse(r.isWithinPolicy());
        assertEquals(1, r.getViolations().size());

        // A shop that asks less of an inner layer than of an outer one passes the same board.
        AnnularRingPolicy relaxedInner = new AnnularRingPolicy(0.15, 0.10);
        assertTrue(r.isWithinPolicy(relaxedInner));
        assertTrue(r.getViolations(relaxedInner).isEmpty());

        // And IPC-6012 Class 3's acceptance figures are far below either.
        assertTrue(r.isWithinPolicy(AnnularRingPolicy.IPC_6012_CLASS_3));
    }

    @Test
    void everyHoleUnderTheRuleIsListedOnce() {
        AnnularRingResult r = measure(
                top("%ADD10C,0.700000*%\nD10*\n"
                        + "X10000000Y10000000D03*\nX12000000Y10000000D03*\nX14000000Y10000000D03*\n"),
                drill(0.6, 10, 10, 12, 10, 14, 10));
        assertEquals(3, r.getRings().size());
        assertEquals(3, r.getViolations().size());              // 0.05 mm rings, all three
        assertEquals(0.05, r.getMinRingMm(), 1e-9);
    }

    // ------------------------------------------------------------------------
    // A drill on a foreign origin
    // ------------------------------------------------------------------------

    @Test
    void aDrillExportedOnAnotherOriginIsMovedOntoTheCopperFirst() {
        String pads = "%ADD10C,1.000000*%\nD10*\n"
                + "X10000000Y10000000D03*\nX20000000Y10000000D03*\nX20000000Y20000000D03*\n"
                + "X10000000Y20000000D03*\nX15000000Y15000000D03*\n";
        // The same five holes, every one of them 100 mm away in X and Y.
        DrillDocument offset = drill(0.6, 110, 110, 120, 110, 120, 120, 110, 120, 115, 115);

        assertTrue(AnnularRingDetector.detect(List.of(top(pads)), List.of(offset)).getRings().isEmpty());

        AnnularRingResult aligned =
                AnnularRingDetector.detectAligned(List.of(top(pads)), List.of(offset));
        assertEquals(5, aligned.getRings().size());
        assertEquals(0.2, aligned.getMinRingMm(), 1e-9);
    }

    @Test
    void nothingToMeasureIsAnEmptyResultRatherThanAnError() {
        assertNotNull(AnnularRingDetector.detect(null, null));
        assertEquals(0, AnnularRingDetector.detect(List.of(), List.of()).getHoleCount());
        assertNull(AnnularRingDetector.detect(List.of(top("")), List.of()).getMinRingMm());
        assertTrue(AnnularRingDetector.detect(List.of(), List.of(drill(0.6, 10, 10)))
                .getRings().isEmpty());
    }

    /**
     * A 2.0 × 1.0 rounded rectangle with 0.25 mm corners, as an aperture macro: two crossing bars
     * and a circle in each corner — the construction tools actually emit.
     */
    private static final String ROUNDED_RECT =
            "%AMROUNDRECT*\n"
                    + "21,1,2.000000,0.500000,0,0,0*\n"
                    + "21,1,1.500000,1.000000,0,0,0*\n"
                    + "1,1,0.500000,-0.750000,-0.250000*\n"
                    + "1,1,0.500000,-0.750000,0.250000*\n"
                    + "1,1,0.500000,0.750000,-0.250000*\n"
                    + "1,1,0.500000,0.750000,0.250000*\n"
                    + "%\n"
                    + "%ADD10ROUNDRECT*%\n";
}
