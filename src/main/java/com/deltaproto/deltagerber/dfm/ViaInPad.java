package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.model.drill.DrillSpan;

import java.util.ArrayList;
import java.util.List;
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
 * <p>A via in pad is one hole <em>location</em>. The same location often arrives as several
 * drill hits — a stacked microvia is one hit per drill pair, and Altium writes every pair as a
 * Gerber X2 drill layer and again as an NC drill file — and those are one hole here;
 * {@link #getDrillHits()} says how many hits it gathered. Only hits whose drill reaches the pad's
 * surface count (see {@link ViaInPadDetector#detect(java.util.Collection, java.util.Collection,
 * java.util.Collection, int)}); {@link #isSpanKnown()} is false when the hole counted only because
 * some file did not say where its holes stop.
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
    private final int drillHits;
    private final List<DrillSpan> spans;
    private final boolean spanKnown;

    ViaInPad(double x, double y, double holeDiameterMm, boolean top, boolean bottom) {
        this(x, y, holeDiameterMm, top, bottom, 1, List.of(), false);
    }

    private ViaInPad(double x, double y, double holeDiameterMm, boolean top, boolean bottom,
                     int drillHits, List<DrillSpan> spans, boolean spanKnown) {
        this.x = x;
        this.y = y;
        this.holeDiameterMm = holeDiameterMm;
        this.top = top;
        this.bottom = bottom;
        this.drillHits = drillHits;
        this.spans = List.copyOf(spans);
        this.spanKnown = spanKnown;
    }

    /** The hole at {@code (x, y)} as it reaches the top and/or bottom surface ({@code null}: does not). */
    static ViaInPad of(double x, double y, ViaInPadDetector.Reach top, ViaInPadDetector.Reach bottom) {
        double diameter = 0;
        int hits = 0;
        boolean known = true;
        List<DrillSpan> spans = new ArrayList<>(2);
        for (ViaInPadDetector.Reach r : new ViaInPadDetector.Reach[]{top, bottom}) {
            if (r == null) {
                continue;
            }
            diameter = Math.max(diameter, r.diameterMm());
            hits = Math.max(hits, r.drillHits());
            known &= r.spanKnown();
            for (DrillSpan span : r.spans()) {
                if (!spans.contains(span)) {
                    spans.add(span);
                }
            }
        }
        return new ViaInPad(x, y, diameter, top != null, bottom != null, hits, spans, known);
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

    /** Area of the drilled hole in mm² — the paste that drains into the barrel rather than reflows. */
    public double getHoleAreaMm2() {
        double r = holeDiameterMm / 2;
        return Math.PI * r * r;
    }

    /** True when the hole sits inside a pad on the top paste layer. */
    public boolean isTop() {
        return top;
    }

    /** True when the hole sits inside a pad on the bottom paste layer. */
    public boolean isBottom() {
        return bottom;
    }

    /**
     * How many drill hits this one hole gathered on the surface it counts for — more than one for a
     * stacked microvia, or for a hole exported both as Gerber X2 and as an NC drill file.
     */
    public int getDrillHits() {
        return drillHits;
    }

    /** The drill spans that reach the pad's surface here, e.g. {@code 1-2} and {@code 1-12}; empty when none was stated. */
    public List<DrillSpan> getSpans() {
        return spans;
    }

    /**
     * False when this hole counts only because a drill file did not say which layers it connects
     * (a plain Excellon file) — it is then assumed to reach the pad, as a through hole would.
     */
    public boolean isSpanKnown() {
        return spanKnown;
    }

    @Override
    public String toString() {
        String side = top && bottom ? "top+bottom" : top ? "top" : "bottom";
        return String.format(Locale.US, "ViaInPad[%.4f,%.4f ø%.4fmm %s, %s]", x, y, holeDiameterMm, side,
                spanKnown ? "span " + spans : "span unknown");
    }
}
