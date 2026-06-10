package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import com.deltaproto.deltagerber.web.GerberViewerServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gating for the web viewer's realistic tabs / PNG buttons. The frontend enables
 * "Board Top/Bottom" and "PNG Top/Bottom" only when
 * {@link GerberViewerServer#renderRealisticSide} returns a non-null SVG for that side.
 *
 * <p>Since {@code renderRealistic} can derive a board edge from copper when no profile
 * (OUTLINE) layer is present, that gate must light up for copper-bearing sides too — but
 * still stay dark when there is nothing to derive an outline from, or too little content.
 */
public class RealisticViewGatingTest {

    private static int u(double mm) { return (int) Math.round(mm * 10000); } // FSLAX44 MM

    /** A Gerber doc with one filled rectangular region (stand-in copper/soldermask pour). */
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

    private static MultiLayerSVGRenderer.Layer layer(String name, LayerType type) {
        return new MultiLayerSVGRenderer.Layer(name, filledRect(0, 0, 40, 30)).setLayerType(type);
    }

    @Test
    void copperOnlySideLightsUpEvenWithoutOutlineLayer() {
        // No OUTLINE layer — top copper + top soldermask. The edge is derived from copper,
        // so the top tab/PNG must enable.
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(layer("top-copper", LayerType.COPPER_TOP));
        layers.add(layer("top-mask", LayerType.SOLDERMASK_TOP));

        assertNotNull(GerberViewerServer.renderRealisticSide(layers, true),
            "copper-bearing top side should produce a realistic view via the derived outline");
    }

    @Test
    void realOutlineLayerStillLightsUp() {
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(layer("outline", LayerType.OUTLINE));
        layers.add(layer("top-copper", LayerType.COPPER_TOP));

        assertNotNull(GerberViewerServer.renderRealisticSide(layers, true));
    }

    @Test
    void sideWithNeitherOutlineNorCopperStaysDark() {
        // Silkscreen + soldermask, no copper and no profile: nothing to derive an edge from.
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(layer("top-silk", LayerType.SILKSCREEN_TOP));
        layers.add(layer("top-mask", LayerType.SOLDERMASK_TOP));

        assertNull(GerberViewerServer.renderRealisticSide(layers, true),
            "with no outline and no copper, the realistic view must stay disabled");
    }

    @Test
    void insufficientContentStaysDark() {
        // A single copper layer alone is not enough content for a realistic view.
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(layer("top-copper", LayerType.COPPER_TOP));

        assertNull(GerberViewerServer.renderRealisticSide(layers, true));
    }

    @Test
    void topOnlyBoardDoesNotLightUpBottom() {
        // Only top-side layers present: the bottom tab/PNG must stay disabled.
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(layer("top-copper", LayerType.COPPER_TOP));
        layers.add(layer("top-mask", LayerType.SOLDERMASK_TOP));

        assertNull(GerberViewerServer.renderRealisticSide(layers, false),
            "no bottom-side content should keep the bottom realistic view disabled");
    }
}
