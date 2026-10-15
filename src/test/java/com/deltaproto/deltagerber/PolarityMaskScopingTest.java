package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.SVGRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Polarity is rendered with <em>scoped</em> masks: a clear can only erase what lies under it, so
 * a dark object is masked by the clear runs drawn after it that can reach it, and by nothing else.
 *
 * <p>The shape under test is an Altium legend with inverted labels — a dark box, the glyphs cleared
 * out of it, the glyph counters put back dark, a few hundred times over — followed by a run of
 * clear pad knockouts that trims every stroke crossing a pad. Wrapping everything drawn before
 * clear run N in mask N nested those masks a few hundred deep, each one board-sized: Batik took
 * 15 s on the thumbnail and lost the labels to accumulated rounding, and a browser repainted the
 * realistic view at four frames a second. The fixture here is synthetic: the real board is a
 * customer design and its geometry is not reproduced, only the polarity pattern.
 */
class PolarityMaskScopingTest {

    private static final String DARK = "#ff0000";
    private static final String BACKGROUND = "#000000";

    /** Label i is a 3.6 × 2 mm box at (5i, 10); its glyphs and the counter sit inside it. */
    private static final double LABEL_PITCH = 5.0;
    private static final double LABEL_Y = 10.0;
    /** A stroke along y = 20 is trimmed by 1 mm knockouts at x = 1 + 2k; one along y = 30 is not. */
    private static final double TRIMMED_Y = 20.0;
    private static final double UNTOUCHED_Y = 30.0;
    private static final int KNOCKOUTS = 50;

    private static String region(double x0, double y0, double x1, double y1) {
        return String.format(Locale.US, "G36*%nX%dY%dD02*%nX%dD01*%nY%dD01*%nX%dD01*%nY%dD01*%nG37*%n",
                um(x0), um(y0), um(x1), um(y1), um(x0), um(y0));
    }

    private static long um(double mm) {
        return Math.round(mm * 1_000_000);
    }

    /**
     * The legend: two strokes, {@code labels} inverted labels, the knockout run, and one object
     * drawn after every clear so nothing may erase it.
     */
    static String legend(int labels, int columns) {
        StringBuilder g = new StringBuilder();
        g.append("%FSLAX46Y46*%\n%MOMM*%\n%ADD10C,0.400000*%\n%LPD*%\nD10*\n");
        g.append(String.format(Locale.US, "X%dY%dD02*%nX%dD01*%n", um(0), um(TRIMMED_Y), um(100)));
        g.append(String.format(Locale.US, "X%dY%dD02*%nX%dD01*%n", um(0), um(UNTOUCHED_Y), um(100)));
        for (int i = 0; i < labels; i++) {
            double x0 = LABEL_PITCH * (i % columns);
            double y0 = LABEL_Y + 3.0 * (i / columns);
            g.append("%LPD*%\n").append(region(x0, y0, x0 + 3.6, y0 + 2.0));
            g.append("%LPC*%\n");
            g.append(region(x0 + 0.5, y0 + 0.5, x0 + 1.5, y0 + 1.5));
            g.append(region(x0 + 2.0, y0 + 0.5, x0 + 3.0, y0 + 1.5));
            g.append("%LPD*%\n").append(region(x0 + 0.7, y0 + 0.8, x0 + 1.0, y0 + 1.2));
        }
        g.append("%LPC*%\n");
        for (int k = 0; k < KNOCKOUTS; k++) {
            double x = 1 + 2 * k;
            g.append(region(x, TRIMMED_Y - 0.5, x + 1, TRIMMED_Y + 0.5));
        }
        g.append("%LPD*%\n").append(region(10, TRIMMED_Y - 0.7, 12, TRIMMED_Y + 0.7));
        g.append("M02*\n");
        return g.toString();
    }

    private static String render(String gerber) {
        GerberDocument doc = new GerberParser().parse(gerber);
        return new SVGRenderer().setDarkColor(DARK).setBackgroundColor(BACKGROUND).setMargin(1).render(doc);
    }

    // ---- structure -------------------------------------------------------------------------

    /** Counts of what the renderer emitted, from a walk over the SVG's tags. */
    private static final class Structure {
        int masks;            // <mask> definitions
        int maskedGroups;     // <g mask="..."> in the drawing
        int deepestNesting;   // masked groups open at once
        int bareObjects;      // drawn objects outside every masked group
        int maskedObjects;    // drawn objects inside one
        int clearsInMasks;    // objects inside <mask> bodies, the white base rect excluded
    }

    private static final Pattern TAG = Pattern.compile("<(/?)(\\w+)([^>]*)>");

    private static Structure structure(String svg) {
        Structure s = new Structure();
        int inDefs = 0, inMask = 0, depth = 0;
        java.util.ArrayDeque<Boolean> groups = new java.util.ArrayDeque<>(); // open <g>: masked?
        Matcher m = TAG.matcher(svg);
        while (m.find()) {
            boolean closing = !m.group(1).isEmpty();
            String tag = m.group(2);
            String attrs = m.group(3);
            boolean selfClosing = attrs.endsWith("/");
            if (tag.equals("defs")) { inDefs += closing ? -1 : 1; continue; }
            if (tag.equals("mask")) {
                if (!closing) s.masks++;
                inMask += closing ? -1 : 1;
                continue;
            }
            if (tag.equals("g")) {
                if (closing) {
                    if (!groups.isEmpty() && groups.pop()) depth--;
                } else if (selfClosing) {
                    continue;
                } else {
                    boolean masked = attrs.contains("mask=\"url(") && inDefs == 0 && inMask == 0;
                    groups.push(masked);
                    if (masked) {
                        s.maskedGroups++;
                        depth++;
                        s.deepestNesting = Math.max(s.deepestNesting, depth);
                    }
                }
                continue;
            }
            if (!selfClosing || tag.equals("svg")) continue;
            boolean drawn = tag.equals("path") || tag.equals("use") || tag.equals("circle")
                    || tag.equals("rect") || tag.equals("line") || tag.equals("polygon");
            if (!drawn) continue;
            if (inMask > 0) {
                if (!attrs.contains("fill=\"white\"")) s.clearsInMasks++;
            } else if (inDefs == 0 && !attrs.contains("fill=\"" + BACKGROUND + "\"")) {
                if (depth > 0) s.maskedObjects++; else s.bareObjects++;
            }
        }
        return s;
    }

    @Test
    void masksAreFlatAndScopedToWhatTheyErase() {
        int labels = 20;
        Structure s = structure(render(legend(labels, labels)));

        assertEquals(1, s.deepestNesting, "a masked group must never sit inside another");
        // One mask per label (its own glyph clears) and one for the knockout run.
        assertEquals(labels + 1, s.masks);
        assertEquals(labels + 1, s.maskedGroups);
        // Each label's box is masked by its two glyphs; the trimmed stroke by the 50 knockouts.
        // The knockouts must not be copied into the label masks.
        assertEquals(2 * labels + KNOCKOUTS, s.clearsInMasks, "every clear drawn once, in one mask");
        assertEquals(labels + 1, s.maskedObjects, "the boxes and the trimmed stroke");
        // The counters are drawn after their glyph clears, the untouched stroke crosses no
        // knockout, and the last object follows every clear: none of them needs a mask.
        assertEquals(labels + 2, s.bareObjects);
    }

    @Test
    void anObjectNoClearReachesHasNoMaskAtAll() {
        String gerber = """
            %FSLAX46Y46*%
            %MOMM*%
            %ADD10C,1.000000*%
            %LPD*%
            D10*
            X1000000Y1000000D03*
            %LPC*%
            X9000000Y9000000D03*
            M02*
            """;
        Structure s = structure(render(gerber));
        assertEquals(0, s.masks, "a clear that overlaps nothing drawn before it erases nothing");
        assertEquals(1, s.bareObjects);
    }

    // ---- pixels ----------------------------------------------------------------------------

    private static final class Raster {
        final BufferedImage img;
        final double minX, minY, width, height;

        Raster(String svg, int widthPx) throws IOException {
            Matcher vb = Pattern.compile("viewBox=\"([-\\d.]+) ([-\\d.]+) ([-\\d.]+) ([-\\d.]+)\"").matcher(svg);
            assertTrue(vb.find());
            minX = Double.parseDouble(vb.group(1));
            minY = Double.parseDouble(vb.group(2));
            width = Double.parseDouble(vb.group(3));
            height = Double.parseDouble(vb.group(4));
            // Both dimensions, from the viewBox: the SVG carries no width/height, and Batik
            // would otherwise letterbox the content into its 400 px default height.
            int heightPx = (int) Math.round(widthPx * height / width);
            byte[] png = MultiLayerSVGRenderer.rasterizeSvgToPng(svg, widthPx, heightPx);
            img = ImageIO.read(new ByteArrayInputStream(png));
        }

        /** Red channel at a Gerber coordinate (the renderer flips Y). */
        int red(double x, double y) {
            int px = (int) Math.round((x - minX) / width * img.getWidth());
            int py = (int) Math.round((minY + height - y) / height * img.getHeight());
            return (img.getRGB(px, py) >> 16) & 0xff;
        }

        void assertDark(double x, double y, String what) {
            assertTrue(red(x, y) > 200, what + " at (" + x + ", " + y + ") should be dark, red=" + red(x, y));
        }

        void assertClear(double x, double y, String what) {
            assertTrue(red(x, y) < 50, what + " at (" + x + ", " + y + ") should be erased, red=" + red(x, y));
        }
    }

    @Test
    void renderedPolarityIsExact() throws IOException {
        int labels = 20;
        Raster r = new Raster(render(legend(labels, labels)), 2040);

        for (int i : new int[] {0, 7, labels - 1}) {
            double x0 = LABEL_PITCH * i, y0 = LABEL_Y;
            r.assertDark(x0 + 1.75, y0 + 1.0, "label box between the glyphs");
            r.assertClear(x0 + 1.25, y0 + 1.0, "glyph cleared out of the box");
            r.assertDark(x0 + 0.85, y0 + 1.0, "counter put back after the clear");
        }
        r.assertDark(50, UNTOUCHED_Y, "stroke no knockout reaches");
        r.assertClear(1.5, TRIMMED_Y, "stroke under a knockout");
        r.assertDark(2.5, TRIMMED_Y, "stroke between knockouts");
        r.assertDark(11, TRIMMED_Y, "object drawn after the knockouts, on top of one");
    }

    /**
     * Two hundred and fifty labels: the depth at which the nested masks lost the labels to
     * rounding. Scoped masks are one deep whatever the count, so the last label is as bright as
     * the first.
     */
    @Test
    void hundredsOfPolarityFlipsStillRenderTheLabels() throws IOException {
        int labels = 250, columns = 25;
        String svg = render(legend(labels, columns));
        assertEquals(1, structure(svg).deepestNesting);

        Raster r = new Raster(svg, 1600);
        double x0 = LABEL_PITCH * ((labels - 1) % columns), y0 = LABEL_Y + 3.0 * ((labels - 1) / columns);
        r.assertDark(x0 + 1.75, y0 + 1.0, "last label's box");
        r.assertClear(x0 + 1.25, y0 + 1.0, "last label's glyph");
        r.assertDark(1.75, LABEL_Y + 1.0, "first label's box");
        r.assertDark(50, UNTOUCHED_Y, "untouched stroke");
    }

    // ---- the real legend, when present -----------------------------------------------------

    /** The customer's top legend, kept out of the repository under a neutral name. */
    private static final Path REAL_LEGEND = Paths.get("excluded/altium-inverted-legend/legend_top.gbr");

    static boolean realLegendIsPresent() {
        return Files.isRegularFile(REAL_LEGEND);
    }

    @Test
    @EnabledIf("realLegendIsPresent")
    void realAltiumLegendIsFlat() throws IOException {
        String svg = render(Files.readString(REAL_LEGEND));
        Structure s = structure(svg);
        assertEquals(1, s.deepestNesting);
        assertEquals(211, s.masks, "one mask per clear run that reaches something");
        assertTrue(s.bareObjects > 1000, "most of the legend is reached by no clear: " + s.bareObjects);
    }
}
