package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer.Side;
import com.deltaproto.deltagerber.renderer.svg.SilkscreenColor;
import com.deltaproto.deltagerber.renderer.svg.SoldermaskColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The realistic render's board-finish colors: soldermask and silkscreen, chosen per side,
 * either of which may be absent on a board ordered without it.
 *
 * <p>Runs off a synthetic two-sided fixture so it never depends on customer board files.
 * Assertions target the marker classes the renderer emits ({@code pcb-soldermask} on the
 * mask rect, {@code pcb-silkscreen} on the legend group) rather than searching the whole
 * document for a hex, since FR4/copper/mask-luminance fills share the color space.
 */
public class BoardFinishColorsRealisticTest {

    private static int u(double mm) { return (int) Math.round(mm * 10000); } // FSLAX44 MM

    /** A Gerber doc with one filled rectangular region (stand-in copper/mask/legend pour). */
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

    /** Outline, copper, soldermask and silkscreen on both sides — every finish is drawable. */
    private static List<MultiLayerSVGRenderer.Layer> twoSidedBoard() {
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        for (LayerType type : new LayerType[]{
            LayerType.OUTLINE,
            LayerType.COPPER_TOP, LayerType.SOLDERMASK_TOP, LayerType.SILKSCREEN_TOP,
            LayerType.COPPER_BOTTOM, LayerType.SOLDERMASK_BOTTOM, LayerType.SILKSCREEN_BOTTOM}) {
            layers.add(new MultiLayerSVGRenderer.Layer(type.name(), filledRect(0, 0, 40, 30))
                .setLayerType(type));
        }
        return layers;
    }

    /** How many times the legend group is emitted with this exact fill. */
    private static int silkscreenGroups(String svg, String fill) {
        return countOf(svg, "class=\"pcb-silkscreen\" fill=\"" + fill + "\"");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void silkscreenDefaultsToTheColorTheMaskPairsWith() {
        String svg = new MultiLayerSVGRenderer()
            .setSoldermaskColor(SoldermaskColor.WHITE)
            .renderRealistic(twoSidedBoard());

        assertEquals(2, silkscreenGroups(svg, "#000000"),
            "white soldermask pairs with a black legend, on both sides");
    }

    @Test
    void silkscreenOverrideBeatsTheMaskPairingRegardlessOfCallOrder() {
        List<MultiLayerSVGRenderer.Layer> board = twoSidedBoard();

        // White mask would pair with black; yellow ink must win either way round.
        String silkLast = new MultiLayerSVGRenderer()
            .setSoldermaskColor(SoldermaskColor.WHITE)
            .setSilkscreenColor(SilkscreenColor.YELLOW)
            .renderRealistic(board);
        String maskLast = new MultiLayerSVGRenderer()
            .setSilkscreenColor(SilkscreenColor.YELLOW)
            .setSoldermaskColor(SoldermaskColor.WHITE)
            .renderRealistic(board);

        assertEquals(2, silkscreenGroups(silkLast, "#ffdd00"));
        assertEquals(0, silkscreenGroups(silkLast, "#000000"),
            "an explicit legend color must not be re-paired by the mask");
        assertEquals(silkLast, maskLast, "the two setters must commute");
    }

    @Test
    void eachSideCarriesItsOwnMaskAndLegend() {
        String svg = new MultiLayerSVGRenderer()
            .setSoldermaskColor(Side.TOP, SoldermaskColor.GREEN)
            .setSoldermaskColor(Side.BOTTOM, SoldermaskColor.BLACK)
            .setSilkscreenColor(Side.TOP, SilkscreenColor.WHITE)
            .setSilkscreenColor(Side.BOTTOM, SilkscreenColor.YELLOW)
            .renderRealistic(twoSidedBoard());

        assertTrue(svg.contains("#004200"), "top keeps the realistic green mask");
        assertTrue(svg.contains("#0f1010"), "bottom takes the black mask");
        assertEquals(1, silkscreenGroups(svg, "#ffffff"), "white legend on top only");
        assertEquals(1, silkscreenGroups(svg, "#ffdd00"), "yellow legend on the bottom only");
    }

    @Test
    void noSoldermaskLeavesTheCopperBareButStillPrintsTheLegend() {
        String svg = new MultiLayerSVGRenderer()
            .setSoldermaskColor(SoldermaskColor.NONE)
            .renderRealistic(twoSidedBoard());

        assertFalse(svg.contains("class=\"pcb-soldermask\""),
            "a board ordered without soldermask must draw no mask sheet");
        assertEquals(2, silkscreenGroups(svg, "#ffffff"),
            "a legend printed straight onto the laminate is still white");
    }

    @Test
    void noSilkscreenPrintsNoLegendEvenWhenTheFilesAreSupplied() {
        String svg = new MultiLayerSVGRenderer()
            .setSilkscreenColor(SilkscreenColor.NONE)
            .renderRealistic(twoSidedBoard());

        assertFalse(svg.contains("class=\"pcb-silkscreen\""),
            "a board ordered without a legend must draw none, whatever files it ships");
        assertTrue(svg.contains("class=\"pcb-soldermask\""), "the mask is untouched");
    }

    @Test
    void neitherFinishOnOneSideDrawsNothingForThatSide() {
        String svg = new MultiLayerSVGRenderer()
            .setSoldermaskColor(Side.BOTTOM, SoldermaskColor.NONE)
            .setSilkscreenColor(Side.BOTTOM, SilkscreenColor.NONE)
            .renderRealistic(twoSidedBoard());

        assertEquals(1, countOf(svg, "class=\"pcb-soldermask\""), "top mask only");
        assertEquals(1, countOf(svg, "class=\"pcb-silkscreen\""), "top legend only");
        assertFalse(svg.contains("url(#sm-bottom-mask)"),
            "the bare bottom side should not open a soldermask group at all");
    }

    @Test
    void perSideColorsSurviveTheSideAndPngRenderPaths() {
        // renderRealisticSide filters to one side, so the bottom's colors must be the ones
        // baked when the bottom is rendered on its own (this is the PNG thumbnail path).
        String bottom = new MultiLayerSVGRenderer()
            .setSoldermaskColor(Side.TOP, SoldermaskColor.GREEN)
            .setSoldermaskColor(Side.BOTTOM, SoldermaskColor.RED)
            .renderRealisticSide(twoSidedBoard(), Side.BOTTOM);

        assertTrue(bottom.contains("#bf0100"), "the bottom's red mask must reach the side render");
        assertFalse(bottom.contains("#004200"), "the top's green must not leak onto the bottom");
    }

    @Test
    void explicitHexFillsAreSanitizedBeforeReachingTheDocument() {
        String svg = new MultiLayerSVGRenderer()
            .setSoldermaskColor("#004200\" onload=\"alert(1)", "#ffffff")
            .renderRealistic(twoSidedBoard());

        assertFalse(svg.contains("onload"), "an untrusted fill must not break out of the attribute");
    }
}
