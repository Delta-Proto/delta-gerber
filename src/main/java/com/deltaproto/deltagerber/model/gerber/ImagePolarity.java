package com.deltaproto.deltagerber.model.gerber;

/**
 * Image polarity from the deprecated {@code %IP%} command (Gerber spec §4.5).
 *
 * <p>{@link #POSITIVE} is the default and the only value used by modern fab output: dark objects
 * add material on a clear field. {@link #NEGATIVE} inverts the whole image (a dark field that the
 * objects clear). {@code IP} is deprecated and must appear at most once at the start of a file;
 * it is retained here so a reader can faithfully report and render legacy negatives.
 */
public enum ImagePolarity {
    POSITIVE,
    NEGATIVE
}
