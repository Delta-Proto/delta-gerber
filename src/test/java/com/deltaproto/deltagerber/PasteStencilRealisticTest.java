package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer.Side;
import com.deltaproto.deltagerber.web.GerberViewerServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The realistic view carries the side's solder paste twice, hidden: as paste over the board and
 * as a stencil — a square foil {@link MultiLayerSVGRenderer#STENCIL_MARGIN_MM} beyond the board
 * with the apertures cut out. The viewer's paste row shows one or the other; a PNG thumbnail,
 * rasterised from the same SVG, shows neither.
 */
public class PasteStencilRealisticTest {

    private static int u(double mm) { return (int) Math.round(mm * 10000); } // FSLAX44 MM

    private static GerberDocument filledRect(double x0, double y0, double x1, double y1) {
        String g = "G04 synthetic*\n%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\n"
            + "G36*\n"
            + "X" + u(x0) + "Y" + u(y0) + "D02*\n"
            + "X" + u(x1) + "Y" + u(y0) + "D01*\n"
            + "X" + u(x1) + "Y" + u(y1) + "D01*\n"
            + "X" + u(x0) + "Y" + u(y1) + "D01*\n"
            + "X" + u(x0) + "Y" + u(y0) + "D01*\n"
            + "G37*\nM02*\n";
        return new GerberParser().parse(g);
    }

    /** A 40 × 30 board with copper on both sides and paste on the top only. */
    private static List<MultiLayerSVGRenderer.Layer> board() {
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        for (LayerType type : new LayerType[]{LayerType.OUTLINE, LayerType.COPPER_TOP, LayerType.COPPER_BOTTOM}) {
            layers.add(new MultiLayerSVGRenderer.Layer(type.name(), filledRect(0, 0, 40, 30)).setLayerType(type));
        }
        layers.add(new MultiLayerSVGRenderer.Layer("paste", filledRect(10, 10, 12, 12)).setLayerType(LayerType.PASTE_TOP));
        return layers;
    }

    private static double attr(String tag, String name) {
        Matcher m = Pattern.compile(name + "=\"(-?[0-9.]+)\"").matcher(tag);
        assertTrue(m.find(), name + " on " + tag);
        return Double.parseDouble(m.group(1));
    }

    @Test
    void topSideCarriesHiddenPasteAndAStencilSheetAroundTheBoard() {
        String svg = new MultiLayerSVGRenderer().renderRealisticSide(board(), Side.TOP);

        assertTrue(svg.contains("<g class=\"pcb-paste\" display=\"none\""), "paste group, hidden");
        assertTrue(svg.contains("<g class=\"pcb-stencil\" display=\"none\""), "stencil group, hidden");
        assertTrue(svg.contains("<mask id=\"stencil-mask\" maskUnits=\"userSpaceOnUse\""), "stencil mask");
        assertTrue(Pattern.compile("<use href=\"#L\\d+_paste\" color=\"black\"/>").matcher(svg).find(),
            "the apertures are cut out of the sheet by the same artwork, in black");

        Matcher sheet = Pattern.compile("<rect ([^>]*) fill=\"#[0-9a-f]{6}\" mask=\"url\\(#stencil-mask\\)\"/>").matcher(svg);
        assertTrue(sheet.find(), "the stencil is a masked rect");
        String rect = sheet.group(1);
        double side = Math.max(40, 30) + 2 * MultiLayerSVGRenderer.STENCIL_MARGIN_MM;
        assertEquals(side, attr(rect, "width"), 1e-6, "square, the longer side plus the margin on both ends");
        assertEquals(side, attr(rect, "height"), 1e-6);
        assertEquals(20 - side / 2, attr(rect, "x"), 1e-6, "centred on the board");
        assertEquals(15 - side / 2, attr(rect, "y"), 1e-6);
    }

    @Test
    void aSideWithoutPasteCarriesNeither() {
        String svg = new MultiLayerSVGRenderer().renderRealisticSide(board(), Side.BOTTOM);

        assertFalse(svg.contains("pcb-paste"));
        assertFalse(svg.contains("pcb-stencil"));
        assertFalse(svg.contains("stencil-mask"));
    }

    @Test
    void theViewerServerPassesTheSidesPasteThrough() {
        assertTrue(GerberViewerServer.renderRealisticSide(board(), true).contains("class=\"pcb-paste\""));
        assertFalse(GerberViewerServer.renderRealisticSide(board(), false).contains("class=\"pcb-paste\""));
    }
}
