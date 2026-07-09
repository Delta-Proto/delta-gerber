package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OutlineDeriver} costs what the board's <em>area</em> costs, not what its geometry costs.
 * <p>
 * It used to derive the silhouette with exact {@link java.awt.geom.Area} booleans, whose
 * morphological close is roughly quadratic in edge count: 5,000 flattened vertices took over two
 * minutes and a real 32 mm board's copper (~49,000 vertices) never finished, while the MFIH_V1
 * board exhausted a multi-GB heap inside {@code erode() -> new Area(stroked band)}. A vertex cap
 * papered over that by degrading to the board bounding box — which fired only on the boards too
 * dense to survive the exact path, and left every board below the cap to hang.
 * <p>
 * The deriver now works on a raster, so vertex count buys nothing. Dense copper must therefore
 * produce a <em>real</em> silhouette that hugs the copper, quickly — never a padded bounding box.
 */
public class OutlineDeriverDenseTest {

    private static int u(double mm) { return (int) Math.round(mm * 10000); } // FSLAX44 MM

    /** Margin the retired bounding-box fallback added on each side, as a fraction of each dimension. */
    private static final double RETIRED_BBOX_MARGIN_FRACTION = 0.10;

    /**
     * A grid of {@code cols x rows} tiny filled pads spanning the board area, standing in for a
     * trace/pad-dense copper layer. Each pad is a 5-vertex region, so the flattened vertex count
     * is ~5 * cols * rows. Pads sit close enough that the morphological close bridges them.
     */
    private static GerberDocument densePadGrid(int cols, int rows, double boardW, double boardH) {
        double pad = 0.1;
        double stepX = (boardW - pad) / (cols - 1);
        double stepY = (boardH - pad) / (rows - 1);
        StringBuilder g = new StringBuilder();
        g.append("G04 dense synthetic copper*\n%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\n");
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                double x0 = i * stepX, y0 = j * stepY, x1 = x0 + pad, y1 = y0 + pad;
                g.append("G36*\n");
                g.append("X").append(u(x0)).append("Y").append(u(y0)).append("D02*\n");
                g.append("X").append(u(x1)).append("Y").append(u(y0)).append("D01*\n");
                g.append("X").append(u(x1)).append("Y").append(u(y1)).append("D01*\n");
                g.append("X").append(u(x0)).append("Y").append(u(y1)).append("D01*\n");
                g.append("X").append(u(x0)).append("Y").append(u(y0)).append("D01*\n");
                g.append("G37*\n");
            }
        }
        g.append("M02*\n");
        return new GerberParser().parse(g.toString());
    }

    @Test
    void denseCopperDerivesARealSilhouetteAndDoesNotHang() {
        // 110 x 100 = 11,000 pads -> ~55,000 flattened vertices, past the retired 50k vertex cap.
        GerberDocument copper = densePadGrid(110, 100, 40.0, 30.0);
        BoundingBox bb = copper.getBoundingBox();
        assertTrue(bb.isValid(), "dense copper should have valid bounds");

        long t0 = System.nanoTime();
        String d = OutlineDeriver.deriveOutlineSvgPath(List.of(copper), 0.6, 0.2);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertNotNull(d);
        assertFalse(d.isBlank(), "dense copper must still yield an outline");

        // The pads are ~0.27 mm apart, so a 0.6 mm close bridges them into one board.
        assertEquals(1, count(d, 'M'), "the pad grid should close into a single silhouette: " + d);

        // The silhouette hugs the copper: it is grown by the 0.2 mm outset, not by a
        // 10 %-of-dimension bounding-box margin (which on this board would be 4 mm / 3 mm).
        double[] extent = extent(d);
        double marginX = bb.getWidth() * RETIRED_BBOX_MARGIN_FRACTION;
        double marginY = bb.getHeight() * RETIRED_BBOX_MARGIN_FRACTION;
        assertTrue(extent[1] < bb.getMaxX() + marginX / 2,
            "right edge must hug the copper (~" + bb.getMaxX() + "), not a 10% bbox margin: " + extent[1]);
        assertTrue(extent[0] > bb.getMinX() - marginX / 2,
            "left edge must hug the copper: " + extent[0]);
        assertTrue(extent[3] < bb.getMaxY() + marginY / 2,
            "top edge must hug the copper: " + extent[3]);
        assertTrue(extent[2] > bb.getMinY() - marginY / 2,
            "bottom edge must hug the copper: " + extent[2]);

        // Bounded by the raster's pixel count, so a vertex tangle can no longer blow it up.
        assertTrue(ms < 5000, "dense derivation must not hang; took " + ms + " ms");
    }

    @Test
    void sparseCopperDerivesASilhouetteThatHugsTheCopperEdge() {
        // A single 40 x 30 pour: the outline tracks the copper edge, only a fraction of a mm out.
        GerberDocument copper = new GerberParser().parse(
            "G04 single pour*\n%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\n"
            + "G36*\n"
            + "X" + u(0) + "Y" + u(0) + "D02*\n"
            + "X" + u(40) + "Y" + u(0) + "D01*\n"
            + "X" + u(40) + "Y" + u(30) + "D01*\n"
            + "X" + u(0) + "Y" + u(30) + "D01*\n"
            + "X" + u(0) + "Y" + u(0) + "D01*\n"
            + "G37*\nM02*\n");

        String d = OutlineDeriver.deriveOutlineSvgPath(List.of(copper), 0.6, 0.2);
        assertFalse(d.isBlank());
        assertEquals(1, count(d, 'M'), "one pour is one silhouette: " + d);

        // Outset is 0.2 mm; a 10% bbox margin would put the right edge near 44.
        double[] extent = extent(d);
        assertEquals(-0.2, extent[0], 0.15, "left edge = copper - outset");
        assertEquals(40.2, extent[1], 0.15, "right edge = copper + outset");
        assertEquals(-0.2, extent[2], 0.15, "bottom edge = copper - outset");
        assertEquals(30.2, extent[3], 0.15, "top edge = copper + outset");
    }

    @Test
    void noGeometryYieldsNoOutline() {
        GerberDocument empty = new GerberParser().parse(
            "G04 nothing*\n%FSLAX44Y44*%\n%MOMM*%\nG01*\nM02*\n");
        assertEquals("", OutlineDeriver.deriveOutlineSvgPath(List.of(empty), 0.6, 0.2));
    }

    private static int count(String s, char ch) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ch) n++;
        return n;
    }

    /** {minX, maxX, minY, maxY} over every coordinate pair in the path. */
    private static double[] extent(String d) {
        Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(d);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        while (m.find()) {
            double x = Double.parseDouble(m.group());
            assertTrue(m.find(), "coordinates come in pairs: " + d);
            double y = Double.parseDouble(m.group());
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        return new double[]{minX, maxX, minY, maxY};
    }
}
