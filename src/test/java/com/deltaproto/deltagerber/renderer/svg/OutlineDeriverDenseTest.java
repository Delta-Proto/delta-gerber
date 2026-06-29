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
 * When the copper is too dense to form a clean board-shaped silhouette, {@link OutlineDeriver}
 * must not try to trace it: a high vertex count signals the geometry is a tangle of traces/pads,
 * not a board edge, and the exact {@link java.awt.geom.Area} union + morphological close is
 * roughly quadratic in edge count and OOMs a multi-GB heap (the MFIH_V1 board did exactly this,
 * dying in {@code erode() -> new Area(stroked band)}). The deriver instead degrades to the board
 * bounding box grown by a fixed margin — a cheap, bounded answer that always completes.
 */
public class OutlineDeriverDenseTest {

    private static int u(double mm) { return (int) Math.round(mm * 10000); } // FSLAX44 MM

    /**
     * A grid of {@code cols x rows} tiny filled pads spanning the unit-ish board area, standing
     * in for a trace/pad-dense copper layer. Each pad is a 5-vertex region, so the flattened
     * vertex count is ~5 * cols * rows — pick a grid that clears MAX_TOTAL_VERTICES (50k).
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
    void denseCopperFallsBackToBoundingBoxWithMarginAndDoesNotHang() {
        // 110 x 100 = 11,000 pads -> ~55,000 flattened vertices, well past the 50k guard.
        GerberDocument copper = densePadGrid(110, 100, 40.0, 30.0);
        BoundingBox bb = copper.getBoundingBox();
        assertTrue(bb.isValid(), "dense copper should have valid bounds");

        long t0 = System.nanoTime();
        String d = OutlineDeriver.deriveOutlineSvgPath(List.of(copper), 0.6, 0.2);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertNotNull(d);
        assertFalse(d.isBlank(), "should still emit an outline (the bounding box)");
        // Bounding-box fallback is a single axis-aligned rectangle: one M, three L, one Z.
        assertEquals(1, count(d, 'M'), "bbox fallback is one subpath: " + d);
        assertEquals(3, count(d, 'L'), "bbox fallback is a 4-corner rectangle: " + d);

        double[] xs = new double[4], ys = new double[4];
        parseCorners(d, xs, ys);
        double gotMinX = min(xs), gotMaxX = max(xs), gotMinY = min(ys), gotMaxY = max(ys);

        // Expected: board bounds grown by 10% of each dimension on every side.
        double mx = bb.getWidth() * 0.10, my = bb.getHeight() * 0.10;
        assertEquals(bb.getMinX() - mx, gotMinX, 1e-3, "left edge = bbox - 10%");
        assertEquals(bb.getMaxX() + mx, gotMaxX, 1e-3, "right edge = bbox + 10%");
        assertEquals(bb.getMinY() - my, gotMinY, 1e-3, "bottom edge = bbox - 10%");
        assertEquals(bb.getMaxY() + my, gotMaxY, 1e-3, "top edge = bbox + 10%");

        // The whole point of the guard: it bails *before* the expensive Area ops, so it is fast.
        assertTrue(ms < 5000, "dense fallback must not hang/OOM; took " + ms + " ms");
    }

    @Test
    void sparseCopperStillDerivesARealSilhouetteNotTheBoundingBox() {
        // A single 40 x 30 pour is well under the vertex guard: the exact path runs and the
        // outline tracks the copper edge (only a fraction of a mm of outset), NOT a 10% bbox.
        GerberDocument copper = densePadGrid(1, 1, 40.0, 30.0); // one pad at origin, 0.1 mm
        // Replace with a genuine large pour so the silhouette is meaningful.
        copper = new GerberParser().parse(
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
        double[] xs = new double[4], ys = new double[4];
        // Outset is DERIVED_OUTLINE_OUTSET_MM-scale (0.2) + close; far below a 10% (4 mm / 3 mm)
        // bbox margin. If the exact path ran, the right edge sits near 40, not near 44.
        boolean isRect = count(d, 'M') == 1 && count(d, 'L') == 3;
        if (isRect) {
            parseCorners(d, xs, ys);
            assertTrue(max(xs) < 41.0,
                "exact path should hug the copper edge (~40mm), not apply a 10% bbox margin (~44mm): " + d);
        }
        // (A rectangular pour can legitimately come back as a rectangle; the margin is the tell.)
    }

    private static int count(String s, char ch) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ch) n++;
        return n;
    }

    /** Parse the 8 numbers of an "M x y L x y L x y L x y Z" rectangle into corner arrays. */
    private static void parseCorners(String d, double[] xs, double[] ys) {
        Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(d);
        for (int i = 0; i < 4; i++) {
            assertTrue(m.find(), "expected x[" + i + "] in " + d);
            xs[i] = Double.parseDouble(m.group());
            assertTrue(m.find(), "expected y[" + i + "] in " + d);
            ys[i] = Double.parseDouble(m.group());
        }
    }

    private static double min(double[] a) { double m = a[0]; for (double v : a) m = Math.min(m, v); return m; }
    private static double max(double[] a) { double m = a[0]; for (double v : a) m = Math.max(m, v); return m; }
}
