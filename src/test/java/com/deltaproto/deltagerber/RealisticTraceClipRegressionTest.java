package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.Test;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard: interior copper traces must survive the realistic render of a board
 * that ships <em>without</em> a dedicated outline layer (the board edge is derived from the
 * copper silhouette).
 * <p>
 * The bug: the derived board-outline path is the union of the copper silhouette with its
 * interior pockets filled and its holes dropped — {@link com.deltaproto.deltagerber}'s
 * {@code OutlineDeriver} emits only same-wound "outer" contours, a path meant to be filled
 * under the <b>non-zero</b> rule. The realistic renderer used to clip/mask it under
 * <b>even-odd</b>. On a copper-dense board the exact-geometry morphological close/outset emitted
 * concentric same-wound boundary loops; under even-odd a loop nested inside another cancels to a
 * hole, so the clip punched holes exactly over the copper-dense zones and erased the traces there
 * — while the board still looked like a full rectangle, hiding the breakage.
 * <p>
 * The deriver now works on a raster and flood-fills the exterior, so every emitted loop is the
 * outer boundary of a disjoint piece and none can nest inside another. That is the stronger
 * guarantee, and it is what this test pins: no nesting, hence no rule under which the clip can
 * cancel itself into holes. The renderer still declares {@code nonzero}, which is what a union of
 * same-wound pieces requires.
 * <p>
 * The geometry below is modelled on the failing real-world set — an outline-less board of
 * dense edge-card connector combs that fan out through thin traces to pad columns — but
 * shares none of its coordinates, dimensions, counts, identifiers or silkscreen. It exists
 * only to exercise the derived-outline clip path.
 */
public class RealisticTraceClipRegressionTest {

    private static int u(double mm) { return (int) Math.round(mm * 10000); } // FSLAX44 MM

    /** Midpoints of every trace drawn into the synthetic copper, in mm. */
    private final List<double[]> traceMidpoints = new ArrayList<>();

    @Test
    void derivedOutlineDoesNotClipInteriorTraces() {
        GerberDocument copper = syntheticCombAndFanCopper();
        assertFalse(traceMidpoints.isEmpty(), "test geometry should contain traces");

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", copper)
            .setLayerType(LayerType.COPPER_TOP));
        String svg = new MultiLayerSVGRenderer().renderRealistic(layers);

        // Pull out the derived board-outline clip path and its declared rule.
        Matcher m = Pattern.compile(
            "<clipPath id=\"board-outline\">\\s*<path d=\"([^\"]*)\"([^/]*)/>").matcher(svg);
        assertTrue(m.find(), "realistic SVG should define a derived board-outline clipPath");
        String pathData = m.group(1);
        String attrs = m.group(2);

        // 1. The fix itself: a derived outline must clip under non-zero, not even-odd.
        assertTrue(attrs.contains("clip-rule=\"nonzero\""),
            "derived board-outline clip must use clip-rule=nonzero, was: " + attrs.trim());

        // 2. Behaviour under the *declared* rule: every trace lies inside the board clip,
        //    so no copper is clipped away.
        Path2D asDeclared = parsePath(pathData);
        asDeclared.setWindingRule(Path2D.WIND_NON_ZERO);
        for (double[] mid : traceMidpoints) {
            assertTrue(asDeclared.contains(mid[0], mid[1]),
                () -> String.format(Locale.US,
                    "trace midpoint (%.3f, %.3f) must be inside the derived board clip", mid[0], mid[1]));
        }

        // 3. The structural reason the bug cannot come back: no silhouette loop nests inside
        //    another, because the exterior flood-fill leaves the mask free of holes. A revert to
        //    dropping holes instead of filling them would reintroduce nesting and fail here.
        List<String> subpaths = splitSubpaths(pathData);
        assertFalse(subpaths.isEmpty(), "derived outline should contain at least one loop");
        for (int i = 0; i < subpaths.size(); i++) {
            Path2D loop = parsePath(subpaths.get(i));
            loop.setWindingRule(Path2D.WIND_NON_ZERO);
            for (int j = 0; j < subpaths.size(); j++) {
                if (i == j) continue;
                double[] vertex = firstVertex(subpaths.get(j));
                assertFalse(loop.contains(vertex[0], vertex[1]),
                    () -> String.format(Locale.US,
                        "derived silhouette loop starting (%.3f, %.3f) nests inside another — "
                        + "a nested same-wound loop cancels to a hole under even-odd and clips "
                        + "the copper beneath it", vertex[0], vertex[1]));
            }
        }

        // 4. Consequently the clip encloses the same pixels under either rule, so the traces
        //    survive even if a caller ignores the declared clip-rule.
        Path2D asEvenOdd = parsePath(pathData);
        asEvenOdd.setWindingRule(Path2D.WIND_EVEN_ODD);
        for (double[] mid : traceMidpoints) {
            assertTrue(asEvenOdd.contains(mid[0], mid[1]),
                () -> String.format(Locale.US,
                    "trace midpoint (%.3f, %.3f) must survive even under even-odd", mid[0], mid[1]));
        }
    }

    /** The path's "M ... Z" runs, one per closed loop. */
    private static List<String> splitSubpaths(String d) {
        List<String> pieces = new ArrayList<>();
        for (String piece : d.trim().split("(?=M)")) {
            if (!piece.isBlank()) pieces.add(piece);
        }
        return pieces;
    }

    /** The "M x y" point that opens a subpath. */
    private static double[] firstVertex(String subpath) {
        Matcher m = Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(subpath);
        assertTrue(m.find(), "subpath should open with a coordinate: " + subpath);
        double x = Double.parseDouble(m.group());
        assertTrue(m.find(), "subpath should open with a coordinate pair: " + subpath);
        return new double[]{x, Double.parseDouble(m.group())};
    }

    /**
     * A synthetic outline-less copper layer: a thin board-edge rectangle (in copper, so the
     * whole layer unions into one board silhouette), three dense edge-card connector combs,
     * and a fan of thin traces from each comb to a pad column. Records every trace midpoint
     * in {@link #traceMidpoints}.
     */
    private GerberDocument syntheticCombAndFanCopper() {
        StringBuilder g = new StringBuilder();
        g.append("G04 synthetic outline-less copper*\n%FSLAX44Y44*%\n%MOMM*%\nG01*\n");
        g.append("%ADD10C,0.2500*%\n");       // trace
        g.append("%ADD11R,1.2000X1.2000*%\n"); // pad
        g.append("%ADD12C,0.1000*%\n");       // thin board edge

        // Board-edge rectangle in copper — unifies the copper into a single silhouette.
        rect(g, "D12", 1, 1, 25, 51);

        double[] groupY = {14, 26, 38};
        int fingers = 10;
        double pitch = 0.55, fingerW = 0.30, fingerLen = 6, combX = 6;
        double padX = 22;
        for (double gy : groupY) {
            // Dense comb of finger pads (one filled region per finger).
            double y0 = gy - (fingers * pitch) / 2.0;
            g.append("G36*\n");
            for (int i = 0; i < fingers; i++) {
                double fy0 = y0 + i * pitch;
                contour(g, combX, fy0, combX + fingerLen, fy0 + fingerW);
            }
            g.append("G37*\n");

            // Fan of thin traces from the comb's right edge out to a pad column.
            g.append("D10*\n");
            for (int i = 0; i < 6; i++) {
                double y = gy - 3 + i * 1.2;
                line(g, combX + fingerLen, y, padX - 0.6, y);
                traceMidpoints.add(new double[]{(combX + fingerLen + padX - 0.6) / 2.0, y});
            }
            // Exposed pads.
            g.append("D11*\n");
            for (int i = 0; i < 6; i++) {
                g.append(String.format(Locale.US, "X%dY%dD03*\n", u(padX), u(gy - 3 + i * 1.2)));
            }
        }
        g.append("M02*\n");
        return new GerberParser().parse(g.toString());
    }

    private static void rect(StringBuilder g, String dcode, double x0, double y0, double x1, double y1) {
        g.append(dcode).append("*\n");
        g.append(String.format(Locale.US, "X%dY%dD02*\n", u(x0), u(y0)));
        g.append(String.format(Locale.US, "X%dY%dD01*\n", u(x1), u(y0)));
        g.append(String.format(Locale.US, "X%dY%dD01*\n", u(x1), u(y1)));
        g.append(String.format(Locale.US, "X%dY%dD01*\n", u(x0), u(y1)));
        g.append(String.format(Locale.US, "X%dY%dD01*\n", u(x0), u(y0)));
    }

    /** One closed region contour (used between G36/G37). */
    private static void contour(StringBuilder g, double x0, double y0, double x1, double y1) {
        g.append(String.format(Locale.US, "X%dY%dD02*\n", u(x0), u(y0)));
        g.append(String.format(Locale.US, "X%dY%dD01*\n", u(x1), u(y0)));
        g.append(String.format(Locale.US, "X%dY%dD01*\n", u(x1), u(y1)));
        g.append(String.format(Locale.US, "X%dY%dD01*\n", u(x0), u(y1)));
        g.append(String.format(Locale.US, "X%dY%dD01*\n", u(x0), u(y0)));
    }

    private static void line(StringBuilder g, double x0, double y0, double x1, double y1) {
        g.append(String.format(Locale.US, "X%dY%dD02*\n", u(x0), u(y0)));
        g.append(String.format(Locale.US, "X%dY%dD01*\n", u(x1), u(y1)));
    }

    /** Parse an SVG path of absolute M/L/Z commands into a {@link Path2D}. */
    private static Path2D parsePath(String d) {
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
