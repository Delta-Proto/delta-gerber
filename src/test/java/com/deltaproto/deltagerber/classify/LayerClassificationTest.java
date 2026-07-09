package com.deltaproto.deltagerber.classify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layer number belongs to inner copper and to nothing else. The type enforces it, so no caller
 * has to remember to.
 */
class LayerClassificationTest {

    @Test
    void innerCopperKeepsItsIndex() {
        LayerClassification inner = new LayerClassification("inner copper 2", LayerFunction.COPPER, LayerSide.INNER, 2);
        assertEquals(2, inner.number());
        assertTrue(inner.isInnerCopper());
    }

    @Test
    @DisplayName("Outer copper carries no index — its side already says everything")
    void outerCopperDropsTheIndex() {
        // A Gerber X2 file gives top copper the L1 of "Copper,L1,Top". That is the absolute
        // stack-up position, not a layer number, and a caller rendering "side + number" would
        // print the meaningless "TOP-1".
        assertNull(new LayerClassification("copper layer L1", LayerFunction.COPPER, LayerSide.TOP, 1).number());
        assertNull(new LayerClassification("copper layer L6", LayerFunction.COPPER, LayerSide.BOTTOM, 6).number());
    }

    @Test
    @DisplayName("Nothing but copper has a stack-up position at all")
    void otherFunctionsDropTheIndex() {
        assertNull(new LayerClassification("x", LayerFunction.SOLDERMASK, LayerSide.INNER, 7).number());
        assertNull(new LayerClassification("x", LayerFunction.SILKSCREEN, LayerSide.TOP, 3).number());
        assertNull(new LayerClassification("x", LayerFunction.DRILL_PLATED, LayerSide.NA, 1).number());
        assertNull(new LayerClassification("x", LayerFunction.OUTLINE, LayerSide.NA, 1).number());
    }

    @Test
    @DisplayName("Retyping an inner layer to anything else drops its index")
    void retypingDropsTheIndex() {
        LayerClassification inner = new LayerClassification("inner copper 3", LayerFunction.COPPER, LayerSide.INNER, 3);
        // What the viewer does when the user picks "Cu Top" for a layer detected as "Cu Inner 3".
        LayerClassification retyped = new LayerClassification(inner.name(), LayerFunction.COPPER, LayerSide.TOP, inner.number());
        assertNull(retyped.number());
        assertFalse(retyped.isInnerCopper());
    }

    @Test
    void withNumberHonoursTheSameRule() {
        assertEquals(4, new LayerClassification("x", LayerFunction.COPPER, LayerSide.INNER).withNumber(4).number());
        assertNull(new LayerClassification("x", LayerFunction.COPPER, LayerSide.TOP).withNumber(1).number());
        assertNull(new LayerClassification("x", LayerFunction.PASTE, LayerSide.TOP).withNumber(1).number());
    }

    @Test
    void withNameKeepsTheIndex() {
        LayerClassification inner = new LayerClassification("a", LayerFunction.COPPER, LayerSide.INNER, 2);
        assertEquals(2, inner.withName("b").number());
    }

    @Test
    void unknownHasNoIndex() {
        assertNull(LayerClassification.unknown("mystery.gbr").number());
        assertFalse(LayerClassification.unknown("mystery.gbr").isInnerCopper());
    }

    @Test
    @DisplayName("Every classifier path obeys the rule, on every naming convention")
    void classifierNeverBreaksTheRule() {
        String[] names = {
            "board.GTL", "board.GBL", "board.G1", "board.G3", "board.GTS", "board.GKO",
            "board-F_Cu.gbr", "board-B_Cu.gbr", "board-In2_Cu.gbr", "board-PTH.drl",
            "board.toplayer.ger", "board.internalplane3.ger", "board_Copper_Signal_2.gbr",
        };
        for (String name : names) {
            LayerClassification actual = LayerClassifier.classify(name, null);
            if (actual == null) {
                continue;
            }
            if (actual.isInnerCopper()) {
                assertNotNull(actual.number(), name + " is inner copper and must carry its index");
            } else {
                assertNull(actual.number(), name + " must carry no layer number");
            }
        }
        // …and the indices really are read off the names, not merely non-null.
        assertEquals(3, LayerClassifier.classify("board.G3", null).number());
        assertEquals(2, LayerClassifier.classify("board-In2_Cu.gbr", null).number());
        assertEquals(3, LayerClassifier.classify("board.internalplane3.ger", null).number());
        assertEquals(2, LayerClassifier.classify("board_Copper_Signal_2.gbr", null).number());

        // And through the X2 attribute, where the index arrives as "L<p>".
        assertEquals(2, LayerClassifier.fromFileFunction("Copper,L2,Inr").number());
        assertNull(LayerClassifier.fromFileFunction("Copper,L1,Top").number());
        assertNull(LayerClassifier.fromFileFunction("Copper,L6,Bot").number());
        assertNull(LayerClassifier.fromFileFunction("Soldermask,Top").number());
    }
}
