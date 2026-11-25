package com.deltaproto.deltagerber.edit;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.aperture.BlockAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.MacroAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.ObroundAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.PolygonAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.RectangleAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroTemplate;
import com.deltaproto.deltagerber.model.gerber.attribute.FileAttribute;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.writer.GerberWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code parse(write(doc))} inks what {@code doc} inks: object by object where the objects
 * correspond (they do unless the document flashes a block aperture, which the parser expands), and
 * as a whole image always — the dark objects added and the clear ones subtracted in order.
 */
class GerberRoundTripTest {

    private static final double TOL_MM = 1e-5;

    @Test
    void everyFeatureBuiltByHandSurvivesARoundTrip() {
        GerberDocument doc = everything();
        String text = GerberWriter.write(doc);
        GerberDocument back = new GerberParser().parse(text);

        assertTrue(back.getWarnings().isEmpty(), back.getWarnings().toString());
        assertSameObjects(doc, back);
        assertSameImage(doc, back);

        assertEquals("Paste,Top", String.join(",", back.getFileAttributes().get(".FileFunction").getValues()));
        assertEquals("Paste,Top", String.join(",", back.getFileFunctionValues()));
        Flash fiducial = (Flash) back.getObjects().get(0);
        assertEquals(List.of("FiducialPad", "Global"), fiducial.getAperture().getAttribute(".AperFunction"));
        assertEquals("U7", back.getObjects().get(1).getComponentRef());
        assertEquals(null, back.getObjects().get(2).getComponentRef(), "%TD must end the component's scope");
        Region region = (Region) back.getObjects().stream().filter(o -> o instanceof Region).findFirst().orElseThrow();
        assertEquals(List.of("SMDPad", "CuDef"), region.getApertureAttributes().get(".AperFunction"));
    }

    @Test
    void writingIsStableAfterTheFirstRoundTrip() {
        String once = GerberWriter.write(new GerberParser().parse(GerberWriter.write(everything())));
        String twice = GerberWriter.write(new GerberParser().parse(once));
        assertEquals(once, twice);
    }

    @Test
    void aFlashedBlockApertureInksTheSameImage() {
        GerberDocument doc = new GerberDocument();
        CircleAperture dot = new CircleAperture(10, 0.4);
        RectangleAperture bar = new RectangleAperture(11, 1.0, 0.2);
        BlockAperture block = new BlockAperture(12);
        block.add(new Flash(0, 0, bar));
        Flash hole = new Flash(0.3, 0, dot);
        hole.setPolarity(Polarity.CLEAR);
        block.add(hole);
        block.add(new Draw(-0.5, 0.5, 0.5, 0.5, dot));
        doc.addObject(new Flash(5, 5, block));
        doc.addObject(new Flash(10, 5, block, 90, 2.0, true, false));

        GerberDocument back = new GerberParser().parse(GerberWriter.write(doc));

        assertEquals(6, back.getObjects().size(), "the parser expands each block flash into its 3 objects");
        assertSameImage(doc, back);
    }

    static Stream<Path> suite() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("test-gerber-suite"))) {
            return files.filter(p -> p.toString().endsWith(".gbr")).sorted().toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("suite")
    void suiteFilesSurviveARoundTrip(Path file) throws IOException {
        GerberDocument doc = new GerberParser().parse(Files.readString(file));
        GerberDocument back = new GerberParser().parse(GerberWriter.write(doc));
        assertSameObjects(doc, back);
        assertSameImage(doc, back);
    }

    // ------------------------------------------------------------------------------------------

    /** A document using every construct the writer has to state. */
    static GerberDocument everything() {
        GerberDocument doc = new GerberDocument();
        doc.addFileAttribute(new FileAttribute(".FileFunction", "Paste", "Top"));
        doc.addFileAttribute(new FileAttribute(".Part", "Single"));

        CircleAperture fiducial = new CircleAperture(10, 1.0);
        fiducial.setAttributes(Map.of(".AperFunction", List.of("FiducialPad", "Global")));
        CircleAperture ring = new CircleAperture(11, 1.2, 0.5);
        RectangleAperture rect = new RectangleAperture(12, 1.5, 0.6);
        RectangleAperture rectHole = new RectangleAperture(13, 2.0, 1.0, 0.4);
        ObroundAperture obround = new ObroundAperture(14, 2.0, 0.8);
        ObroundAperture obroundHole = new ObroundAperture(15, 0.8, 2.0, 0.3);
        PolygonAperture hexagon = new PolygonAperture(16, 1.6, 6, 15);
        PolygonAperture octagonHole = new PolygonAperture(17, 2.0, 8, 0, 0.6);
        CircleAperture trace = new CircleAperture(18, 0.25);

        MacroTemplate roundRect = new MacroTemplate("RoundRect");
        roundRect.parse("0 rounded rectangle*$5=$1-$3*$6=$2-$3*21,1,$5,$2,0,0,$4*21,1,$1,$6,0,0,$4*"
                + "1,1,$3,$5/2,$6/2,$4*1,1,$3,-$5/2,$6/2,$4*1,1,$3,-$5/2,-$6/2,$4*1,1,$3,$5/2,-$6/2,$4*");
        MacroAperture rr = new MacroAperture(19, roundRect, List.of(1.6, 0.9, 0.3, 30.0));
        MacroTemplate thermal = new MacroTemplate("THERM");
        thermal.parse("7,0,0,$1,$2,$3,45*");
        MacroAperture therm = new MacroAperture(20, thermal, List.of(1.8, 1.2, 0.3));
        MacroTemplate inchBox = new MacroTemplate("InchBox");
        inchBox.parse("4,1,4,0,0,0.1,0,0.1,0.05,0,0.05,0,0,0*20,1,0.01,0,0,0.1,0.05,0*5,0,4,0.05,0.025,0.02,0*");
        MacroAperture inch = new MacroAperture(21, inchBox, List.of(), 25.4);

        // Programmatic apertures need not be registered; register some to check both paths.
        doc.addAperture(fiducial);
        doc.addAperture(rect);

        doc.addObject(new Flash(1, 1, fiducial));
        Flash pad = new Flash(4, 1, rect);
        pad.setAttributes(new LinkedHashMap<>(Map.of(".C", List.of("U7"), ".P", List.of("U7", "3"))));
        doc.addObject(pad);
        doc.addObject(new Flash(7, 1, ring));
        doc.addObject(new Flash(10, 1, rectHole, 30, 1.0, false, false));
        doc.addObject(new Flash(13, 1, obround, 0, 1.5, false, false));
        doc.addObject(new Flash(16, 1, obroundHole, 45, 1.0, true, false));
        doc.addObject(new Flash(19, 1, hexagon, 0, 1.0, false, true));
        doc.addObject(new Flash(22, 1, octagonHole));
        doc.addObject(new Flash(1, 5, rr));
        doc.addObject(new Flash(4, 5, rr, 60, 1.0, true, true));
        doc.addObject(new Flash(7, 5, therm));
        doc.addObject(new Flash(10, 5, inch, 10, 0.5, false, false));

        doc.addObject(new Draw(1, 9, 6, 9, trace));
        doc.addObject(new Draw(6, 9, 6, 12, trace));
        doc.addObject(new Draw(8, 9, 10, 11, rect));
        doc.addObject(new Arc(12, 9, 14, 11, 14, 9, true, trace));
        doc.addObject(new Arc(16, 9, 18, 11, 18, 9, false, trace));
        doc.addObject(new Arc(22, 10, 22, 10, 21, 10, false, trace));

        Region region = new Region();
        region.setApertureAttributes(Map.of(".AperFunction", List.of("SMDPad", "CuDef")));
        Contour outline = new Contour(1, 14);
        outline.addLineTo(5, 14);
        outline.addArcTo(5, 18, 5, 16, false);
        outline.addLineTo(1, 18);
        outline.addLineTo(1, 14);
        region.addContour(outline);
        Contour second = new Contour(7, 14);
        second.addLineTo(9, 14);
        second.addLineTo(8, 16);
        second.addLineTo(7, 14);
        region.addContour(second);
        doc.addObject(region);

        Flash clear = new Flash(3, 16, trace);
        clear.setPolarity(Polarity.CLEAR);
        doc.addObject(clear);
        Region clearRegion = new Region();
        clearRegion.setPolarity(Polarity.CLEAR);
        Contour cut = new Contour(7.5, 14.2);
        cut.addLineTo(8.5, 14.2);
        cut.addLineTo(8, 15);
        clearRegion.addContour(cut);   // left open: the writer closes it
        doc.addObject(clearRegion);
        doc.addObject(new Flash(12, 16, rect, 0, 1.0, false, false));
        return doc;
    }

    static void assertSameObjects(GerberDocument expected, GerberDocument actual) {
        assertEquals(expected.getObjects().size(), actual.getObjects().size(), "object count");
        for (int i = 0; i < expected.getObjects().size(); i++) {
            GraphicsObject e = expected.getObjects().get(i);
            GraphicsObject a = actual.getObjects().get(i);
            assertEquals(e.getClass(), a.getClass(), "object " + i);
            assertEquals(e.getPolarity(), a.getPolarity(), "polarity of object " + i);
            assertEquals(attributes(e), attributes(a), "attributes of object " + i);
            Area ea = PadOutlines.inked(e);
            Area aa = PadOutlines.inked(a);
            assertEquals(ea == null, aa == null, "object " + i + " inks something");
            if (ea != null) {
                assertSameArea(ea, aa, "object " + i + " " + e);
            }
        }
    }

    private static Map<String, List<String>> attributes(GraphicsObject o) {
        return o.getAttributes() == null ? Map.of() : o.getAttributes();
    }

    static void assertSameImage(GerberDocument expected, GerberDocument actual) {
        assertSameArea(image(expected), image(actual), "whole image");
    }

    private static Area image(GerberDocument doc) {
        Area image = new Area();
        for (GraphicsObject o : doc.getObjects()) {
            Area a = PadOutlines.inked(o);
            if (a == null) {
                continue;
            }
            if (o.getPolarity() == Polarity.CLEAR) {
                image.subtract(a);
            } else {
                image.add(a);
            }
        }
        return image;
    }

    private static void assertSameArea(Area expected, Area actual, String what) {
        assertNotNull(actual, what);
        Area xor = new Area(expected);
        xor.exclusiveOr(actual);
        double total = area(expected);
        double diff = area(xor);
        // The writer rounds to 1 nm; the outlines differ by that times their perimeter at most.
        assertTrue(diff <= TOL_MM * Math.max(1, Math.sqrt(total)) * 10,
                what + ": inked area differs by " + diff + " mm² of " + total);
        assertEquals(total, area(actual), Math.max(1e-6, total * 1e-6), what + " area");
    }

    /** Absolute area: outer boundaries minus holes, as Area's subpaths give them. */
    private static double area(Area a) {
        double sum = 0;
        double[] c = new double[6];
        double sx = 0, sy = 0, px = 0, py = 0, ring = 0;
        for (PathIterator it = a.getPathIterator(null, 1e-4); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    sx = px = c[0];
                    sy = py = c[1];
                    ring = 0;
                }
                case PathIterator.SEG_LINETO -> {
                    ring += px * c[1] - c[0] * py;
                    px = c[0];
                    py = c[1];
                }
                case PathIterator.SEG_CLOSE -> {
                    ring += px * sy - sx * py;
                    sum += ring / 2;
                    px = sx;
                    py = sy;
                }
                default -> {
                }
            }
        }
        return Math.abs(sum);
    }
}
