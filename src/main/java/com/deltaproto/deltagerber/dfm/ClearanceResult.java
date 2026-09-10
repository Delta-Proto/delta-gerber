package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Collections;
import java.util.List;

/**
 * The clearance check of one copper layer: the tightest gap between every pair of nets that come
 * within the cutoff of each other, tightest first.
 *
 * <p>Nothing beyond the cutoff is measured — a gap wider than a millimetre is nobody's
 * manufacturing problem — so {@link #getMinMm()} is {@code null} when no two nets come that close,
 * which means "at least the cutoff", not "unknown".
 */
@Beta("not yet validated against an external DFM tool")
public final class ClearanceResult {

    private final String fileName;
    private final double cutoffMm;
    private final int netCount;
    private final List<Clearance> clearances;
    private final List<String> warnings;

    ClearanceResult(String fileName, double cutoffMm, int netCount, List<Clearance> clearances,
                    List<String> warnings) {
        this.fileName = fileName;
        this.cutoffMm = cutoffMm;
        this.netCount = netCount;
        this.clearances = Collections.unmodifiableList(clearances);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public String getFileName() {
        return fileName;
    }

    /** Gaps wider than this were not measured. */
    public double getCutoffMm() {
        return cutoffMm;
    }

    /** How many separate pieces of copper the layer resolved into. */
    public int getNetCount() {
        return netCount;
    }

    /** One entry per pair of nets closer than the cutoff, tightest first. */
    public List<Clearance> getClearances() {
        return clearances;
    }

    /** The tightest gap, or null when no two nets come within the cutoff. */
    public Double getMinMm() {
        return clearances.isEmpty() ? null : clearances.get(0).distanceMm();
    }

    /** The tightest gap in full, or null. */
    public Clearance getMin() {
        return clearances.isEmpty() ? null : clearances.get(0);
    }

    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return "ClearanceResult[" + fileName + ": min=" + getMinMm() + " mm over " + netCount + " nets]";
    }
}
