package com.deltaproto.deltagerber.align;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.DrillOperation;
import com.deltaproto.deltagerber.model.drill.DrillSlot;
import com.deltaproto.deltagerber.model.drill.Tool;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detects and corrects an origin mismatch between an Excellon drill file and its Gerber stack.
 *
 * <p>Some EDA tools (notably Altium Designer) let the Gerber and NC-drill outputs reference
 * different origins — e.g. the Gerbers are written relative to the board/relative origin (board
 * near 0,0) while the drill file is written relative to the absolute sheet origin (board offset
 * far from 0,0). Nothing in the drill file records that offset, so the holes end up placed far
 * from the copper they belong to and any "all layers" overlay shows them floating off the board.
 *
 * <p>This class recovers the offset <em>exactly</em> rather than guessing: every plated hole is
 * concentric with its copper pad, so the set of {@code (pad - hole)} vectors collapses to a
 * single translation — the origin difference between the two files. {@link #analyze} votes those
 * vectors and accepts the dominant one only when it snaps a strong majority of holes onto pads.
 * If no translation earns enough support the result is left {@linkplain Status#MISALIGNED_UNRESOLVED
 * unresolved} — an exact fix or none, never an approximate one.
 *
 * <p>The result is purely informational: callers decide whether to {@link #apply} it (producing a
 * corrected {@link DrillDocument}), surface {@link Result#getWarningText() its explanation} to the
 * user, or ignore it.
 */
public final class DrillGerberAlignment {

    private DrillGerberAlignment() {
    }

    // Quantisation of the candidate (pad - hole) offset vectors when voting for the dominant
    // translation. 0.05 mm is fine enough to separate genuinely different offsets yet coarse
    // enough that the same physical offset, re-expressed across many hole/pad pairs, lands in
    // one bucket despite coordinate rounding between the two files.
    private static final double VOTE_GRID_MM = 0.05;
    // A hole is considered to "land on" a pad when, after the candidate offset, their centres are
    // within this distance. Plated holes are concentric with their pads, so the true match is
    // near-exact; this tolerance only absorbs coordinate rounding.
    private static final double MATCH_TOL_MM = 0.075;
    // The winning offset must map at least this fraction (and this absolute count) of the drill
    // holes onto pads, otherwise the match is rejected as not confident enough.
    private static final double MIN_SUPPORT_FRACTION = 0.5;
    private static final int MIN_SUPPORT_COUNT = 3;
    // Backstop on the O(holes x pads) voting cost. Analysis only attempts a match for a drill
    // whose bounds are disjoint from the board (rare), but a pathological file could still have
    // huge counts — subsample the holes so the product stays bounded. The dominant translation is
    // recoverable from a subset because every plated hole shares the same offset.
    private static final int MAX_PAIRS = 4_000_000;

    /** Whether the drill is aligned with the Gerbers, and if not, whether we could fix it. */
    public enum Status {
        /** The drill already overlaps the board — nothing to do. */
        ALIGNED,
        /** The drill is on a different origin and the exact offset was recovered. */
        MISALIGNED_RESOLVED,
        /** The drill is on a different origin but the offset could not be recovered. */
        MISALIGNED_UNRESOLVED
    }

    /** Outcome of {@link DrillGerberAlignment#analyze analysing} a drill against the Gerbers. */
    public static final class Result {
        private final Status status;
        private final double offsetX;
        private final double offsetY;
        private final int matchedHoles;
        private final int totalHoles;

        Result(Status status, double offsetX, double offsetY, int matchedHoles, int totalHoles) {
            this.status = status;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.matchedHoles = matchedHoles;
            this.totalHoles = totalHoles;
        }

        public Status getStatus() { return status; }

        /** True when the drill sits on a different origin than the Gerbers (resolved or not). */
        public boolean isMisaligned() { return status != Status.ALIGNED; }

        /** True when an exact offset was recovered and {@link #apply} will correct the drill. */
        public boolean isResolved() { return status == Status.MISALIGNED_RESOLVED; }

        /** Millimetres to add to the drill X coordinates to align them (0 unless resolved). */
        public double getOffsetX() { return offsetX; }

        /** Millimetres to add to the drill Y coordinates to align them (0 unless resolved). */
        public double getOffsetY() { return offsetY; }

        /** Number of holes that matched a copper pad under the recovered offset. */
        public int getMatchedHoles() { return matchedHoles; }

        /** Total number of drill hits considered. */
        public int getTotalHoles() { return totalHoles; }

        /**
         * A human-readable explanation suitable for surfacing to the user — what was detected,
         * what was done (or why it couldn't be), why it happens, and how to fix the export. Returns
         * {@code null} when the drill is {@link Status#ALIGNED aligned} (nothing to report).
         */
        public String getWarningText() {
            switch (status) {
                case MISALIGNED_RESOLVED:
                    return String.format(Locale.US,
                        "Drill holes were exported on a different coordinate origin than the Gerber "
                        + "files, placing them off the board. They have been automatically re-aligned "
                        + "by matching %d of %d holes to the copper pads (drill shifted %+.3f, %+.3f "
                        + "mm).", matchedHoles, totalHoles, offsetX, offsetY)
                        + ORIGIN_EXPLANATION;
                case MISALIGNED_UNRESOLVED:
                    return
                        "Drill holes appear to use a different coordinate origin than the Gerber "
                        + "files (they fall entirely outside the board outline), but the correct "
                        + "offset could not be determined automatically — the holes did not match the "
                        + "copper pads. The drill layer is shown at its exported position. Re-export "
                        + "the drill and Gerber files using the same origin reference so they share "
                        + "an origin.";
                default:
                    return null;
            }
        }
    }

    // Shared tail for the "re-aligned" warning: why it happens and how to fix the export.
    private static final String ORIGIN_EXPLANATION =
        " This usually happens when the NC Drill and Gerber outputs were generated with different "
        + "origin references — for example Altium's \"Reference to Absolute Origin\" versus "
        + "\"Reference to Relative Origin\". To fix it at the source, re-export the drill and Gerber "
        + "files using the same origin setting.";

    /**
     * Analyse a drill document against an explicit Gerber reference: the union of the Gerber
     * bounding boxes (used to detect that the drill is off the board) and the copper pad centres
     * (used to recover the exact offset). Prefer the copper-layer flashes for {@code padCenters};
     * see {@link #flashCenters(GerberDocument)}.
     */
    public static Result analyze(DrillDocument drill, BoundingBox gerberBounds,
                                 List<double[]> padCenters) {
        int total = holeCount(drill);
        if (drill == null || gerberBounds == null || !gerberBounds.isValid()) {
            return new Result(Status.ALIGNED, 0, 0, 0, total);
        }
        BoundingBox drillBounds = drill.getBoundingBox();
        if (drillBounds == null || !drillBounds.isValid() || boxesOverlap(drillBounds, gerberBounds)) {
            return new Result(Status.ALIGNED, 0, 0, 0, total);
        }
        // The drill is entirely off the board → suspect a different origin. Try to resolve it.
        double[] match = correlate(drill, padCenters);
        if (match == null) {
            return new Result(Status.MISALIGNED_UNRESOLVED, 0, 0, 0, total);
        }
        return new Result(Status.MISALIGNED_RESOLVED, match[0], match[1], (int) match[2], total);
    }

    /**
     * Convenience overload that derives the reference bounds and pad centres from a collection of
     * Gerber documents (using every flash as a candidate pad). When layer roles are known, prefer
     * {@link #analyze(DrillDocument, BoundingBox, List)} with only the copper-layer flashes.
     */
    public static Result analyze(DrillDocument drill, Collection<GerberDocument> gerbers) {
        BoundingBox bounds = new BoundingBox();
        List<double[]> pads = new ArrayList<>();
        if (gerbers != null) {
            for (GerberDocument g : gerbers) {
                if (g == null) {
                    continue;
                }
                BoundingBox b = g.getBoundingBox();
                if (b != null && b.isValid()) {
                    bounds.include(b);
                }
                pads.addAll(flashCenters(g));
            }
        }
        return analyze(drill, bounds, pads);
    }

    /**
     * Return a copy of {@code drill} translated by the offset in {@code result}, aligning it with
     * the Gerbers. Returns {@code drill} unchanged when the result is not
     * {@linkplain Result#isResolved() resolved}, so callers can apply unconditionally.
     */
    public static DrillDocument apply(DrillDocument drill, Result result) {
        if (drill == null || result == null || !result.isResolved()) {
            return drill;
        }
        return shift(drill, result.getOffsetX(), result.getOffsetY());
    }

    /**
     * Return a copy of {@code drill} translated by ({@code dx}, {@code dy}) mm — the operation
     * {@link #apply} performs, exposed for callers that already know the offset (e.g. a client
     * re-applying a previously detected alignment) and so want to skip detection. Returns
     * {@code drill} unchanged when the offset is zero.
     */
    public static DrillDocument shift(DrillDocument drill, double dx, double dy) {
        if (drill == null || (dx == 0 && dy == 0)) {
            return drill;
        }
        return translate(drill, dx, dy);
    }

    /**
     * One-call detect-and-correct: return {@code drill} aligned into the Gerber frame, or the input
     * unchanged when it is already aligned or the offset could not be recovered. The returned
     * document carries corrected coordinates (fix-once) and a reversible
     * {@link DrillDocument#getOriginOffsetX() originOffset} stamp.
     * <p>
     * This is the entry point for analyses that need drill positions in the same frame as the
     * Gerbers — e.g. testing whether a hole falls inside a soldermask opening (via-in-pad) — so they
     * never operate on raw, un-aligned coordinates by accident.
     */
    public static DrillDocument aligned(DrillDocument drill, Collection<GerberDocument> gerbers) {
        return apply(drill, analyze(drill, gerbers));
    }

    /**
     * As {@link #aligned(DrillDocument, Collection)} but against an explicit reference (the union of
     * the Gerber bounds and the copper pad centres), letting the caller restrict pads to the copper
     * layers.
     */
    public static DrillDocument aligned(DrillDocument drill, BoundingBox gerberBounds,
                                        List<double[]> padCenters) {
        return apply(drill, analyze(drill, gerberBounds, padCenters));
    }

    /** Extract the centre of every flash (pad) in a Gerber document. */
    public static List<double[]> flashCenters(GerberDocument gerber) {
        List<double[]> out = new ArrayList<>();
        if (gerber == null) {
            return out;
        }
        for (GraphicsObject obj : gerber.getObjects()) {
            if (obj instanceof Flash) {
                Flash f = (Flash) obj;
                out.add(new double[]{f.getX(), f.getY()});
            }
        }
        return out;
    }

    /**
     * Recover the exact translation that maps this drill's holes onto the given pad centres, as
     * {@code [offsetX, offsetY, matchedHoleCount]}, or {@code null} if no translation earns enough
     * support. Wrong pairings scatter across the vote space and cannot out-vote the true offset.
     * <p>
     * Both hole and pad coordinates are millimetres — {@code ExcellonParser} and {@code GerberParser}
     * normalise to mm at parse time — so the {@code (pad - hole)} vectors are directly comparable
     * without any unit conversion.
     */
    private static double[] correlate(DrillDocument drill, List<double[]> pads) {
        if (pads == null || pads.isEmpty()) {
            return null;
        }
        List<double[]> holes = new ArrayList<>();
        for (DrillOperation op : drill.getOperations()) {
            if (op instanceof DrillHit) {
                DrillHit h = (DrillHit) op;
                holes.add(new double[]{h.getX(), h.getY()});
            }
        }
        if (holes.size() < MIN_SUPPORT_COUNT) {
            return null;
        }

        // Bound the work by subsampling holes when holes x pads would be too large.
        List<double[]> voteHoles = holes;
        long pairs = (long) holes.size() * pads.size();
        if (pairs > MAX_PAIRS) {
            int step = (int) Math.ceil((double) pairs / MAX_PAIRS);
            voteHoles = new ArrayList<>();
            for (int i = 0; i < holes.size(); i += step) {
                voteHoles.add(holes.get(i));
            }
        }

        // Vote on quantised (pad - hole) offset vectors; remember the dominant bucket.
        Map<Long, Integer> votes = new HashMap<>();
        long bestKey = 0;
        int bestVotes = 0;
        for (double[] h : voteHoles) {
            for (double[] p : pads) {
                long gx = Math.round((p[0] - h[0]) / VOTE_GRID_MM);
                long gy = Math.round((p[1] - h[1]) / VOTE_GRID_MM);
                long key = (gx << 32) | (gy & 0xffffffffL);
                int v = votes.merge(key, 1, Integer::sum);
                if (v > bestVotes) {
                    bestVotes = v;
                    bestKey = key;
                }
            }
        }
        if (bestVotes == 0) {
            return null;
        }
        double coarseX = (int) (bestKey >> 32) * VOTE_GRID_MM;
        double coarseY = (int) bestKey * VOTE_GRID_MM;

        // Refine to sub-grid precision and measure support: how many distinct holes land on a pad
        // under this offset, and the mean exact offset over the matched pairs.
        double tolSq = MATCH_TOL_MM * MATCH_TOL_MM;
        double sumX = 0;
        double sumY = 0;
        int matchedPairs = 0;
        int matchedHoles = 0;
        for (double[] h : voteHoles) {
            boolean holeMatched = false;
            for (double[] p : pads) {
                double dx = p[0] - h[0] - coarseX;
                double dy = p[1] - h[1] - coarseY;
                if (dx * dx + dy * dy <= tolSq) {
                    sumX += p[0] - h[0];
                    sumY += p[1] - h[1];
                    matchedPairs++;
                    holeMatched = true;
                }
            }
            if (holeMatched) {
                matchedHoles++;
            }
        }

        if (matchedHoles < MIN_SUPPORT_COUNT
            || matchedHoles < MIN_SUPPORT_FRACTION * voteHoles.size()) {
            return null;
        }
        return new double[]{sumX / matchedPairs, sumY / matchedPairs, matchedHoles};
    }

    /**
     * Build a copy of {@code src} with every hit and slot translated by {@code (dx, dy)} — the
     * coordinates are baked in (fix-once) and the document's {@link DrillDocument#getOriginOffsetX
     * originOffset} stamp is advanced by the same amount so the shift stays reversible:
     * {@code original = corrected - originOffset}.
     */
    private static DrillDocument translate(DrillDocument src, double dx, double dy) {
        DrillDocument out = new DrillDocument();
        out.setFileName(src.getFileName());
        out.setUnit(src.getUnit());
        out.setCoordinateMode(src.getCoordinateMode());
        out.setIntegerDigits(src.getIntegerDigits());
        out.setDecimalDigits(src.getDecimalDigits());
        out.setLeadingZeros(src.isLeadingZeros());
        out.setOriginOffset(src.getOriginOffsetX() + dx, src.getOriginOffsetY() + dy);
        for (Tool t : src.getTools().values()) {
            out.addTool(t);
        }
        for (String c : src.getComments()) {
            out.addComment(c);
        }
        for (String w : src.getWarnings()) {
            out.addWarning(w);
        }
        for (DrillOperation op : src.getOperations()) {
            if (op instanceof DrillHit) {
                DrillHit h = (DrillHit) op;
                out.addOperation(new DrillHit(h.getTool(), h.getX() + dx, h.getY() + dy));
            } else if (op instanceof DrillSlot) {
                DrillSlot s = (DrillSlot) op;
                out.addOperation(new DrillSlot(s.getTool(),
                    s.getStartX() + dx, s.getStartY() + dy,
                    s.getEndX() + dx, s.getEndY() + dy));
            }
        }
        return out;
    }

    private static int holeCount(DrillDocument drill) {
        if (drill == null) {
            return 0;
        }
        int n = 0;
        for (DrillOperation op : drill.getOperations()) {
            if (op instanceof DrillHit) {
                n++;
            }
        }
        return n;
    }

    /** True when two valid bounding boxes share any area (touching edges count as overlap). */
    private static boolean boxesOverlap(BoundingBox a, BoundingBox b) {
        return a.getMinX() <= b.getMaxX() && a.getMaxX() >= b.getMinX()
            && a.getMinY() <= b.getMaxY() && a.getMaxY() >= b.getMinY();
    }
}
