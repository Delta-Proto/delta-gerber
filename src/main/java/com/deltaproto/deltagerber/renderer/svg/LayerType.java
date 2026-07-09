package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;

/**
 * Identifies the role of a PCB layer for realistic rendering.
 *
 * <p>Where {@link LayerFunction} and {@link LayerSide} stay orthogonal — a classifier can be sure
 * of one without the other — this enum fuses them into the single value a renderer needs to decide
 * what to draw. {@link #of} is the one place that conversion happens.
 */
public enum LayerType {
    OUTLINE,
    COPPER_TOP,
    COPPER_BOTTOM,
    /** Inner copper layer: not drawn in the realistic view, but its pours participate
     *  in board-outline derivation when the set has no dedicated outline file. */
    COPPER_INNER,
    SOLDERMASK_TOP,
    SOLDERMASK_BOTTOM,
    SILKSCREEN_TOP,
    SILKSCREEN_BOTTOM,
    PASTE_TOP,
    PASTE_BOTTOM,
    DRILL,
    DRILL_PLATED,
    DRILL_NON_PLATED,
    PNP_TOP,
    PNP_BOTTOM,
    OTHER;

    /**
     * The layer type a renderer should draw a given classification as.
     *
     * <p>Two cases deserve their reasoning stated, because they are where the callers this method
     * replaced used to disagree:
     *
     * <ul>
     *   <li>Copper with no side is {@link #COPPER_INNER}, not {@link #OTHER}. It is still copper:
     *       it isn't drawn in the realistic view, but its pours must feed board-outline derivation
     *       for a set that ships no profile.
     *   <li>A soldermask, silkscreen or paste layer with no side is {@link #OTHER}. There is no
     *       honest default — guessing "top" renders a bottom layer on the wrong face half the time.
     * </ul>
     *
     * <p>A pick-and-place file has no {@link LayerFunction} of its own — {@code .FileFunction
     * Component} is not a fabrication layer — so {@link #PNP_TOP} and {@link #PNP_BOTTOM} are never
     * returned here. Callers that care read it off the parsed document instead.
     */
    public static LayerType of(LayerFunction function, LayerSide side) {
        if (function == null) {
            return OTHER;
        }
        return switch (function) {
            case COPPER -> switch (nullSafe(side)) {
                case TOP -> COPPER_TOP;
                case BOTTOM -> COPPER_BOTTOM;
                default -> COPPER_INNER;
            };
            case SOLDERMASK -> sided(side, SOLDERMASK_TOP, SOLDERMASK_BOTTOM);
            case SILKSCREEN -> sided(side, SILKSCREEN_TOP, SILKSCREEN_BOTTOM);
            case PASTE -> sided(side, PASTE_TOP, PASTE_BOTTOM);
            // Protel's "Gerber KeepOut" is what everyone actually uses as the board profile.
            case OUTLINE, KEEP_OUT -> OUTLINE;
            case DRILL, DRILL_THRUHOLE, DRILL_BLINDBURIED -> DRILL;
            case DRILL_PLATED -> DRILL_PLATED;
            case DRILL_NONPLATED -> DRILL_NON_PLATED;
            default -> OTHER;
        };
    }

    private static LayerType sided(LayerSide side, LayerType top, LayerType bottom) {
        return switch (nullSafe(side)) {
            case TOP -> top;
            case BOTTOM -> bottom;
            default -> OTHER;
        };
    }

    private static LayerSide nullSafe(LayerSide side) {
        return side == null ? LayerSide.NA : side;
    }

    /**
     * The manufacturing role this type draws. {@link LayerFunction#UNKNOWN} for the types that have
     * none — {@link #PNP_TOP}, {@link #PNP_BOTTOM} and {@link #OTHER}.
     *
     * <p>Not a perfect inverse of {@link #of}: a keep-out layer reads back as an outline, and inner
     * copper loses the stack-up index the classification carried.
     */
    public LayerFunction getFunction() {
        return switch (this) {
            case OUTLINE -> LayerFunction.OUTLINE;
            case COPPER_TOP, COPPER_BOTTOM, COPPER_INNER -> LayerFunction.COPPER;
            case SOLDERMASK_TOP, SOLDERMASK_BOTTOM -> LayerFunction.SOLDERMASK;
            case SILKSCREEN_TOP, SILKSCREEN_BOTTOM -> LayerFunction.SILKSCREEN;
            case PASTE_TOP, PASTE_BOTTOM -> LayerFunction.PASTE;
            case DRILL -> LayerFunction.DRILL;
            case DRILL_PLATED -> LayerFunction.DRILL_PLATED;
            case DRILL_NON_PLATED -> LayerFunction.DRILL_NONPLATED;
            case PNP_TOP, PNP_BOTTOM, OTHER -> LayerFunction.UNKNOWN;
        };
    }

    /** Which side of the board this type sits on; {@link LayerSide#NA} for the sideless ones. */
    public LayerSide getSide() {
        return switch (this) {
            case COPPER_TOP, SOLDERMASK_TOP, SILKSCREEN_TOP, PASTE_TOP, PNP_TOP -> LayerSide.TOP;
            case COPPER_BOTTOM, SOLDERMASK_BOTTOM, SILKSCREEN_BOTTOM, PASTE_BOTTOM, PNP_BOTTOM -> LayerSide.BOTTOM;
            case COPPER_INNER -> LayerSide.INNER;
            default -> LayerSide.NA;
        };
    }

    /** True for every NC drill variant this renderer knows. */
    public boolean isDrill() {
        return this == DRILL || this == DRILL_PLATED || this == DRILL_NON_PLATED;
    }
}
