package com.deltaproto.deltagerber.dfm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What a via-in-pad verdict rests on, in a form a consumer can show and judge.
 *
 * <p>{@link ViaInPadResult#requiresFilledAndCapped()} is one boolean for the whole board, and one
 * borderline pad is enough to set it. That is right as a rule and wrong as the only thing anyone
 * sees: a board with 90 of 220 pads needing fill and a board with 1 of 56 both answer
 * {@code true}, and the paste file a verdict rests on may not even belong to the design. So this
 * carries the counts side by side — {@link #getPadsWithHole()}, {@link #getThermalPads()},
 * {@link #getFillPads()} — and lists the pads needing fill as {@link PadKind}s: pads that are the
 * same pad (same side, paste file, shape, area, hole and via count) are one entry with a count and
 * the first few locations.
 *
 * <p>Nothing here changes the verdict; {@link #requiresFilledAndCapped()} is the result's own.
 */
public final class ViaInPadSummary {

    /** How many kinds of fill pad {@link ViaInPadResult#summarize()} lists. */
    public static final int DEFAULT_MAX_KINDS = 10;

    /** How many locations each {@link PadKind} keeps. */
    public static final int MAX_LOCATIONS_PER_KIND = 5;

    private final int padsWithHole;
    private final int thermalPads;
    private final int fillPads;
    private final int holes;
    private final int excludedBySpan;
    private final boolean spanUnknown;
    private final ViaInPadPolicy policy;
    private final List<PadKind> fillPadKinds;
    private final int omittedFillPadKinds;

    private ViaInPadSummary(int padsWithHole, int thermalPads, int fillPads, int holes,
                            int excludedBySpan, boolean spanUnknown, ViaInPadPolicy policy,
                            List<PadKind> fillPadKinds, int omittedFillPadKinds) {
        this.padsWithHole = padsWithHole;
        this.thermalPads = thermalPads;
        this.fillPads = fillPads;
        this.holes = holes;
        this.excludedBySpan = excludedBySpan;
        this.spanUnknown = spanUnknown;
        this.policy = policy;
        this.fillPadKinds = List.copyOf(fillPadKinds);
        this.omittedFillPadKinds = omittedFillPadKinds;
    }

    static ViaInPadSummary of(ViaInPadResult result, ViaInPadPolicy policy, int maxKinds) {
        Objects.requireNonNull(policy, "policy");
        int thermal = 0;
        Map<String, KindBuilder> kinds = new LinkedHashMap<>();
        for (ViaInPadGroup g : result.getGroups()) {
            if (g.isLikelyThermal(policy)) {
                thermal++;
                continue;
            }
            String key = String.format(Locale.US, "%b|%b|%s|%s|%.4f|%.4f|%d", g.isTop(), g.isBottom(),
                    g.getPasteFileName(), g.getPadShape(), g.getPadAreaMm2(), g.getViaDiameterMm(),
                    g.getViaCount());
            kinds.computeIfAbsent(key, k -> new KindBuilder(g)).add(g);
        }
        int fill = result.getGroups().size() - thermal;
        List<PadKind> list = new ArrayList<>();
        for (KindBuilder b : kinds.values()) {
            list.add(b.build());
        }
        // Most numerous first; among equals, the smallest pad — the one furthest from thermal.
        list.sort(Comparator.comparingInt(PadKind::getPadCount).reversed()
                .thenComparingDouble(PadKind::getPadAreaMm2));
        int keep = Math.max(0, Math.min(maxKinds, list.size()));
        return new ViaInPadSummary(result.getGroups().size(), thermal, fill, result.getCount(),
                result.getExcludedBySpanCount(), result.hasUnknownSpan(), policy,
                list.subList(0, keep), list.size() - keep);
    }

    /** Pads with at least one hole in them. */
    public int getPadsWithHole() {
        return padsWithHole;
    }

    /** Of those, the pads judged thermal — their holes can stay open. */
    public int getThermalPads() {
        return thermalPads;
    }

    /** Of those, the pads whose holes need filling and capping. */
    public int getFillPads() {
        return fillPads;
    }

    /** Distinct holes inside a pad. */
    public int getHoles() {
        return holes;
    }

    /** Holes inside a pad but left out because their stated drill span does not reach its surface. */
    public int getExcludedBySpan() {
        return excludedBySpan;
    }

    /** True when some hole was counted only because its drill file stated no layer span. */
    public boolean isSpanUnknown() {
        return spanUnknown;
    }

    /** The thresholds the pads were judged by. */
    public ViaInPadPolicy getPolicy() {
        return policy;
    }

    /** The board-level verdict: at least one pad needs fill. */
    public boolean requiresFilledAndCapped() {
        return fillPads > 0;
    }

    /** The pads needing fill, one entry per kind of identical pad, most numerous first. */
    public List<PadKind> getFillPadKinds() {
        return fillPadKinds;
    }

    /** Kinds of fill pad left off {@link #getFillPadKinds()} by the cap. */
    public int getOmittedFillPadKinds() {
        return omittedFillPadKinds;
    }

    /**
     * A few lines of plain text: the counts, then one line per kind of fill pad. Meant to be shown
     * as it is, or handed to someone weighing the verdict.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Via in pad: %d hole(s) in %d pad(s); %d thermal, %d need fill and cap",
                holes, padsWithHole, thermalPads, fillPads));
        if (excludedBySpan > 0) {
            sb.append(String.format(Locale.US, "; %d hole(s) under a pad left out (drill span does not reach it)",
                    excludedBySpan));
        }
        if (spanUnknown) {
            sb.append("; some drill files state no layer span, their holes are assumed to reach the pad");
        }
        for (PadKind kind : fillPadKinds) {
            sb.append("\n  ").append(kind);
        }
        if (omittedFillPadKinds > 0) {
            sb.append(String.format(Locale.US, "\n  ... and %d more kind(s) of pad", omittedFillPadKinds));
        }
        return sb.toString();
    }

    /**
     * Identical pads needing fill: same side, same paste file, same shape, area, hole diameter and
     * via count. {@link #getLocations()} holds the centres of the first
     * {@value ViaInPadSummary#MAX_LOCATIONS_PER_KIND} of them.
     */
    public static final class PadKind {
        private final boolean top;
        private final boolean bottom;
        private final String pasteFileName;
        private final String padShape;
        private final double padAreaMm2;
        private final double holeDiameterMm;
        private final int viaCount;
        private final double padToViaAreaRatio;
        private final boolean spanKnown;
        private final int padCount;
        private final List<double[]> locations;

        private PadKind(ViaInPadGroup g, boolean spanKnown, int padCount, List<double[]> locations) {
            this.top = g.isTop();
            this.bottom = g.isBottom();
            this.pasteFileName = g.getPasteFileName();
            this.padShape = g.getPadShape();
            this.padAreaMm2 = g.getPadAreaMm2();
            this.holeDiameterMm = g.getViaDiameterMm();
            this.viaCount = g.getViaCount();
            this.padToViaAreaRatio = g.getPadToViaAreaRatio();
            this.spanKnown = spanKnown;
            this.padCount = padCount;
            this.locations = List.copyOf(locations);
        }

        /** True for a pad on the top paste layer. */
        public boolean isTop() {
            return top;
        }

        /** True for a pad on the bottom paste layer. */
        public boolean isBottom() {
            return bottom;
        }

        /** The paste file the pads come from, or {@code null} when it was not named. */
        public String getPasteFileName() {
            return pasteFileName;
        }

        /** The aperture template code, or {@code "region"}. */
        public String getPadShape() {
            return padShape;
        }

        /** Area of one pad's paste opening in mm². */
        public double getPadAreaMm2() {
            return padAreaMm2;
        }

        /** Drilled diameter of the largest hole in the pad, mm. */
        public double getHoleDiameterMm() {
            return holeDiameterMm;
        }

        /** Distinct holes in each pad. */
        public int getViaCount() {
            return viaCount;
        }

        /** Pad area over total hole area — the figure the policy's ratio rule compares. */
        public double getPadToViaAreaRatio() {
            return padToViaAreaRatio;
        }

        /** False when some hole in these pads counts only because its drill stated no span. */
        public boolean isSpanKnown() {
            return spanKnown;
        }

        /** How many pads are of this kind. */
        public int getPadCount() {
            return padCount;
        }

        /** Pad centres {@code {x, y}} in mm, at most {@value ViaInPadSummary#MAX_LOCATIONS_PER_KIND}. */
        public List<double[]> getLocations() {
            return locations;
        }

        @Override
        public String toString() {
            StringBuilder at = new StringBuilder();
            for (double[] p : locations) {
                at.append(at.length() == 0 ? "" : " ").append(String.format(Locale.US, "(%.3f, %.3f)", p[0], p[1]));
            }
            if (padCount > locations.size()) {
                at.append(" ...");
            }
            String side = top && bottom ? "top+bottom" : top ? "top" : "bottom";
            return String.format(Locale.US, "%dx %s %s pad %.3f mm2, %d hole(s) of %.3f mm, ratio %.1f%s, %s at %s",
                    padCount, side, padShape, padAreaMm2, viaCount, holeDiameterMm, padToViaAreaRatio,
                    spanKnown ? "" : " (span unknown)",
                    pasteFileName == null ? "unnamed paste" : pasteFileName, at);
        }
    }

    private static final class KindBuilder {
        private final ViaInPadGroup first;
        private int count;
        private boolean spanKnown = true;
        private final List<double[]> locations = new ArrayList<>();

        KindBuilder(ViaInPadGroup first) {
            this.first = first;
        }

        void add(ViaInPadGroup g) {
            count++;
            spanKnown &= g.isSpanKnown();
            if (locations.size() < MAX_LOCATIONS_PER_KIND) {
                locations.add(new double[]{g.getPadCenterX(), g.getPadCenterY()});
            }
        }

        PadKind build() {
            return new PadKind(first, spanKnown, count, locations);
        }
    }
}
