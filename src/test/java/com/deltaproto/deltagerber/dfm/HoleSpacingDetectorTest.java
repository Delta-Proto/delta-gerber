package com.deltaproto.deltagerber.dfm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The laminate between neighbouring holes, wall to wall. */
class HoleSpacingDetectorTest {

    @Test
    void theGapIsTheCentreDistanceLessBothRadii() {
        // ⌀0.4 holes 0.8 mm apart leave 0.4 mm; the ⌀1.0 hole 3 mm away is past the cutoff.
        HoleSpacingResult r = HoleSpacingDetector.detect(List.of(
                new DrilledHole(0, 0, 0.4, null), new DrilledHole(0.8, 0, 0.4, null),
                new DrilledHole(3.8, 0, 1.0, null)), 1.0);
        assertEquals(0.4, r.getMinMm(), 1e-9);
        assertEquals(0.4, r.getMin().xMm(), 1e-9, "reported at the middle of the gap");
        assertEquals(1, r.getSpacings().size(), "one pair, whichever hole it is counted from");
    }

    @Test
    void differentSizesMeetInTheMiddleOfTheirGap() {
        HoleSpacingResult r = HoleSpacingDetector.detect(List.of(
                new DrilledHole(0, 0, 1.0, null), new DrilledHole(1.0, 0, 0.6, null)), 1.0);
        assertEquals(0.2, r.getMinMm(), 1e-9);
        assertEquals(0.6, r.getMin().xMm(), 1e-9);
    }

    @Test
    void overlappingHolesAreASlotNotASpacing() {
        // Three ⌀1.3 holes 0.76 mm apart — a slot drilled as holes — and one clean neighbour.
        HoleSpacingResult r = HoleSpacingDetector.detect(List.of(
                new DrilledHole(0, 0, 1.3, null), new DrilledHole(0.76, 0, 1.3, null),
                new DrilledHole(1.52, 0, 1.3, null), new DrilledHole(1.52, 2.0, 1.3, null)), 1.0);
        assertEquals(2, r.getOverlapping().size());
        assertTrue(r.getOverlapping().get(0).distanceMm() < 0);
        assertEquals(0.7, r.getMinMm(), 1e-9);
    }

    @Test
    void holesFarApartHaveNoSpacingToReport() {
        assertNull(HoleSpacingDetector.detect(List.of(
                new DrilledHole(0, 0, 0.3, null), new DrilledHole(5, 5, 0.3, null)), 1.0).getMinMm());
    }
}
