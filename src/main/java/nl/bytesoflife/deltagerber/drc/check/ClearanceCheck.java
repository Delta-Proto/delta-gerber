package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.DrcViolation;
import nl.bytesoflife.deltagerber.drc.geometry.GerberGeometryConverter;
import nl.bytesoflife.deltagerber.drc.geometry.SpatialIndex;
import nl.bytesoflife.deltagerber.drc.model.ConstraintType;
import nl.bytesoflife.deltagerber.drc.model.DrcConstraint;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ClearanceCheck implements DrcCheck {

    private final GerberGeometryConverter converter = new GerberGeometryConverter();

    @Override
    public ConstraintType getSupportedType() {
        return ConstraintType.CLEARANCE;
    }

    @Override
    public List<DrcViolation> check(DrcRule rule, DrcConstraint constraint, DrcBoardInput board) {
        List<DrcViolation> violations = new ArrayList<>();
        if (constraint.getMinMm() == null) return violations;

        double minClearance = constraint.getMinMm();

        for (Map.Entry<String, GerberDocument> entry : board.getLayers().entrySet()) {
            String layerName = entry.getKey();
            GerberDocument doc = entry.getValue();

            if (!DrcBoardInput.isCopperLayer(layerName)) continue;
            if (rule.getLayer() != null && !rule.getLayer().matches(layerName)) continue;

            double unitFactor = doc.getUnit().toMm(1.0);
            List<Geometry> geometries = converter.convert(doc, unitFactor);

            if (geometries.size() < 2) continue;

            // Use spatial index for efficient pair finding
            SpatialIndex index = new SpatialIndex();
            index.insertAll(geometries);

            // Track checked pairs to avoid duplicates
            java.util.Set<Long> checkedPairs = new java.util.HashSet<>();

            for (int i = 0; i < geometries.size(); i++) {
                Geometry geom = geometries.get(i);
                List<Geometry> neighbors = index.queryNeighbors(geom, minClearance);

                for (Geometry neighbor : neighbors) {
                    if (geom == neighbor) continue;

                    // Create pair key to avoid duplicates
                    int idx1 = System.identityHashCode(geom);
                    int idx2 = System.identityHashCode(neighbor);
                    long pairKey = Math.min(idx1, idx2) * 1000000L + Math.max(idx1, idx2);
                    if (!checkedPairs.add(pairKey)) continue;

                    double distance = geom.distance(neighbor);
                    if (distance < minClearance && distance > 0) {
                        // Find violation location (midpoint of closest points)
                        Coordinate[] closestPoints = org.locationtech.jts.operation.distance.DistanceOp
                                .nearestPoints(geom, neighbor);
                        double vx = (closestPoints[0].x + closestPoints[1].x) / 2;
                        double vy = (closestPoints[0].y + closestPoints[1].y) / 2;

                        violations.add(new DrcViolation(
                                rule, constraint, rule.getSeverity(),
                                "Clearance violation",
                                distance, minClearance,
                                vx, vy, layerName));
                    }
                }
            }
        }

        return violations;
    }
}
