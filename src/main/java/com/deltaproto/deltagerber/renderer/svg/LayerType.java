package com.deltaproto.deltagerber.renderer.svg;

/**
 * Identifies the role of a PCB layer for realistic rendering.
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
    OTHER
}
