package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Unit;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.attribute.FileAttribute;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exercises a KiCad-style Gerber X2 drill file (a .gbr file whose FileFunction
 * attribute identifies it as a drill layer, as emitted by KiCad 9 for PTH/NPTH).
 *
 * The data mirrors a real board's PTH drill layer: 679 flashes across 11
 * circular apertures ranging from 0.5 mm vias to 3.9 mm mounting holes.
 * Identifying project metadata (ProjectId, CreationDate, TO.C component
 * references) has been stripped from the fixture.
 */
public class GerberX2DrillTest {

    private static final String FIXTURE = "/gerber-x2-drill-pth.gbr";

    private static String loadFixture() {
        try (InputStream is = GerberX2DrillTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(is, "fixture not found on classpath: " + FIXTURE);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            fail("failed to read fixture", e);
            return null;
        }
    }

    @Test
    void parsesAllDrillHits() {
        GerberDocument doc = new GerberParser().parse(loadFixture());

        assertEquals(Unit.MM, doc.getUnit());
        assertEquals(11, doc.getApertures().size());
        assertEquals(679, doc.getObjects().size());

        Map<Integer, Integer> flashCounts = new TreeMap<>();
        for (GraphicsObject obj : doc.getObjects()) {
            assertInstanceOf(Flash.class, obj, "all drill operations should be flashes");
            Flash f = (Flash) obj;
            flashCounts.merge(f.getAperture().getDCode(), 1, Integer::sum);
        }

        // Counts match the raw file exactly (377 + 95 + 6 + 23 + 64 + 44 + 43 + 12 + 6 + 5 + 4).
        assertEquals(377, flashCounts.get(10));
        assertEquals( 95, flashCounts.get(11));
        assertEquals(  6, flashCounts.get(12));
        assertEquals( 23, flashCounts.get(13));
        assertEquals( 64, flashCounts.get(14));
        assertEquals( 44, flashCounts.get(15));
        assertEquals( 43, flashCounts.get(16));
        assertEquals( 12, flashCounts.get(17));
        assertEquals(  6, flashCounts.get(18));
        assertEquals(  5, flashCounts.get(19));
        assertEquals(  4, flashCounts.get(20));
    }

    @Test
    void apertureDiametersAreNormalizedToMillimeters() {
        GerberDocument doc = new GerberParser().parse(loadFixture());

        // Every drill aperture is a plain circle in mm.
        double[] expectedDiameterMm = {0.5, 0.8, 0.8, 0.95, 1.0, 1.02, 1.2, 1.3, 1.7018, 3.0, 3.9};
        for (int i = 0; i < expectedDiameterMm.length; i++) {
            int dCode = 10 + i;
            Aperture a = doc.getAperture(dCode);
            assertInstanceOf(CircleAperture.class, a, "D" + dCode + " should be a circle");
            assertEquals(expectedDiameterMm[i], ((CircleAperture) a).getDiameter(), 1e-6,
                "D" + dCode + " diameter");
        }
    }

    @Test
    void fileFunctionAttributeIdentifiesItAsDrill() {
        // The TF.FileFunction attribute is the Gerber X2 way to declare a drill
        // layer. It is the signal a viewer should use when the filename ends in
        // .gbr rather than .drl/.xln. Values here are ["Plated","1","4","PTH","Drill"].
        GerberDocument doc = new GerberParser().parse(loadFixture());

        FileAttribute ff = doc.getFileAttributes().get(".FileFunction");
        assertNotNull(ff, "FileFunction attribute should be parsed");

        assertTrue(ff.getValues().contains("Drill"),
            "FileFunction should declare this file as a Drill layer, got " + ff.getValues());
        assertTrue(ff.getValues().contains("PTH"),
            "FileFunction should mark plated through-hole, got " + ff.getValues());
    }

    @Test
    void realisticRenderProducesHolePunchesWhenLayerIsTaggedAsDrill() {
        // Regression guard: a Gerber X2 drill file tagged DRILL_PLATED must make
        // holes punch through in the realistic view. Before the fix, mech-mask was
        // only populated from DrillDocument-backed layers, so a Gerber-parsed drill
        // file left the mask empty and no holes appeared.
        GerberDocument drill = new GerberParser().parse(loadFixture());

        // Minimal outline so renderRealistic has a board boundary to clip to.
        GerberDocument outline = new GerberParser().parse("""
            %FSLAX46Y46*%
            %MOMM*%
            %ADD10C,0.1*%
            D10*
            X0Y0D02*
            X300000000Y0D01*
            X300000000Y-220000000D01*
            X0Y-220000000D01*
            X0Y0D01*
            M02*
            """);

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(java.util.List.of(
            new MultiLayerSVGRenderer.Layer("outline.gbr", outline).setLayerType(LayerType.OUTLINE),
            new MultiLayerSVGRenderer.Layer("pth.gbr", drill).setLayerType(LayerType.DRILL_PLATED)
        ));

        assertTrue(svg.contains("mech-mask"),
            "realistic render should emit a mech-mask for drill hole punches");

        // The drill aperture should be referenced inside the mech-mask so the holes
        // actually punch through.
        int maskStart = svg.indexOf("<mask id=\"mech-mask\">");
        int maskEnd = svg.indexOf("</mask>", maskStart);
        String maskBody = svg.substring(maskStart, maskEnd);
        assertTrue(maskBody.contains("href=") || maskBody.contains("<circle") || maskBody.contains("<path"),
            "mech-mask should contain drill hole shapes, but body was:\n" + maskBody);

        // And one <use> per flash — 679 in the fixture.
        int useCount = 0;
        int idx = 0;
        while ((idx = maskBody.indexOf("<use", idx)) >= 0) { useCount++; idx += 4; }
        assertEquals(679, useCount, "every drill flash should land in the mech-mask");
    }

}
