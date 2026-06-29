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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link MultiLayerSVGRenderer#alignDrillLayers} — the opt-in step that detects a
 * drill/Gerber origin mismatch and bakes the correction into a copy before the (pure) render.
 * <p>
 * Synthetic, non-traceable fixtures: a 40x30 mm board with three corner pads, and a drill parked
 * ~100 mm off-origin.
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
