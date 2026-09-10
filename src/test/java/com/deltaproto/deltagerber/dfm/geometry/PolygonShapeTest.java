package com.deltaproto.deltagerber.dfm.geometry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolygonShapeTest {

    private static final double[] SQUARE_CW = {0, 0, 0, 10, 10, 10, 10, 0};
    private static final double[] HOLE_CCW = {4, 4, 6, 4, 6, 6, 4, 6};

    @Test
    void windingIsNormalisedToMaterialOnTheLeft() {
        PolygonShape p = PolygonShape.of(List.of(SQUARE_CW, HOLE_CCW));
        assertEquals(100 - 4, p.area(), 1e-9, "outer counter-clockwise, hole clockwise");
        Capsule outer = p.edge(0, 0);
        double crossZ = (outer.x2 - outer.x1) * (5 - outer.y1) - (outer.y2 - outer.y1) * (5 - outer.x1);
        assertTrue(crossZ > 0, "the centre lies to the left of an outer edge");
    }

    @Test
    void containmentHonoursHolesAndTreatsTheBoundaryAsOutside() {
        PolygonShape p = PolygonShape.of(List.of(SQUARE_CW, HOLE_CCW));
        assertTrue(p.contains(1, 1));
        assertFalse(p.contains(5, 5), "inside the hole");
        assertFalse(p.contains(0, 5), "on the boundary");
        assertFalse(p.contains(11, 5));
    }

    @Test
    void pathBetweenEdgesIsTheShorterWayRound() {
        PolygonShape p = PolygonShape.of(List.of(SQUARE_CW));
        assertEquals(10, p.pathBetween(0, 0, 2), 1e-9, "opposite sides: one side between them");
        assertEquals(0, p.pathBetween(0, 0, 1), 1e-9, "consecutive edges");
    }

    @Test
    void repeatedPointsAreDroppedAndDegenerateRingsIgnored() {
        PolygonShape p = PolygonShape.of(List.of(
                new double[]{0, 0, 0, 0, 10, 0, 10, 10, 10, 10, 0, 10, 0, 0},
                new double[]{20, 20, 21, 21}));
        assertEquals(1, p.ringCount());
        assertEquals(4, p.ringSize(0));
    }
}
