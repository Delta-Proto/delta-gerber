package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.DrcViolation;
import nl.bytesoflife.deltagerber.drc.model.ConstraintType;
import nl.bytesoflife.deltagerber.drc.model.DrcConstraint;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.drill.DrillHit;
import nl.bytesoflife.deltagerber.model.drill.DrillOperation;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.deltagerber.model.gerber.aperture.RectangleAperture;
import nl.bytesoflife.deltagerber.model.gerber.operation.Flash;
import nl.bytesoflife.deltagerber.model.gerber.operation.GraphicsObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnnularWidthCheck implements DrcCheck {

    private static final double POSITION_TOLERANCE_MM = 0.01;

    @Override
    public ConstraintType getSupportedType() {
        return ConstraintType.ANNULAR_WIDTH;
    }

    @Override
    public List<DrcViolation> check(DrcRule rule, DrcConstraint constraint, DrcBoardInput board) {
        List<DrcViolation> violations = new ArrayList<>();

        for (DrillDocument drill : board.getDrillFiles()) {
            double drillUnitFactor = drill.getUnit().toMm(1.0);

            for (DrillOperation op : drill.getOperations()) {
                if (!(op instanceof DrillHit hit)) continue;

                double drillDiameterMm = hit.getTool().getDiameter() * drillUnitFactor;
                double hitXMm = hit.getX() * drillUnitFactor;
                double hitYMm = hit.getY() * drillUnitFactor;

                // Find matching flash on copper layers
                for (Map.Entry<String, GerberDocument> entry : board.getLayers().entrySet()) {
                    String layerName = entry.getKey();
                    if (!DrcBoardInput.isCopperLayer(layerName)) continue;
                    if (rule.getLayer() != null && !rule.getLayer().matches(layerName)) continue;

                    GerberDocument doc = entry.getValue();
                    double gerberUnitFactor = doc.getUnit().toMm(1.0);

                    for (GraphicsObject obj : doc.getObjects()) {
                        if (!(obj instanceof Flash flash)) continue;

                        double flashXMm = flash.getX() * gerberUnitFactor;
                        double flashYMm = flash.getY() * gerberUnitFactor;

                        // Check if positions match
                        if (Math.abs(flashXMm - hitXMm) > POSITION_TOLERANCE_MM ||
                            Math.abs(flashYMm - hitYMm) > POSITION_TOLERANCE_MM) {
                            continue;
                        }

                        double padDiameterMm = getPadDiameterMm(flash, gerberUnitFactor);
                        if (padDiameterMm <= 0) continue;

                        double annularWidth = (padDiameterMm - drillDiameterMm) / 2;

                        if (constraint.getMinMm() != null && annularWidth < constraint.getMinMm()) {
                            violations.add(new DrcViolation(
                                    rule, constraint, rule.getSeverity(),
                                    "Annular width too small",
                                    annularWidth, constraint.getMinMm(),
                                    hitXMm, hitYMm, layerName));
                        }
                    }
                }
            }
        }

        return violations;
    }

    private double getPadDiameterMm(Flash flash, double unitFactor) {
        if (flash.getAperture() instanceof CircleAperture circle) {
            return circle.getDiameter() * unitFactor;
        } else if (flash.getAperture() instanceof RectangleAperture rect) {
            // Use minimum dimension as effective diameter
            return Math.min(rect.getWidth(), rect.getHeight()) * unitFactor;
        }
        return 0;
    }
}
