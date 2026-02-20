package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.DrcViolation;
import nl.bytesoflife.deltagerber.drc.model.ConstraintType;
import nl.bytesoflife.deltagerber.drc.model.DrcConstraint;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.deltagerber.model.gerber.aperture.RectangleAperture;
import nl.bytesoflife.deltagerber.model.gerber.operation.Draw;
import nl.bytesoflife.deltagerber.model.gerber.operation.GraphicsObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TrackWidthCheck implements DrcCheck {

    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();

    @Override
    public ConstraintType getSupportedType() {
        return ConstraintType.TRACK_WIDTH;
    }

    @Override
    public List<DrcViolation> check(DrcRule rule, DrcConstraint constraint, DrcBoardInput board) {
        List<DrcViolation> violations = new ArrayList<>();

        for (Map.Entry<String, GerberDocument> entry : board.getLayers().entrySet()) {
            String layerName = entry.getKey();
            GerberDocument doc = entry.getValue();

            if (!DrcBoardInput.isCopperLayer(layerName)) continue;
            if (rule.getLayer() != null && !rule.getLayer().matches(layerName)) continue;

            double unitFactor = doc.getUnit().toMm(1.0);

            for (GraphicsObject obj : doc.getObjects()) {
                if (!(obj instanceof Draw draw)) continue;

                ConditionEvaluator.Result condResult =
                        conditionEvaluator.evaluateForObject(rule.getConditionExpression(), obj);
                if (condResult != ConditionEvaluator.Result.APPLICABLE) continue;

                double widthMm = getTrackWidthMm(draw, unitFactor);
                if (widthMm <= 0) continue;

                if (constraint.getMinMm() != null && widthMm < constraint.getMinMm()) {
                    violations.add(new DrcViolation(
                            rule, constraint, rule.getSeverity(),
                            "Track width too small",
                            widthMm, constraint.getMinMm(),
                            unitFactor * (draw.getStartX() + draw.getEndX()) / 2,
                            unitFactor * (draw.getStartY() + draw.getEndY()) / 2,
                            layerName));
                }

                if (constraint.getMaxMm() != null && widthMm > constraint.getMaxMm()) {
                    violations.add(new DrcViolation(
                            rule, constraint, rule.getSeverity(),
                            "Track width too large",
                            widthMm, constraint.getMaxMm(),
                            unitFactor * (draw.getStartX() + draw.getEndX()) / 2,
                            unitFactor * (draw.getStartY() + draw.getEndY()) / 2,
                            layerName));
                }
            }
        }

        return violations;
    }

    private double getTrackWidthMm(Draw draw, double unitFactor) {
        if (draw.getAperture() instanceof CircleAperture circle) {
            return circle.getDiameter() * unitFactor;
        } else if (draw.getAperture() instanceof RectangleAperture rect) {
            return Math.min(rect.getWidth(), rect.getHeight()) * unitFactor;
        }
        return 0;
    }
}
