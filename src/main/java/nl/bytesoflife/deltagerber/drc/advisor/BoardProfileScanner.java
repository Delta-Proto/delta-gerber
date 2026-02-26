package nl.bytesoflife.deltagerber.drc.advisor;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.geometry.GerberGeometryConverter;
import nl.bytesoflife.deltagerber.drc.geometry.SpatialIndex;
import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.drill.DrillOperation;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.deltagerber.model.gerber.aperture.RectangleAperture;
import nl.bytesoflife.deltagerber.model.gerber.operation.Draw;
import nl.bytesoflife.deltagerber.model.gerber.operation.GraphicsObject;
import org.locationtech.jts.geom.Geometry;

import java.util.*;

/**
 * Scans a {@link DrcBoardInput} to produce a {@link BoardProfile} with statistical
 * information about trace widths, hole sizes, and optionally clearances.
 */
public class BoardProfileScanner {

    private boolean clearanceAnalysis;
    private ManufacturerProfile tierProfile;

    /**
     * Enable or disable expensive clearance analysis (JTS-based).
     */
    public BoardProfileScanner withClearanceAnalysis(boolean enabled) {
        this.clearanceAnalysis = enabled;
        return this;
    }

    /**
     * Set tier boundaries for histogram bucketing. If not set, default buckets are used.
     */
    public BoardProfileScanner withTierBoundaries(ManufacturerProfile profile) {
        this.tierProfile = profile;
        return this;
    }

    /**
     * Scan the board and produce a statistical profile.
     */
    public BoardProfile scan(DrcBoardInput board) {
        BoardProfile profile = new BoardProfile();

        // Count copper layers
        int copperLayers = 0;
        for (String layerName : board.getLayers().keySet()) {
            if (DrcBoardInput.isCopperLayer(layerName)) {
                copperLayers++;
            }
        }
        profile.setCopperLayerCount(copperLayers);

        scanTraceWidths(board, profile);
        scanHoleSizes(board, profile);

        if (clearanceAnalysis) {
            scanClearances(board, profile);
        }

        return profile;
    }

    private void scanTraceWidths(DrcBoardInput board, BoardProfile profile) {
        double globalMin = Double.MAX_VALUE;
        String globalMinLayer = null;
        int totalTraces = 0;
        List<Double> allWidths = new ArrayList<>();

        for (Map.Entry<String, GerberDocument> entry : board.getLayers().entrySet()) {
            String layerName = entry.getKey();
            GerberDocument doc = entry.getValue();

            if (!DrcBoardInput.isCopperLayer(layerName)) continue;

            double unitFactor = doc.getUnit().toMm(1.0);

            for (GraphicsObject obj : doc.getObjects()) {
                if (!(obj instanceof Draw draw)) continue;

                double widthMm = getTrackWidthMm(draw, unitFactor);
                if (widthMm <= 0) continue;

                totalTraces++;
                allWidths.add(widthMm);

                if (widthMm < globalMin) {
                    globalMin = widthMm;
                    globalMinLayer = layerName;
                }
            }
        }

        profile.setTraceCount(totalTraces);
        if (totalTraces > 0) {
            profile.setMinTraceWidthMm(globalMin);
            profile.setMinTraceWidthLayer(globalMinLayer);
        }
        profile.setTraceWidthDistribution(buildDistribution(allWidths, getTraceBoundaries()));
    }

    private void scanHoleSizes(DrcBoardInput board, BoardProfile profile) {
        double globalMin = Double.MAX_VALUE;
        int totalHoles = 0;
        List<Double> allDiameters = new ArrayList<>();

        for (DrillDocument drill : board.getDrillFiles()) {
            double unitFactor = drill.getUnit().toMm(1.0);

            for (DrillOperation op : drill.getOperations()) {
                double diameterMm = op.getTool().getDiameter() * unitFactor;
                totalHoles++;
                allDiameters.add(diameterMm);

                if (diameterMm < globalMin) {
                    globalMin = diameterMm;
                }
            }
        }

        profile.setHoleCount(totalHoles);
        if (totalHoles > 0) {
            profile.setMinHoleSizeMm(globalMin);
        }
        profile.setHoleSizeDistribution(buildDistribution(allDiameters, getHoleBoundaries()));
    }

    private void scanClearances(DrcBoardInput board, BoardProfile profile) {
        GerberGeometryConverter converter = new GerberGeometryConverter();
        double globalMin = Double.MAX_VALUE;
        String globalMinLayer = null;
        int totalPairs = 0;
        List<Double> allClearances = new ArrayList<>();

        for (Map.Entry<String, GerberDocument> entry : board.getLayers().entrySet()) {
            String layerName = entry.getKey();
            GerberDocument doc = entry.getValue();

            if (!DrcBoardInput.isCopperLayer(layerName)) continue;

            double unitFactor = doc.getUnit().toMm(1.0);
            List<Geometry> geometries = converter.convert(doc, unitFactor);

            if (geometries.size() < 2) continue;

            SpatialIndex index = new SpatialIndex();
            index.insertAll(geometries);

            // Use a generous search distance for clearance analysis
            double searchDistance = getMaxClearanceBoundary();
            Set<Long> checkedPairs = new HashSet<>();

            for (int i = 0; i < geometries.size(); i++) {
                Geometry geom = geometries.get(i);
                List<Geometry> neighbors = index.queryNeighbors(geom, searchDistance);

                for (Geometry neighbor : neighbors) {
                    if (geom == neighbor) continue;

                    int idx1 = System.identityHashCode(geom);
                    int idx2 = System.identityHashCode(neighbor);
                    long pairKey = Math.min(idx1, idx2) * 1000000L + Math.max(idx1, idx2);
                    if (!checkedPairs.add(pairKey)) continue;

                    double distance = geom.distance(neighbor);
                    if (distance > 0 && distance <= searchDistance) {
                        totalPairs++;
                        allClearances.add(distance);

                        if (distance < globalMin) {
                            globalMin = distance;
                            globalMinLayer = layerName;
                        }
                    }
                }
            }
        }

        profile.setClearancePairCount(totalPairs);
        if (totalPairs > 0) {
            profile.setMinClearanceMm(globalMin);
            profile.setMinClearanceLayer(globalMinLayer);
        }
        profile.setClearanceDistribution(buildDistribution(allClearances, getTraceBoundaries()));
    }

    private double getTrackWidthMm(Draw draw, double unitFactor) {
        if (draw.getAperture() instanceof CircleAperture circle) {
            return circle.getDiameter() * unitFactor;
        } else if (draw.getAperture() instanceof RectangleAperture rect) {
            return Math.min(rect.getWidth(), rect.getHeight()) * unitFactor;
        }
        return 0;
    }

    /**
     * Get tier-based trace/clearance boundaries, or defaults.
     */
    private List<Double> getTraceBoundaries() {
        if (tierProfile != null) {
            List<Double> boundaries = new ArrayList<>();
            for (ManufacturingTier tier : tierProfile.getTiers()) {
                if (tier.minTraceWidthMm() != null) {
                    boundaries.add(tier.minTraceWidthMm());
                }
            }
            if (!boundaries.isEmpty()) return boundaries;
        }
        // Default boundaries: 0.1mm (4mil), 0.127mm (5mil), 0.15mm (6mil), 0.2mm (8mil)
        return List.of(0.1016, 0.127, 0.1524, 0.2032);
    }

    private List<Double> getHoleBoundaries() {
        if (tierProfile != null) {
            List<Double> boundaries = new ArrayList<>();
            for (ManufacturingTier tier : tierProfile.getTiers()) {
                if (tier.minHoleSizeMm() != null) {
                    boundaries.add(tier.minHoleSizeMm());
                }
            }
            if (!boundaries.isEmpty()) return boundaries;
        }
        // Default: 0.15mm, 0.2mm, 0.3mm
        return List.of(0.15, 0.2, 0.3);
    }

    private double getMaxClearanceBoundary() {
        List<Double> bounds = getTraceBoundaries();
        double max = 0.5; // default 0.5mm search distance
        for (double b : bounds) {
            if (b > max) max = b;
        }
        return max;
    }

    /**
     * Build histogram buckets from measurements and boundary thresholds.
     * Boundaries are sorted, and buckets are created: [0, b0), [b0, b1), ..., [bN, inf)
     */
    static List<FeatureBucket> buildDistribution(List<Double> measurements, List<Double> boundaries) {
        if (measurements.isEmpty()) {
            return List.of();
        }

        List<Double> sorted = new ArrayList<>(new TreeSet<>(boundaries));
        int[] counts = new int[sorted.size() + 1];

        for (double value : measurements) {
            int bucket = sorted.size(); // default: last bucket (>= highest boundary)
            for (int i = 0; i < sorted.size(); i++) {
                if (value < sorted.get(i)) {
                    bucket = i;
                    break;
                }
            }
            counts[bucket]++;
        }

        List<FeatureBucket> result = new ArrayList<>();
        for (int i = 0; i <= sorted.size(); i++) {
            if (counts[i] == 0) continue;

            double lower = (i == 0) ? 0 : sorted.get(i - 1);
            double upper = (i == sorted.size()) ? Double.MAX_VALUE : sorted.get(i);
            String label;
            if (i == 0) {
                label = String.format("< %.4fmm", sorted.get(0));
            } else if (i == sorted.size()) {
                label = String.format(">= %.4fmm", sorted.get(sorted.size() - 1));
            } else {
                label = String.format("%.4fmm - %.4fmm", sorted.get(i - 1), sorted.get(i));
            }
            result.add(new FeatureBucket(lower, upper, counts[i], label));
        }

        return result;
    }
}
