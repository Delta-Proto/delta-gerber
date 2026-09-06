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
 * <p><b>Misalignment is judged by hole-on-pad support, not by bounding boxes.</b> A displaced drill
 * only clears the board's bounding box when the shift exceeds the board's own size, so on a large
 * board a badly-placed drill still overlaps it — the boxes are useless as a verdict. What they are
 * good for is a cheap trigger: a correctly placed hole is always inside the board, so a drill whose
 * bounds are <em>contained</em> in the Gerber bounds is aligned and is dismissed without any further
 * work (this is what keeps a healthy set from paying for the pad index). Anything that pokes outside
 * is then judged properly — by counting how many of its holes already sit on a copper pad, and only
 * searching for a translation when few of them do.
 *
 * <p>The result is purely informational: callers decide whether to {@link #apply} it (producing a
 * corrected {@link DrillDocument}), surface {@link Result#getWarningText() its explanation} to the
 * user, or ignore it.
 *
 * <p>A drill <em>set</em> should go through {@link #analyzeAll} / {@link #alignedAll} rather than
 * analysing each file alone. EDA tools split the drill program across files — Altium writes round
 * holes and slots separately — and a file of nothing but slots carries no hole centres to correlate,
 * so it can never recover its own offset. All files in one export share an origin, so the offset
 * recovered from the round holes is handed to its siblings.
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
    // A drill with at least this fraction of its holes already sitting on pads is where it belongs;
    // no translation is searched for. The remainder are the file's NPTH holes, which have no pad.
    private static final double SEATED_FRACTION = 0.5;
    // The winning offset must map at least this fraction (and this absolute count) of the drill
    // holes onto pads, otherwise the match is rejected as not confident enough.
    private static final double MIN_SUPPORT_FRACTION = 0.5;
    private static final int MIN_SUPPORT_COUNT = 3;
    // The bar is raised for a drill that still overlaps the board: overlapping is weak evidence of
    // being misplaced (a drill hanging entirely off the board is unambiguous), so moving one needs
    // near-unanimous support over enough holes that a coincidental pad grid cannot supply it.
    private static final double OVERLAP_MIN_SUPPORT_FRACTION = 0.9;
    private static final int OVERLAP_MIN_SUPPORT_COUNT = 8;
    // Backstop on the O(holes x pads) voting cost. Analysis only attempts a match for a drill that
    // is not seated on the copper (rare), but a pathological file could still have huge counts —
    // subsample the holes so the product stays bounded. The dominant translation is recoverable
    // from a subset because every plated hole shares the same offset.
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
        private final boolean inherited;

        Result(Status status, double offsetX, double offsetY, int matchedHoles, int totalHoles) {
            this(status, offsetX, offsetY, matchedHoles, totalHoles, false);
        }

        Result(Status status, double offsetX, double offsetY, int matchedHoles, int totalHoles,
               boolean inherited) {
            this.status = status;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.matchedHoles = matchedHoles;
            this.totalHoles = totalHoles;
            this.inherited = inherited;
        }

        public Status getStatus() { return status; }

        /** True when the drill sits on a different origin than the Gerbers (resolved or not). */
        public boolean isMisaligned() { return status != Status.ALIGNED; }

        /** True when an exact offset was recovered and {@link #apply} will correct the drill. */
        public boolean isResolved() { return status == Status.MISALIGNED_RESOLVED; }

        /**
         * True when this drill could not recover its own offset and took the one another drill in
         * the same set recovered — see {@link DrillGerberAlignment#analyzeAll}. Always false for a
         * result produced by the single-document {@link DrillGerberAlignment#analyze}.
         */
        public boolean isInherited() { return inherited; }

        /** Millimetres to add to the drill X coordinates to align them (0 unless resolved). */
        public double getOffsetX() { return offsetX; }

        /** Millimetres to add to the drill Y coordinates to align them (0 unless resolved). */
        public double getOffsetY() { return offsetY; }

        /**
         * Number of holes that matched a copper pad under the recovered offset. Zero for an
         * {@linkplain #isInherited() inherited} offset — that drill matched nothing itself, which
         * is why it needed a sibling's answer.
         */
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
                    if (inherited) {
                        return String.format(Locale.US,
                            "Drill holes were exported on a different coordinate origin than the "
                            + "Gerber files, placing them off the board. This file carries no hole "
                            + "centres to match against the copper pads (a slot-only drill file, for "
                            + "example), so it has been re-aligned using the offset recovered from "
                            + "another drill file in the same set (shifted %+.3f, %+.3f mm).",
                            offsetX, offsetY)
                            + ORIGIN_EXPLANATION;
                    }
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
     * bounding boxes (used as a cheap "is it on the board at all" trigger) and the copper pad
     * centres (used to judge whether the holes are seated and, if not, to recover the exact offset).
     * Prefer the copper-layer flashes for {@code padCenters}; see {@link #flashCenters(GerberDocument)}.
     * <p>
     * For a set of drill files that belong together, prefer {@link #analyzeAll} — see this class's
     * documentation for why a slot-only file cannot answer for itself.
     */
    public static Result analyze(DrillDocument drill, BoundingBox gerberBounds,
                                 List<double[]> padCenters) {
        int total = holeCount(drill);
        if (drill == null || gerberBounds == null || !gerberBounds.isValid()) {
            return new Result(Status.ALIGNED, 0, 0, 0, total);
        }
        if (!suspect(drill, gerberBounds)) {
            return new Result(Status.ALIGNED, 0, 0, 0, total);
        }
        return examine(drill, gerberBounds, padCenters, PadIndex.of(padCenters), total);
    }

    /**
     * Convenience overload that derives the reference bounds and pad centres from a collection of
     * Gerber documents (using every flash as a candidate pad). When layer roles are known, prefer
     * {@link #analyze(DrillDocument, BoundingBox, List)} with only the copper-layer flashes.
     */
    public static Result analyze(DrillDocument drill, Collection<GerberDocument> gerbers) {
        Reference ref = Reference.of(gerbers);
        return analyze(drill, ref.bounds, ref.pads);
    }

    /**
     * Analyse a whole drill set against one Gerber reference, resolving the files together.
     * Returns one {@link Result} per input, in order.
     *
     * <p>Each file is first judged on its own. Then any file that is off the board but could not
     * recover its own offset takes the offset of the best-supported sibling that did, provided that
     * shift actually lands it on the board. This is what rescues a slot-only drill file: it has no
     * hole centres to correlate, but it was exported from the same origin as the round-hole file
     * next to it, so the one offset serves both.
     *
     * <p>The pad index is built once for the set, so analysing several drills together also costs
     * less than analysing them one at a time.
     */
    public static List<Result> analyzeAll(List<DrillDocument> drills, BoundingBox gerberBounds,
                                          List<double[]> padCenters) {
        List<Result> out = new ArrayList<>();
        if (drills == null || drills.isEmpty()) {
            return out;
        }
        boolean haveBounds = gerberBounds != null && gerberBounds.isValid();

        // Cheap pass: anything contained in the board is aligned. A set with nothing suspicious
        // never builds the pad index.
        List<DrillDocument> toExamine = new ArrayList<>();
        for (DrillDocument drill : drills) {
            boolean suspect = haveBounds && drill != null && suspect(drill, gerberBounds);
            out.add(suspect ? null : new Result(Status.ALIGNED, 0, 0, 0, holeCount(drill)));
            if (suspect) {
                toExamine.add(drill);
            }
        }
        if (toExamine.isEmpty()) {
            return out;
        }

        PadIndex index = PadIndex.of(padCenters);
        for (int i = 0; i < drills.size(); i++) {
            if (out.get(i) == null) {
                DrillDocument drill = drills.get(i);
                out.set(i, examine(drill, gerberBounds, padCenters, index, holeCount(drill)));
            }
        }

        // Set-level rescue: hand the best-supported recovered offset to the files that could not
        // find one themselves.
        Result donor = null;
        for (Result r : out) {
            if (r.isResolved() && !r.isInherited()
                && (donor == null || r.getMatchedHoles() > donor.getMatchedHoles())) {
                donor = r;
            }
        }
        if (donor == null) {
            return out;
        }
        for (int i = 0; i < out.size(); i++) {
            Result r = out.get(i);
            if (r.getStatus() != Status.MISALIGNED_UNRESOLVED) {
                continue;
            }
            DrillDocument drill = drills.get(i);
            if (fitsAfterShift(drill, donor.getOffsetX(), donor.getOffsetY(), gerberBounds)) {
                out.set(i, new Result(Status.MISALIGNED_RESOLVED,
                    donor.getOffsetX(), donor.getOffsetY(), 0, r.getTotalHoles(), true));
            }
        }
        return out;
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

    /**
     * One-call detect-and-correct for a whole drill set — {@link #analyzeAll} followed by
     * {@link #apply} on each — returning one document per input, in order, corrected where an offset
     * was recovered (its own or a sibling's) and unchanged otherwise.
     */
    public static List<DrillDocument> alignedAll(List<DrillDocument> drills,
                                                 BoundingBox gerberBounds,
                                                 List<double[]> padCenters) {
        List<Result> results = analyzeAll(drills, gerberBounds, padCenters);
        List<DrillDocument> out = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            out.add(apply(drills.get(i), results.get(i)));
        }
        return out;
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

    // ------------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------------

    /**
     * Cheap first gate: is this drill worth examining at all? A correctly placed hole is always
     * inside the board, so a drill whose bounds fit within the Gerber bounds is aligned and needs
     * no pads, no index and no correlation. Poking outside is only grounds for a closer look —
     * {@link #examine} decides — because a hole tangent to the routed edge, or a mouse bite, can
     * stick out of the union of the Gerber extents by its own radius.
     */
    private static boolean suspect(DrillDocument drill, BoundingBox gerberBounds) {
        BoundingBox b = drill.getBoundingBox();
        return b != null && b.isValid() && !boxContains(gerberBounds, b);
    }

    /**
     * Decide the status of a drill that is not wholly inside the board, by hole-on-pad support:
     * already seated → aligned; otherwise recover a translation that seats it, or report the
     * mismatch unresolved.
     */
    private static Result examine(DrillDocument drill, BoundingBox gerberBounds,
                                  List<double[]> pads, PadIndex index, int total) {
        // A drill hanging entirely off the board is unambiguously misplaced; one that merely pokes
        // outside is not, so it gets the benefit of the doubt when nothing can be recovered.
        boolean offBoard = !boxesOverlap(drill.getBoundingBox(), gerberBounds);
        Result unresolved = offBoard
            ? new Result(Status.MISALIGNED_UNRESOLVED, 0, 0, 0, total)
            : new Result(Status.ALIGNED, 0, 0, 0, total);

        List<double[]> holes = holeCenters(drill);
        if (holes.size() < MIN_SUPPORT_COUNT) {
            // Too few hole centres to judge by support — a slot-only file has none at all. Its
            // offset can still arrive from a sibling; see analyzeAll.
            return unresolved;
        }
        if (index.seatedCount(holes, 0, 0) >= SEATED_FRACTION * holes.size()) {
            return new Result(Status.ALIGNED, 0, 0, 0, total);
        }
        double[] match = correlate(holes, pads, index, offBoard);
        if (match == null) {
            return unresolved;
        }
        return new Result(Status.MISALIGNED_RESOLVED, match[0], match[1], (int) match[2], total);
    }

    /**
     * Recover the exact translation that maps these holes onto the given pad centres, as
     * {@code [offsetX, offsetY, matchedHoleCount]}, or {@code null} if no translation earns enough
     * support. Wrong pairings scatter across the vote space and cannot out-vote the true offset.
     * <p>
     * Both hole and pad coordinates are millimetres — {@code ExcellonParser} and {@code GerberParser}
     * normalise to mm at parse time — so the {@code (pad - hole)} vectors are directly comparable
     * without any unit conversion.
     *
     * @param offBoard whether the drill lies entirely clear of the board, which lowers the bar the
     *                 winning offset must clear (see {@link #OVERLAP_MIN_SUPPORT_FRACTION})
     */
    private static double[] correlate(List<double[]> holes, List<double[]> pads, PadIndex index,
                                      boolean offBoard) {
        if (pads == null || pads.isEmpty() || holes.size() < MIN_SUPPORT_COUNT) {
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

        // Refine the winning bucket to sub-grid precision: the mean exact offset over the pairs that
        // land within tolerance of it.
        double[] sum = new double[2];
        int[] matchedPairs = new int[1];
        for (double[] h : voteHoles) {
            index.forEachNear(h[0] + coarseX, h[1] + coarseY, p -> {
                sum[0] += p[0] - h[0];
                sum[1] += p[1] - h[1];
                matchedPairs[0]++;
            });
        }
        if (matchedPairs[0] == 0) {
            return null;
        }
        double offsetX = sum[0] / matchedPairs[0];
        double offsetY = sum[1] / matchedPairs[0];

        // Measure support over *every* hole, not the subsample the vote was bounded to — seating is
        // O(1) per hole through the index, so the reported count is the true one.
        int matchedHoles = index.seatedCount(holes, offsetX, offsetY);
        int minCount = offBoard ? MIN_SUPPORT_COUNT : OVERLAP_MIN_SUPPORT_COUNT;
        double minFraction = offBoard ? MIN_SUPPORT_FRACTION : OVERLAP_MIN_SUPPORT_FRACTION;
        if (matchedHoles < minCount || matchedHoles < minFraction * holes.size()) {
            return null;
        }
        // Never move a drill to a position no better than where it already is: the offset has to
        // seat strictly more holes than leaving it alone does.
        if (matchedHoles <= index.seatedCount(holes, 0, 0)) {
            return null;
        }
        return new double[]{offsetX, offsetY, matchedHoles};
    }

    /**
     * Whether shifting {@code drill} by ({@code dx}, {@code dy}) lands it on the board — the sanity
     * check before a file adopts a sibling's offset. The board bounds are allowed the drill's
     * largest tool diameter of slack, since a slot on the routed edge (a castellation, a mouse bite)
     * legitimately overhangs it.
     */
    private static boolean fitsAfterShift(DrillDocument drill, double dx, double dy,
                                          BoundingBox gerberBounds) {
        BoundingBox b = drill == null ? null : drill.getBoundingBox();
        if (b == null || !b.isValid()) {
            return false;
        }
        BoundingBox shifted = new BoundingBox(
            b.getMinX() + dx, b.getMinY() + dy, b.getMaxX() + dx, b.getMaxY() + dy);
        BoundingBox room = new BoundingBox(gerberBounds.getMinX(), gerberBounds.getMinY(),
            gerberBounds.getMaxX(), gerberBounds.getMaxY());
        room.expand(largestToolDiameter(drill));
        return boxContains(room, shifted);
    }

    private static double largestToolDiameter(DrillDocument drill) {
        double max = 0;
        for (Tool t : drill.getTools().values()) {
            max = Math.max(max, t.getDiameter());
        }
        return max;
    }

    /**
     * A spatial hash over the pad centres, so asking "does a hole sit on a pad" costs O(1) instead
     * of a scan. Cells are one match tolerance across, so every pad within tolerance of a point is
     * in one of the nine cells around it.
     */
    private static final class PadIndex {
        private final Map<Long, List<double[]>> cells;

        private PadIndex(Map<Long, List<double[]>> cells) {
            this.cells = cells;
        }

        static PadIndex of(List<double[]> pads) {
            Map<Long, List<double[]>> cells = new HashMap<>();
            if (pads != null) {
                for (double[] p : pads) {
                    cells.computeIfAbsent(key(p[0], p[1]), k -> new ArrayList<>()).add(p);
                }
            }
            return new PadIndex(cells);
        }

        private static long key(double x, double y) {
            long cx = (long) Math.floor(x / MATCH_TOL_MM);
            long cy = (long) Math.floor(y / MATCH_TOL_MM);
            return (cx << 32) | (cy & 0xffffffffL);
        }

        /** Number of holes with at least one pad within tolerance after the given offset. */
        int seatedCount(List<double[]> holes, double offsetX, double offsetY) {
            int n = 0;
            for (double[] h : holes) {
                if (seated(h[0] + offsetX, h[1] + offsetY)) {
                    n++;
                }
            }
            return n;
        }

        boolean seated(double x, double y) {
            double tolSq = MATCH_TOL_MM * MATCH_TOL_MM;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    List<double[]> bucket = cells.get(
                        key(x + dx * MATCH_TOL_MM, y + dy * MATCH_TOL_MM));
                    if (bucket == null) {
                        continue;
                    }
                    for (double[] p : bucket) {
                        double ex = p[0] - x;
                        double ey = p[1] - y;
                        if (ex * ex + ey * ey <= tolSq) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** Visit every pad within tolerance of (x, y). */
        void forEachNear(double x, double y, java.util.function.Consumer<double[]> action) {
            double tolSq = MATCH_TOL_MM * MATCH_TOL_MM;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    List<double[]> bucket = cells.get(
                        key(x + dx * MATCH_TOL_MM, y + dy * MATCH_TOL_MM));
                    if (bucket == null) {
                        continue;
                    }
                    for (double[] p : bucket) {
                        double ex = p[0] - x;
                        double ey = p[1] - y;
                        if (ex * ex + ey * ey <= tolSq) {
                            action.accept(p);
                        }
                    }
                }
            }
        }
    }

    /** The Gerber reference an analysis runs against: board bounds plus candidate pad centres. */
    private static final class Reference {
        final BoundingBox bounds = new BoundingBox();
        final List<double[]> pads = new ArrayList<>();

        static Reference of(Collection<GerberDocument> gerbers) {
            Reference ref = new Reference();
            if (gerbers != null) {
                for (GerberDocument g : gerbers) {
                    if (g == null) {
                        continue;
                    }
                    BoundingBox b = g.getBoundingBox();
                    if (b != null && b.isValid()) {
                        ref.bounds.include(b);
                    }
                    ref.pads.addAll(flashCenters(g));
                }
            }
            return ref;
        }
    }

    // ------------------------------------------------------------------------
    // Document surgery
    // ------------------------------------------------------------------------

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

    /** The centres of the drilled holes — hits only; a slot has no single centre to match. */
    private static List<double[]> holeCenters(DrillDocument drill) {
        List<double[]> holes = new ArrayList<>();
        for (DrillOperation op : drill.getOperations()) {
            if (op instanceof DrillHit) {
                DrillHit h = (DrillHit) op;
                holes.add(new double[]{h.getX(), h.getY()});
            }
        }
        return holes;
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

    /** True when {@code outer} wholly contains {@code inner}. */
    private static boolean boxContains(BoundingBox outer, BoundingBox inner) {
        return inner.getMinX() >= outer.getMinX() && inner.getMaxX() <= outer.getMaxX()
            && inner.getMinY() >= outer.getMinY() && inner.getMaxY() <= outer.getMaxY();
    }
}
