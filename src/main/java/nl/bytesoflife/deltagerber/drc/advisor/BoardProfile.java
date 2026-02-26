package nl.bytesoflife.deltagerber.drc.advisor;

import java.util.List;

/**
 * Statistical profile of a board's manufacturing-relevant features.
 */
public class BoardProfile {

    // Trace width stats
    private double minTraceWidthMm;
    private String minTraceWidthLayer;
    private int traceCount;
    private List<FeatureBucket> traceWidthDistribution;

    // Clearance stats (nullable — expensive, opt-in)
    private Double minClearanceMm;
    private String minClearanceLayer;
    private int clearancePairCount;
    private List<FeatureBucket> clearanceDistribution;

    // Hole stats
    private double minHoleSizeMm;
    private int holeCount;
    private List<FeatureBucket> holeSizeDistribution;

    // Board info
    private int copperLayerCount;

    public double getMinTraceWidthMm() {
        return minTraceWidthMm;
    }

    public void setMinTraceWidthMm(double minTraceWidthMm) {
        this.minTraceWidthMm = minTraceWidthMm;
    }

    public String getMinTraceWidthLayer() {
        return minTraceWidthLayer;
    }

    public void setMinTraceWidthLayer(String minTraceWidthLayer) {
        this.minTraceWidthLayer = minTraceWidthLayer;
    }

    public int getTraceCount() {
        return traceCount;
    }

    public void setTraceCount(int traceCount) {
        this.traceCount = traceCount;
    }

    public List<FeatureBucket> getTraceWidthDistribution() {
        return traceWidthDistribution;
    }

    public void setTraceWidthDistribution(List<FeatureBucket> traceWidthDistribution) {
        this.traceWidthDistribution = traceWidthDistribution;
    }

    public Double getMinClearanceMm() {
        return minClearanceMm;
    }

    public void setMinClearanceMm(Double minClearanceMm) {
        this.minClearanceMm = minClearanceMm;
    }

    public String getMinClearanceLayer() {
        return minClearanceLayer;
    }

    public void setMinClearanceLayer(String minClearanceLayer) {
        this.minClearanceLayer = minClearanceLayer;
    }

    public int getClearancePairCount() {
        return clearancePairCount;
    }

    public void setClearancePairCount(int clearancePairCount) {
        this.clearancePairCount = clearancePairCount;
    }

    public List<FeatureBucket> getClearanceDistribution() {
        return clearanceDistribution;
    }

    public void setClearanceDistribution(List<FeatureBucket> clearanceDistribution) {
        this.clearanceDistribution = clearanceDistribution;
    }

    public double getMinHoleSizeMm() {
        return minHoleSizeMm;
    }

    public void setMinHoleSizeMm(double minHoleSizeMm) {
        this.minHoleSizeMm = minHoleSizeMm;
    }

    public int getHoleCount() {
        return holeCount;
    }

    public void setHoleCount(int holeCount) {
        this.holeCount = holeCount;
    }

    public List<FeatureBucket> getHoleSizeDistribution() {
        return holeSizeDistribution;
    }

    public void setHoleSizeDistribution(List<FeatureBucket> holeSizeDistribution) {
        this.holeSizeDistribution = holeSizeDistribution;
    }

    public int getCopperLayerCount() {
        return copperLayerCount;
    }

    public void setCopperLayerCount(int copperLayerCount) {
        this.copperLayerCount = copperLayerCount;
    }
}
