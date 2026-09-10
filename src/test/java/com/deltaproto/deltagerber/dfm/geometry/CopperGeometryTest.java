package com.deltaproto.deltagerber.dfm.geometry;

import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry.Feature;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What each Gerber object becomes: exact where it can be, half a micrometre off where it cannot. */
class CopperGeometryTest {

    static GerberDocument parse(String body) {
        return new GerberParser().parse("%FSLAX46Y46*%\n%MOMM*%\n" + body + "M02*\n");
    }

    private static Feature only(CopperGeometry g) {
        assertEquals(1, g.features().size());
        return g.feature(0);
    }

    @Test
    void roundFlashIsOneDisc() {
        Feature f = only(CopperGeometry.of(parse("%ADD10C,0.6*%\nD10*\nX1000000Y2000000D03*\n")));
        assertEquals(CopperGeometry.Kind.PAD, f.kind);
        assertEquals(1, f.solids.size());
        assertEquals(0.3, f.solids.get(0).r, 1e-12);
        assertTrue(f.contains(1.2, 2.1));
        assertNull(f.polygon);
    }

    @Test
    void obroundFlashIsACapsuleAlongItsLongAxis() {
        Feature f = only(CopperGeometry.of(parse("%ADD10O,1.0X0.4*%\nD10*\nX0Y0D03*\n")));
        Capsule c = f.solids.get(0);
        assertEquals(0.2, c.r, 1e-12);
        assertEquals(0.6, c.length(), 1e-12);
        assertEquals(0, c.y1, 1e-12);
    }

    @Test
    void rotatedRectangleFlashKeepsItsCorners() {
        Feature f = only(CopperGeometry.of(parse("%ADD10R,2.0X1.0*%\nD10*\n%LR90*%\nX0Y0D03*\n")));
        assertNotNull(f.polygon);
        assertEquals(4, f.polygon.ringSize(0));
        BoundingBox b = f.bounds;
        assertEquals(1.0, b.getWidth(), 1e-9, "rotated by 90°: the long side now stands up");
        assertEquals(2.0, b.getHeight(), 1e-9);
        assertEquals(2.0, f.polygon.area(), 1e-9);
    }

    @Test
    void roundDrawIsACapsuleOfTheApertureWidth() {
        Feature f = only(CopperGeometry.of(parse("%ADD10C,0.2*%\nD10*\nX0Y0D02*\nX5000000Y0D01*\n")));
        assertEquals(CopperGeometry.Kind.STROKE, f.kind);
        assertEquals(0.2, f.strokeWidthMm, 1e-12);
        assertEquals(0.1, f.solids.get(0).r, 1e-12);
    }

    @Test
    void rectangularDrawIsTheHullOfTheBrushAtBothEnds() {
        Feature f = only(CopperGeometry.of(parse("%ADD10R,0.4X0.2*%\nD10*\nX0Y0D02*\nX3000000Y3000000D01*\n")));
        assertNotNull(f.polygon);
        assertEquals(6, f.polygon.ringSize(0), "a diagonal sweep of a rectangle is a hexagon");
        assertEquals(0.2, f.strokeWidthMm, 1e-12);
        assertTrue(f.contains(1.5, 1.5));
        assertTrue(f.contains(3.15, 3.05));
    }

    @Test
    void arcStrokeIsFlattenedWithinTheTolerance() {
        // A quarter turn of radius 5 mm about the origin with a 0.2 mm round aperture.
        Feature f = only(CopperGeometry.of(parse("%ADD10C,0.2*%\nD10*\nG75*\nX5000000Y0D02*\nG03*\nX0Y5000000I-5000000J0D01*\n")));
        assertTrue(f.solids.size() > 50, "many chords: " + f.solids.size());
        for (Capsule c : f.solids) {
            assertEquals(0.1, c.r, 1e-12);
            double mid = Math.hypot((c.x1 + c.x2) / 2, (c.y1 + c.y2) / 2);
            assertTrue(5 - mid <= CopperGeometry.FLATNESS_MM + 1e-9, "chord sagitta " + (5 - mid));
        }
    }

    @Test
    void regionArcsAreFlattenedAndTheRegionIsAPolygon() {
        // A 2 mm square whose top edge is a semicircle bulging up.
        Feature f = only(CopperGeometry.of(parse("G36*\nX0Y0D02*\nX2000000Y0D01*\nX2000000Y2000000D01*\nG75*\nG03*\nX0Y2000000I-1000000J0D01*\nG01*\nX0Y0D01*\nG37*\n")));
        assertEquals(CopperGeometry.Kind.REGION, f.kind);
        assertEquals(4 + Math.PI / 2, f.polygon.area(), 0.003, "inscribed chords lose a little area");
        assertTrue(f.contains(1, 2.9));
        assertFalse(f.contains(0.1, 2.9));
    }

    @Test
    void clearObjectsAreKeptAsPolygonsAndLaterClearsAreLinked() {
        CopperGeometry g = CopperGeometry.of(parse(
                "%ADD10C,1.0*%\nD10*\nX0Y0D03*\n%LPC*%\nX0Y0D03*\n%LPD*%\nX5000000Y0D03*\n"));
        assertEquals(3, g.features().size());
        Feature clear = g.feature(1);
        assertTrue(clear.clear);
        assertNotNull(clear.polygon, "a clear is always walkable as a polygon");
        assertEquals(1, g.feature(0).laterClears().length);
        assertEquals(0, g.feature(2).laterClears().length, "drawn after the clear, so not under it");
        assertTrue(g.erasedAt(g.feature(0), 0.1, 0.1));
        assertEquals(-1, g.copperAt(0.1, 0.1));
        assertEquals(2, g.copperAt(5.1, 0.1));
    }

    @Test
    void strokesOutsideTheBoardAreLeftOut() {
        GerberDocument doc = parse("%ADD10C,0.05*%\nD10*\nX0Y0D02*\nX10000000Y0D01*\n%ADD11C,0.2*%\nD11*\nX1000000Y1000000D02*\nX9000000Y1000000D01*\n");
        CopperGeometry all = CopperGeometry.of(doc);
        CopperGeometry inside = CopperGeometry.of(doc, new BoundingBox(0.01, 0.01, 9.99, 9.99));
        assertEquals(2, all.features().size());
        assertEquals(1, inside.features().size());
        assertEquals(0.2, inside.feature(0).strokeWidthMm, 1e-12);
    }

    @Test
    void negativeImagesAreNotMeasured() {
        CopperGeometry g = CopperGeometry.of(parse("%IPNEG*%\n%ADD10C,1.0*%\nD10*\nX0Y0D03*\n"));
        assertTrue(g.isEmpty());
        assertEquals(1, g.warnings().size());
    }
}
