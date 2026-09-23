package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Collections;
import java.util.List;

/**
 * Hole-to-hole spacing over a whole drill program: one entry per hole whose nearest neighbour is
 * within the cutoff, tightest first. A fabricator asks for 0.2–0.3 mm of laminate between holes
 * (more between holes of different nets, which HQDFM checks at 16–18 mil); less and the web between
 * them cracks when the second hole is drilled.
 */
@Beta("validated against HQDFM on one board")
public final class HoleSpacingResult {

    private final double cutoffMm;
    private final List<HoleSpacing> spacings;
    private final List<HoleSpacing> overlapping;

    HoleSpacingResult(double cutoffMm, List<HoleSpacing> spacings, List<HoleSpacing> overlapping) {
        this.cutoffMm = cutoffMm;
        this.spacings = Collections.unmodifiableList(spacings);
        this.overlapping = Collections.unmodifiableList(overlapping);
    }

    /**
     * Holes that touch or overlap, deepest first: a slot drilled as a row of holes, or the same
     * hole hit twice. Not spacings, and not in {@link #getMinMm()}.
     */
    public List<HoleSpacing> getOverlapping() {
        return overlapping;
    }

    /** Gaps wider than this were not measured. */
    public double getCutoffMm() {
        return cutoffMm;
    }

    /** Each pair once, tightest first. */
    public List<HoleSpacing> getSpacings() {
        return spacings;
    }

    /** The tightest gap in mm, or null when no two holes come within the cutoff. */
    public Double getMinMm() {
        return spacings.isEmpty() ? null : spacings.get(0).distanceMm();
    }

    public HoleSpacing getMin() {
        return spacings.isEmpty() ? null : spacings.get(0);
    }

    @Override
    public String toString() {
        return "HoleSpacingResult[min=" + getMinMm() + " mm, " + spacings.size() + " pair(s)]";
    }
}
