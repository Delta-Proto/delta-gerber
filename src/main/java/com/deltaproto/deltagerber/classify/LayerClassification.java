package com.deltaproto.deltagerber.classify;

/**
 * What a single file in a Gerber/drill set turned out to be.
 *
 * @param name     human-readable label, e.g. "top copper", "inner copper 2", "plated drill"
 * @param function the manufacturing role
 * @param side     which side of the board, or {@link LayerSide#NA} for sideless layers
 * @param number   stack-up index of an inner copper layer, {@code null} for every other layer.
 *                 See {@link #isInnerCopper()} — this is an invariant, not a convention: a number
 *                 passed for anything else is dropped.
 */
public record LayerClassification(String name, LayerFunction function, LayerSide side, Integer number) {

    /**
     * Only inner copper carries a layer number.
     *
     * <p>Outer copper deliberately carries none: its side already says everything, and the
     * {@code L<p>} index a Gerber X2 file gives it is just the absolute stack-up position. A caller
     * that renders "side + number" turns a number there into the meaningless label "TOP-1", and a
     * caller that offers a dropdown of TOP / BOTTOM / INNER-1 / INNER-2 finds "TOP-1" matches none
     * of them and reads back empty. Nothing else has a stack-up position at all.
     */
    public LayerClassification {
        if (number != null && !(function == LayerFunction.COPPER && side == LayerSide.INNER)) {
            number = null;
        }
    }

    public LayerClassification(String name, LayerFunction function, LayerSide side) {
        this(name, function, side, null);
    }

    /** The catch-all for a file we could not place. */
    public static LayerClassification unknown(String name) {
        return new LayerClassification(name, LayerFunction.UNKNOWN, LayerSide.NA, null);
    }

    /** True when this layer is the kind that carries a stack-up index. */
    public boolean isInnerCopper() {
        return function == LayerFunction.COPPER && side == LayerSide.INNER;
    }

    /** Ignored unless this is inner copper — see {@link #isInnerCopper()}. */
    public LayerClassification withNumber(Integer number) {
        return new LayerClassification(name, function, side, number);
    }

    public LayerClassification withName(String name) {
        return new LayerClassification(name, function, side, number);
    }
}
