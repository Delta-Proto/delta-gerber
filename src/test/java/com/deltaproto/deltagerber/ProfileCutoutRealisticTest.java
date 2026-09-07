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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the loops on a profile layer mean, and where they may be found — the cluster of defects
 * behind a panel that rendered with none of its cut-outs (issue #13).
 *
 * <p>Four separate readings were wrong, and each has its own test here:
 * <ul>
 *   <li>cut-outs that <b>overlap</b> cancelled each other, because the loops were handed to an
 *       even-odd fill rule; Altium draws one L- or C-shaped routed slot as two or three
 *       overlapping rectangles, and the overlap came back as board;</li>
 *   <li>a chain that never <b>closed</b> was force-closed and filled, turning a routed slot into
 *       a solid wedge;</li>
 *   <li>only the <b>first</b> profile layer was read, and a set routinely ships two or three —
 *       with the edge on one file and its cut-outs on another;</li>
 *   <li>a loop nested inside the board edge was always a cut-out, even when it was the outline
 *       of one board on a panel, with all of that board's <b>copper</b> inside it.</li>
 * </ul>
 *
 * <p>The geometry is synthetic and shares only the <em>shape</em> of the reported case. It is
 * drawn with straight lines so the resulting clip path can be evaluated as an AWT shape.
 */
public class ProfileCutoutRealisticTest {

    private static final String HEADER =
        "%FSLAX46Y46*%\n%MOMM*%\nG01*\nG75*\n%ADD10C,0.100*%\n%ADD11C,1.000*%\n%ADD12C,0.500*%\n";

    // FSLAX46Y46 / MOMM: 1 mm = 1e6 units.
    private static long u(double mm) {
        return Math.round(mm * 1e6);
    }

    /** A closed rectangular loop as a D02/D01 polyline, drawn with the current aperture. */
    private static String rect(double x0, double y0, double x1, double y1) {
        return "X" + u(x0) + "Y" + u(y0) + "D02*\n"
             + "X" + u(x1) + "Y" + u(y0) + "D01*\n"
             + "X" + u(x1) + "Y" + u(y1) + "D01*\n"
             + "X" + u(x0) + "Y" + u(y1) + "D01*\n"
             + "X" + u(x0) + "Y" + u(y0) + "D01*\n";
    }

    /** The same rectangle as a filled G36 region. */
    private static String regionRect(double x0, double y0, double x1, double y1) {
        return "G36*\n"
             + "X" + u(x0) + "Y" + u(y0) + "D02*\n"
             + "X" + u(x1) + "Y" + u(y0) + "D01*\n"
             + "X" + u(x1) + "Y" + u(y1) + "D01*\n"
             + "X" + u(x0) + "Y" + u(y1) + "D01*\n"
             + "X" + u(x0) + "Y" + u(y0) + "D01*\n"
             + "G37*\n";
    }

    /** The 40 x 30 mm board edge, stroked with a hairline. */
    private static final String BOARD_EDGE = "D10*\n" + rect(0, 0, 40, 30);

    /**
     * A C-shaped routed slot: three D01 strokes with a 1 mm router aperture, left open — the ends
     * are 10 mm apart, so nothing joins them but the chainer's imagination.
     */
    private static final String OPEN_C_SLOT =
        "D11*\n"
        + "X" + u(30) + "Y" + u(10) + "D02*\n"
        + "X" + u(20) + "Y" + u(10) + "D01*\n"
        + "X" + u(20) + "Y" + u(20) + "D01*\n"
        + "X" + u(28) + "Y" + u(20) + "D01*\n";

    @Test
    void overlappingCutoutsUnionInsteadOfCancelling() throws Exception {
        // An L-shaped opening, drawn the way Altium draws one: two rectangles that share a corner
        // square. Under even-odd that square is covered twice and comes back as board.
        Shape board = boardShape(HEADER + BOARD_EDGE
            + regionRect(10, 10, 20, 12)
            + regionRect(10, 10, 12, 20)
            + "M02*\n");

        assertFalse(board.contains(11.0, 11.0),
            "the corner both rectangles cover is still a cut-out, not board");
        assertFalse(board.contains(16.0, 11.0), "the arm of the L is a cut-out");
        assertFalse(board.contains(11.0, 16.0), "the other arm of the L is a cut-out");
        assertTrue(board.contains(30.0, 20.0), "the rest of the board is board");
    }

    @Test
    void aCutoutDrawnTwiceStillCuts() throws Exception {
        // A profile layer that emits the same opening twice. The duplicate used to cancel it.
        Shape board = boardShape(HEADER + BOARD_EDGE
            + regionRect(10, 10, 20, 20)
            + regionRect(10, 10, 20, 20)
            + "M02*\n");

        assertFalse(board.contains(15.0, 15.0), "the doubled opening is one cut-out, not none");
        assertTrue(board.contains(30.0, 20.0), "the rest of the board is board");
    }

    @Test
    void anOpenRoutedChainCutsItsOwnWidth() throws Exception {
        // The chain does not close, so it is a route, not a loop: what it removes is the 1 mm
        // aperture swept along it. Force-closing it filled the whole C in as a wedge.
        Shape board = boardShape(HEADER + BOARD_EDGE + OPEN_C_SLOT + "M02*\n");

        assertTrue(board.contains(25.0, 15.0),
            "the tab the C encloses is still board — the slot is 1 mm wide, not solid");
        assertFalse(board.contains(20.0, 15.0), "the slot itself is cut");
        assertFalse(board.contains(25.0, 10.0), "...along its whole length");
        assertTrue(board.contains(35.0, 15.0), "and the board beyond it is untouched");
    }

    @Test
    void anEdgeOnOneLayerTakesItsCutoutsFromAnother() throws Exception {
        // The reported case: the panel edge on .GKO, the routed openings on .GM. Reading only the
        // first profile layer rendered the board with no cut-outs at all.
        GerberDocument edgeOnly = parse(HEADER + BOARD_EDGE + "M02*\n");
        GerberDocument edgeAndCutout = parse(HEADER + BOARD_EDGE
            + regionRect(10, 10, 20, 20) + "M02*\n");

        Shape board = boardShape(List.of(
            outline("edge.gko", edgeOnly),
            outline("mech.gm", edgeAndCutout),
            copperAt(30, 20)));

        assertFalse(board.contains(15.0, 15.0), "the sibling layer's opening must be cut");
        assertTrue(board.contains(30.0, 20.0), "the board itself is intact");
        assertTrue(board.contains(2.0, 2.0), "including its corners");
    }

    @Test
    void theSameCutoutDescribedOnBothLayersIsNotFilledBackIn() throws Exception {
        // A tool that writes the profile to two files describes the slot on both — stroked on one,
        // filled as regions on the other. Neither is a duplicate of the other, so each read as
        // nested inside its twin and the second filled the first's slot back in.
        GerberDocument stroked = parse(HEADER + BOARD_EDGE + OPEN_C_SLOT + "M02*\n");
        GerberDocument filled = parse(HEADER + BOARD_EDGE
            + regionRect(19.5, 9.5, 30.5, 10.5)
            + regionRect(19.5, 9.5, 20.5, 20.5)
            + regionRect(19.5, 19.5, 28.5, 20.5)
            + "M02*\n");

        Shape board = boardShape(List.of(
            outline("edge.gko", stroked),
            outline("mech.gm", filled),
            copperAt(35, 25)));

        assertFalse(board.contains(20.0, 15.0), "the slot is cut once, not cut and filled again");
        assertTrue(board.contains(25.0, 15.0), "and the tab it encloses is still board");
    }

    @Test
    void aDrawingSheetDoesNotBecomeTheBoard() throws Exception {
        // A mechanical layer carrying the fab drawing's frame holds the copper just as well as the
        // board edge does, and is not the board. The tighter of the two is.
        GerberDocument edge = parse(HEADER + BOARD_EDGE + "M02*\n");
        GerberDocument sheet = parse(HEADER + "D10*\n" + rect(-10, -10, 50, 40) + "M02*\n");

        Shape board = boardShape(List.of(
            outline("sheet.gm1", sheet),
            outline("edge.gko", edge),
            copperAt(20, 15)));

        assertTrue(board.contains(20.0, 15.0), "the board is board");
        assertFalse(board.contains(45.0, 35.0), "the drawing frame is not");
    }

    @Test
    void aLoopFullOfCopperIsABoardOutlineAndNotACutout() throws Exception {
        // An Altium panel draws the panel edge and then each individual board's keep-out inside
        // it. Read as cut-outs those boards become holes and only the panel rail renders.
        String panel = HEADER + "D10*\n" + rect(0, 0, 100, 60) + rect(10, 10, 45, 50) + "M02*\n";

        Shape withCopper = boardShape(List.of(
            outline("panel.gko", parse(panel)),
            copperAt(20, 20, 25, 25, 30, 30, 35, 35, 40, 40)));
        assertTrue(withCopper.contains(25.0, 30.0),
            "a loop with the board's own copper in it is that board, not a hole in the panel");
        assertTrue(withCopper.contains(70.0, 30.0), "and the panel around it is still panel");

        // Copper is what decides it. With none to consult, nesting alone still says cut-out.
        Shape noCopper = boardShape(List.of(outline("panel.gko", parse(panel))));
        assertFalse(noCopper.contains(25.0, 30.0),
            "with no copper to judge by, a nested loop is read as a cut-out as before");
    }

    // --- helpers ------------------------------------------------------------------------

    private static GerberDocument parse(String gerber) {
        return new GerberParser().parse(gerber);
    }

    private static MultiLayerSVGRenderer.Layer outline(String name, GerberDocument doc) {
        return new MultiLayerSVGRenderer.Layer(name, doc).setLayerType(LayerType.OUTLINE);
    }

    /** A copper layer with a 0.5 mm flash at each (x, y) pair given. */
    private static MultiLayerSVGRenderer.Layer copperAt(double... xy) {
        StringBuilder g = new StringBuilder(HEADER).append("D12*\n");
        for (int i = 0; i + 1 < xy.length; i += 2) {
            g.append(String.format(Locale.US, "X%dY%dD03*%n", u(xy[i]), u(xy[i + 1])));
        }
        return new MultiLayerSVGRenderer.Layer("copper.gtl", parse(g.append("M02*\n").toString()))
            .setLayerType(LayerType.COPPER_TOP);
    }

    private static Shape boardShape(String outlineGerber) throws Exception {
        return boardShape(List.of(outline("outline", parse(outlineGerber))));
    }

    /** Render realistically and read the board-outline clip path back as a shape. */
    private static Shape boardShape(List<MultiLayerSVGRenderer.Layer> layers) throws Exception {
        String svg = new MultiLayerSVGRenderer().renderRealistic(new ArrayList<>(layers));
        Matcher m = Pattern.compile(
            "<clipPath id=\"board-outline\">\\s*<path d=\"([^\"]*)\"([^/]*)/>").matcher(svg);
        assertTrue(m.find(), "realistic SVG should define a board-outline clipPath");
        assertTrue(m.group(2).contains("clip-rule=\"nonzero\""),
            "the outline arrives resolved, so it clips under nonzero: " + m.group(2));

        AWTPathProducer producer = new AWTPathProducer();
        producer.setWindingRule(Path2D.WIND_NON_ZERO);
        PathParser parser = new PathParser();
        parser.setPathHandler(producer);
        parser.parse(m.group(1));
        return producer.getShape();
    }
}
