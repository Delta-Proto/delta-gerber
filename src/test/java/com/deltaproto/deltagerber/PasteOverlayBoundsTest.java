package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the invariant the dp-3 paste-overlay feature relies on: {@link
 * MultiLayerSVGRenderer#renderRealistic} and {@link MultiLayerSVGRenderer#render} emit
 * the SAME viewBox for the same layer list, so a paste layer rendered on its own (via
 * render() over the same set, with non-paste layers hidden) overlays the realistic view
 * pixel-perfectly.
 *
 * <p>renderRealistic was changed to size its viewBox to the union of ALL input layers'
 * bounds (matching render()) instead of the board-outline bbox. These tests use a copper
 * pad placed OUTSIDE the outline so the union is strictly larger than the outline — under
 * the old outline-only logic the viewBoxes would differ and these tests would fail.
 */
public class PasteOverlayBoundsTest {

    private static final GerberParser parser = new GerberParser();

    /** Rectangle outline 10..90 x 10..60 mm (FSLAX44Y44 MM, 1 unit = 0.1 µm). */
    private static String rectOutline() {
        return "%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\nD10*\n"
                + "X100000Y100000D02*\n"
                + "X900000Y100000D01*\n"
                + "X900000Y600000D01*\n"
                + "X100000Y600000D01*\n"
                + "X100000Y100000D01*\n"
                + "M02*\n";
    }

    /** A single 1 mm round pad flashed at (px, py) in 0.1 µm units. */
    private static String pad(int px, int py) {
        return "%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,1.0000*%\nD10*\n"
                + "X" + px + "Y" + py + "D03*\n"
                + "M02*\n";
    }

    private static String viewBox(String svg) {
        int i = svg.indexOf("viewBox=\"");
        assertTrue(i >= 0, "svg must carry a viewBox");
        return svg.substring(i + 9, svg.indexOf('"', i + 9));
    }

    private static List<MultiLayerSVGRenderer.Layer> outlineCopperPaste() {
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", parser.parse(rectOutline()))
                .setLayerType(LayerType.OUTLINE));
        // Copper pad at x=95 mm — OUTSIDE the 10..90 mm outline, so the union exceeds it.
        layers.add(new MultiLayerSVGRenderer.Layer("copper", parser.parse(pad(950000, 350000)))
                .setLayerType(LayerType.COPPER_TOP));
        // Paste pad well inside the board.
        layers.add(new MultiLayerSVGRenderer.Layer("paste", parser.parse(pad(500000, 350000)))
                .setLayerType(LayerType.PASTE_TOP));
        return layers;
    }

    @Test
    void realisticViewBoxMatchesMultiLayerForSameLayers() {
        List<MultiLayerSVGRenderer.Layer> layers = outlineCopperPaste();

        String realistic = new MultiLayerSVGRenderer().renderRealistic(layers);
        String multi = new MultiLayerSVGRenderer().render(layers);

        assertEquals(viewBox(multi), viewBox(realistic),
                "renderRealistic and render must share a viewBox so overlays align");

        // Proves the viewBox is the union (incl. the 95 mm pad), not the ~80 mm-wide outline.
        double width = Double.parseDouble(viewBox(realistic).split(" ")[2]);
        assertTrue(width > 85.0,
                "viewBox width should span the union incl. the 95 mm pad, got " + width);
    }

    @Test
    void pasteOnlyOverlaySharesRealisticViewBoxAndIsGrey() {
        // Mirror the backend worker (PcbRenderWorker#pasteOverlaySvg): render the full
        // layer set but with only the paste layer visible, in grey.
        List<MultiLayerSVGRenderer.Layer> all = outlineCopperPaste();
        String realistic = new MultiLayerSVGRenderer().renderRealistic(all);

        for (MultiLayerSVGRenderer.Layer l : all) {
            boolean isPaste = l.getLayerType() == LayerType.PASTE_TOP
                    || l.getLayerType() == LayerType.PASTE_BOTTOM;
            l.setVisible(isPaste);
            if (isPaste) {
                l.setColor("#888888");
                l.setOpacity(1.0);
            }
        }
        String paste = new MultiLayerSVGRenderer().render(all);

        assertEquals(viewBox(realistic), viewBox(paste),
                "paste-only overlay must share the realistic viewBox (bounds use all layers)");
        assertTrue(paste.contains("#888888"), "paste overlay should render in grey #888888");
    }

    @Test
    void inverseSharesRealisticViewBoxAndBuildsKnockoutSheet() throws Exception {
        List<MultiLayerSVGRenderer.Layer> all = outlineCopperPaste();
        String realistic = new MultiLayerSVGRenderer().renderRealistic(all);

        // Same paste-overlay layer setup the backend worker uses (only paste visible).
        for (MultiLayerSVGRenderer.Layer l : all) {
            boolean isPaste = l.getLayerType() == LayerType.PASTE_TOP
                    || l.getLayerType() == LayerType.PASTE_BOTTOM;
            l.setVisible(isPaste);
            if (isPaste) {
                l.setColor("#888888");
                l.setOpacity(1.0);
            }
        }
        String inverse = new MultiLayerSVGRenderer().renderInverse(all, "#888888");

        assertEquals(viewBox(realistic), viewBox(inverse),
                "inverse overlay must share the realistic viewBox");
        assertTrue(inverse.contains("<mask id=\"layer-knockout\""), "should build a knockout mask");
        assertTrue(inverse.contains("mask=\"url(#layer-knockout)\""), "sheet rect should use the knockout mask");
        assertTrue(inverse.contains("fill=\"white\""), "mask needs a white full-bounds rect (the sheet)");
        assertTrue(inverse.contains("#888888"), "sheet should be grey");

        // Persist for the rasterisation check in the build script.
        Path out = Path.of("target/paste-inverse.svg");
        Files.createDirectories(out.getParent());
        Files.writeString(out, inverse);
    }

    @Test
    void rasterFramesToOutlineWhileSvgFramesToUnion() {
        // Outline 10..90 mm wide; a silkscreen flash at x=300 mm — far off-board. It's
        // clipped out of the realistic view, but would dominate a union-framed viewBox.
        List<MultiLayerSVGRenderer.Layer> all = new ArrayList<>();
        all.add(new MultiLayerSVGRenderer.Layer("outline", parser.parse(rectOutline()))
                .setLayerType(LayerType.OUTLINE));
        all.add(new MultiLayerSVGRenderer.Layer("copper", parser.parse(pad(500000, 350000)))
                .setLayerType(LayerType.COPPER_TOP));
        all.add(new MultiLayerSVGRenderer.Layer("silk", parser.parse(pad(3000000, 350000)))
                .setLayerType(LayerType.SILKSCREEN_TOP));

        // Interactive SVG path: union bounds → viewBox spans out to the 300 mm flash, so a
        // separate paste overlay over the same set still shares this frame.
        String svg = new MultiLayerSVGRenderer().renderRealisticSide(all, MultiLayerSVGRenderer.Side.TOP);
        double svgWidth = Double.parseDouble(viewBox(svg).split(" ")[2]);
        assertTrue(svgWidth > 250, "SVG should frame to the union (incl. the 300 mm flash), got " + svgWidth);

        // Raster thumbnail path: outline bounds → the ~80 mm board fills the image and the
        // off-board flash is ignored.
        MultiLayerSVGRenderer.PngWithScale png = new MultiLayerSVGRenderer()
                .renderRealisticSidePngWithScale(all, MultiLayerSVGRenderer.Side.TOP, 200, 0, false);
        assertNotNull(png);
        assertTrue(png.widthMm < 120,
                "PNG should frame to the ~80 mm outline (plus thumb margin), not the union; got " + png.widthMm);
    }
}
