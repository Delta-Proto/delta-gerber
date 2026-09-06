package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.align.DrillGerberAlignment;
import com.deltaproto.deltagerber.align.DrillGerberAlignment.Result;
import com.deltaproto.deltagerber.align.DrillGerberAlignment.Status;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.DrillOperation;
import com.deltaproto.deltagerber.model.drill.DrillSlot;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link DrillGerberAlignment} library API — detecting that a drill file was
 * exported on a different origin than the Gerbers and recovering the exact offset by matching
 * holes to copper pads.
 * <p>
 * The fixtures are synthetic (metric, a 40x30 mm board, pads clustered in one corner so a
 * naive bounding-box centring would give a different answer than the true offset) — they
 * reproduce only the <em>shape</em> of the reported defect, not its real coordinates. The second
 * set does the same for the follow-up report: a board larger than the offset applied to it, whose
 * drill program is split into a round-hole file and a slot file.
 */
public class DrillGerberAlignmentTest {

    private final GerberParser gerberParser = new GerberParser();
    private final ExcellonParser drillParser = new ExcellonParser();

    /** 40x30 mm outline with three copper pads at (8,6), (12,6), (10,10) mm. */
    private GerberDocument copperWithPads() {
        return gerberParser.parse("""
            %FSLAX46Y46*%
            %MOMM*%
            %ADD10C,0.200*%
            %ADD11C,1.000*%
            D10*
            X0Y0D02*
            X40000000Y0D01*
            X40000000Y30000000D01*
            X0Y30000000D01*
            X0Y0D01*
            D11*
            X8000000Y6000000D03*
            X12000000Y6000000D03*
            X10000000Y10000000D03*
            M02*
            """);
    }

    /** Same 40x30 mm outline but no pads (stroked profile only). */
    private GerberDocument outlineNoPads() {
        return gerberParser.parse("""
            %FSLAX46Y46*%
            %MOMM*%
            %ADD10C,0.200*%
            D10*
            X0Y0D02*
            X40000000Y0D01*
            X40000000Y30000000D01*
            X0Y30000000D01*
            X0Y0D01*
            M02*
            """);
    }

    /** Holes at (8,6),(12,6),(10,10) translated by (+100,+80) → off the board. */
    private DrillDocument offOriginDrill() {
        return drillParser.parse("""
            M48
            METRIC
            T1C1.0
            %
            T1
            X108.0Y86.0
            X112.0Y86.0
            X110.0Y90.0
            M30
            """);
    }

    /** Holes already on the pads at (8,6),(12,6),(10,10). */
    private DrillDocument onBoardDrill() {
        return drillParser.parse("""
            M48
            METRIC
            T1C1.0
            %
            T1
            X8.0Y6.0
            X12.0Y6.0
            X10.0Y10.0
            M30
            """);
    }

    @Test
    void recoversExactOffsetByMatchingHolesToPads() {
        Result r = DrillGerberAlignment.analyze(offOriginDrill(), List.of(copperWithPads()));

        assertEquals(Status.MISALIGNED_RESOLVED, r.getStatus());
        assertTrue(r.isMisaligned());
        assertTrue(r.isResolved());
        // The pads sit at hole - (100,80), so the exact offset is (-100,-80) — not the
        // bounding-box centre (the pads are clustered in a corner).
        assertEquals(-100.0, r.getOffsetX(), 1e-6);
        assertEquals(-80.0, r.getOffsetY(), 1e-6);
        assertEquals(3, r.getMatchedHoles());
        assertEquals(3, r.getTotalHoles());
        assertTrue(r.getWarningText().contains("Reference to Absolute Origin"),
            "warning should explain the Altium origin setting");
    }

    @Test
    void applyTranslatesEveryHoleOntoItsPad() {
        DrillDocument drill = offOriginDrill();
        Result r = DrillGerberAlignment.analyze(drill, List.of(copperWithPads()));
        DrillDocument fixed = DrillGerberAlignment.apply(drill, r);

        assertNotSame(drill, fixed, "apply should return a corrected copy, not mutate the input");
        // Original is untouched (still off-board); the copy lands on the pads.
        assertEquals(108.0, ((DrillHit) drill.getOperations().get(0)).getX(), 1e-6);
        assertFalse(drill.isOriginCorrected(), "input doc must not be stamped");
        double[][] expected = {{8, 6}, {12, 6}, {10, 10}};
        for (int i = 0; i < expected.length; i++) {
            DrillHit h = (DrillHit) fixed.getOperations().get(i);
            assertEquals(expected[i][0], h.getX(), 1e-6);
            assertEquals(expected[i][1], h.getY(), 1e-6);
        }
        // Same tools/format carried over.
        assertEquals(drill.getTools().size(), fixed.getTools().size());
    }

    @Test
    void correctedDocStampsAReversibleOffset() {
        DrillDocument drill = offOriginDrill();
        DrillDocument fixed = DrillGerberAlignment.apply(
            drill, DrillGerberAlignment.analyze(drill, List.of(copperWithPads())));

        // Fix-once: coordinates are baked, and the stamp records what was applied.
        assertTrue(fixed.isOriginCorrected());
        assertEquals(-100.0, fixed.getOriginOffsetX(), 1e-6);
        assertEquals(-80.0, fixed.getOriginOffsetY(), 1e-6);
        // Reversible: original = corrected - originOffset.
        DrillHit h = (DrillHit) fixed.getOperations().get(0);
        assertEquals(108.0, h.getX() - fixed.getOriginOffsetX(), 1e-6);
        assertEquals(86.0, h.getY() - fixed.getOriginOffsetY(), 1e-6);
    }

    @Test
    void alignedConvenienceReturnsCorrectedDocInTheGerberFrame() {
        DrillDocument drill = offOriginDrill();
        DrillDocument aligned = DrillGerberAlignment.aligned(drill, List.of(copperWithPads()));

        // The one-call entry point future analyses (e.g. via-in-pad) use: holes now sit on the pads.
        assertTrue(aligned.isOriginCorrected());
        assertEquals(8.0, ((DrillHit) aligned.getOperations().get(0)).getX(), 1e-6);

        // An already-aligned drill is returned unchanged (no stamp, same instance).
        DrillDocument onBoard = onBoardDrill();
        DrillDocument same = DrillGerberAlignment.aligned(onBoard, List.of(copperWithPads()));
        assertSame(onBoard, same);
        assertFalse(same.isOriginCorrected());
    }

    @Test
    void alignedDrillReportsNoMisalignment() {
        Result r = DrillGerberAlignment.analyze(onBoardDrill(), List.of(copperWithPads()));

        assertEquals(Status.ALIGNED, r.getStatus());
        assertFalse(r.isMisaligned());
        assertFalse(r.isResolved());
        assertEquals(0.0, r.getOffsetX(), 1e-9);
        assertEquals(0.0, r.getOffsetY(), 1e-9);
        assertNull(r.getWarningText(), "no warning for an aligned drill");
        // apply() is a no-op pass-through when not resolved.
        DrillDocument drill = onBoardDrill();
        assertSame(drill, DrillGerberAlignment.apply(drill, r));
    }

    // ------------------------------------------------------------------------
    // A shift smaller than the board, and a drill set split across files (issue #5)
    // ------------------------------------------------------------------------

    // A 200x150 mm board with twelve irregularly placed pads. The point of the size: the drill is
    // displaced by (120, 90) mm — a real offset, but *less than the board's own dimensions*, so the
    // displaced drill still overlaps the board's bounding box. Only counting how many holes sit on
    // a pad can see that it is misplaced.
    private static final double[][] PADS = {
        {12, 9}, {28, 9}, {44, 17}, {12, 33}, {31, 41}, {57, 26},
        {75, 63}, {98, 14}, {121, 88}, {149, 37}, {176, 112}, {193, 141}
    };
    private static final double SHIFT_X = 120;
    private static final double SHIFT_Y = 90;

    private GerberDocument largeBoardWithPads() {
        StringBuilder sb = new StringBuilder("""
            %FSLAX46Y46*%
            %MOMM*%
            %ADD10C,0.200*%
            %ADD11C,1.000*%
            D10*
            X0Y0D02*
            X200000000Y0D01*
            X200000000Y150000000D01*
            X0Y150000000D01*
            X0Y0D01*
            D11*
            """);
        for (double[] pad : PADS) {
            sb.append(String.format(Locale.US, "X%dY%dD03*\n",
                (long) (pad[0] * 1e6), (long) (pad[1] * 1e6)));
        }
        return gerberParser.parse(sb.append("M02*\n").toString());
    }

    /** The round-hole file: one hole per pad, every one displaced by the same (120, 90) mm. */
    private DrillDocument displacedRoundHoles() {
        StringBuilder sb = new StringBuilder("M48\nMETRIC\nT1C1.0\n%\nT1\n");
        for (double[] pad : PADS) {
            sb.append(String.format(Locale.US, "X%.3fY%.3f\n", pad[0] + SHIFT_X, pad[1] + SHIFT_Y));
        }
        return drillParser.parse(sb.append("M30\n").toString());
    }

    /**
     * The slot file the same export writes alongside it — routed slots only, so it carries no hole
     * centres to correlate and can never recover its own offset. Slots sit at (10,100)-(14,100) and
     * (60,120)-(60,126) mm on the board, here written on the displaced origin.
     */
    private DrillDocument displacedSlots() {
        return drillParser.parse("""
            M48
            METRIC
            T2C1.600
            %
            G90
            G05
            T2
            G00X130.000Y190.000
            M15
            G01X134.000Y190.000
            M16
            G00X180.000Y210.000
            M15
            G01X180.000Y216.000
            M16
            M30
            """);
    }

    /** Four mounting holes on the board, none of them on a pad — an NPTH file, correctly placed. */
    private DrillDocument npthMountingHoles() {
        return drillParser.parse("""
            M48
            METRIC
            T1C3.2
            %
            T1
            X10.0Y10.0
            X190.0Y10.0
            X10.0Y140.0
            X190.0Y140.0
            M30
            """);
    }

    private static boolean overlaps(BoundingBox a, BoundingBox b) {
        return a.getMinX() <= b.getMaxX() && a.getMaxX() >= b.getMinX()
            && a.getMinY() <= b.getMaxY() && a.getMaxY() >= b.getMinY();
    }

    @Test
    void recoversAnOffsetSmallerThanTheBoardWhereTheBoxesStillOverlap() {
        GerberDocument board = largeBoardWithPads();
        DrillDocument drill = displacedRoundHoles();
        // Setup: this is precisely the case a bounding-box test declares aligned.
        assertTrue(overlaps(drill.getBoundingBox(), board.getBoundingBox()),
            "setup: the displaced drill must still overlap the board's bounding box");

        Result r = DrillGerberAlignment.analyze(drill, List.of(board));

        assertEquals(Status.MISALIGNED_RESOLVED, r.getStatus());
        assertEquals(-SHIFT_X, r.getOffsetX(), 1e-6);
        assertEquals(-SHIFT_Y, r.getOffsetY(), 1e-6);
        assertEquals(PADS.length, r.getMatchedHoles(), "every hole should land on its pad");
        assertFalse(r.isInherited());

        DrillDocument fixed = DrillGerberAlignment.apply(drill, r);
        for (int i = 0; i < PADS.length; i++) {
            DrillHit h = (DrillHit) fixed.getOperations().get(i);
            assertEquals(PADS[i][0], h.getX(), 1e-6);
            assertEquals(PADS[i][1], h.getY(), 1e-6);
        }
    }

    @Test
    void slotOnlyDrillTakesTheOffsetItsSiblingRecovered() {
        GerberDocument board = largeBoardWithPads();
        DrillDocument rounds = displacedRoundHoles();
        DrillDocument slots = displacedSlots();
        assertEquals(0, slots.getOperations().stream().filter(o -> o instanceof DrillHit).count(),
            "setup: the slot file has no hole centres of its own");

        // Alone it is stranded — nothing to correlate.
        BoundingBox bounds = board.getBoundingBox();
        List<double[]> pads = DrillGerberAlignment.flashCenters(board);
        assertEquals(Status.MISALIGNED_UNRESOLVED,
            DrillGerberAlignment.analyze(slots, bounds, pads).getStatus());

        // As a set, it rides on the offset the round holes recovered.
        List<Result> results = DrillGerberAlignment.analyzeAll(List.of(rounds, slots), bounds, pads);
        Result slotResult = results.get(1);
        assertTrue(results.get(0).isResolved());
        assertEquals(Status.MISALIGNED_RESOLVED, slotResult.getStatus());
        assertTrue(slotResult.isInherited(), "the slot file matched nothing itself");
        assertEquals(-SHIFT_X, slotResult.getOffsetX(), 1e-6);
        assertEquals(-SHIFT_Y, slotResult.getOffsetY(), 1e-6);
        assertEquals(0, slotResult.getMatchedHoles(), "an inherited offset claims no matches");
        assertTrue(slotResult.getWarningText().contains("another drill file in the same set"));

        // ...and applying it puts the slots back on the board.
        List<DrillDocument> aligned =
            DrillGerberAlignment.alignedAll(List.of(rounds, slots), bounds, pads);
        DrillSlot first = (DrillSlot) aligned.get(1).getOperations().get(0);
        assertEquals(10.0, first.getStartX(), 1e-6);
        assertEquals(100.0, first.getStartY(), 1e-6);
        assertEquals(14.0, first.getEndX(), 1e-6);
        assertTrue(board.getBoundingBox().getMaxY() > aligned.get(1).getBoundingBox().getMaxY(),
            "the corrected slots sit within the board");
    }

    @Test
    void doesNotMoveAnNpthDrillThatMatchesNoPads() {
        // A legitimately placed drill whose holes are on no pad at all (mounting holes) must be left
        // where it is — the support test must not be talked into a coincidental translation.
        GerberDocument board = largeBoardWithPads();
        DrillDocument npth = npthMountingHoles();

        Result r = DrillGerberAlignment.analyze(npth, List.of(board));

        assertEquals(Status.ALIGNED, r.getStatus());
        assertNull(r.getWarningText());
        assertSame(npth, DrillGerberAlignment.apply(npth, r));
    }

    @Test
    void analyzeAllOnAHealthySetReportsEverythingAligned() {
        List<Result> results = DrillGerberAlignment.analyzeAll(
            List.of(onBoardDrill(), onBoardDrill()),
            copperWithPads().getBoundingBox(),
            DrillGerberAlignment.flashCenters(copperWithPads()));

        assertEquals(2, results.size());
        for (Result r : results) {
            assertEquals(Status.ALIGNED, r.getStatus());
            assertFalse(r.isMisaligned());
        }
    }

    @Test
    void offBoardDrillWithNoCopperPadsIsUnresolvedNotGuessed() {
        Result r = DrillGerberAlignment.analyze(offOriginDrill(), List.of(outlineNoPads()));

        // Detected as misaligned (off the board) but, with nothing to match against, left as-is.
        assertEquals(Status.MISALIGNED_UNRESOLVED, r.getStatus());
        assertTrue(r.isMisaligned());
        assertFalse(r.isResolved());
        DrillDocument drill = offOriginDrill();
        assertSame(drill, DrillGerberAlignment.apply(drill, r), "unresolved → apply is a no-op");
        assertTrue(r.getWarningText().toLowerCase().contains("origin"));
    }
}
