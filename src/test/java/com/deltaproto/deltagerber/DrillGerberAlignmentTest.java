package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.align.DrillGerberAlignment;
import com.deltaproto.deltagerber.align.DrillGerberAlignment.Result;
import com.deltaproto.deltagerber.align.DrillGerberAlignment.Status;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.DrillOperation;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link DrillGerberAlignment} library API — detecting that a drill file was
 * exported on a different origin than the Gerbers and recovering the exact offset by matching
 * holes to copper pads.
 * <p>
 * The fixtures are synthetic (metric, a 40x30 mm board, pads clustered in one corner so a
 * naive bounding-box centring would give a different answer than the true offset) — they
 * reproduce only the <em>shape</em> of the reported defect, not its real coordinates.
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
