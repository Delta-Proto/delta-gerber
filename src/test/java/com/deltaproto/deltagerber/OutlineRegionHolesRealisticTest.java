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
 * Realistic rendering of a board profile that combines a <em>stroked</em> outline
 * (D02/D01 draws) with G36/G37 <em>region</em> cutouts that sit inside it.
 * <p>
 * Some EDA tools emit the board edge as strokes while expressing internal openings
 * (slots, windows) as filled regions in the same Profile layer. The realistic
 * renderer used to "prefer regions" and return only those — so the board clipped to
 * just the holes, inverting the view (you saw the holes filled and the board empty).
 * <p>
 * The synthetic geometry below is a plain 40 mm x 30 mm rectangle with two
 * rectangular windows; it shares only the <em>structure</em> of the case that
 * surfaced this bug, not its coordinates. The outline is drawn with straight
 * segments only so the resulting clip path can be evaluated as an AWT {@link Path2D}
 * to check the actual fill topology.
 */
public class OutlineRegionHolesRealisticTest {

    /** Profile layer: stroked rectangular edge + two region windows inside it. */
    private static final String OUTLINE_GERBER =
        "G04 Synthetic profile - stroked edge with region windows*\n" +
        "%FSLAX46Y46*%\n" +
        "%MOMM*%\n" +
        "%TF.FileFunction,Profile,NP*%\n" +
        "%TF.FilePolarity,Positive*%\n" +
        "G01*\n" +
        "G75*\n" +
        "%ADD10C,0.150000*%\n" +
        // --- stroked board edge: rectangle (0,0)-(40,30) ---
        "D10*\n" +
        "X0Y0D02*\n" +
        "X40000000Y0D01*\n" +
        "X40000000Y30000000D01*\n" +
        "X0Y30000000D01*\n" +
        "X0Y0D01*\n" +
        // --- region window 1: square (8,8)-(14,14) ---
        "G36*\n" +
        "X8000000Y8000000D02*\n" +
        "X14000000Y8000000D01*\n" +
        "X14000000Y14000000D01*\n" +
        "X8000000Y14000000D01*\n" +
        "X8000000Y8000000D01*\n" +
        "G37*\n" +
        // --- region window 2: rectangle (24,18)-(32,25) ---
        "G36*\n" +
        "X24000000Y18000000D02*\n" +
        "X32000000Y18000000D01*\n" +
        "X32000000Y25000000D01*\n" +
        "X24000000Y25000000D01*\n" +
        "X24000000Y18000000D01*\n" +
        "G37*\n" +
        "M02*\n";

    @Test
    void boardFillsWithRegionsAsHoles() throws Exception {
        GerberDocument outline = new GerberParser().parse(OUTLINE_GERBER);

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", outline)
            .setLayerType(LayerType.OUTLINE));

        String svg = new MultiLayerSVGRenderer().renderRealistic(layers);
        assertNotNull(svg);

        // Pull the board-outline clip path back out of the SVG.
        Matcher m = Pattern.compile(
            "<clipPath id=\"board-outline\">\\s*<path d=\"([^\"]*)\"([^/]*)/>")
            .matcher(svg);
        assertTrue(m.find(), "realistic SVG should define a board-outline clipPath");
        String d = m.group(1);
        String pathAttrs = m.group(2);

        // A clipPath child is governed by clip-rule, NOT fill-rule (SVG ignores
        // fill-rule here). It must be evenodd so the regions clip away as holes
        // rather than — under the default nonzero winding — letting the grey FR4
        // substrate fill them.
        assertTrue(pathAttrs.contains("clip-rule=\"evenodd\""),
            "board-outline clip path must use clip-rule=evenodd, was: " + pathAttrs);

        // It must contain the stroked edge AND both windows: three subpaths. The
        // pre-fix renderer dropped the stroked edge and kept only the windows.
        long subpaths = d.chars().filter(c -> c == 'M').count();
        assertEquals(3, subpaths,
            "clip path should hold the stroked edge plus two window subpaths, got " + subpaths);
        assertTrue(d.contains("40.000000"),
            "stroked board edge must survive into the clip path (a corner coord)");

        // Strongest check: evaluate the fill. Coordinates are emitted unflipped for a
        // lines-only path, so we can read them straight into AWT geometry.
        Path2D fill = parseLinePath(d);
        fill.setWindingRule(Path2D.WIND_EVEN_ODD);

        // Board interior, clear of both windows -> filled (board present).
        assertTrue(fill.contains(20.0, 15.0), "board interior should be filled");
        assertTrue(fill.contains(2.0, 2.0), "board corner area should be filled");
        // Inside each window -> not filled (holes punched through).
        assertFalse(fill.contains(11.0, 11.0), "window 1 should be a hole");
        assertFalse(fill.contains(28.0, 21.5), "window 2 should be a hole");
        // Outside the board -> not filled.
        assertFalse(fill.contains(45.0, 15.0), "area outside the board must be empty");
    }

    /** Parse an SVG path made only of absolute M/L/Z commands into a {@link Path2D}. */
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
