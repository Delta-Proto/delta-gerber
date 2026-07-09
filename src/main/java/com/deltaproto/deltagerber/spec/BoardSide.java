package com.deltaproto.deltagerber.spec;

/**
 * Which side(s) of the board a process applies to — soldermask, silkscreen, stencil.
 */
public enum BoardSide {
    NONE,
    TOP,
    BOTTOM,
    BOTH;

    public static BoardSide of(boolean top, boolean bottom) {
        if (top && bottom) {
            return BOTH;
        } else if (top) {
            return TOP;
        } else if (bottom) {
            return BOTTOM;
        }
        return NONE;
    }
}
