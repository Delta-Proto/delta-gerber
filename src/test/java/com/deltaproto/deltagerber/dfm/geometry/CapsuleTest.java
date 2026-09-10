package com.deltaproto.deltagerber.dfm.geometry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one distance primitive: exact for round apertures, so the answers here are arithmetic. */
class CapsuleTest {

    @Test
    void twoRoundPadsMeasureBetweenTheirRims() {
        Capsule a = Capsule.disc(0, 0, 0.5);
        Capsule b = Capsule.disc(2, 0, 0.5);
        assertEquals(1.0, a.distance(b), 1e-12);
        double[] p = a.closestSurfacePoints(b);
        assertEquals(0.5, p[0], 1e-12);
        assertEquals(1.5, p[2], 1e-12);
    }

    @Test
    void parallelTracesMeasureBetweenTheirEdges() {
        Capsule a = new Capsule(0, 0, 10, 0, 0.1);
        Capsule b = new Capsule(0, 1, 10, 1, 0.1);
        assertEquals(0.8, a.distance(b), 1e-12);
    }

    @Test
    void crossingTracesOverlapAndTheOverlapPointIsTheCrossing() {
        Capsule a = new Capsule(0, 0, 10, 10, 0.1);
        Capsule b = new Capsule(0, 10, 10, 0, 0.1);
        assertEquals(0, a.distance(b), 1e-12);
        double[] x = a.axisIntersection(b);
        assertNotNull(x);
        assertEquals(5, x[0], 1e-12);
        assertEquals(5, x[1], 1e-12);
        double[] p = a.closestSurfacePoints(b);
        assertEquals(p[0], p[2], 1e-12);
        assertTrue(a.contains(p[0], p[1]) && b.contains(p[0], p[1]));
    }

    @Test
    void parallelAxesNeverCross() {
        assertNull(new Capsule(0, 0, 10, 0, 0).axisIntersection(new Capsule(0, 1, 10, 1, 0)));
        assertNull(new Capsule(0, 0, 10, 0, 0).axisIntersection(new Capsule(2, 0, 12, 0, 0)));
    }

    @Test
    void overlappingTracesMeetInsideBoth() {
        Capsule a = new Capsule(0, 0, 10, 0, 0.2);
        Capsule b = new Capsule(5, 0.3, 15, 0.3, 0.2);     // axes 0.3 apart, radii sum to 0.4
        assertEquals(0, a.distance(b), 1e-12);
        double[] p = a.closestSurfacePoints(b);
        assertTrue(a.contains(p[0], p[1]));
        assertTrue(b.contains(p[0], p[1]));
    }

    @Test
    void containmentIsStrictAndBareEdgesContainNothing() {
        Capsule pad = Capsule.disc(0, 0, 1);
        assertTrue(pad.contains(0.5, 0.5));
        assertFalse(pad.contains(1, 0));
        assertFalse(new Capsule(0, 0, 10, 0, 0).contains(5, 0));
    }
}
