package com.deltaproto.deltagerber.classify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Generators disagree on where inner-layer counting starts. Protel writes {@code .G1 .G2 …} and
 * Allegro writes {@code IN1 IN2 …}, both counting inner layers. Gerber X2 writes
 * {@code Copper,L2,Inr} — the absolute stack position — so KiCad's {@code In1_Cu} arrives as 2.
 *
 * <p>Only the whole set can settle it, and {@link LayerClassifier#normalizeInnerCopperNumbers} does.
 */
class InnerCopperNumberingTest {

    private static LayerClassification inner(int number) {
        return new LayerClassification("copper layer L" + number, LayerFunction.COPPER, LayerSide.INNER, number);
    }

    private static List<Integer> numbersOf(List<LayerClassification> classifications) {
        List<Integer> numbers = new ArrayList<>();
        classifications.forEach(c -> numbers.add(c == null ? null : c.number()));
        return numbers;
    }

    @Test
    @DisplayName("Gerber X2 counts the stack: In1_Cu arrives as 2 and becomes 1")
    void x2StartsAtTwo() {
        // Zeway_BMS2: a six-layer KiCad board, inner layers L2..L5.
        List<LayerClassification> normalized =
                LayerClassifier.normalizeInnerCopperNumbers(List.of(inner(2), inner(3), inner(4), inner(5)));
        assertEquals(List.of(1, 2, 3, 4), numbersOf(normalized));
    }

    @Test
    @DisplayName("Protel and Allegro already count inner layers: nothing moves")
    void protelStartsAtOne() {
        List<LayerClassification> normalized =
                LayerClassifier.normalizeInnerCopperNumbers(List.of(inner(1), inner(2), inner(3), inner(4)));
        assertEquals(List.of(1, 2, 3, 4), numbersOf(normalized));
    }

    @Test
    @DisplayName("Running it twice changes nothing")
    void idempotent() {
        List<LayerClassification> once = LayerClassifier.normalizeInnerCopperNumbers(List.of(inner(2), inner(3)));
        List<LayerClassification> twice = LayerClassifier.normalizeInnerCopperNumbers(once);
        assertEquals(numbersOf(once), numbersOf(twice));
        assertEquals(List.of(1, 2), numbersOf(twice));
    }

    @Test
    @DisplayName("A gap stays a gap — it means a missing file, not a shorter stack-up")
    void gapsArePreserved() {
        // Shifted, not densely renumbered: L2 and L4 with no L3 is a set that lost a layer.
        assertEquals(Arrays.asList(1, 3), numbersOf(
                LayerClassifier.normalizeInnerCopperNumbers(List.of(inner(2), inner(4)))));
    }

    @Test
    @DisplayName("The label follows the number, so nothing reads 'copper layer L2' while numbered 1")
    void labelIsRewritten() {
        List<LayerClassification> normalized = LayerClassifier.normalizeInnerCopperNumbers(List.of(inner(2)));
        assertEquals("inner copper 1", normalized.get(0).name());
    }

    @Test
    @DisplayName("Order is preserved and everything else passes through untouched")
    void onlyInnerCopperIsTouched() {
        LayerClassification top = new LayerClassification("top copper", LayerFunction.COPPER, LayerSide.TOP);
        LayerClassification outline = new LayerClassification("board outline", LayerFunction.OUTLINE, LayerSide.NA);
        List<LayerClassification> input = Arrays.asList(top, inner(3), null, outline, inner(2));

        List<LayerClassification> normalized = LayerClassifier.normalizeInnerCopperNumbers(input);
        assertEquals(5, normalized.size());
        assertSame(top, normalized.get(0));
        assertEquals(2, normalized.get(1).number());
        assertNull(normalized.get(2));
        assertSame(outline, normalized.get(3));
        assertEquals(1, normalized.get(4).number());
    }

    @Test
    void setsWithoutInnerCopperAreUnchanged() {
        LayerClassification top = new LayerClassification("top copper", LayerFunction.COPPER, LayerSide.TOP);
        assertSame(top, LayerClassifier.normalizeInnerCopperNumbers(List.of(top)).get(0));
        assertEquals(List.of(), LayerClassifier.normalizeInnerCopperNumbers(List.of()));
        assertEquals(List.of(), LayerClassifier.normalizeInnerCopperNumbers(null));
    }

    @Test
    @DisplayName("An inner layer whose index could not be read stays unnumbered")
    void unnumberedInnerLayerSurvives() {
        LayerClassification unnumbered = new LayerClassification("inner copper", LayerFunction.COPPER, LayerSide.INNER);
        List<LayerClassification> normalized =
                LayerClassifier.normalizeInnerCopperNumbers(Arrays.asList(unnumbered, inner(2), inner(3)));
        assertNull(normalized.get(0).number());
        assertEquals(List.of(1, 2), numbersOf(normalized).subList(1, 3));
    }
}
