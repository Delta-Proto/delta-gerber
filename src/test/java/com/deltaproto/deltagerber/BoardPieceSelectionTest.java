package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.BoardOutline;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stack-up table, title block or drill legend drawn as closed shapes beside the board resolves
 * into a second piece of board; {@link MultiLayerSVGRenderer#resolveBoardOutline} keeps only the
 * pieces holding copper flashes. Synthetic: the two real sets this came from are customer data.
 */
class BoardPieceSelectionTest {

    private static final GerberParser parser = new GerberParser();
    private static final String HEADER = "%FSLAX44Y44*%\n%MOMM*%\nG01*\n%LPD*%\n";

    private static String rect(StringBuilder g, double x0, double y0, double x1, double y1) {
        g.append(String.format("X%dY%dD02*\nX%dY%dD01*\nX%dY%dD01*\nX%dY%dD01*\nX%dY%dD01*\n",
                u(x0), u(y0), u(x1), u(y0), u(x1), u(y1), u(x0), u(y1), u(x0), u(y0)));
        return g.toString();
    }

    private static int u(double mm) {
        return (int) Math.round(mm * 10000);
    }

    /** A 40 x 30 board with a 4 x 4 slot, and a 40 x 10 "stack-up table" frame below it. */
    private static String profileWithTable() {
        StringBuilder g = new StringBuilder(HEADER + "%ADD10C,0.1000*%\nD10*\n");
        rect(g, 0, 0, 40, 30);
        rect(g, 18, 13, 22, 17);                    // a routed cut-out in the board
        rect(g, 0, -20, 40, -10);                   // the table's frame
        return g.append("M02*\n").toString();
    }

    /** Two 40 x 30 boards side by side: a panel. */
    private static String panelProfile() {
        StringBuilder g = new StringBuilder(HEADER + "%ADD10C,0.1000*%\nD10*\n");
        rect(g, 0, 0, 40, 30);
        rect(g, 50, 0, 90, 30);
        return g.append("M02*\n").toString();
    }

    /** Pads flashed over {@code [x0, x1] x [y0, y1]}, and table "text" drawn as strokes below. */
    private static String copper(double[]... areas) {
        StringBuilder g = new StringBuilder(HEADER + "%ADD10C,1.0000*%\n%ADD11C,0.2000*%\nD10*\n");
        for (double[] a : areas) {
            for (double x = a[0]; x <= a[1]; x += 5) {
                for (double y = a[2]; y <= a[3]; y += 5) {
                    g.append(String.format("X%dY%dD03*\n", u(x), u(y)));
                }
            }
        }
        g.append("D11*\n");
        for (double x = 2; x < 38; x += 3) {         // lettering in the table: draws, not pads
            g.append(String.format("X%dY%dD02*\nX%dY%dD01*\n", u(x), u(-17), u(x + 2), u(-13)));
        }
        return g.append("M02*\n").toString();
    }

    private static String strokesOnly() {
        StringBuilder g = new StringBuilder(HEADER + "%ADD11C,0.2000*%\nD11*\n");
        g.append("X50000Y50000D02*\nX350000Y250000D01*\n");
        g.append("X50000Y-170000D02*\nX350000Y-130000D01*\n");
        return g.append("M02*\n").toString();
    }

    private static List<MultiLayerSVGRenderer.Layer> layers(String outline, String copper) {
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        if (outline != null) {
            layers.add(new MultiLayerSVGRenderer.Layer("outline", parser.parse(outline))
                    .setLayerType(LayerType.OUTLINE));
        }
        layers.add(new MultiLayerSVGRenderer.Layer("top", parser.parse(copper))
                .setLayerType(LayerType.COPPER_TOP));
        return layers;
    }

    private static Rectangle2D bounds(BoardOutline o) {
        return o.toShape().getBounds2D();
    }

    @Test
    void tableBesideTheBoardIsDropped() {
        BoardOutline o = new MultiLayerSVGRenderer()
                .resolveBoardOutline(layers(profileWithTable(), copper(new double[]{3, 37, 3, 27})));
        assertEquals(2, o.getIslandCount());
        assertEquals(1, o.getPieceCount());
        assertTrue(o.droppedPieces());
        assertFalse(o.isSelectedBySizeOnly());
        Rectangle2D b = bounds(o);
        assertEquals(0, b.getMinY(), 1e-6, "the table below y=0 must be gone");
        assertEquals(40, b.getWidth(), 1e-6);
        assertEquals(30, b.getHeight(), 1e-6);
        // The board's own cut-out goes with it.
        assertFalse(o.toShape().contains(20, 15), "the routed cut-out must stay a hole");
        assertTrue(o.toShape().contains(5, 5));
    }

    @Test
    void rasterFramesToTheBoardAlone() {
        MultiLayerSVGRenderer.PngWithScale png = new MultiLayerSVGRenderer().renderRealisticSidePngWithScale(
                layers(profileWithTable(), copper(new double[]{3, 37, 3, 27})),
                MultiLayerSVGRenderer.Side.TOP, 400, 0, false);
        assertNotNull(png);
        // Board 40 x 30 plus the thumbnail margin on each side — not 40 x 50 with the table.
        assertTrue(png.heightMm < 36, "height " + png.heightMm);
        assertTrue(png.minYmm > -5, "minY " + png.minYmm);
        assertEquals(png.widthMm * png.pxPerMm, png.widthPx, 1.0);
    }

    @Test
    void everyBoardOfAPanelIsKept() {
        BoardOutline o = new MultiLayerSVGRenderer().resolveBoardOutline(layers(panelProfile(),
                copper(new double[]{3, 37, 3, 27}, new double[]{53, 87, 3, 27})));
        assertEquals(2, o.getIslandCount());
        assertEquals(2, o.getPieceCount());
        assertFalse(o.droppedPieces());
        assertEquals(90, bounds(o).getWidth(), 1e-6);
    }

    @Test
    void withoutFlashesNothingIsDroppedAndTheGuessIsFlagged() {
        BoardOutline o = new MultiLayerSVGRenderer().resolveBoardOutline(layers(profileWithTable(), strokesOnly()));
        assertTrue(o.isSelectedBySizeOnly());
        assertFalse(o.droppedPieces());
        assertEquals(-20, bounds(o).getMinY(), 1e-6, "the table is kept when the board cannot be told apart");
    }

    @Test
    void selectionCanBeSwitchedOff() {
        MultiLayerSVGRenderer r = new MultiLayerSVGRenderer().setBoardPieceSelection(false);
        BoardOutline o = r.resolveBoardOutline(layers(profileWithTable(), copper(new double[]{3, 37, 3, 27})));
        assertEquals(-1, o.getIslandCount());
        assertEquals(-20, bounds(o).getMinY(), 1e-6);
    }

    @Test
    void derivedTitleBlockIsDropped() {
        // No profile: the edge is the copper and mask silhouette, and the title block is drawn
        // as a filled box in the soldermask, away from the board.
        String mask = HEADER + "G36*\nX0Y-300000D02*\nX400000Y-300000D01*\nX400000Y-200000D01*\n"
                + "X0Y-200000D01*\nX0Y-300000D01*\nG37*\nM02*\n";
        String pour = HEADER + "G36*\nX20000Y20000D02*\nX380000Y20000D01*\nX380000Y280000D01*\n"
                + "X20000Y280000D01*\nX20000Y20000D01*\nG37*\nM02*\n";
        List<MultiLayerSVGRenderer.Layer> layers = layers(null, copper(new double[]{3, 37, 3, 27}));
        layers.add(new MultiLayerSVGRenderer.Layer("plane", parser.parse(pour)).setLayerType(LayerType.COPPER_INNER));
        layers.add(new MultiLayerSVGRenderer.Layer("mask", parser.parse(mask)).setLayerType(LayerType.SOLDERMASK_TOP));
        BoardOutline o = new MultiLayerSVGRenderer().resolveBoardOutline(layers);
        assertTrue(o.isDerived());
        assertEquals(1, o.getPieceCount());
        assertTrue(o.droppedPieces());
        assertTrue(bounds(o).getMinY() > -5, "title block must be gone: " + bounds(o));
    }

    @Test
    void derivedFragmentsOfOneBoardAreAllKept() {
        // No profile and no pour: the silhouette falls apart into a dense pad field and one lone
        // pad 10 mm away. Both are board — only a fragment with no pad at all is dropped.
        String dense = HEADER + "%ADD10C,1.2000*%\nD10*\n";
        StringBuilder g = new StringBuilder(dense);
        for (int x = 0; x <= 200000; x += 15000) {
            for (int y = 0; y <= 200000; y += 15000) g.append("X").append(x).append("Y").append(y).append("D03*\n");
        }
        g.append("X350000Y100000D03*\nM02*\n");
        BoardOutline o = new MultiLayerSVGRenderer().resolveBoardOutline(layers(null, g.toString()));
        assertTrue(o.isDerived());
        assertFalse(o.droppedPieces(), "islands " + o.getIslandCount() + ", kept " + o.getPieceCount());
        assertTrue(o.toShape().contains(35, 10), "the lone pad must stay board");
    }

    @Test
    void aSingleBoardIsUntouched() {
        String outline = HEADER + "%ADD10C,0.1000*%\nD10*\n" + rect(new StringBuilder(), 0, 0, 40, 30) + "M02*\n";
        List<MultiLayerSVGRenderer.Layer> l = layers(outline, copper(new double[]{3, 37, 3, 27}));
        BoardOutline on = new MultiLayerSVGRenderer().resolveBoardOutline(l);
        BoardOutline off = new MultiLayerSVGRenderer().setBoardPieceSelection(false).resolveBoardOutline(l);
        assertEquals(off.getSvgPath(), on.getSvgPath());
        assertEquals(1, on.getIslandCount());
        assertEquals(1, on.getPieceCount());
    }
}
