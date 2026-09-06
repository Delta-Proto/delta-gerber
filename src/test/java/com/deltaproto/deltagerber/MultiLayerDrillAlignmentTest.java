package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer.Layer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link MultiLayerSVGRenderer#alignDrillLayers} — the opt-in step that detects a
 * drill/Gerber origin mismatch and bakes the correction into a copy before the (pure) render.
 * <p>
 * Synthetic, non-traceable fixtures: a 40x30 mm board with three corner pads and a drill parked
 * ~100 mm off-origin, plus a 200x150 mm board whose drill set is displaced by less than its own
 * size and split into a round-hole and a slot file (issue #5).
 */
public class MultiLayerDrillAlignmentTest {

    private final GerberParser gerberParser = new GerberParser();
    private final ExcellonParser drillParser = new ExcellonParser();

    private Layer copperWithPads() {
        GerberDocument doc = gerberParser.parse("""
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
        return new Layer("copper.gtl", doc).setLayerType(LayerType.COPPER_TOP);
    }

    private Layer outlineNoPads() {
        GerberDocument doc = gerberParser.parse("""
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
        return new Layer("outline.gko", doc).setLayerType(LayerType.OUTLINE);
    }

    private Layer offOriginDrill() {
        DrillDocument doc = drillParser.parse("""
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
        return new Layer("plated.txt", doc).setLayerType(LayerType.DRILL_PLATED);
    }

    private Layer onBoardDrill() {
        DrillDocument doc = drillParser.parse("""
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
        return new Layer("plated.txt", doc).setLayerType(LayerType.DRILL_PLATED);
    }

    private static boolean overlaps(BoundingBox a, BoundingBox b) {
        return a.getMinX() <= b.getMaxX() && a.getMaxX() >= b.getMinX()
            && a.getMinY() <= b.getMaxY() && a.getMaxY() >= b.getMinY();
    }

    @Test
    void bakesTheCorrectionAndWarnsWithMatchCounts() {
        Layer copper = copperWithPads();
        Layer drill = offOriginDrill();
        BoundingBox board = copper.getBoundingBox();
        assertFalse(overlaps(drill.getBoundingBox(), board), "setup: drill starts off the board");

        List<Layer> aligned = MultiLayerSVGRenderer.alignDrillLayers(List.of(copper, drill));

        Layer alignedDrill = aligned.stream().filter(Layer::isDrill).findFirst().orElseThrow();
        DrillDocument fixed = alignedDrill.getDrillDoc();
        assertTrue(overlaps(alignedDrill.getBoundingBox(), board), "corrected drill overlaps the board");
        assertEquals(8.0, ((DrillHit) fixed.getOperations().get(0)).getX(), 1e-6);
        // Baked coordinates + reversible stamp.
        assertTrue(fixed.isOriginCorrected());
        assertEquals(-100.0, fixed.getOriginOffsetX(), 1e-6);
        assertEquals(-80.0, fixed.getOriginOffsetY(), 1e-6);
        // The warning keeps the hole-match evidence (3 of 3) and the export-fix guidance.
        String warning = fixed.getWarnings().stream()
            .filter(w -> w.contains("origin")).findFirst().orElseThrow();
        assertTrue(warning.contains("3 of 3 holes"), "warning should report match counts: " + warning);
        assertTrue(warning.contains("Reference to Absolute Origin"));

        // The caller's original document is untouched (no move, no warning, no stamp).
        assertEquals(108.0, ((DrillHit) drill.getDrillDoc().getOperations().get(0)).getX(), 1e-6);
        assertFalse(drill.getDrillDoc().isOriginCorrected());
        assertTrue(drill.getDrillDoc().getWarnings().isEmpty());
    }

    @Test
    void leavesAlignedDrillUntouchedAndUnwarned() {
        Layer copper = copperWithPads();
        Layer drill = onBoardDrill();

        List<Layer> aligned = MultiLayerSVGRenderer.alignDrillLayers(List.of(copper, drill));

        // Fast path: the in-board drill is returned as the same instance, unstamped and unwarned.
        Layer same = aligned.stream().filter(Layer::isDrill).findFirst().orElseThrow();
        assertSame(drill, same);
        assertFalse(drill.getDrillDoc().isOriginCorrected());
        assertTrue(drill.getDrillDoc().getWarnings().isEmpty());
    }

    @Test
    void offBoardButUnrecoverableIsLeftInPlaceWithAWarning() {
        Layer outline = outlineNoPads(); // no copper pads to match against
        Layer drill = offOriginDrill();

        List<Layer> aligned = MultiLayerSVGRenderer.alignDrillLayers(List.of(outline, drill));

        Layer same = aligned.stream().filter(Layer::isDrill).findFirst().orElseThrow();
        assertSame(drill, same, "unrecoverable drill is not moved");
        assertFalse(drill.getDrillDoc().isOriginCorrected());
        assertTrue(drill.getDrillDoc().getWarnings().stream()
            .anyMatch(w -> w.contains("could not be determined")));
    }

    // ------------------------------------------------------------------------
    // A shift smaller than the board, and a drill set split across files (issue #5)
    // ------------------------------------------------------------------------

    // 200x150 mm board, twelve irregular pads, drill displaced by (120, 90) mm — less than the
    // board's own size, so the displaced drill still overlaps its bounding box. Altium splits the
    // program into a round-hole file and a slot file; only the first can recover the offset.
    private static final double[][] PADS = {
        {12, 9}, {28, 9}, {44, 17}, {12, 33}, {31, 41}, {57, 26},
        {75, 63}, {98, 14}, {121, 88}, {149, 37}, {176, 112}, {193, 141}
    };

    private Layer largeBoardWithPads() {
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
        return new Layer("copper.gtl", gerberParser.parse(sb.append("M02*\n").toString()))
            .setLayerType(LayerType.COPPER_TOP);
    }

    private Layer displacedRoundHoles() {
        StringBuilder sb = new StringBuilder("M48\nMETRIC\nT1C1.0\n%\nT1\n");
        for (double[] pad : PADS) {
            sb.append(String.format(Locale.US, "X%.3fY%.3f\n", pad[0] + 120, pad[1] + 90));
        }
        return new Layer("rounds.txt", drillParser.parse(sb.append("M30\n").toString()))
            .setLayerType(LayerType.DRILL_PLATED);
    }

    private Layer displacedSlots() {
        DrillDocument doc = drillParser.parse("""
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
        return new Layer("slots.txt", doc).setLayerType(LayerType.DRILL_PLATED);
    }

    @Test
    void resolvesTheSetTogetherSoTheSlotFileRidesOnTheRoundHoleOffset() {
        Layer copper = largeBoardWithPads();
        Layer rounds = displacedRoundHoles();
        Layer slots = displacedSlots();
        BoundingBox board = copper.getBoundingBox();
        // Setup: the round-hole file still overlaps the board, so a box test sees nothing wrong.
        assertTrue(overlaps(rounds.getBoundingBox(), board),
            "setup: the displaced round-hole drill overlaps the board's bounding box");

        List<Layer> aligned = MultiLayerSVGRenderer.alignDrillLayers(List.of(copper, rounds, slots));

        List<Layer> drills = aligned.stream().filter(Layer::isDrill).toList();
        assertEquals(2, drills.size());
        for (Layer d : drills) {
            DrillDocument doc = d.getDrillDoc();
            assertTrue(doc.isOriginCorrected(), d.getName() + " should have been corrected");
            assertEquals(-120.0, doc.getOriginOffsetX(), 1e-6);
            assertEquals(-90.0, doc.getOriginOffsetY(), 1e-6);
            assertTrue(overlaps(d.getBoundingBox(), board), d.getName() + " should sit on the board");
        }
        // The round holes matched pads themselves; the slot file says where its offset came from.
        assertTrue(drills.get(0).getDrillDoc().getWarnings().stream()
            .anyMatch(w -> w.contains("12 of 12 holes")));
        assertTrue(drills.get(1).getDrillDoc().getWarnings().stream()
            .anyMatch(w -> w.contains("another drill file in the same set")));

        // The caller's originals are untouched.
        assertFalse(rounds.getDrillDoc().isOriginCorrected());
        assertFalse(slots.getDrillDoc().isOriginCorrected());
    }

    @Test
    void renderStaysPure_doesNotAlignOnItsOwn() {
        Layer copper = copperWithPads();
        Layer drill = offOriginDrill();
        String svg = new MultiLayerSVGRenderer().render(List.of(copper, drill));

        assertTrue(svg.contains("cx=\"108.000000\""),
            "pure render should draw the drill at its exported (off-board) position");
        assertTrue(drill.getDrillDoc().getWarnings().isEmpty(),
            "pure render should not record alignment warnings");
    }
}
