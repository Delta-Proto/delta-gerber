package com.deltaproto.deltagerber.dfm;

import java.util.List;
import java.util.Locale;

/**
 * One drilled hole and the copper around it, layer by layer.
 *
 * <p>A hole's annular ring is not one number until the stack is read: it has a ring on every copper
 * layer whose pad it passes through, and those differ. {@link #getMinRingMm()} is the number a
 * fabricator's design rule is applied to; {@link #getPads()} is the breakdown a designer fixes,
 * naming the layer that is worst.
 *
 * <p>A layer appears here only where a pad was actually found. That is deliberate: a via passing
 * through a plane it does not connect to has an <em>antipad</em> there, not a thin ring, and a
 * hole that has no pad on any layer at all ({@link #hasPad()} false) is either a non-plated
 * mounting hole — which needs none — or a plated hole missing its pad, which is a different defect
 * from a tight ring and is reported separately by
 * {@link AnnularRingResult#getHolesWithoutPad()}.
 *
 * <p>Coordinates are millimetres in the Gerber frame, so they line up with everything else the
 * library reports; a drill exported on a foreign origin is moved onto the copper first (see
 * {@link AnnularRingDetector#detectAligned}).
 */
public final class AnnularRing {

    private final double x;
    private final double y;
    private final double holeDiameterMm;
    private final String drillFile;
    private final Boolean plated;
    private final List<PadRing> pads;

    AnnularRing(double x, double y, double holeDiameterMm, String drillFile, Boolean plated,
                List<PadRing> pads) {
        this.x = x;
        this.y = y;
        this.holeDiameterMm = holeDiameterMm;
        this.drillFile = drillFile;
        this.plated = plated;
        this.pads = List.copyOf(pads);
    }

    /** Hole centre X in millimetres. */
    public double getX() {
        return x;
    }

    /** Hole centre Y in millimetres. */
    public double getY() {
        return y;
    }

    /** The drilled diameter in millimetres, as the drill file's tool table states it. */
    public double getHoleDiameterMm() {
        return holeDiameterMm;
    }

    /** The drill file this hole came from, or null when it carried no file name. */
    public String getDrillFile() {
        return drillFile;
    }

    /**
     * Whether the drill file said this hole is plated: {@code TRUE}, or {@code null} when it did
     * not say. Never {@code FALSE} — a hole stated to be non-plated is not measured at all.
     *
     * <p>It decides what a missing pad means: a plated hole with no pad has lost one, an unstated
     * hole with no pad is as likely to be a mounting hole.
     */
    public Boolean getPlated() {
        return plated;
    }

    /** The ring on every copper layer where this hole found a pad, in the order the layers were given. */
    public List<PadRing> getPads() {
        return pads;
    }

    /** True when at least one copper layer has a pad around this hole. */
    public boolean hasPad() {
        return !pads.isEmpty();
    }

    /**
     * The smallest ring across the stack in millimetres — the figure a design rule is applied to —
     * or {@code null} when the hole has no pad on any layer.
     */
    public Double getMinRingMm() {
        PadRing worst = getWorstPad();
        return worst == null ? null : worst.getRingMm();
    }

    /** The layer with the least copper around this hole, or null when it has no pad anywhere. */
    public PadRing getWorstPad() {
        PadRing worst = null;
        for (PadRing pad : pads) {
            if (worst == null || pad.getRingMm() < worst.getRingMm()) {
                worst = pad;
            }
        }
        return worst;
    }

    /** True when the hole breaks out of its pad on at least one layer. */
    public boolean isBreakout() {
        for (PadRing pad : pads) {
            if (pad.isBreakout()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        Double min = getMinRingMm();
        return String.format(Locale.US, "AnnularRing[%.4f,%.4f ø%.4fmm %s on %d layer(s)]",
                x, y, holeDiameterMm,
                min == null ? "no pad" : String.format(Locale.US, "min ring %.4fmm", min),
                pads.size());
    }
}
