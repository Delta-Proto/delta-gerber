package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Collections;
import java.util.List;

/**
 * Drill-to-copper clearance on one copper layer: for each hole with foreign copper within the
 * cutoff, its closest approach — closest first. The drill wanders and the plating grows the hole's
 * copper outward, so fabricators ask for 0.2–0.25 mm (8–10 mil) between a hole's wall and copper it
 * must not reach, and more on inner layers where the registration is looser.
 */
@Beta("validated against HQDFM on one board")
public final class DrillClearanceResult {

    private final String fileName;
    private final double cutoffMm;
    private final List<DrillClearance> clearances;

    DrillClearanceResult(String fileName, double cutoffMm, List<DrillClearance> clearances) {
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

    /** One entry per hole, closest first. */
    public List<DrillClearance> getClearances() {
        return clearances;
    }

    /** The tightest gap in mm, or null when no hole has foreign copper within the cutoff. */
    public Double getMinMm() {
        return clearances.isEmpty() ? null : clearances.get(0).distanceMm();
    }

    public DrillClearance getMin() {
        return clearances.isEmpty() ? null : clearances.get(0);
    }

    @Override
    public String toString() {
        return "DrillClearanceResult[" + fileName + ": min=" + getMinMm() + " mm]";
    }
}
