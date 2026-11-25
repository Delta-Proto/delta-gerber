package com.deltaproto.deltagerber.edit;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.MacroAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.ObroundAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.PolygonAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.RectangleAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroTemplate;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.Region;
import com.deltaproto.deltagerber.parser.GerberParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PadOutlinesTest {

    private static final double EPS = 1e-9;

    private static PadOutline only(GerberDocument doc) {
        List<PadOutline> all = PadOutlines.of(doc);
        assertEquals(1, all.size(), all.toString());
        return all.get(0);
    }

    private static GerberDocument docOf(Object... objects) {
        GerberDocument doc = new GerberDocument();
        for (Object o : objects) {
            doc.addObject((com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject) o);
        }
        return doc;
    }

    private static void assertBox(PadOutline p, double cx, double cy, double w, double h, double rot) {
        assertEquals(cx, p.centerX(), 1e-6, "centerX");
        assertEquals(cy, p.centerY(), 1e-6, "centerY");
        assertEquals(w, p.width(), 1e-6, "width");
        assertEquals(h, p.height(), 1e-6, "height");
        assertEquals(rot, p.rotationDeg(), 1e-6, "rotation");
    }

    private static void assertCounterClockwise(PadOutline p) {
        assertTrue(PadOutlines.signedArea(p.ring()) > 0, "ring is counter-clockwise");
        double[] first = p.ring().get(0);
        double[] last = p.ring().get(p.ring().size() - 1);
        assertTrue(Math.abs(first[0] - last[0]) > EPS || Math.abs(first[1] - last[1]) > EPS,
                "closing point is not repeated");
    }

    @Test
    void circleFlashIsADiscWithinTheFlatness() {
        PadOutline p = only(docOf(new Flash(3, 4, new CircleAperture(10, 2.0))));
        assertEquals(PadOutline.Kind.CIRCLE, p.kind());
        assertBox(p, 3, 4, 2, 2, 0);
        assertCounterClockwise(p);
        for (double[] v : p.ring()) {
            assertEquals(1.0, Math.hypot(v[0] - 3, v[1] - 4), 1e-9, "vertices lie on the circle");
        }
        // Chords stay within the flatness of the arc they replace.
        double[] a = p.ring().get(0);
        double[] b = p.ring().get(1);
        double sagitta = 1.0 - Math.hypot((a[0] + b[0]) / 2 - 3, (a[1] + b[1]) / 2 - 4);
        assertTrue(sagitta <= PadOutlines.FLATNESS_MM, "sagitta " + sagitta);
    }

    @Test
    void rotatedRectangleFlashKeepsItsOwnWidthAxis() {
        PadOutline p = only(docOf(new Flash(10, 5, new RectangleAperture(10, 2.0, 0.5), 30, 1.0, false, false)));
        assertEquals(PadOutline.Kind.RECTANGLE, p.kind());
        assertBox(p, 10, 5, 2.0, 0.5, 30);
        assertEquals(4, p.ring().size());
        assertCounterClockwise(p);

        // A quarter turn is axis-aligned: reported as 0° with the sides swapped.
        PadOutline q = only(docOf(new Flash(0, 0, new RectangleAperture(10, 2.0, 0.5), 90, 1.0, false, false)));
        assertBox(q, 0, 0, 0.5, 2.0, 0);
        // Scale and mirroring apply.
        PadOutline s = only(docOf(new Flash(0, 0, new RectangleAperture(10, 2.0, 0.5), 30, 2.0, true, false)));
        assertBox(s, 0, 0, 4.0, 1.0, 150);
        assertCounterClockwise(s);
    }

    @Test
    void obroundFlashIsAStadium() {
        PadOutline p = only(docOf(new Flash(1, 1, new ObroundAperture(10, 3.0, 1.0), 45, 1.0, false, false)));
        assertEquals(PadOutline.Kind.OBROUND, p.kind());
        assertBox(p, 1, 1, 3.0, 1.0, 45);
        assertCounterClockwise(p);
        // Every vertex is exactly half the height from the stadium's spine.
        double ux = Math.cos(Math.toRadians(45));
        double uy = Math.sin(Math.toRadians(45));
        for (double[] v : p.ring()) {
            double along = Math.max(-1.0, Math.min(1.0, (v[0] - 1) * ux + (v[1] - 1) * uy));
            double dist = Math.hypot(v[0] - 1 - along * ux, v[1] - 1 - along * uy);
            assertEquals(0.5, dist, 1e-9);
        }
        // An obround as wide as it is tall is a circle.
        assertEquals(PadOutline.Kind.CIRCLE, only(docOf(new Flash(0, 0, new ObroundAperture(10, 1, 1)))).kind());
    }

    @Test
    void rotatedMacroRectangleIsARectangle() {
        MacroTemplate t = new MacroTemplate("RECT");
        t.parse("21,1,$1,$2,0,0,$3*");
        MacroAperture rect = new MacroAperture(10, t, List.of(1.2, 0.4, 20.0));
        PadOutline p = only(docOf(new Flash(5, 5, rect, 10, 1.0, false, false)));
        assertEquals(PadOutline.Kind.RECTANGLE, p.kind());
        assertBox(p, 5, 5, 1.2, 0.4, 30);
        assertEquals(4, p.ring().size());

        // An outline primitive drawing the same rectangle as four vertices is one too.
        MacroTemplate outline = new MacroTemplate("OUT");
        outline.parse("4,1,4,-1,-0.5,1,-0.5,1,0.5,-1,0.5,-1,-0.5,0*");
        PadOutline o = only(docOf(new Flash(0, 0, new MacroAperture(11, outline, List.of()))));
        assertEquals(PadOutline.Kind.RECTANGLE, o.kind());
        assertBox(o, 0, 0, 2, 1, 0);
    }

    @Test
    void roundedRectangleMacroIsAPolygonBoxedByItsExtent() {
        MacroTemplate t = new MacroTemplate("RoundRect");
        t.parse("21,1,1.0,0.6,0,0,0*21,1,1.2,0.4,0,0,0*1,1,0.2,0.5,0.2*1,1,0.2,-0.5,0.2*"
                + "1,1,0.2,-0.5,-0.2*1,1,0.2,0.5,-0.2*");
        PadOutline p = only(docOf(new Flash(2, 3, new MacroAperture(10, t, List.of()), 90, 1.0, false, false)));
        assertEquals(PadOutline.Kind.POLYGON, p.kind());
        assertBox(p, 2, 3, 0.6, 1.2, 0);
        assertCounterClockwise(p);
    }

    @Test
    void anApertureHoleIsNotPartOfTheRingAndSpoilsTheRectangle() {
        PadOutline p = only(docOf(new Flash(0, 0, new RectangleAperture(10, 2.0, 1.0, 0.4))));
        assertEquals(PadOutline.Kind.POLYGON, p.kind());
        assertEquals(4, p.ring().size());
        assertBox(p, 0, 0, 2.0, 1.0, 0);
    }

    @Test
    void polygonApertureIsAPolygonInItsMinimumBox() {
        PadOutline p = only(docOf(new Flash(0, 0, new PolygonAperture(10, 2.0, 6, 0))));
        assertEquals(PadOutline.Kind.POLYGON, p.kind());
        assertEquals(6, p.ring().size());
        assertEquals(2.0 * Math.sqrt(3) / 2, Math.min(p.width(), p.height()), 1e-9);
    }

    @Test
    void drawIsTheBandItsApertureSweeps() {
        Draw d = new Draw(0, 0, 3, 4, new CircleAperture(10, 0.5));
        PadOutline p = only(docOf(d));
        assertEquals(PadOutline.Kind.STROKE, p.kind());
        assertEquals(0.5, p.strokeWidth(), EPS);
        // A capsule along a 5 mm segment at atan2(4,3).
        assertBox(p, 1.5, 2, 5.5, 0.5, Math.toDegrees(Math.atan2(4, 3)));
        assertCounterClockwise(p);
    }

    @Test
    void arcIsAnAnnularBandWithRoundEnds() {
        Arc a = new Arc(1, 0, -1, 0, 0, 0, false, new CircleAperture(10, 0.2));
        PadOutline p = only(docOf(a));
        assertEquals(PadOutline.Kind.STROKE, p.kind());
        assertEquals(0.2, p.strokeWidth(), EPS);
        double maxY = p.ring().stream().mapToDouble(v -> v[1]).max().orElseThrow();
        double minY = p.ring().stream().mapToDouble(v -> v[1]).min().orElseThrow();
        assertEquals(1.1, maxY, PadOutlines.FLATNESS_MM);
        assertEquals(-0.1, minY, PadOutlines.FLATNESS_MM, "the round end caps reach below the chord");
    }

    @Test
    void regionArcsAreFlattenedAndDisjointContoursAreSeparateEntries() {
        Region r = new Region();
        r.setApertureAttributes(Map.of(".AperFunction", List.of("SMDPad", "CuDef")));
        Contour disc = new Contour(2, 0);
        disc.addArcTo(2, 0, 0, 0, false);
        r.addContour(disc);
        Contour square = new Contour(5, -1);
        square.addLineTo(7, -1);
        square.addLineTo(7, 1);
        square.addLineTo(5, 1);
        square.addLineTo(5, -1);
        r.addContour(square);
        List<PadOutline> all = PadOutlines.of(docOf(r));

        assertEquals(2, all.size());
        PadOutline round = all.stream().filter(p -> p.kind() == PadOutline.Kind.POLYGON).findFirst().orElseThrow();
        PadOutline box = all.stream().filter(p -> p.kind() == PadOutline.Kind.RECTANGLE).findFirst().orElseThrow();
        for (double[] v : round.ring()) {
            double d = Math.hypot(v[0], v[1]);
            assertTrue(d <= 2 + 1e-9 && d >= 2 - PadOutlines.FLATNESS_MM, "within flatness: " + d);
        }
        assertBox(box, 6, 0, 2, 2, 0);
        assertEquals("SMDPad,CuDef", box.apertureFunction());
        assertEquals(0, box.objectIndex());
    }

    @Test
    void clearObjectsAreSkippedAndAttributesReported() {
        CircleAperture fid = new CircleAperture(10, 1.0);
        fid.setAttributes(Map.of(".AperFunction", List.of("FiducialPad", "Global")));
        Flash fiducial = new Flash(0, 0, fid);
        Flash clear = new Flash(0, 0, new CircleAperture(11, 0.5));
        clear.setPolarity(Polarity.CLEAR);
        Flash pad = new Flash(4, 0, new RectangleAperture(12, 1, 1));
        pad.setAttributes(Map.of(".C", List.of("R12")));
        List<PadOutline> all = PadOutlines.of(docOf(fiducial, clear, pad));

        assertEquals(2, all.size());
        assertEquals("FiducialPad,Global", all.get(0).apertureFunction());
        assertNull(all.get(0).componentRef());
        assertEquals(2, all.get(1).objectIndex());
        assertEquals("R12", all.get(1).componentRef());
        assertNull(all.get(1).apertureFunction());
    }

    @Test
    void parsedPasteLayerGivesOneOutlinePerPad() {
        String gerber = """
                %FSLAX46Y46*%
                %MOMM*%
                %TA.AperFunction,SMDPad,CuDef*%
                %ADD10R,1.0X0.5*%
                %AMRR*21,1,$1,$2,0,0,$3*%
                %ADD11RR,1.5X0.3X45*%
                %TD*%
                %TO.C,U1*%
                D10*
                X1000000Y1000000D03*
                %LR90*%
                X3000000Y1000000D03*
                %LR0*%
                %TD*%
                D11*
                X5000000Y1000000D03*
                M02*
                """;
        GerberDocument doc = new GerberParser().parse(gerber);
        List<PadOutline> all = PadOutlines.of(doc);
        assertEquals(3, all.size());
        assertBox(all.get(0), 1, 1, 1.0, 0.5, 0);
        assertEquals("U1", all.get(0).componentRef());
        assertEquals("SMDPad,CuDef", all.get(0).apertureFunction());
        assertBox(all.get(1), 3, 1, 0.5, 1.0, 0);
        assertEquals(PadOutline.Kind.RECTANGLE, all.get(2).kind());
        assertBox(all.get(2), 5, 1, 1.5, 0.3, 45);
    }

    @Test
    void strokeTextDrawsCentrelinesAtTheRequestedHeight() {
        List<double[]> segments = StrokeText.segments("L1", 10, 20, 2.0);
        assertTrue(segments.size() >= 3, "L is two strokes, 1 at least two");
        double minY = segments.stream().mapToDouble(s -> Math.min(s[1], s[3])).min().orElseThrow();
        double maxY = segments.stream().mapToDouble(s -> Math.max(s[1], s[3])).max().orElseThrow();
        double minX = segments.stream().mapToDouble(s -> Math.min(s[0], s[2])).min().orElseThrow();
        double maxX = segments.stream().mapToDouble(s -> Math.max(s[0], s[2])).max().orElseThrow();
        assertEquals(20, minY, EPS, "baseline");
        assertEquals(22, maxY, EPS, "cap height");
        assertEquals(10, minX, EPS, "left-aligned");
        assertEquals(10 + StrokeText.width("L1", 2.0), maxX, 0.4 + EPS);
        assertEquals(StrokeText.width("AB", 2.0), StrokeText.width("ab", 2.0), EPS);
        assertEquals(0, StrokeText.width("", 2.0), EPS);
        assertTrue(StrokeText.segments(" ", 0, 0, 1).isEmpty(), "a space draws nothing");
    }
}
