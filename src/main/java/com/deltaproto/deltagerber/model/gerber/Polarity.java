package com.deltaproto.deltagerber.model.gerber;

/**
 * Polarity for graphics objects.
 * DARK adds material, CLEAR removes material.
 */
public enum Polarity {
    DARK,
    CLEAR;

    /** The opposite polarity. Used when a clear-polarity block flash toggles its contents. */
    public Polarity inverse() {
        return this == DARK ? CLEAR : DARK;
    }
}
