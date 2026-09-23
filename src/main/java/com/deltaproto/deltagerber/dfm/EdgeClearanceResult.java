package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Collections;
import java.util.List;

/**
 * Copper-to-board-edge clearance on one copper layer: for every object that comes within the cutoff
 * of the edge, its closest approach — closest first. A fabricator asks for 0.2–0.3 mm to a routed
 * edge and more to a scored one; copper nearer than that is exposed or torn when the board is cut
 * out.
 *
 * <p>Gaps wider than the cutoff are not measured, so {@link #getMinMm()} is null on a layer whose
 * copper stays well inside the board.
 */
@Beta("validated against HQDFM on one board")
public final class EdgeClearanceResult {

    private final String fileName;
    private final double cutoffMm;
    private final List<EdgeClearance> clearances;

    EdgeClearanceResult(String fileName, double cutoffMm, List<EdgeClearance> clearances) {
        this.fileName = fileName;
        this.cutoffMm = cutoffMm;
        this.clearances = Collections.unmodifiableList(clearances);
    }

    public String getFileName() {
        return fileName;
    }

    /** Gaps wider than this were not measured. */
    public double getCutoffMm() {
        return cutoffMm;
    }

    /** One entry per object within the cutoff of the edge, closest first. */
    public List<EdgeClearance> getClearances() {
        return clearances;
    }

    /** The copper nearest the edge in mm, or null when none is within the cutoff. */
    public Double getMinMm() {
        return clearances.isEmpty() ? null : clearances.get(0).distanceMm();
    }

    public EdgeClearance getMin() {
        return clearances.isEmpty() ? null : clearances.get(0);
    }

    @Override
    public String toString() {
        return "EdgeClearanceResult[" + fileName + ": min=" + getMinMm() + " mm]";
    }
}
