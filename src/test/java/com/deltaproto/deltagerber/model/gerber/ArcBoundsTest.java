package com.deltaproto.deltagerber.model.gerber;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ArcBounds} must add a cardinal point only when the sweep actually reaches it. The naive
 * {@code center ± radius} box is right for a full circle and wrong for everything else.
 */
class ArcBoundsTest {

    private static final double EPS = 1e-9;

    private static void assertBounds(BoundingBox actual, double minX, double minY, double maxX, double maxY) {
        assertEquals(minX, actual.getMinX(), EPS, "minX");
        assertEquals(minY, actual.getMinY(), EPS, "minY");
        assertEquals(maxX, actual.getMaxX(), EPS, "maxX");
        assertEquals(maxY, actual.getMaxY(), EPS, "maxY");
    }

    @Test
    @DisplayName("A full circle (coincident endpoints) spans center ± radius")
    void fullCircle() {
        // How Gerber writes a circle: start where you end. This is the NDc board profile.
        assertBounds(ArcBounds.of(16, 0, 16, 0, 0, 0, false), -16, -16, 16, 16);
        assertBounds(ArcBounds.of(16, 0, 16, 0, 0, 0, true), -16, -16, 16, 16);
    }

    @Test
    @DisplayName("A quarter arc spans its endpoints only — no cardinal point lies strictly inside")
    void quarterArcHugsItsEndpoints() {
        // (10,0) → (0,10) counter-clockwise about the origin: passes 0° and 90°, both endpoints.
        assertBounds(ArcBounds.of(10, 0, 0, 10, 0, 0, false), 0, 0, 10, 10);
    }

    @Test
    @DisplayName("The same endpoints swept the other way wrap three cardinal points")
    void quarterArcClockwiseSweepsTheLongWayRound() {
        // (10,0) → (0,10) clockwise goes through 270°, 180°, 90° — three quarters of the circle.
        assertBounds(ArcBounds.of(10, 0, 0, 10, 0, 0, true), -10, -10, 10, 10);
    }

    @Test
    @DisplayName("A half arc includes the cardinal point it crosses, and no others")
    void halfArc() {
        // (10,0) → (-10,0) counter-clockwise crosses 90° (top) but never 270° (bottom).
        assertBounds(ArcBounds.of(10, 0, -10, 0, 0, 0, false), -10, 0, 10, 10);
        // Clockwise crosses 270° instead.
        assertBounds(ArcBounds.of(10, 0, -10, 0, 0, 0, true), -10, -10, 10, 0);
    }

    @Test
    @DisplayName("An arc off the origin keeps its centre's offset")
    void offCentreArc() {
        assertBounds(ArcBounds.of(105, 100, 100, 105, 100, 100, false), 100, 100, 105, 105);
    }

    @Test
    @DisplayName("A zero-radius arc collapses to its point")
    void degenerateArc() {
        assertBounds(ArcBounds.of(5, 5, 5, 5, 5, 5, false), 5, 5, 5, 5);
    }
}
