package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.DrcViolation;
import nl.bytesoflife.deltagerber.drc.model.ConstraintType;
import nl.bytesoflife.deltagerber.drc.model.DrcConstraint;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.drill.DrillHit;
import nl.bytesoflife.deltagerber.model.drill.DrillOperation;
import nl.bytesoflife.deltagerber.model.drill.DrillSlot;

import java.util.ArrayList;
import java.util.List;

public class HoleSizeCheck implements DrcCheck {

    @Override
    public ConstraintType getSupportedType() {
        return ConstraintType.HOLE_SIZE;
    }

    @Override
    public List<DrcViolation> check(DrcRule rule, DrcConstraint constraint, DrcBoardInput board) {
        List<DrcViolation> violations = new ArrayList<>();

        for (DrillDocument drill : board.getDrillFiles()) {
            double unitFactor = drill.getUnit().toMm(1.0);

            for (DrillOperation op : drill.getOperations()) {
                double diameterMm = op.getTool().getDiameter() * unitFactor;
                double x, y;

                if (op instanceof DrillHit hit) {
                    x = hit.getX() * unitFactor;
                    y = hit.getY() * unitFactor;
                } else if (op instanceof DrillSlot slot) {
                    x = (slot.getStartX() + slot.getEndX()) / 2 * unitFactor;
                    y = (slot.getStartY() + slot.getEndY()) / 2 * unitFactor;
                } else {
                    continue;
                }

                if (constraint.getMinMm() != null && diameterMm < constraint.getMinMm()) {
                    violations.add(new DrcViolation(
                            rule, constraint, rule.getSeverity(),
                            "Hole size too small",
                            diameterMm, constraint.getMinMm(),
                            x, y, null));
                }

                if (constraint.getMaxMm() != null && diameterMm > constraint.getMaxMm()) {
                    violations.add(new DrcViolation(
                            rule, constraint, rule.getSeverity(),
                            "Hole size too large",
                            diameterMm, constraint.getMaxMm(),
                            x, y, null));
                }
            }
        }

        return violations;
    }
}
