package com.deltaproto.deltagerber.dfm;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of a {@link ViaInPadDetector} run: which drilled holes, if any, land inside a
 * surface-mount pad, and — pad by pad — whether that actually forces a via-fill process.
 *
 * <p>Two questions, and they are not the same one:
 *
 * <ul>
 *   <li>{@link #hasViaInPad()} is the geometric fact: a hole sits in a paste opening.</li>
 *   <li>{@link #requiresFilledAndCapped()} is the process verdict. A via field under a QFN heat pad
 *       trips the first and not the second — the land is large, it carries paste to spare, and the
 *       holes can be left open. A single via in an 0402 land trips both. The difference is decided
 *       per pad by {@link ViaInPadGroup}, weighed by {@link ViaInPadPolicy}.</li>
 * </ul>
 *
 * <p>Quote off the verdict, not the fact: only {@link #requiresFilledAndCapped()} means IPC-4761
 * Type VII and its cost and lead time. {@link #getGroups()} carries the evidence — each pad's area,
 * its via count and its hole diameter — for a caller that wants to show the reasoning or apply its
 * own thresholds.
 */
public final class ViaInPadResult {

    private static final ViaInPadResult EMPTY = new ViaInPadResult(List.of(), List.of(), 0);

    private final List<ViaInPad> viaInPads;
    private final List<ViaInPadGroup> groups;
    private final int excludedBySpan;

    ViaInPadResult(List<ViaInPad> viaInPads, List<ViaInPadGroup> groups) {
        this(viaInPads, groups, 0);
    }

    ViaInPadResult(List<ViaInPad> viaInPads, List<ViaInPadGroup> groups, int excludedBySpan) {
        this.viaInPads = List.copyOf(viaInPads);
        this.groups = List.copyOf(groups);
        this.excludedBySpan = excludedBySpan;
    }

    /** A result with no vias in pad — also what detection returns when there is nothing to check. */
    public static ViaInPadResult empty() {
        return EMPTY;
    }

    /** True when at least one hole falls inside a pad — the geometric fact, not the process verdict. */
    public boolean hasViaInPad() {
        return !viaInPads.isEmpty();
    }

    /**
     * How many holes land inside a pad — distinct hole locations, so a stacked microvia or a hole
     * that two drill files both carry counts once.
     */
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

    /**
     * The vias in pad grouped by the pad they sit in, in the order the pads were read from the paste
     * layers. A hole inside both a top and a bottom pad appears in two groups.
     */
    public List<ViaInPadGroup> getGroups() {
        return groups;
    }

    /**
     * How many holes sit inside a paste pad but were left out because their drill's stated span
     * does not reach that pad's surface — a buried via, or a microvia on the far side.
     */
    public int getExcludedBySpanCount() {
        return excludedBySpan;
    }

    /**
     * True when at least one via in pad counts only because its drill file did not state which
     * layers it connects (a plain Excellon file); it was then assumed to reach the pad. A caller
     * showing the verdict should say so — on an HDI board such a hole may be buried.
     */
    public boolean hasUnknownSpan() {
        return viaInPads.stream().anyMatch(v -> !v.isSpanKnown());
    }

    /** As {@link #summarize(ViaInPadPolicy, int)} under {@link ViaInPadPolicy#DEFAULT}, listing up to {@value ViaInPadSummary#DEFAULT_MAX_KINDS} kinds of fill pad. */
    public ViaInPadSummary summarize() {
        return summarize(ViaInPadPolicy.DEFAULT, ViaInPadSummary.DEFAULT_MAX_KINDS);
    }

    /**
     * The evidence behind {@link #requiresFilledAndCapped(ViaInPadPolicy)}, compact enough to show:
     * how many pads have a hole, how many of those are thermal and how many need fill, and the pads
     * needing fill grouped into kinds of identical pad (side, paste file, shape, area, hole, via
     * count), most numerous first, at most {@code maxKinds} of them.
     */
    public ViaInPadSummary summarize(ViaInPadPolicy policy, int maxKinds) {
        return ViaInPadSummary.of(this, policy, maxKinds);
    }

    /** As {@link #requiresFilledAndCapped(ViaInPadPolicy)}, under {@link ViaInPadPolicy#DEFAULT}. */
    public boolean requiresFilledAndCapped() {
        return requiresFilledAndCapped(ViaInPadPolicy.DEFAULT);
    }

    /**
     * True when at least one pad's vias have to be filled and capped (IPC-4761 Type VII) — i.e. the
     * board has a via in a pad that is <em>not</em> explained away as a thermal land. This is the
     * flag a quote keys off.
     */
    public boolean requiresFilledAndCapped(ViaInPadPolicy policy) {
        return groups.stream().anyMatch(g -> g.requiresFilledAndCapped(policy));
    }

    /** As {@link #getFilledAndCappedGroups(ViaInPadPolicy)}, under {@link ViaInPadPolicy#DEFAULT}. */
    public List<ViaInPadGroup> getFilledAndCappedGroups() {
        return getFilledAndCappedGroups(ViaInPadPolicy.DEFAULT);
    }

    /** The pads that force the fill process — the ones to show a customer when a quote goes up. */
    public List<ViaInPadGroup> getFilledAndCappedGroups(ViaInPadPolicy policy) {
        List<ViaInPadGroup> out = new ArrayList<>();
        for (ViaInPadGroup group : groups) {
            if (group.requiresFilledAndCapped(policy)) {
                out.add(group);
            }
        }
        return List.copyOf(out);
    }

    /** As {@link #getThermalGroups(ViaInPadPolicy)}, under {@link ViaInPadPolicy#DEFAULT}. */
    public List<ViaInPadGroup> getThermalGroups() {
        return getThermalGroups(ViaInPadPolicy.DEFAULT);
    }

    /** The pads whose vias are judged thermal, so they need no capping. */
    public List<ViaInPadGroup> getThermalGroups(ViaInPadPolicy policy) {
        List<ViaInPadGroup> out = new ArrayList<>();
        for (ViaInPadGroup group : groups) {
            if (group.isLikelyThermal(policy)) {
                out.add(group);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() {
        return "ViaInPadResult[count=" + viaInPads.size() + ", pads=" + groups.size()
                + ", fillAndCap=" + getFilledAndCappedGroups().size()
                + (excludedBySpan > 0 ? ", excludedBySpan=" + excludedBySpan : "")
                + (hasUnknownSpan() ? ", spanUnknown" : "") + "]";
    }
}
