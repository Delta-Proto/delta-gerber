package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #11: how narrow the copper actually gets, from strokes and from the necks of pours. */
class ConductorWidthDetectorTest {

    private static GerberDocument copper(String body) {
        GerberDocument doc = new GerberParser().parse("%FSLAX46Y46*%\n%MOMM*%\n" + body + "M02*\n");
        doc.setFileName("top.gbr");
        return doc;
    }

    private static String region(double... xy) {
        StringBuilder sb = new StringBuilder("G36*\n");
        for (int i = 0; i < xy.length; i += 2) {
            sb.append(String.format("X%dY%dD%s*\n", Math.round(xy[i] * 1e6), Math.round(xy[i + 1] * 1e6), i == 0 ? "02" : "01"));
        }
        return sb.append(String.format("X%dY%dD01*\nG37*\n", Math.round(xy[0] * 1e6), Math.round(xy[1] * 1e6))).toString();
    }

    @Test
    void strokesReportTheirApertureWhateverItsShape() {
        ConductorWidthResult r = ConductorWidthDetector.detect(copper("%ADD10C,0.15*%\n%ADD11R,0.4X0.25*%\n"
                + "D10*\nX0Y0D02*\nX5000000Y0D01*\n"
                + "D11*\nX0Y2000000D02*\nX5000000Y2000000D01*\n"));
        assertEquals(0.15, r.getMinMm(), 1e-9);
        List<ConductorWidth> widths = r.getWidths();
        assertEquals(2, widths.size());
        assertEquals(ConductorWidth.Kind.STROKE, widths.get(0).kind());
        assertEquals(0.25, widths.get(1).widthMm(), 1e-9, "a rectangular brush is as wide as its short side");
    }

    @Test
    void aDumbbellNecksAtItsBar() {
        // Two 2 mm squares joined by a 0.1 mm wide, 2 mm long bar.
        ConductorWidthResult r = ConductorWidthDetector.detect(copper(region(
                0, 0, 2, 0, 2, 0.95, 4, 0.95, 4, 0, 6, 0, 6, 2, 4, 2, 4, 1.05, 2, 1.05, 2, 2, 0, 2)));
        assertEquals(0.1, r.getMinMm(), 1e-9);
        ConductorWidth neck = r.getMin();
        assertEquals(ConductorWidth.Kind.REGION_NECK, neck.kind());
        assertEquals(1.0, neck.yMm(), 1e-9);
        assertTrue(neck.xMm() > 2 && neck.xMm() < 4);
    }

    @Test
    void aRectangleIsAsWideAsItsShortSideAndNoNarrower() {
        ConductorWidthResult r = ConductorWidthDetector.detect(copper(region(0, 0, 5, 0, 5, 0.5, 0, 0.5)));
        assertEquals(0.5, r.getMinMm(), 1e-9, "the corners do not read as zero");
    }

    @Test
    void aWedgeIsNotANeck() {
        ConductorWidthResult r = ConductorWidthDetector.detect(copper(region(0, 0, 5, 0, 5, 0.6, 0, 2.0)));
        assertNull(r.getMinMm(), "a pour tapering to a point has no width to report");
    }

    @Test
    void theWebBetweenTwoAntipadsIsANeck() {
        ConductorWidthResult r = ConductorWidthDetector.detect(copper(
                region(0, 0, 10, 0, 10, 10, 0, 10)
                + "%ADD10C,1.0*%\n%LPC*%\nD10*\nX4000000Y5000000D03*\nX5600000Y5000000D03*\n%LPD*%\n"));
        assertEquals(0.6, r.getMinMm(), 1e-3, "to the flattening of the antipads");
        assertEquals(4.8, r.getMin().xMm(), 1e-3);
    }

    @Test
    void aSliverLyingOnAPadIsNotAConductor() {
        // A 1 mm pad with a 0.02 mm wide region tail along its edge, as an Altium teardrop leaves.
        ConductorWidthResult r = ConductorWidthDetector.detect(copper("%ADD10R,1.0X1.0*%\nD10*\nX0Y0D03*\n"
                + region(-0.5, 0.49, 0.5, 0.49, 0.5, 0.51, -0.5, 0.51)));
        assertNull(r.getMinMm());
    }

    @Test
    void aPadPaintedStrokeBesideStrokeIsNotAConductorOfTheBrushWidth() {
        // Five 0.1 mm strokes 0.08 mm apart fill a 0.42 mm pad; a 0.2 mm track leaves it.
        StringBuilder body = new StringBuilder("%ADD10C,0.1*%\n%ADD11C,0.2*%\nD10*\n");
        for (int i = 0; i < 5; i++) {
            body.append(String.format("X0Y%dD02*\nX700000Y%dD01*\n", i * 80000, i * 80000));
        }
        body.append("D11*\nX700000Y160000D02*\nX5000000Y160000D01*\n");
        ConductorWidthResult r = ConductorWidthDetector.detect(copper(body.toString()));
        assertEquals(0.2, r.getMinMm(), 1e-9, "each paint stroke has copper on one side at least");
        assertEquals(1, r.getWidths().size());
    }

    @Test
    void aStrokeWithNoLengthIsADotNotAConductor() {
        ConductorWidthResult r = ConductorWidthDetector.detect(copper("%ADD10C,0.1*%\n%ADD11C,0.2*%\n"
                + "D10*\nX0Y0D02*\nX0Y0D01*\nD11*\nX0Y2000000D02*\nX5000000Y2000000D01*\n"));
        assertEquals(0.2, r.getMinMm(), 1e-9);
    }

    @Test
    void floatingCopperIsLeftOutOfTheWidth() {
        // A pad with a 0.2 mm track, and 0.1 mm copper lettering connected to nothing.
        GerberDocument doc = copper("%ADD10C,1.0*%\n%ADD11C,0.2*%\n%ADD12C,0.1*%\n"
                + "D10*\nX0Y0D03*\nD11*\nX0Y0D02*\nX5000000Y0D01*\n"
                + "D12*\nX10000000Y10000000D02*\nX11000000Y11000000D01*\n");
        com.deltaproto.deltagerber.dfm.geometry.CopperGeometry g =
                com.deltaproto.deltagerber.dfm.geometry.CopperGeometry.of(doc);
        assertEquals(0.1, ConductorWidthDetector.detect(g, "top.gbr", 1.0).getMinMm(), 1e-9);
        FloatingCopperResult floating = FloatingCopperDetector.detect(
                com.deltaproto.deltagerber.dfm.geometry.CopperNets.of(g), "top.gbr", null, true);
        assertEquals(0.2, ConductorWidthDetector.detect(g, "top.gbr", 1.0, floating).getMinMm(), 1e-9);
    }

    @Test
    void necksWiderThanTheCutoffAreNotLookedFor() {
        ConductorWidthResult r = ConductorWidthDetector.detect(copper(region(0, 0, 5, 0, 5, 1.5, 0, 1.5)));
        assertNull(r.getMinMm());
    }
}
