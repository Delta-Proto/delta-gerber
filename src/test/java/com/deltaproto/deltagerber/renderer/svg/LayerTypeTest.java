package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link LayerType#of} is the one place a classification becomes something a renderer can draw.
 * It replaced three hand-rolled copies that had drifted apart from one another.
 */
class LayerTypeTest {

    @Test
    void copper() {
        assertEquals(LayerType.COPPER_TOP, LayerType.of(LayerFunction.COPPER, LayerSide.TOP));
        assertEquals(LayerType.COPPER_BOTTOM, LayerType.of(LayerFunction.COPPER, LayerSide.BOTTOM));
        assertEquals(LayerType.COPPER_INNER, LayerType.of(LayerFunction.COPPER, LayerSide.INNER));
    }

    @Test
    @DisplayName("Copper with no side is still copper — it feeds outline derivation")
    void sidelessCopperIsInner() {
        assertEquals(LayerType.COPPER_INNER, LayerType.of(LayerFunction.COPPER, LayerSide.NA));
        assertEquals(LayerType.COPPER_INNER, LayerType.of(LayerFunction.COPPER, LayerSide.UNKNOWN));
        assertEquals(LayerType.COPPER_INNER, LayerType.of(LayerFunction.COPPER, null));
    }

    @Test
    @DisplayName("A sideless mask, silkscreen or paste has no honest side to be drawn on")
    void sidelessSurfaceLayersAreOther() {
        // Guessing "top" here renders a bottom layer on the wrong face half the time.
        assertEquals(LayerType.OTHER, LayerType.of(LayerFunction.SOLDERMASK, LayerSide.NA));
        assertEquals(LayerType.OTHER, LayerType.of(LayerFunction.SILKSCREEN, LayerSide.NA));
        assertEquals(LayerType.OTHER, LayerType.of(LayerFunction.PASTE, LayerSide.UNKNOWN));
    }

    @Test
    void surfaceLayers() {
        assertEquals(LayerType.SOLDERMASK_TOP, LayerType.of(LayerFunction.SOLDERMASK, LayerSide.TOP));
        assertEquals(LayerType.SOLDERMASK_BOTTOM, LayerType.of(LayerFunction.SOLDERMASK, LayerSide.BOTTOM));
        assertEquals(LayerType.SILKSCREEN_BOTTOM, LayerType.of(LayerFunction.SILKSCREEN, LayerSide.BOTTOM));
        assertEquals(LayerType.PASTE_TOP, LayerType.of(LayerFunction.PASTE, LayerSide.TOP));
    }

    @Test
    @DisplayName("Protel's keep-out layer is the board profile, as every fabricator treats it")
    void outlineAndKeepOut() {
        assertEquals(LayerType.OUTLINE, LayerType.of(LayerFunction.OUTLINE, LayerSide.NA));
        assertEquals(LayerType.OUTLINE, LayerType.of(LayerFunction.KEEP_OUT, LayerSide.NA));
    }

    @Test
    void drills() {
        assertEquals(LayerType.DRILL, LayerType.of(LayerFunction.DRILL, LayerSide.NA));
        assertEquals(LayerType.DRILL, LayerType.of(LayerFunction.DRILL_THRUHOLE, LayerSide.NA));
        assertEquals(LayerType.DRILL, LayerType.of(LayerFunction.DRILL_BLINDBURIED, LayerSide.NA));
        assertEquals(LayerType.DRILL_PLATED, LayerType.of(LayerFunction.DRILL_PLATED, LayerSide.NA));
        assertEquals(LayerType.DRILL_NON_PLATED, LayerType.of(LayerFunction.DRILL_NONPLATED, LayerSide.NA));
    }

    @Test
    void everythingElseIsNotDrawn() {
        assertEquals(LayerType.OTHER, LayerType.of(LayerFunction.FAB_DRAWING, LayerSide.NA));
        assertEquals(LayerType.OTHER, LayerType.of(LayerFunction.SCORE, LayerSide.TOP));
        assertEquals(LayerType.OTHER, LayerType.of(LayerFunction.ROUT, LayerSide.NA));
        assertEquals(LayerType.OTHER, LayerType.of(LayerFunction.UNKNOWN, LayerSide.NA));
        assertEquals(LayerType.OTHER, LayerType.of(null, LayerSide.TOP));
    }

    @Test
    @DisplayName("Every type reports back the function and side it was built from")
    void roundTrip() {
        for (LayerType type : LayerType.values()) {
            if (type == LayerType.OTHER || type == LayerType.PNP_TOP || type == LayerType.PNP_BOTTOM) {
                assertEquals(LayerFunction.UNKNOWN, type.getFunction(), type + " has no fabrication function");
                continue;
            }
            assertSame(type, LayerType.of(type.getFunction(), type.getSide()),
                    type + " must survive a round trip through its function and side");
        }
    }

    @Test
    void isDrill() {
        for (LayerType type : LayerType.values()) {
            assertEquals(type.getFunction().isDrill(), type.isDrill(), type.toString());
        }
    }
}
