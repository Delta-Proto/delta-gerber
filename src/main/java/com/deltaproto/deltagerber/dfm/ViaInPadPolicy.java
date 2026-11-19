package com.deltaproto.deltagerber.dfm;

import java.util.Locale;

/**
 * The thresholds that decide whether a pad full of vias is a <em>thermal</em> pad — one where the
 * holes can be left open — or a land that forces a filled-and-capped via process.
 *
 * <p>Finding a hole inside a paste opening is a geometric fact; deciding whether the fabricator has
 * to plug it is a judgement, and this is where that judgement lives. Two signals separate the two
 * cases, and either one on its own is enough:
 *
 * <ul>
 *   <li><strong>Several vias in one pad</strong> ({@link #getMinViasForThermal()}). Nobody puts an
 *       array of vias in a signal land — a via field only ever appears under a heat-spreader pad,
 *       so the pad is thermal by construction.</li>
 *   <li><strong>A pad far larger than its hole</strong> ({@link #getMinPadToViaAreaRatio()}, with an
 *       absolute floor from {@link #getMinThermalPadAreaMm2()}). A big land carries enough paste
 *       that what wicks down the barrel does not starve the joint; a small land does not, and its
 *       via has to be capped.</li>
 * </ul>
 *
 * <p>The defaults ({@link #DEFAULT}) are 2 vias, 25× the hole area and 2.0 mm². For a 0.3 mm via
 * (0.071 mm² of hole) that puts the cut at a 2 mm² land: a QFN/DFN thermal pad clears it, while an
 * SOIC land (~0.9 mm²) or an 0805/1206 land (~1.9 mm²) does not — which is the intent, since a via
 * in one of those really does wick the joint dry. Fabricators disagree about where the line sits,
 * so construct your own instance to move it.
 */
public final class ViaInPadPolicy {

    /**
     * The default judgement: 2 vias in a pad, or a pad ≥ 2.0 mm² and ≥ 25× the hole area; and at
     * most 2 fill pads within a factor 2 of those thresholds make a {@link ViaInPadVerdict#REVIEW}.
     */
    public static final ViaInPadPolicy DEFAULT = new ViaInPadPolicy(2, 25.0, 2.0);

    /** Default for {@link #getMaxReviewFillPads()}. */
    public static final int DEFAULT_MAX_REVIEW_FILL_PADS = 2;

    /** Default for {@link #getReviewMarginFactor()}. */
    public static final double DEFAULT_REVIEW_MARGIN_FACTOR = 2.0;

    private final int minViasForThermal;
    private final double minPadToViaAreaRatio;
    private final double minThermalPadAreaMm2;
    private final int maxReviewFillPads;
    private final double reviewMarginFactor;

    /**
     * @param minViasForThermal    how many vias in one pad make it thermal regardless of size (≥ 2;
     *                             set it impossibly high to switch this rule off)
     * @param minPadToViaAreaRatio how many times the total hole area the pad must be
     * @param minThermalPadAreaMm2 the absolute pad area, in mm², below which no pad is thermal —
     *                             without it a small hole would let a small land pass on ratio alone
     */
    public ViaInPadPolicy(int minViasForThermal, double minPadToViaAreaRatio,
                          double minThermalPadAreaMm2) {
        this(minViasForThermal, minPadToViaAreaRatio, minThermalPadAreaMm2,
                DEFAULT_MAX_REVIEW_FILL_PADS, DEFAULT_REVIEW_MARGIN_FACTOR);
    }

    /**
     * As {@link #ViaInPadPolicy(int, double, double)}, with where a fill verdict becomes a review.
     *
     * @param maxReviewFillPads  at most this many fill pads, all marginal, make the board
     *                           {@link ViaInPadVerdict#REVIEW} rather than {@link ViaInPadVerdict#FILL}
     *                           ({@code 0} switches review off)
     * @param reviewMarginFactor a fill pad is marginal when its area is at least the area floor
     *                           divided by this, and its pad-to-hole ratio at least the ratio
     *                           threshold divided by this (≥ 1)
     */
    public ViaInPadPolicy(int minViasForThermal, double minPadToViaAreaRatio,
                          double minThermalPadAreaMm2, int maxReviewFillPads,
                          double reviewMarginFactor) {
        if (maxReviewFillPads < 0) {
            throw new IllegalArgumentException("maxReviewFillPads must not be negative, was " + maxReviewFillPads);
        }
        if (!(reviewMarginFactor >= 1)) {
            throw new IllegalArgumentException("reviewMarginFactor must be at least 1, was " + reviewMarginFactor);
        }
        this.maxReviewFillPads = maxReviewFillPads;
        this.reviewMarginFactor = reviewMarginFactor;
        if (minViasForThermal < 2) {
            throw new IllegalArgumentException("minViasForThermal must be at least 2, was "
                    + minViasForThermal);   // 1 would call every via in pad thermal
        }
        if (minPadToViaAreaRatio < 0 || minThermalPadAreaMm2 < 0) {
            throw new IllegalArgumentException("thresholds must not be negative");
        }
        this.minViasForThermal = minViasForThermal;
        this.minPadToViaAreaRatio = minPadToViaAreaRatio;
        this.minThermalPadAreaMm2 = minThermalPadAreaMm2;
    }

    /** Vias in a single pad from which it counts as a via field, i.e. a thermal pad. */
    public int getMinViasForThermal() {
        return minViasForThermal;
    }

    /** How many times the combined hole area the pad must measure to be judged thermal. */
    public double getMinPadToViaAreaRatio() {
        return minPadToViaAreaRatio;
    }

    /** Pad area in mm² below which the ratio rule does not apply at all. */
    public double getMinThermalPadAreaMm2() {
        return minThermalPadAreaMm2;
    }

    /** At most this many fill pads, every one marginal, make a {@link ViaInPadVerdict#REVIEW}. */
    public int getMaxReviewFillPads() {
        return maxReviewFillPads;
    }

    /** How far below the area floor and the ratio threshold a fill pad may sit and still be marginal. */
    public double getReviewMarginFactor() {
        return reviewMarginFactor;
    }

    /**
     * Whether a pad that needs fill only just does: its area is at least the area floor and its
     * pad-to-hole ratio at least the ratio threshold, each divided by {@link #getReviewMarginFactor()}.
     * A 1.35 mm² land with one ⌀0.3 mm via (ratio 19) is marginal under the defaults; a 0.05 mm²
     * ball land is not.
     */
    public boolean isMarginal(double padAreaMm2, double padToViaAreaRatio) {
        return padAreaMm2 >= minThermalPadAreaMm2 / reviewMarginFactor
                && padToViaAreaRatio >= minPadToViaAreaRatio / reviewMarginFactor;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "ViaInPadPolicy[vias>=%d or (area>=%.2fmm2 and ratio>=%.1f); review <=%d pads within x%.1f]",
                minViasForThermal, minThermalPadAreaMm2, minPadToViaAreaRatio, maxReviewFillPads,
                reviewMarginFactor);
    }
}
