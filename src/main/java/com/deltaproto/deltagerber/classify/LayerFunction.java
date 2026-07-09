package com.deltaproto.deltagerber.classify;

/**
 * The manufacturing role a file in a Gerber/drill set plays.
 *
 * <p>Distinct from {@link com.deltaproto.deltagerber.renderer.svg.LayerType}, which fuses function
 * and side into a single value for rendering. Here function and {@link LayerSide} stay orthogonal,
 * because a classifier can be sure of one without the other.
 */
public enum LayerFunction {

    UNKNOWN("Unknown"),
    COPPER("Copper layer"),
    SOLDERMASK("Soldermask layer, sometimes called mask"),
    SILKSCREEN("Silkscreen or legend layer"),
    PASTE("Solder paste layer"),
    DRILL("NC drill"),
    DRILL_THRUHOLE("NC drill (thru-hole)"),
    DRILL_BLINDBURIED("NC drill (blind/buried)"),
    DRILL_NONPLATED("NC drill (non-plated)"),
    DRILL_PLATED("NC drill (plated)"),
    OUTLINE("Board border, outline or profile layer, sometimes called edge cuts"),
    ROUT("NC rout"),
    KEEP_OUT("Keep-out layer"),
    FAB_DRAWING("Fabrication drawing"),
    MECHANICAL_DRAWING("Mechanical drawing (reference only)"),
    SCORE("Score layer for panel snap-outs"),
    GERBER("Gerber artwork of unknown function");

    private final String description;

    LayerFunction(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** Any NC drill variant, whatever its plating or depth. */
    public boolean isDrill() {
        return this == DRILL || this == DRILL_THRUHOLE || this == DRILL_BLINDBURIED
                || this == DRILL_NONPLATED || this == DRILL_PLATED;
    }

    /** True for the copper layers that make up the stack-up. */
    public boolean isCopper() {
        return this == COPPER;
    }
}
