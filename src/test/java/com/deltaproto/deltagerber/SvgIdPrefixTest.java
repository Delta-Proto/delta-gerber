package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MultiLayerSVGRenderer#setIdPrefix}: two documents inlined into one HTML page share one
 * id space. The realistic board and a paste overlay from {@code render} over the same set both
 * name apertures {@code L<n>_ap<dcode>} but count {@code n} differently, so with the paste file
 * first in the list the overlay's {@code #L0_ap10} resolved to the board's copper aperture — a
 * 5 mm disc drawn where a 1 mm paste pad belongs.
 */
public class SvgIdPrefixTest {

    private static final GerberParser parser = new GerberParser();

    private static final Pattern ID = Pattern.compile("\\sid=\"([^\"]+)\"");
    private static final Pattern REF = Pattern.compile("href=\"#([^\"]+)\"|url\\(#([^)]+)\\)");

    private static String rectOutline() {
        return "%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\nD10*\n"
                + "X100000Y100000D02*\nX900000Y100000D01*\nX900000Y600000D01*\n"
                + "X100000Y600000D01*\nX100000Y100000D01*\nM02*\n";
    }

    /** D10 is a {@code diameter} disc; flashed dark at (px, py), then a clear flash beside it. */
    private static String layer(String diameter, int px, int py) {
        return "%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C," + diameter + "*%\n"
                + "%AMTHERM*7,0,0,2.0,1.5,0.3,0*%\n%ADD11THERM*%\n"
                + "%LPD*%\nD10*\nX" + px + "Y" + py + "D03*\n"
                + "D11*\nX" + (px + 100000) + "Y" + py + "D03*\n"
                + "%LPC*%\nD10*\nX" + (px + 20000) + "Y" + py + "D03*\n"
                + "M02*\n";
    }

    private static String drill() {
        return "M48\nMETRIC\nT1C0.800\n%\nT1\nX30.0Y30.0\nM30\n";
    }

    /** Paste first, as in the production bug; copper's D10 is 5 mm, paste's is 1 mm. */
    private static List<MultiLayerSVGRenderer.Layer> pasteFirst() {
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("paste top.gbr", parser.parse(layer("1.0000", 500000, 350000)))
                .setLayerType(LayerType.PASTE_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("copper", parser.parse(layer("5.0000", 400000, 350000)))
                .setLayerType(LayerType.COPPER_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("mask", parser.parse(layer("1.2000", 500000, 350000)))
                .setLayerType(LayerType.SOLDERMASK_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("outline", parser.parse(rectOutline()))
                .setLayerType(LayerType.OUTLINE));
        layers.add(new MultiLayerSVGRenderer.Layer("drill", new ExcellonParser().parse(drill()))
                .setLayerType(LayerType.DRILL));
        return layers;
    }

    private static Set<String> ids(String svg) {
        Set<String> ids = new HashSet<>();
        Matcher m = ID.matcher(svg);
        while (m.find()) assertTrue(ids.add(m.group(1)), "duplicate id " + m.group(1));
        return ids;
    }

    private static Set<String> refs(String svg) {
        Set<String> refs = new HashSet<>();
        Matcher m = REF.matcher(svg);
        while (m.find()) refs.add(m.group(1) != null ? m.group(1) : m.group(2));
        return refs;
    }

    private static void assertSelfContained(String svg, String what) {
        Set<String> ids = ids(svg);
        for (String ref : refs(svg)) {
            assertTrue(ids.contains(ref), what + ": reference #" + ref + " has no id in this document");
        }
    }

    private static List<MultiLayerSVGRenderer.Layer> pasteOnly(List<MultiLayerSVGRenderer.Layer> all) {
        for (MultiLayerSVGRenderer.Layer l : all) {
            boolean paste = l.getLayerType() == LayerType.PASTE_TOP;
            l.setVisible(paste);
            if (paste) l.setColor("#888888").setOpacity(1.0);
        }
        return all;
    }

    @Test
    void unprefixedDocumentsCollide() {
        // The bug this option exists for: without a prefix the two documents share aperture ids.
        String board = new MultiLayerSVGRenderer().renderRealistic(pasteFirst());
        String overlay = new MultiLayerSVGRenderer().render(pasteOnly(pasteFirst()));
        Set<String> shared = new HashSet<>(ids(board));
        shared.retainAll(ids(overlay));
        assertTrue(shared.contains("L0_ap10"), "expected the L0_ap10 collision, shared: " + shared);
    }

    @Test
    void prefixedOverlayIsDisjointFromBoardAndResolvesInternally() {
        String board = new MultiLayerSVGRenderer().renderRealistic(pasteFirst());

        for (boolean inverse : new boolean[] {false, true}) {
            MultiLayerSVGRenderer r = new MultiLayerSVGRenderer().setIdPrefix("paste-");
            List<MultiLayerSVGRenderer.Layer> layers = pasteOnly(pasteFirst());
            String overlay = inverse ? r.renderInverse(layers, "#888888") : r.render(layers);
            String what = inverse ? "renderInverse" : "render";

            Set<String> overlayIds = ids(overlay);
            assertFalse(overlayIds.isEmpty());
            for (String id : overlayIds) {
                assertTrue(id.startsWith("paste-"), what + ": unprefixed id " + id);
            }
            Set<String> shared = new HashSet<>(ids(board));
            shared.retainAll(overlayIds);
            assertTrue(shared.isEmpty(), what + ": ids shared with the board: " + shared);

            assertSelfContained(overlay, what);
            // The paste pad must reference the paste layer's own 1 mm aperture.
            assertTrue(overlay.contains("<circle id=\"paste-L0_ap10\" cx=\"0\" cy=\"0\" r=\"0.500000\""),
                    what + ": paste aperture def missing");
        }
        assertSelfContained(board, "renderRealistic");
    }

    @Test
    void prefixCoversRealisticViewAndItsMirroredSide() {
        String board = new MultiLayerSVGRenderer().setIdPrefix("b_").renderRealistic(pasteFirst());
        assertSelfContained(board, "renderRealistic");
        for (String id : ids(board)) assertTrue(id.startsWith("b_"), "unprefixed id " + id);
        assertTrue(board.contains("clip-path=\"url(#b_board-outline)\""));
        assertTrue(board.contains("mask=\"url(#b_stencil-mask)\""));

        String bottom = new MultiLayerSVGRenderer().setIdPrefix("b_")
                .renderRealisticSide(pasteFirst(), MultiLayerSVGRenderer.Side.TOP, true);
        assertNotNull(bottom);
        // Mirroring finds the viewport group by its (prefixed) id; on BOTTOM it adds a flip.
        List<MultiLayerSVGRenderer.Layer> bottomSet = new ArrayList<>();
        for (MultiLayerSVGRenderer.Layer l : pasteFirst()) {
            if (l.getLayerType() == LayerType.COPPER_TOP) l.setLayerType(LayerType.COPPER_BOTTOM);
            bottomSet.add(l);
        }
        String mirrored = new MultiLayerSVGRenderer().setIdPrefix("b_")
                .renderRealisticSide(bottomSet, MultiLayerSVGRenderer.Side.BOTTOM, true);
        assertNotNull(mirrored);
        assertTrue(mirrored.contains("<g id=\"b_viewport\" transform=\"translate("),
                "bottom side should still be mirrored with a prefix");
        assertTrue(mirrored.contains("scale(-1,1)"), "bottom side should be mirrored");

        byte[] png = new MultiLayerSVGRenderer().setIdPrefix("b_")
                .renderRealisticSidePng(pasteFirst(), MultiLayerSVGRenderer.Side.TOP, 200);
        assertNotNull(png);
        assertTrue(png.length > 0);
    }

    @Test
    void noPrefixIsByteIdentical() {
        List<MultiLayerSVGRenderer.Layer> layers = pasteFirst();
        MultiLayerSVGRenderer plain = new MultiLayerSVGRenderer();
        MultiLayerSVGRenderer cleared = new MultiLayerSVGRenderer().setIdPrefix("x").setIdPrefix(null);
        MultiLayerSVGRenderer empty = new MultiLayerSVGRenderer().setIdPrefix("");
        for (MultiLayerSVGRenderer r : List.of(cleared, empty)) {
            assertEquals(plain.render(layers), r.render(layers));
            assertEquals(plain.renderInverse(layers, "#888888"), r.renderInverse(layers, "#888888"));
            assertEquals(plain.renderRealistic(layers), r.renderRealistic(layers));
        }
        // The per-layer group id stays the sanitised file name when no prefix is set.
        assertTrue(plain.render(layers).contains("<g class=\"layer\" id=\"paste_top.gbr\""));
    }

    @Test
    void layerGroupIdIsPrefixedFileName() {
        String svg = new MultiLayerSVGRenderer().setIdPrefix("ov-").render(pasteFirst());
        assertTrue(svg.contains("<g class=\"layer\" id=\"ov-paste_top.gbr\""));
    }

    @Test
    void rejectsPrefixThatIsNotAnId() {
        MultiLayerSVGRenderer r = new MultiLayerSVGRenderer();
        for (String bad : new String[] {"1a", "-a", "a b", "a\"", "a#", "a)"}) {
            assertThrows(IllegalArgumentException.class, () -> r.setIdPrefix(bad), bad);
        }
    }
}
