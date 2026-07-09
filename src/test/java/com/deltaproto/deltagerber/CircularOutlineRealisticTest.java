package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.apache.batik.parser.AWTPathProducer;
import org.apache.batik.parser.PathParser;
import org.junit.jupiter.api.Test;

import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Realistic rendering of a round board profile, whose edge is a <em>full-circle arc</em>.
 * <p>
 * Gerber draws a full circle as one G75 arc that ends where it starts. SVG will not:
 * an elliptical-arc command whose endpoints are identical is defined to be omitted
 * entirely (SVG 1.1 §F.6.2). Emitted as a single {@code A}, a round board edge therefore
 * drew <em>nothing</em>, the clip path collapsed, and the realistic view came out blank.
 * The renderer now splits such an arc at the antipodal point into two half-arcs.
 * <p>
 * The same layer also shows the second half of the problem. An internal round cut-out is
 * often drawn as a <em>pair</em> of arcs — a short one and the long one back — which share
 * both endpoints. Segment de-duplication compared endpoints alone, so it took the second
 * arc for a reversed copy of the first and dropped it; the cut-out shrank to the sliver
 * between the chord and the short arc. Arcs are now compared by centre, radius and
 * direction as well.
 * <p>
 * The geometry here is synthetic — a 40 mm disc with a 12 mm hole, an Altium-style export
 * with no {@code .FileFunction} — and shares only its <em>structure</em> with the board
 * that surfaced this. Fill topology is checked by parsing the emitted clip path with
 * Batik, the same SVG path parser the PNG thumbnail path rasterises through.
 */
public class CircularOutlineRealisticTest {

    /** Half the chord that splits the cut-out circle: y = -sqrt(6^2 - 0.5^2). */
    private static final String CUTOUT_CHORD_Y = "-5979130";   // -5.979130 mm, in 4.6 format

    private static final String HEADER =
        "G04 Synthetic round profile*\n" +
        "%FSLAX46Y46*%\n" +
        "%MOMM*%\n" +
        "%TF.FilePolarity,Positive*%\n" +
        "G01*\n" +
        "G75*\n" +
        "%ADD11C,0.050000*%\n" +
        "%ADD93C,0.025000*%\n";

    /** Board edge: one CCW full-circle arc of radius 20 about the origin, closing on itself. */
    private static final String OUTER_CIRCLE =
        "D11*\n" +
        "X20000000Y0D02*\n" +
        "G03*\n" +
        "X20000000Y0I-20000000J0D01*\n" +
        "G01*\n";

    /**
     * Round cut-out of radius 6 about the origin, drawn the way Altium does: a short arc
     * across the bottom of the circle, then the long arc back. Both run counter-clockwise
     * and share their two endpoints.
     */
    private static final String INNER_CUTOUT =
        "D93*\n" +
        "X-500000Y" + CUTOUT_CHORD_Y + "D02*\n" +
        "G03*\n" +
        "X500000Y" + CUTOUT_CHORD_Y + "I500000J5979130D01*\n" +   // short way, under (0,-6)
        "X-500000Y" + CUTOUT_CHORD_Y + "I-500000J5979130D01*\n" + // long way back, over (0,6)
        "G01*\n";

    @Test
    void fullCircleBoardEdgeClipsToTheWholeDisc() throws Exception {
        Shape board = boardClipShape(HEADER + OUTER_CIRCLE + "M02*\n");

        // A single degenerate "A" would have left an empty shape here.
        assertTrue(board.contains(0.0, 0.0), "the centre of a disc board must be board");
        assertTrue(board.contains(0.0, 18.0), "near the top edge must be board");
        assertTrue(board.contains(-18.0, 0.0), "near the left edge must be board");
        assertFalse(board.contains(0.0, 24.0), "beyond the edge must be empty");

        assertBounds(board, 40.0);
    }

    @Test
    void complementaryArcsFormAWholeCutout() throws Exception {
        Shape board = boardClipShape(HEADER + OUTER_CIRCLE + INNER_CUTOUT + "M02*\n");

        // The cut-out is a whole circle of radius 6, not the sliver under its chord.
        assertFalse(board.contains(0.0, 0.0), "the cut-out must be a hole");
        assertFalse(board.contains(0.0, 4.0), "the whole cut-out must be a hole, not a sliver");
        assertFalse(board.contains(0.0, -4.0), "the whole cut-out must be a hole, not a sliver");

        // ... and the ring of board around it survives.
        assertTrue(board.contains(13.0, 0.0), "the ring of board must survive");
        assertTrue(board.contains(0.0, 13.0), "the ring of board must survive");
        assertTrue(board.contains(0.0, -13.0), "the ring of board must survive");
        assertFalse(board.contains(24.0, 0.0), "beyond the edge must be empty");

        assertBounds(board, 40.0);
    }

    /** The same round board edge, expressed as a G36 region rather than a stroked chain. */
    private static final String OUTER_CIRCLE_REGION =
        "G36*\n" +
        "X20000000Y0D02*\n" +
        "G03*\n" +
        "X20000000Y0I-20000000J0D01*\n" +
        "G01*\n" +
        "G37*\n";

    @Test
    void fullCircleRegionOutlineClipsToTheWholeDisc() throws Exception {
        // Region contours emit their own arcs (Contour.toSvgPath), and had the same blind spot.
        Shape board = boardClipShape(HEADER + OUTER_CIRCLE_REGION + "M02*\n");

        assertTrue(board.contains(0.0, 0.0), "the centre of a disc board must be board");
        assertTrue(board.contains(-18.0, 0.0), "near the left edge must be board");
        assertFalse(board.contains(0.0, 24.0), "beyond the edge must be empty");

        assertBounds(board, 40.0);
    }

    @Test
    void fullCircleIsEmittedAsTwoHalfArcs() throws Exception {
        String d = boardClipPath(HEADER + OUTER_CIRCLE + "M02*\n");

        // Two "A" commands by way of the antipode, not one that ends where it started.
        assertEquals(2, countArcCommands(d), "full circle should be split in two: " + d);
        assertTrue(d.contains("-20.000000"),
            "the antipodal point of the split should appear in the path: " + d);
    }

    /** Render the profile alone and pull the board-outline clip path out of the SVG. */
    private static String boardClipPath(String outlineGerber) {
        GerberDocument outline = new GerberParser().parse(outlineGerber);
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", outline)
            .setLayerType(LayerType.OUTLINE));

        String svg = new MultiLayerSVGRenderer().renderRealistic(layers);
        assertNotNull(svg);

        Matcher m = Pattern.compile("<clipPath id=\"board-outline\">\\s*<path d=\"([^\"]*)\"")
            .matcher(svg);
        assertTrue(m.find(), "realistic SVG should define a board-outline clipPath");
        return m.group(1);
    }

    /**
     * The clip path as an AWT shape, wound even-odd to match the {@code clip-rule} the
     * renderer sets, so nested subpaths read as holes.
     */
    private static Shape boardClipShape(String outlineGerber) throws Exception {
        AWTPathProducer producer = new AWTPathProducer();
        producer.setWindingRule(Path2D.WIND_EVEN_ODD);
        PathParser parser = new PathParser();
        parser.setPathHandler(producer);
        parser.parse(boardClipPath(outlineGerber));
        return producer.getShape();
    }

    private static int countArcCommands(String d) {
        return (int) d.chars().filter(c -> c == 'A').count();
    }

    /**
     * Batik approximates each arc with cubics accurate to ~0.5%, so a circle's extent is
     * checked loosely — the failure this guards against is a collapsed path, not a wobble.
     */
    private static void assertBounds(Shape board, double expectedSizeMm) {
        Rectangle2D b = board.getBounds2D();
        assertEquals(expectedSizeMm, b.getWidth(), 0.3, "board width");
        assertEquals(expectedSizeMm, b.getHeight(), 0.3, "board height");
        assertEquals(0.0, b.getCenterX(), 0.05, "board centred on the origin in x");
        assertEquals(0.0, b.getCenterY(), 0.05, "board centred on the origin in y");
    }
}
