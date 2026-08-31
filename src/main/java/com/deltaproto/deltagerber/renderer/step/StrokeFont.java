package com.deltaproto.deltagerber.renderer.step;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single-stroke vector font — the kind an engraver or a plotter draws, one polyline per pen
 * stroke rather than a filled outline.
 *
 * <p>It exists so the STEP export can write a word onto a face without depending on a real font:
 * {@link java.awt.Font} would drag in whatever the host's fontconfig happens to offer, produce
 * different geometry on different machines, and hand back empty glyphs on a stripped-down
 * container — none of which is acceptable for a file format whose output we promise is
 * reproducible.
 *
 * <p>Glyphs are drawn on a 6 × 10 grid with the baseline at y=0, and {@link #strokes} scales that
 * to the requested cap height. Unknown characters draw nothing.
 */
final class StrokeFont {

    /** Glyph advance and grid width, in grid units — one unit of space between letters. */
    private static final double GRID_WIDTH = 6;
    private static final double GRID_HEIGHT = 10;
    private static final double ADVANCE = 8;

    private static final Map<Character, double[][]> GLYPHS = new HashMap<>();

    static {
        glyph('A', new double[][]{{0, 0, 3, 10, 6, 0}, {1, 3.3, 5, 3.3}});
        glyph('B', new double[][]{{0, 0, 0, 10, 4, 10, 6, 8, 4, 5, 0, 5}, {4, 5, 6, 2.5, 4, 0, 0, 0}});
        glyph('C', new double[][]{{6, 8, 4, 10, 2, 10, 0, 8, 0, 2, 2, 0, 4, 0, 6, 2}});
        glyph('D', new double[][]{{0, 0, 0, 10, 3, 10, 6, 7, 6, 3, 3, 0, 0, 0}});
        glyph('E', new double[][]{{6, 10, 0, 10, 0, 0, 6, 0}, {0, 5, 4, 5}});
        glyph('F', new double[][]{{6, 10, 0, 10, 0, 0}, {0, 5, 4, 5}});
        glyph('G', new double[][]{{6, 8, 4, 10, 2, 10, 0, 8, 0, 2, 2, 0, 4, 0, 6, 2, 6, 4.5, 3.5, 4.5}});
        glyph('H', new double[][]{{0, 0, 0, 10}, {6, 0, 6, 10}, {0, 5, 6, 5}});
        glyph('I', new double[][]{{1, 10, 5, 10}, {3, 10, 3, 0}, {1, 0, 5, 0}});
        glyph('J', new double[][]{{6, 10, 6, 2, 4, 0, 2, 0, 0, 2}});
        glyph('K', new double[][]{{0, 0, 0, 10}, {6, 10, 0, 4.5}, {2, 6, 6, 0}});
        glyph('L', new double[][]{{0, 10, 0, 0, 6, 0}});
        glyph('M', new double[][]{{0, 0, 0, 10, 3, 5, 6, 10, 6, 0}});
        glyph('N', new double[][]{{0, 0, 0, 10, 6, 0, 6, 10}});
        glyph('O', new double[][]{{2, 10, 4, 10, 6, 8, 6, 2, 4, 0, 2, 0, 0, 2, 0, 8, 2, 10}});
        glyph('P', new double[][]{{0, 0, 0, 10, 4, 10, 6, 8, 6, 7, 4, 5, 0, 5}});
        glyph('Q', new double[][]{{2, 10, 4, 10, 6, 8, 6, 2, 4, 0, 2, 0, 0, 2, 0, 8, 2, 10}, {3.5, 2.5, 6, 0}});
        glyph('R', new double[][]{{0, 0, 0, 10, 4, 10, 6, 8, 6, 7, 4, 5, 0, 5}, {3, 5, 6, 0}});
        glyph('S', new double[][]{{6, 9, 4, 10, 2, 10, 0, 8, 0, 6.5, 6, 3.5, 6, 2, 4, 0, 2, 0, 0, 1}});
        glyph('T', new double[][]{{0, 10, 6, 10}, {3, 10, 3, 0}});
        glyph('U', new double[][]{{0, 10, 0, 2, 2, 0, 4, 0, 6, 2, 6, 10}});
        glyph('V', new double[][]{{0, 10, 3, 0, 6, 10}});
        glyph('W', new double[][]{{0, 10, 1.5, 0, 3, 6, 4.5, 0, 6, 10}});
        glyph('X', new double[][]{{0, 0, 6, 10}, {0, 10, 6, 0}});
        glyph('Y', new double[][]{{0, 10, 3, 5, 6, 10}, {3, 5, 3, 0}});
        glyph('Z', new double[][]{{0, 10, 6, 10, 0, 0, 6, 0}});
        glyph('0', new double[][]{{2, 10, 4, 10, 6, 8, 6, 2, 4, 0, 2, 0, 0, 2, 0, 8, 2, 10}, {1, 1.5, 5, 8.5}});
        glyph('1', new double[][]{{1, 8, 3, 10, 3, 0}, {1, 0, 5, 0}});
        glyph('2', new double[][]{{0, 8, 2, 10, 4, 10, 6, 8, 6, 6.5, 0, 0, 6, 0}});
        glyph('3', new double[][]{{0, 10, 6, 10, 3, 6, 6, 4, 6, 2, 4, 0, 2, 0, 0, 1}});
        glyph('4', new double[][]{{4.5, 0, 4.5, 10, 0, 3, 6, 3}});
        glyph('5', new double[][]{{6, 10, 0, 10, 0, 6, 4, 6, 6, 4, 6, 2, 4, 0, 2, 0, 0, 1}});
        glyph('6', new double[][]{{6, 9, 4, 10, 2, 10, 0, 8, 0, 2, 2, 0, 4, 0, 6, 2, 6, 4, 4, 6, 2, 6, 0, 4}});
        glyph('7', new double[][]{{0, 10, 6, 10, 2, 0}});
        glyph('8', new double[][]{{2, 5, 0, 3, 0, 1, 2, 0, 4, 0, 6, 1, 6, 3, 4, 5, 2, 5,
                                   0, 6.5, 0, 8.5, 2, 10, 4, 10, 6, 8.5, 6, 6.5, 4, 5}});
        glyph('9', new double[][]{{0, 1, 2, 0, 4, 0, 6, 2, 6, 8, 4, 10, 2, 10, 0, 8, 0, 6, 2, 4, 4, 4, 6, 6}});
        glyph('-', new double[][]{{1, 5, 5, 5}});
        glyph('_', new double[][]{{0, 0, 6, 0}});
        glyph('.', new double[][]{{2.5, 0, 3.5, 0}});
        glyph('/', new double[][]{{0, 0, 6, 10}});
    }

    private static void glyph(char c, double[][] strokes) {
        GLYPHS.put(c, strokes);
    }

    private StrokeFont() {}

    /** Width of {@code text} at the given cap height, in the same units. */
    static double width(String text, double capHeight) {
        if (text == null || text.isEmpty()) return 0;
        double scale = capHeight / GRID_HEIGHT;
        return (ADVANCE * (text.length() - 1) + GRID_WIDTH) * scale;
    }

    /**
     * {@code text} as polylines, laid out left to right from {@code (x, y)} — {@code y} being the
     * baseline — at the given cap height.
     *
     * @param mirrorX mirror each point about the text's own centre line, so a label on the
     *                underside of a board reads correctly when the board is viewed from below
     */
    static List<double[][]> strokes(String text, double x, double y, double capHeight, boolean mirrorX) {
        List<double[][]> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        double scale = capHeight / GRID_HEIGHT;
        double totalWidth = width(text, capHeight);
        double pen = x;
        for (char c : text.toUpperCase().toCharArray()) {
            double[][] glyph = GLYPHS.get(c);
            if (glyph != null) {
                for (double[] stroke : glyph) {
                    double[][] points = new double[stroke.length / 2][2];
                    for (int i = 0; i < points.length; i++) {
                        double px = pen + stroke[2 * i] * scale;
                        points[i][0] = mirrorX ? 2 * x + totalWidth - px : px;
                        points[i][1] = y + stroke[2 * i + 1] * scale;
                    }
                    out.add(points);
                }
            }
            pen += ADVANCE * scale;
        }
        return out;
    }
}
