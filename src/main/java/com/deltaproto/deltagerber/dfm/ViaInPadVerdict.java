package com.deltaproto.deltagerber.dfm;

/**
 * What a board's vias in pad mean for the via process, in three answers rather than one boolean.
 *
 * <p>{@link ViaInPadResult#requiresFilledAndCapped()} turns true on a single pad, and one pad that
 * only just misses the thermal thresholds is a different finding from a board of ball lands with a
 * microvia in each. {@link #REVIEW} is that difference: a board whose only fill pads are one or two
 * that fall within a factor of the policy's thresholds (see {@link ViaInPadPolicy#getMaxReviewFillPads()}
 * and {@link ViaInPadPolicy#getReviewMarginFactor()}) is put to a person rather than priced as a
 * filled-and-capped process.
 */
public enum ViaInPadVerdict {

    /** No pad needs its vias filled: none has a hole, or every one that has is thermal. */
    NO_FILL,

    /** One or two pads need fill by the policy, and each of them only just — look before quoting. */
    REVIEW,

    /** Pads need filling and capping (IPC-4761 Type VII). */
    FILL
}
