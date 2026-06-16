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
 * Realistic rendering of a layer set that has NO dedicated outline file. The board edge
 * is derived from the union of the copper geometry (the copper-union silhouette): the
 * outer boundary of all copper, with internal copper-free pockets filled and small
 * clearance seams between separate pours bridged so the board comes out as one piece.
 * <p>
 * Geometry is synthetic filled regions standing in for copper pours; the assertions
 * evaluate the derived clip path as a non-zero {@link Path2D} — a derived outline carries
 * no real cut-outs, so its same-wound silhouette pieces must union (nonzero), never cancel
 * (even-odd). See {@link OutlineDeriver} / the realistic renderer's {@code outlineFillRule}.
 */
public class DerivedOutlineRealisticTest {

    private static int u(double mm) { return (int) Math.round(mm * 10000); } // FSLAX44 MM

    /** A Gerber doc with one or more filled rectangular regions (stand-in copper pours). */
    private static GerberDocument copperWithRects(double[][] rects) {
        StringBuilder g = new StringBuilder();
        g.append("G04 synthetic copper pour*\n%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\n");
        for (double[] r : rects) {
            g.append("G36*\n");
            g.append("X").append(u(r[0])).append("Y").append(u(r[1])).append("D02*\n");
            g.append("X").append(u(r[2])).append("Y").append(u(r[1])).append("D01*\n");
            g.append("X").append(u(r[2])).append("Y").append(u(r[3])).append("D01*\n");
            g.append("X").append(u(r[0])).append("Y").append(u(r[3])).append("D01*\n");
            g.append("X").append(u(r[0])).append("Y").append(u(r[1])).append("D01*\n");
            g.append("G37*\n");
        }
        g.append("M02*\n");
        return new GerberParser().parse(g.toString());
    }

    @Test
    void derivesBoardSilhouetteFromSingleCopperPour() throws Exception {
        // One 40 x 30 mm copper pour, no outline layer.
        GerberDocument copper = copperWithRects(new double[][]{{0, 0, 40, 30}});
        Path2D fill = derivedClipFill(copper);

        assertTrue(fill.contains(20.0, 15.0), "board interior should be filled");
        assertTrue(fill.contains(0.0, 15.0), "copper edge area should be filled (slight outset)");
        // Well outside the copper stays empty (outset is only a fraction of a mm).
        assertFalse(fill.contains(45.0, 15.0), "well outside the board must be empty");
        assertFalse(fill.contains(20.0, 40.0), "well above the board must be empty");
    }

    @Test
    void bridgesClearanceGapBetweenPoursIntoOneBoard() throws Exception {
        // Two pours split by a ~1 mm copper-free channel (a board with two ground zones).
        // The morphological close must bridge it so the board is a single silhouette.
        GerberDocument copper = copperWithRects(new double[][]{
            {0, 0, 19.5, 30},
            {20.5, 0, 40, 30},
        });

        // Single clip subpath (the two pours merged into one board).
        String d = derivedClipPath(copper);
        long subpaths = d.chars().filter(c -> c == 'M').count();
        assertEquals(1, subpaths, "the two pours should merge into one board silhouette: " + d);

        Path2D fill = parseLinePath(d);
        fill.setWindingRule(Path2D.WIND_NON_ZERO);
        assertTrue(fill.contains(10.0, 15.0), "left pour should be filled");
        assertTrue(fill.contains(30.0, 15.0), "right pour should be filled");
        assertTrue(fill.contains(20.0, 15.0), "the bridged gap between pours should be filled");
        assertFalse(fill.contains(45.0, 15.0), "outside the board must be empty");
    }

    private static Path2D derivedClipFill(GerberDocument copper) throws Exception {
        Path2D fill = parseLinePath(derivedClipPath(copper));
        fill.setWindingRule(Path2D.WIND_NON_ZERO);
        return fill;
    }

    private static String derivedClipPath(GerberDocument copper) {
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", copper)
            .setLayerType(LayerType.COPPER_TOP));
        String svg = new MultiLayerSVGRenderer().renderRealistic(layers);

        Matcher m = Pattern.compile(
            "<clipPath id=\"board-outline\">\\s*<path d=\"([^\"]*)\"([^/]*)/>").matcher(svg);
        assertTrue(m.find(), "realistic SVG should define a derived board-outline clipPath");
        assertTrue(m.group(2).contains("clip-rule=\"nonzero\""),
            "derived clip path must use clip-rule=nonzero so its same-wound silhouette "
            + "pieces union instead of cancelling into holes, was: " + m.group(2));
        return m.group(1);
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
