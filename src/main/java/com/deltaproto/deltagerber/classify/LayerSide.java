package com.deltaproto.deltagerber.classify;

/**
 * Which side of the board a layer belongs to.
 *
 * <p>{@link #NA} means the layer has no side by nature (drills, outline, fabrication drawings);
 * {@link #UNKNOWN} means it has one but we could not determine it.
 */
public enum LayerSide {
    TOP,
    BOTTOM,
    INNER,
    NA,
    UNKNOWN
}
