package com.deltaproto.deltagerber.dfm;

import java.util.Locale;

/**
 * The minimum annular ring a board is judged against — the "min annular ring" line on a
 * fabricator's capability table.
 *
 * <p>Measuring the ring is geometry; deciding whether it is too small is a fabricator's call, and
 * this is where that call lives. Two numbers, because the answer differs by where the layer sits:
 *
 * <ul>
 *   <li><strong>Outer layers</strong> ({@link #getMinOuterRingMm()}). The pad is imaged on the
 *       outside of the panel, where the drill is registered directly to the artwork.</li>
 *   <li><strong>Inner layers</strong> ({@link #getMinInnerRingMm()}). Registration has to survive
 *       lamination, which moves the inner artwork relative to the drill, so an inner pad is the one
 *       that breaks out first — most houses ask for the same ring or a larger one there.</li>
 * </ul>
 *
 * <p>{@link #DEFAULT} is 0.15 mm (6 mil) on both, which is what a standard-capability shop quotes
 * without surcharge. {@link #IPC_6012_CLASS_3} is a different kind of number: 0.05 mm external and
 * 0.025 mm internal is what IPC-6012 Class 3 will <em>accept on the finished board</em>, measured
 * after plating and etching, not what a design should aim for — a design drawn to it leaves nothing
 * for registration. Judge a design with {@link #DEFAULT} or with your fabricator's own figures;
 * reach for the IPC one only to answer "is this even manufacturable".
 *
 * <p>A hole that breaks out of its pad (a negative ring) fails every policy.
 */
public final class AnnularRingPolicy {

    /** The common standard-capability design rule: 0.15 mm (6 mil), outer and inner alike. */
    public static final AnnularRingPolicy DEFAULT = new AnnularRingPolicy(0.15, 0.15);

    /**
     * What IPC-6012 Class 3 accepts on a finished board — 0.050 mm external, 0.025 mm internal.
     * An acceptance limit, not a design rule; see the class documentation.
     */
    public static final AnnularRingPolicy IPC_6012_CLASS_3 = new AnnularRingPolicy(0.05, 0.025);

    private final double minOuterRingMm;
    private final double minInnerRingMm;

    /**
     * @param minOuterRingMm smallest acceptable ring on an outer copper layer, in millimetres
     * @param minInnerRingMm smallest acceptable ring on an inner copper layer, in millimetres
     */
    public AnnularRingPolicy(double minOuterRingMm, double minInnerRingMm) {
        if (minOuterRingMm < 0 || minInnerRingMm < 0) {
            throw new IllegalArgumentException("minimum ring must not be negative");
        }
        this.minOuterRingMm = minOuterRingMm;
        this.minInnerRingMm = minInnerRingMm;
    }

    /** The same rule on every layer. */
    public static AnnularRingPolicy of(double minRingMm) {
        return new AnnularRingPolicy(minRingMm, minRingMm);
    }

    /** Smallest acceptable ring on an outer copper layer, in millimetres. */
    public double getMinOuterRingMm() {
        return minOuterRingMm;
    }

    /** Smallest acceptable ring on an inner copper layer, in millimetres. */
    public double getMinInnerRingMm() {
        return minInnerRingMm;
    }

    /** The rule that applies to one measured ring — the outer figure for outer copper. */
    public double minRingMm(PadRing ring) {
        return ring.isOuter() ? minOuterRingMm : minInnerRingMm;
    }

    /** Whether this measured ring is under the rule for the layer it sits on. */
    public boolean isViolation(PadRing ring) {
        return ring.getRingMm() < minRingMm(ring);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "AnnularRingPolicy[outer>=%.3fmm, inner>=%.3fmm]",
                minOuterRingMm, minInnerRingMm);
    }
}
