package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
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
 * Regression tests for drill/Gerber origin-mismatch handling.
 * <p>
 * Some EDA tools (Altium Designer in particular) can export the Gerbers relative to the
 * board origin (board near 0,0) while writing the NC-drill file relative to the absolute
 * sheet origin (board offset far from 0,0), with nothing in the drill file recording the
 * offset. The "all layers" view then shows the drill holes translated away from the copper
 * they belong to. {@link MultiLayerSVGRenderer} detects a drill layer whose bounds are
 * entirely disjoint from the Gerber stack and re-centres it on the board.
 * <p>
 * The coordinates below are synthetic (metric, board 40x30 mm, holes parked ~100 mm away)
 * — they only reproduce the <em>shape</em> of the reported defect, not its actual data.
 */
public class DrillOriginAlignmentTest {

    private final GerberParser gerberParser = new GerberParser();
    private final ExcellonParser drillParser = new ExcellonParser();

    /** A 40 mm x 30 mm board outline anchored at the origin. */
    private GerberDocument boardOutline() {
        String gerber = """
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
            """;
        return gerberParser.parse(gerber);
    }

    /** Three drill hits parked ~100 mm off the board — the "wrong origin" case. */
    private DrillDocument offOriginDrill() {
        String drill = """
            M48
            METRIC
            T1C1.0
            %
            T1
            X110.0Y90.0
            X130.0Y90.0
            X120.0Y110.0
            M30
            """;
        return drillParser.parse(drill);
    }

    /** Three drill hits already inside the board outline — the normal, correct case. */
    private DrillDocument onBoardDrill() {
        String drill = """
            M48
            METRIC
            T1C1.0
            %
            T1
            X10.0Y5.0
            X30.0Y5.0
            X20.0Y25.0
            M30
            """;
        return drillParser.parse(drill);
    }

    private static boolean overlaps(BoundingBox a, BoundingBox b) {
        return a.getMinX() <= b.getMaxX() && a.getMaxX() >= b.getMinX()
            && a.getMinY() <= b.getMaxY() && a.getMaxY() >= b.getMinY();
    }

    @Test
    void offOriginDrillIsPulledOntoTheBoard() {
        Layer board = new Layer("board.gko", boardOutline()).setLayerType(LayerType.OUTLINE);
        Layer drillLayer = new Layer("plated.txt", offOriginDrill()).setLayerType(LayerType.DRILL_PLATED);
        List<Layer> layers = List.of(board, drillLayer);

        // Sanity: as exported, the drill sits completely off the board.
        assertFalse(overlaps(drillLayer.getRawBoundingBox(), board.getRawBoundingBox()),
            "test setup: drill should start disjoint from the board");

        String svg = new MultiLayerSVGRenderer().render(layers);

        // The drill layer was shifted...
        assertTrue(drillLayer.hasRenderOffset(), "disjoint drill layer should be re-homed");
        // ...and the shifted bounds now overlap the board.
        assertTrue(overlaps(drillLayer.getBoundingBox(), board.getRawBoundingBox()),
            "re-homed drill should overlap the board outline");
        // Centre-to-centre alignment: board centre (20,15), drill centre (120,100).
        assertEquals(-100.0, drillLayer.getOffsetX(), 1e-6);
        assertEquals(-85.0, drillLayer.getOffsetY(), 1e-6);

        // A warning records the correction for callers to surface.
        List<String> warnings = drillLayer.getDrillDoc().getWarnings();
        assertTrue(warnings.stream().anyMatch(w -> w.toLowerCase().contains("origin")),
            "an origin-mismatch warning should be recorded, got: " + warnings);

        // The emitted SVG carries the translate that moves the holes.
        assertTrue(svg.contains("translate(-100.000000,-85.000000)"),
            "drill geometry should be wrapped in the alignment translate");
    }

    @Test
    void correctlyAlignedDrillIsLeftUntouched() {
        Layer board = new Layer("board.gko", boardOutline()).setLayerType(LayerType.OUTLINE);
        Layer drillLayer = new Layer("plated.txt", onBoardDrill()).setLayerType(LayerType.DRILL_PLATED);
        List<Layer> layers = List.of(board, drillLayer);

        String svg = new MultiLayerSVGRenderer().render(layers);

        // No shift, no warning — a correct file set must render exactly as before.
        assertFalse(drillLayer.hasRenderOffset(), "an in-board drill layer must not be moved");
        assertEquals(0.0, drillLayer.getOffsetX(), 1e-9);
        assertEquals(0.0, drillLayer.getOffsetY(), 1e-9);
        assertTrue(drillLayer.getDrillDoc().getWarnings().stream()
                .noneMatch(w -> w.toLowerCase().contains("origin")),
            "no origin warning expected for an aligned drill layer");
        assertFalse(svg.contains("transform=\"translate(0.000000,0.000000)\""),
            "no zero-offset translate group should be emitted");
    }

    @Test
    void alignmentIsIdempotentAcrossRepeatedRenders() {
        Layer board = new Layer("board.gko", boardOutline()).setLayerType(LayerType.OUTLINE);
        Layer drillLayer = new Layer("plated.txt", offOriginDrill()).setLayerType(LayerType.DRILL_PLATED);
        List<Layer> layers = List.of(board, drillLayer);

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        renderer.render(layers);
        double firstX = drillLayer.getOffsetX();
        double firstY = drillLayer.getOffsetY();

        renderer.render(layers); // second pass over the same (already-shifted) layers
        assertEquals(firstX, drillLayer.getOffsetX(), 1e-9, "offset must not drift on re-render");
        assertEquals(firstY, drillLayer.getOffsetY(), 1e-9, "offset must not drift on re-render");
    }

    @Test
    void drillOnlySetWithNoGerberIsNotShifted() {
        // With nothing to align against, the drill must be left where it is.
        Layer drillLayer = new Layer("plated.txt", offOriginDrill()).setLayerType(LayerType.DRILL_PLATED);
        List<Layer> layers = List.of(drillLayer);

        new MultiLayerSVGRenderer().render(layers);

        assertFalse(drillLayer.hasRenderOffset(), "no Gerber reference -> no shift");
    }
}
