package com.deltaproto.deltagerber.dfm;

import java.util.List;

/**
 * The outcome of a {@link ViaInPadDetector} run: which drilled holes, if any, land inside a
 * surface-mount pad.
 *
 * <p>A board with any via in pad has to be built with a filled-and-capped via process
 * (IPC-4761 Type VII), which the calculator flags as extra cost and lead time — so the useful
 * question a caller asks is {@link #hasViaInPad()}, with {@link #getCount()} and
 * {@link #getViaInPads()} available when the individual holes matter.
 */
public final class ViaInPadResult {

    private static final ViaInPadResult EMPTY = new ViaInPadResult(List.of());

    private final List<ViaInPad> viaInPads;

    ViaInPadResult(List<ViaInPad> viaInPads) {
        this.viaInPads = List.copyOf(viaInPads);
    }

    /** A result with no vias in pad — also what detection returns when there is nothing to check. */
    public static ViaInPadResult empty() {
        return EMPTY;
    }

    /** True when at least one hole falls inside a pad, i.e. the board needs a via-fill process. */
    public boolean hasViaInPad() {
        return !viaInPads.isEmpty();
    }

    /** How many holes land inside a pad. */
    public int getCount() {
        return viaInPads.size();
    }

    /** True when any via in pad sits on a top-side pad. */
    public boolean isOnTop() {
        return viaInPads.stream().anyMatch(ViaInPad::isTop);
    }

    /** True when any via in pad sits on a bottom-side pad. */
    public boolean isOnBottom() {
        return viaInPads.stream().anyMatch(ViaInPad::isBottom);
    }

    /** Every via in pad found, in the order the holes were read. */
    public List<ViaInPad> getViaInPads() {
        return viaInPads;
    }

    @Override
    public String toString() {
        return "ViaInPadResult[count=" + viaInPads.size() + "]";
    }
}
