package com.deltaproto.deltagerber.renderer.svg;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every layer a set can contain gets a colour of its own: two layers in one colour are two
 * layers a reader confuses. KiCad's own default theme repeats its silkscreen colours on In7/In8,
 * which is the one place this palette departs from it. And every colour reads on the canvas the
 * viewer draws it on, which is KiCad's in both themes for that reason.
 */
class LayerPaletteTest {

    /** The viewer's layer canvas in both themes: KiCad's board background, {@code --bg-viewer}. */
    private static final String CANVAS = "#001023";

    @Test
    void everyTypeAndEveryInnerLayerHasItsOwnColor() {
        Map<String, String> owners = new HashMap<>();
        for (Map.Entry<String, String> e : palette().entrySet()) {
            assertTrue(e.getValue().matches("#[0-9a-f]{6}"), e.getKey() + " -> " + e.getValue());
            String previous = owners.put(e.getValue(), e.getKey());
            assertNull(previous, e.getKey() + " shares " + e.getValue() + " with " + previous);
        }
    }

    @Test
    void everyColorReadsOnTheCanvas() throws IOException {
        // WCAG contrast; 3:1 is the floor for graphics. The palette is KiCad's and is only at
        // home on KiCad's near-black canvas — F.SilkS on a light grey is 1.0, invisible — which is
        // why the viewer keeps that canvas in both of its themes.
        for (Map.Entry<String, String> e : palette().entrySet()) {
            assertTrue(contrast(e.getValue(), CANVAS) >= 3.0, e.getKey() + " " + e.getValue());
        }
        assertTrue(contrast(LayerPalette.colorOf(LayerType.SILKSCREEN_TOP), "#e8e8e8") < 1.5);

        String page;
        try (InputStream in = LayerPaletteTest.class.getResourceAsStream("/web/index.html")) {
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("--bg-viewer:\\s*(#[0-9a-fA-F]{6})").matcher(page);
        int themes = 0;
        while (m.find()) {
            themes++;
            assertEquals(CANVAS, m.group(1).toLowerCase(), "theme " + themes + " draws layers on another canvas");
        }
        assertEquals(2, themes, "one canvas per theme");

        // The 10 mm grid is drawn over that canvas, so it is the same in both themes too, and it
        // is KiCad's grid grey at an alpha that shows — a black line at 4 % was invisible on navy.
        Matcher g = Pattern.compile("--grid-line:\\s*rgba\\((\\d+),(\\d+),(\\d+),([0-9.]+)\\)").matcher(page);
        int grids = 0;
        while (g.find()) {
            grids++;
            assertEquals("132,132,132", g.group(1) + "," + g.group(2) + "," + g.group(3), "theme " + grids + " grid is not KiCad's grey");
            assertTrue(Double.parseDouble(g.group(4)) >= 0.4, "theme " + grids + " grid too faint to see");
        }
        assertEquals(2, grids, "one grid line per theme");
    }

    private static Map<String, String> palette() {
        Map<String, String> out = new HashMap<>();
        for (LayerType type : LayerType.values()) {
            if (type == LayerType.COPPER_INNER) continue;
            out.put(type.name(), LayerPalette.colorOf(type, null));
        }
        for (int n = 1; n <= LayerPalette.innerColors(); n++) {
            out.put("inner " + n, LayerPalette.colorOf(LayerType.COPPER_INNER, n));
        }
        return out;
    }

    private static double[] rgb(String hex) {
        return new double[]{
            Integer.parseInt(hex.substring(1, 3), 16) / 255.0,
            Integer.parseInt(hex.substring(3, 5), 16) / 255.0,
            Integer.parseInt(hex.substring(5, 7), 16) / 255.0};
    }

    private static double linear(double c) {
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double luminance(String hex) {
        double[] c = rgb(hex);
        return 0.2126 * linear(c[0]) + 0.7152 * linear(c[1]) + 0.0722 * linear(c[2]);
    }

    static double contrast(String a, String b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    @Test
    void innerCopperIsColoredByStackIndex() {
        assertEquals("#7fc87f", LayerPalette.colorOf(LayerType.COPPER_INNER, 1), "KiCad In1.Cu");
        assertEquals("#ce7d2c", LayerPalette.colorOf(LayerType.COPPER_INNER, 2), "KiCad In2.Cu");
        assertEquals(LayerPalette.colorOf(LayerType.COPPER_INNER, 1),
            LayerPalette.colorOf(LayerType.COPPER_INNER, null), "no index reads as the first inner layer");
        assertEquals(LayerPalette.colorOf(LayerType.COPPER_INNER, 1),
            LayerPalette.colorOf(LayerType.COPPER_INNER, LayerPalette.innerColors() + 1), "beyond the palette it cycles");
    }

    @Test
    void kicadsOuterCopperAndUnknown() {
        assertEquals("#c83434", LayerPalette.colorOf(LayerType.COPPER_TOP), "KiCad F.Cu");
        assertEquals("#4d7fc4", LayerPalette.colorOf(LayerType.COPPER_BOTTOM), "KiCad B.Cu");
        assertEquals(LayerPalette.UNKNOWN, LayerPalette.colorOf(LayerType.OTHER));
        assertEquals(LayerPalette.UNKNOWN, LayerPalette.colorOf(null));
    }
}
