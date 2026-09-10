package com.deltaproto.deltagerber.dfm;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of an {@link AnnularRingDetector} run: how much copper every drilled hole has around
 * it, and which holes have too little.
 *
 * <p>Three questions, and they are not the same one:
 *
 * <ul>
 *   <li>{@link #getMinRingMm()} — the board's tightest ring. This is the quote-form figure, the one
 *       a fabricator's capability table is compared against.</li>
 *   <li>{@link #getViolations()} — the holes under the rule, each naming the layer it fails on.
 *       {@link #hasBreakout()} separates the holes that are already outside their pad.</li>
 *   <li>{@link #getHolesWithoutPad()} — holes with no pad on any copper layer. A mounting hole the
 *       file never called plated belongs here and is not a fault;
 *       {@link #getPlatedHolesWithoutPad()} is the subset that has lost a pad it was meant to have,
 *       which is a different defect from a thin ring and must not be reported as ring zero.</li>
 * </ul>
 *
 * <p>Holes are measured against the drilled diameter, and only where a pad was found — see
 * {@link AnnularRing}.
 */
public final class AnnularRingResult {

    private static final AnnularRingResult EMPTY = new AnnularRingResult(List.of(), List.of());

    private final List<AnnularRing> rings;
    private final List<AnnularRing> withoutPad;

    AnnularRingResult(List<AnnularRing> rings, List<AnnularRing> withoutPad) {
        this.rings = List.copyOf(rings);
        this.withoutPad = List.copyOf(withoutPad);
    }

    /** Nothing measured — also what detection returns when there is no copper or no drill to judge. */
    public static AnnularRingResult empty() {
        return EMPTY;
    }

    /** True when at least one hole was measured against a pad. */
    public boolean isMeasured() {
        return !rings.isEmpty();
    }

    /** Every hole that found a pad on at least one copper layer, in the order the holes were read. */
    public List<AnnularRing> getRings() {
        return rings;
    }

    /**
     * The holes with no pad on any copper layer: non-plated mounting holes, buried vias whose
     * layers are not in the set, and plated holes that really are missing their pad. Nothing here
     * has a ring — reporting them as zero would bury the real ones.
     */
    public List<AnnularRing> getHolesWithoutPad() {
        return withoutPad;
    }

    /**
     * The holes the drill file called <em>plated</em> that have no pad on any copper layer — a
     * plated hole is meant to connect something, so this is a real fault rather than the shrug a
     * padless mounting hole deserves. Empty when the drill file never stated plating.
     */
    public List<AnnularRing> getPlatedHolesWithoutPad() {
        List<AnnularRing> out = new ArrayList<>();
        for (AnnularRing ring : withoutPad) {
            if (Boolean.TRUE.equals(ring.getPlated())) {
                out.add(ring);
            }
        }
        return List.copyOf(out);
    }

    /** How many holes were measured, pads or not. Non-plated holes are not among them. */
    public int getHoleCount() {
        return rings.size() + withoutPad.size();
    }

    /**
     * The board's tightest annular ring in millimetres, or {@code null} when no hole found a pad.
     * Negative when a hole breaks out of its pad.
     */
    public Double getMinRingMm() {
        AnnularRing worst = getWorstHole();
        return worst == null ? null : worst.getMinRingMm();
    }

    /** The hole with the least copper around it, or null when nothing was measured. */
    public AnnularRing getWorstHole() {
        AnnularRing worst = null;
        Double worstRing = null;
        for (AnnularRing ring : rings) {
            Double min = ring.getMinRingMm();
            if (min != null && (worstRing == null || min < worstRing)) {
                worst = ring;
                worstRing = min;
            }
        }
        return worst;
    }

    /** True when at least one hole's edge crosses its pad's edge on some layer. */
    public boolean hasBreakout() {
        for (AnnularRing ring : rings) {
            if (ring.isBreakout()) {
                return true;
            }
        }
        return false;
    }

    /** As {@link #getViolations(AnnularRingPolicy)}, under {@link AnnularRingPolicy#DEFAULT}. */
    public List<AnnularRing> getViolations() {
        return getViolations(AnnularRingPolicy.DEFAULT);
    }

    /**
     * The holes whose ring is under the rule on at least one layer — what to show a customer when a
     * board needs a finer-capability fabricator, or a designer when it needs a bigger pad.
     */
    public List<AnnularRing> getViolations(AnnularRingPolicy policy) {
        List<AnnularRing> out = new ArrayList<>();
        for (AnnularRing ring : rings) {
            for (PadRing pad : ring.getPads()) {
                if (policy.isViolation(pad)) {
                    out.add(ring);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    /** As {@link #isWithinPolicy(AnnularRingPolicy)}, under {@link AnnularRingPolicy#DEFAULT}. */
    public boolean isWithinPolicy() {
        return isWithinPolicy(AnnularRingPolicy.DEFAULT);
    }

    /** True when every measured hole clears the rule. A board with nothing measured clears it too. */
    public boolean isWithinPolicy(AnnularRingPolicy policy) {
        return getViolations(policy).isEmpty();
    }

    @Override
    public String toString() {
        Double min = getMinRingMm();
        return "AnnularRingResult[holes=" + getHoleCount()
                + ", measured=" + rings.size()
                + ", noPad=" + withoutPad.size()
                + ", min=" + (min == null ? "n/a" : String.format(java.util.Locale.US, "%.4fmm", min))
                + ", violations=" + getViolations().size() + "]";
    }
}
