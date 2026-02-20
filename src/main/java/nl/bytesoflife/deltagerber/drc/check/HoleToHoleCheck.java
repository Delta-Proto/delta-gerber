package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.DrcViolation;
import nl.bytesoflife.deltagerber.drc.model.ConstraintType;
import nl.bytesoflife.deltagerber.drc.model.DrcConstraint;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.drill.DrillHit;
import nl.bytesoflife.deltagerber.model.drill.DrillOperation;

import java.util.ArrayList;
import java.util.List;

public class HoleToHoleCheck implements DrcCheck {

    @Override
    public ConstraintType getSupportedType() {
        return ConstraintType.HOLE_TO_HOLE;
    }

    @Override
    public List<DrcViolation> check(DrcRule rule, DrcConstraint constraint, DrcBoardInput board) {
        List<DrcViolation> violations = new ArrayList<>();
        if (constraint.getMinMm() == null) return violations;

        double minSpacing = constraint.getMinMm();

        // Collect all drill hits across all drill files
        List<DrillHitInfo> allHits = new ArrayList<>();
        for (DrillDocument drill : board.getDrillFiles()) {
            double uf = drill.getUnit().toMm(1.0);
            for (DrillOperation op : drill.getOperations()) {
                if (op instanceof DrillHit hit) {
                    allHits.add(new DrillHitInfo(
                            hit.getX() * uf, hit.getY() * uf,
                            hit.getTool().getDiameter() * uf / 2));
                }
            }
        }

        // Check all pairs (with spatial filtering)
        for (int i = 0; i < allHits.size(); i++) {
            DrillHitInfo h1 = allHits.get(i);
            for (int j = i + 1; j < allHits.size(); j++) {
                DrillHitInfo h2 = allHits.get(j);

                double centerDist = Math.sqrt(
                        (h1.x - h2.x) * (h1.x - h2.x) + (h1.y - h2.y) * (h1.y - h2.y));
                double edgeDist = centerDist - h1.radius - h2.radius;

                if (edgeDist < minSpacing && edgeDist >= 0) {
                    double mx = (h1.x + h2.x) / 2;
                    double my = (h1.y + h2.y) / 2;

                    violations.add(new DrcViolation(
                            rule, constraint, rule.getSeverity(),
                            "Hole to hole spacing too small",
                            edgeDist, minSpacing,
                            mx, my, null));
                }
            }
        }

        return violations;
    }

    private record DrillHitInfo(double x, double y, double radius) {}
}
