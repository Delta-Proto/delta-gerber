package com.deltaproto.deltagerber.dfm;

import java.util.List;
import java.util.Locale;

/**
 * One solder-paste pad together with every drilled hole that lands inside it — the unit the
 * filled-and-capped question is actually decided on.
 *
 * <p>A bare list of offending holes cannot answer that question: nine vias under a QFN heat pad and
 * a single via in an 0402 land look identical hole by hole, yet only the second one wicks the joint
 * dry. Grouping the holes by the pad they sit in, and measuring that pad, is what separates them —
 * hence {@link #getPadAreaMm2()}, {@link #getViaCount()} and {@link #getPadToViaAreaRatio()}, which
 * {@link ViaInPadPolicy} weighs into {@link #isLikelyThermal()}.
 *
 * <p>Areas are mm², coordinates mm in the Gerber frame. The pad area is the paste opening itself
 * (the aperture as flashed, scale included, or the painted region), <em>not</em> its bounding box —
 * except for a macro or block aperture, which falls back to its bounds, the same approximation the
 * containment test makes.
 *
 * <p>A hole that sits in a top pad and a bottom pad appears in two groups, one per side, and is the
 * same {@link ViaInPad} instance in both.
 */
public final class ViaInPadGroup {

    private final double padAreaMm2;
    private final double padCenterX;
    private final double padCenterY;
    private final String padShape;
    private final boolean top;
    private final boolean bottom;
    private final List<ViaInPad> vias;

    ViaInPadGroup(double padAreaMm2, double padCenterX, double padCenterY, String padShape,
                  boolean top, boolean bottom, List<ViaInPad> vias) {
        this.padAreaMm2 = padAreaMm2;
        this.padCenterX = padCenterX;
        this.padCenterY = padCenterY;
        this.padShape = padShape;
        this.top = top;
        this.bottom = bottom;
        this.vias = List.copyOf(vias);
    }

    /** Area of the paste opening in mm². */
    public double getPadAreaMm2() {
        return padAreaMm2;
    }

    /** Pad centre X in millimetres — the flash point, or the centre of a region's bounds. */
    public double getPadCenterX() {
        return padCenterX;
    }

    /** Pad centre Y in millimetres. */
    public double getPadCenterY() {
        return padCenterY;
    }

    /**
     * What drew the pad: the aperture template code ({@code C}, {@code R}, {@code O}, {@code P}, or
     * a macro name) for a flash, or {@code "region"} for painted artwork.
     */
    public String getPadShape() {
        return padShape;
    }

    /** True when the pad is on the top paste layer. */
    public boolean isTop() {
        return top;
    }

    /** True when the pad is on the bottom paste layer. */
    public boolean isBottom() {
        return bottom;
    }

    /** The holes inside this pad, in the order they were read from the drill file. */
    public List<ViaInPad> getVias() {
        return vias;
    }

    /** How many holes land in this pad. */
    public int getViaCount() {
        return vias.size();
    }

    /**
     * The drilled diameter in millimetres of the largest hole in the pad — the worst case, and the
     * only figure that differs from "the via diameter" on the rare pad with mixed tools.
     */
    public double getViaDiameterMm() {
        double max = 0;
        for (ViaInPad via : vias) {
            max = Math.max(max, via.getHoleDiameterMm());
        }
        return max;
    }

    /** The combined area in mm² of every hole in the pad — the paste that drains rather than reflows. */
    public double getTotalViaAreaMm2() {
        double sum = 0;
        for (ViaInPad via : vias) {
            sum += via.getHoleAreaMm2();
        }
        return sum;
    }

    /**
     * Pad area divided by the combined hole area: how much paste the land holds per unit of hole.
     * {@link Double#POSITIVE_INFINITY} when the holes have no measurable diameter, so a malformed
     * tool never manufactures a fill requirement.
     */
    public double getPadToViaAreaRatio() {
        double viaArea = getTotalViaAreaMm2();
        return viaArea <= 0 ? Double.POSITIVE_INFINITY : padAreaMm2 / viaArea;
    }

    /** As {@link #isLikelyThermal(ViaInPadPolicy)}, under {@link ViaInPadPolicy#DEFAULT}. */
    public boolean isLikelyThermal() {
        return isLikelyThermal(ViaInPadPolicy.DEFAULT);
    }

    /**
     * Whether this pad is a thermal/heat-spreader land whose vias need no capping: it holds a via
     * field, or it is large enough relative to its holes that the paste it carries survives what
     * drains away. See {@link ViaInPadPolicy} for what each threshold means.
     */
    public boolean isLikelyThermal(ViaInPadPolicy policy) {
        if (vias.size() >= policy.getMinViasForThermal()) {
            return true;
        }
        return padAreaMm2 >= policy.getMinThermalPadAreaMm2()
                && getPadToViaAreaRatio() >= policy.getMinPadToViaAreaRatio();
    }

    /** As {@link #requiresFilledAndCapped(ViaInPadPolicy)}, under {@link ViaInPadPolicy#DEFAULT}. */
    public boolean requiresFilledAndCapped() {
        return requiresFilledAndCapped(ViaInPadPolicy.DEFAULT);
    }

    /**
     * Whether this pad forces an IPC-4761 Type VII (filled and capped) via process — the negation of
     * {@link #isLikelyThermal(ViaInPadPolicy)}.
     */
    public boolean requiresFilledAndCapped(ViaInPadPolicy policy) {
        return !isLikelyThermal(policy);
    }

    @Override
    public String toString() {
        String side = top && bottom ? "top+bottom" : top ? "top" : "bottom";
        return String.format(Locale.US,
                "ViaInPadGroup[%s pad %.4f,%.4f %.4fmm2 %s, %d via(s) ø%.4fmm, ratio %.1f, %s]",
                side, padCenterX, padCenterY, padAreaMm2, padShape, vias.size(), getViaDiameterMm(),
                getPadToViaAreaRatio(), isLikelyThermal() ? "thermal" : "needs fill+cap");
    }
}
