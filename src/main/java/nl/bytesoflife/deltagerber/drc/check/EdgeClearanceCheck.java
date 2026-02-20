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

public class EdgeClearanceCheck implements DrcCheck {

    private final GerberGeometryConverter converter = new GerberGeometryConverter();

    @Override
    public ConstraintType getSupportedType() {
        return ConstraintType.EDGE_CLEARANCE;
    }

    @Override
    public List<DrcViolation> check(DrcRule rule, DrcConstraint constraint, DrcBoardInput board) {
        List<DrcViolation> violations = new ArrayList<>();
        if (constraint.getMinMm() == null) return violations;

        double minClearance = constraint.getMinMm();

        // Get Edge.Cuts layer
        GerberDocument edgeDoc = board.getLayer("Edge.Cuts");
        if (edgeDoc == null) return violations;

        double edgeUnitFactor = edgeDoc.getUnit().toMm(1.0);
        List<Geometry> edgeGeometries = converter.convert(edgeDoc, edgeUnitFactor);
        if (edgeGeometries.isEmpty()) return violations;

        // Build spatial index for edge geometries
        SpatialIndex edgeIndex = new SpatialIndex();
        edgeIndex.insertAll(edgeGeometries);

        // Check each copper layer
        for (Map.Entry<String, GerberDocument> entry : board.getLayers().entrySet()) {
            String layerName = entry.getKey();
            GerberDocument doc = entry.getValue();

            if (!DrcBoardInput.isCopperLayer(layerName)) continue;
            if (rule.getLayer() != null && !rule.getLayer().matches(layerName)) continue;

            double unitFactor = doc.getUnit().toMm(1.0);
            List<Geometry> copperGeometries = converter.convert(doc, unitFactor);

            for (Geometry copper : copperGeometries) {
                List<Geometry> nearEdges = edgeIndex.queryNeighbors(copper, minClearance);

                for (Geometry edge : nearEdges) {
                    double distance = copper.distance(edge);

                    if (distance < minClearance && distance >= 0) {
                        Coordinate[] closestPoints = org.locationtech.jts.operation.distance.DistanceOp
                                .nearestPoints(copper, edge);
                        double vx = (closestPoints[0].x + closestPoints[1].x) / 2;
                        double vy = (closestPoints[0].y + closestPoints[1].y) / 2;

                        violations.add(new DrcViolation(
                                rule, constraint, rule.getSeverity(),
                                "Edge clearance violation",
                                distance, minClearance,
                                vx, vy, layerName));
                    }
                }
            }
        }

        return violations;
    }
}
