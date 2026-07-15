package com.deltaproto.deltagerber.dfm;

import java.util.Locale;

/**
 * One drilled hole that lands inside a surface-mount component pad — a <em>via in pad</em>.
 *
 * <p>The pad is taken from the solder-paste layer, which marks exactly the lands a component
 * solders onto (a through-hole pad gets no paste), so a hole whose centre falls inside a paste
 * opening is a routing/thermal via sitting in an SMD land. Such a board must be fabricated with a
 * filled-and-capped via process (IPC-4761 Type VII) so solder does not wick down the barrel during
 * reflow — which is why {@link ViaInPadDetector} surfaces these at all.
 *
 * <p>Coordinates are millimetres in the Gerber/drill coordinate space (both parsers normalise to
 * mm), so they line up with the flashes and holes the rest of the library reports.
 */
public final class ViaInPad {

    private final double x;
    private final double y;
    private final double holeDiameterMm;
    private final boolean top;
    private final boolean bottom;

    ViaInPad(double x, double y, double holeDiameterMm, boolean top, boolean bottom) {
        this.x = x;
        this.y = y;
        this.holeDiameterMm = holeDiameterMm;
        this.top = top;
        this.bottom = bottom;
    }

    /** Hole X in millimetres. */
    public double getX() {
        return x;
    }

    /** Hole Y in millimetres. */
    public double getY() {
        return y;
    }

    /** Drilled diameter of the hole in millimetres. */
    public double getHoleDiameterMm() {
        return holeDiameterMm;
    }

    /** True when the hole sits inside a pad on the top paste layer. */
    public boolean isTop() {
        return top;
    }

    /** True when the hole sits inside a pad on the bottom paste layer. */
    public boolean isBottom() {
        return bottom;
    }

    @Override
    public String toString() {
        String side = top && bottom ? "top+bottom" : top ? "top" : "bottom";
        return String.format(Locale.US, "ViaInPad[%.4f,%.4f ø%.4fmm %s]", x, y, holeDiameterMm, side);
    }
}
