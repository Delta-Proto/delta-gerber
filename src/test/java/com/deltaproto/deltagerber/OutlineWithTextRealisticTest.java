package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.Test;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Realistic rendering of a profile / mechanical layer that carries more than the bare
 * board edge — the situation that arises when an EDA tool places stroked text and
 * notes on the outline layer, emits the profile twice, and leaves a gap where a
 * cut-out's closing edge should be.
 * <p>
 * The renderer used to chain every stroke together and fill whatever closed, so the
 * board's broken edge fragmented and only the clean little text glyphs filled in —
 * you saw floating letters instead of the PCB. The fix groups strokes into connected
 * components, chains each whole component (bridging internal gaps), and de-duplicates
 * the doubled profile, so the board itself renders. Small floating marks (text,
 * dimensions) are intentionally kept too — they render as harmless little features and
 * keeping them avoids ever dropping a genuine board section.
 * <p>
 * The geometry here is synthetic and lines-only (so the clip path can be evaluated as
 * an AWT {@link Path2D}); it shares only the <em>structure</em> of the real case.
 */
public class OutlineWithTextRealisticTest {

    // FSLAX44Y44 / MOMM: 1 mm = 10000 units.
    private static int u(double mm) { return (int) Math.round(mm * 10000); }

    /** A closed rectangular loop as a D02/D01 polyline. */
    private static String rect(double x0, double y0, double x1, double y1) {
        return "X" + u(x0) + "Y" + u(y0) + "D02*\n"
             + "X" + u(x1) + "Y" + u(y0) + "D01*\n"
             + "X" + u(x1) + "Y" + u(y1) + "D01*\n"
             + "X" + u(x0) + "Y" + u(y1) + "D01*\n"
             + "X" + u(x0) + "Y" + u(y0) + "D01*\n";
    }

    @Test
    void boardRendersAndDuplicatedProfileDoesNotCancel() throws Exception {
        StringBuilder g = new StringBuilder();
        g.append("G04 synthetic mechanical layer with text + duplicated profile*\n");
        g.append("%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\nD10*\n");
        // Board edge: 60 x 40 mm rectangle, emitted TWICE (tools often duplicate the
        // profile; under evenodd a doubled loop would cancel itself without de-dup).
        g.append(rect(0, 0, 60, 40));
        g.append(rect(0, 0, 60, 40));
        // Two small text-like glyphs floating ABOVE the board (outside it), also doubled.
        g.append(rect(8, 45, 12, 49));
        g.append(rect(8, 45, 12, 49));
        g.append(rect(16, 45, 20, 49));
        g.append(rect(16, 45, 20, 49));
        g.append("M02*\n");

        Path2D fill = clipFill(g.toString());

        // Board interior is filled (the duplicated profile did not cancel itself).
        assertTrue(fill.contains(30.0, 20.0), "board interior should be filled");
        assertTrue(fill.contains(2.0, 2.0), "board corner should be filled");
        // Floating glyphs are kept (rendered as small features), not dropped.
        assertTrue(fill.contains(10.0, 47.0), "floating glyph 1 should render");
        assertTrue(fill.contains(18.0, 47.0), "floating glyph 2 should render");
        // Genuinely empty space (no board, no glyph) stays empty.
        assertFalse(fill.contains(30.0, 47.0), "empty space between board and glyphs");
        assertFalse(fill.contains(40.0, 47.0), "empty space beside the glyphs");
    }

    @Test
    void boardWithMissingEdgeStillCloses() throws Exception {
        // 60 x 40 board whose bottom edge has a gap in the middle (x 25..35) — the kind
        // of missing closing edge a "don't forget the cut-out" note warns about. The
        // component is still connected around the other three sides, so chaining bridges
        // the gap and the board fills as a whole instead of fragmenting.
        StringBuilder g = new StringBuilder();
        g.append("G04 synthetic board with a gap in the bottom edge*\n");
        g.append("%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\nD10*\n");
        g.append("X" + u(0) + "Y" + u(0) + "D02*\n");
        g.append("X" + u(25) + "Y" + u(0) + "D01*\n");   // bottom-left part
        g.append("X" + u(35) + "Y" + u(0) + "D02*\n");   // (gap 25..35)
        g.append("X" + u(60) + "Y" + u(0) + "D01*\n");   // bottom-right part
        g.append("X" + u(60) + "Y" + u(40) + "D01*\n");  // right
        g.append("X" + u(0) + "Y" + u(40) + "D01*\n");   // top
        g.append("X" + u(0) + "Y" + u(0) + "D01*\n");    // left
        g.append("M02*\n");

        Path2D fill = clipFill(g.toString());

        assertTrue(fill.contains(30.0, 20.0), "board interior should be filled");
        // Just above the bridged gap is inside the board; just below the edge is outside.
        assertTrue(fill.contains(30.0, 2.0), "interior just above the bridged gap should fill");
        assertFalse(fill.contains(30.0, -2.0), "below the board edge must be empty");
    }

    /** Render realistically and parse the board-outline clip path into an even-odd Path2D. */
    private static Path2D clipFill(String gerber) throws Exception {
        GerberDocument doc = new GerberParser().parse(gerber);
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", doc).setLayerType(LayerType.OUTLINE));
        String svg = new MultiLayerSVGRenderer().renderRealistic(layers);

        Matcher m = Pattern.compile(
            "<clipPath id=\"board-outline\">\\s*<path d=\"([^\"]*)\"([^/]*)/>").matcher(svg);
        assertTrue(m.find(), "realistic SVG should define a board-outline clipPath");
        assertTrue(m.group(2).contains("clip-rule=\"evenodd\""),
            "clip path must use clip-rule=evenodd, was: " + m.group(2));

        Path2D fill = parseLinePath(m.group(1));
        fill.setWindingRule(Path2D.WIND_EVEN_ODD);
        return fill;
    }

    /** Parse an SVG path of absolute M/L/Z commands into a {@link Path2D}. */
    private static Path2D parseLinePath(String d) {
        Path2D.Double path = new Path2D.Double();
        Matcher tok = Pattern.compile("([MLZ])|(-?\\d+(?:\\.\\d+)?)").matcher(d);
        char cmd = 0;
        List<Double> nums = new ArrayList<>();
        while (tok.find()) {
            if (tok.group(1) != null) {
                flush(path, cmd, nums);
                cmd = tok.group(1).charAt(0);
                nums.clear();
            } else {
                nums.add(Double.parseDouble(tok.group(2)));
            }
        }
        flush(path, cmd, nums);
        return path;
    }

    private static void flush(Path2D.Double path, char cmd, List<Double> nums) {
        switch (cmd) {
            case 'M' -> path.moveTo(nums.get(0), nums.get(1));
            case 'L' -> path.lineTo(nums.get(0), nums.get(1));
            case 'Z' -> path.closePath();
            default -> { /* nothing pending */ }
        }
    }
}
