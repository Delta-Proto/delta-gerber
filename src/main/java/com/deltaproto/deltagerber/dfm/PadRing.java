package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.classify.LayerSide;

import java.util.Locale;

/**
 * The annular ring one drilled hole has on one copper layer: how much copper is left between the
 * wall of the hole and the nearest edge of its pad.
 *
 * <p>The ring is measured <strong>in the worst direction</strong>, not as
 * {@code (pad width − hole) / 2}: it is the shortest distance from the hole's edge to the pad's
 * edge, over every direction. That is the only figure that means anything on a pad that is not
 * round or a hole that does not sit in the middle of its pad — on an obround pad the short axis
 * decides, and a hole pushed off-centre has less copper on one side than the other, which is the
 * side that breaks out.
 *
 * <p>It is measured against the <em>drilled</em> diameter — what the drill file states, before
 * plating shrinks the finished hole and before drill registration moves it. A fabricator's own
 * acceptance figure is taken on the finished board and will differ.
 *
 * <p>A negative ring means the hole's edge crosses its pad's edge: {@link #isBreakout() breakout},
 * which is a defect and not merely a tight ring.
 */
public final class PadRing {

    /**
     * How far under zero a ring has to be before it is called a breakout, in mm.
     *
     * <p>A pad drawn the same size as its hole is routine — it states a ring of nothing, which is
     * what it is. Neither number survives the export exactly: a nominal 3.2 mm pad is written
     * 3.1999 and lands a digit or two off the hole's own rounded coordinates, so such a pad
     * measures a micron or two negative and would be reported as a hole outside its pad. The
     * tolerance is a tenth of a mil — under anything a design is drawn to, and two orders of
     * magnitude under the tightest ring anyone specifies.
     */
    private static final double BREAKOUT_TOLERANCE_MM = 0.0025;

    private final String layerName;
    private final LayerSide side;
    private final Integer layerNumber;
    private final double ringMm;
    private final String padShape;
    private final double padCenterX;
    private final double padCenterY;
    private final double offsetMm;

    PadRing(String layerName, LayerSide side, Integer layerNumber, double ringMm, String padShape,
            double padCenterX, double padCenterY, double offsetMm) {
        this.layerName = layerName;
        this.side = side;
        this.layerNumber = layerNumber;
        this.ringMm = ringMm;
        this.padShape = padShape;
        this.padCenterX = padCenterX;
        this.padCenterY = padCenterY;
        this.offsetMm = offsetMm;
    }

    /** The copper layer this ring was measured on, as the caller named it. */
    public String getLayerName() {
        return layerName;
    }

    /** Which side of the board that layer is — {@link LayerSide#INNER} for an inner layer. */
    public LayerSide getSide() {
        return side;
    }

    /** Stack-up index of an inner layer, null for outer copper. */
    public Integer getLayerNumber() {
        return layerNumber;
    }

    /** True for a layer on the outside of the board, where the fabricator's rule is the looser one. */
    public boolean isOuter() {
        return side == LayerSide.TOP || side == LayerSide.BOTTOM;
    }

    /**
     * The ring in millimetres: the shortest distance from the wall of the hole to the edge of the
     * pad. Negative when the hole breaks out of its pad.
     */
    public double getRingMm() {
        return ringMm;
    }

    /** The ring in micrometres, for callers that quote in µm as track widths are. */
    public double getRingUm() {
        return ringMm * 1000;
    }

    /**
     * True when the hole's edge crosses the pad's edge — a breakout, not a tight ring. A ring
     * negative by a rounding error is a pad drawn to the size of its hole and is not one; see
     * {@link #getRingMm()} for the measurement itself, which is reported as it was measured.
     */
    public boolean isBreakout() {
        return ringMm < -BREAKOUT_TOLERANCE_MM;
    }

    /**
     * What drew the pad: the aperture template code ({@code C}, {@code R}, {@code O}, {@code P} or
     * a macro name) for a flash, or {@code "stroke"} for a pad drawn as a swept aperture — which is
     * how several tools draw an oblong through-hole pad.
     */
    public String getPadShape() {
        return padShape;
    }

    /** Pad centre X in millimetres, in the Gerber frame. */
    public double getPadCenterX() {
        return padCenterX;
    }

    /** Pad centre Y in millimetres, in the Gerber frame. */
    public double getPadCenterY() {
        return padCenterY;
    }

    /**
     * How far the hole sits from the centre of its pad, in millimetres. Zero on a healthy pad;
     * anything else is a deliberately offset pad or a footprint error, and it is what turns an
     * ample nominal ring into a thin one on the near side.
     */
    public double getOffsetMm() {
        return offsetMm;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "PadRing[%s %s%s ring %.4fmm %s]",
                layerName, side, layerNumber == null ? "" : " " + layerNumber, ringMm, padShape);
    }
}
