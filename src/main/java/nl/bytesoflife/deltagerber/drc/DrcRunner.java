package nl.bytesoflife.deltagerber.drc;

import nl.bytesoflife.deltagerber.drc.check.ConditionEvaluator;
import nl.bytesoflife.deltagerber.drc.check.DrcCheck;
import nl.bytesoflife.deltagerber.drc.model.ConstraintType;
import nl.bytesoflife.deltagerber.drc.model.DrcConstraint;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.drc.model.DrcRuleSet;
import nl.bytesoflife.deltagerber.drc.model.Severity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DrcRunner {

    private final Map<ConstraintType, DrcCheck> checks = new HashMap<>();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator();

    public DrcRunner registerCheck(DrcCheck check) {
        checks.put(check.getSupportedType(), check);
        return this;
    }

    public DrcReport run(DrcRuleSet ruleSet, DrcBoardInput board) {
        DrcReport report = new DrcReport();

        for (DrcRule rule : ruleSet.getRules()) {
            if (rule.getSeverity() == Severity.IGNORE) continue;

            // Check if the rule's condition is supported
            ConditionEvaluator.Result condResult =
                    conditionEvaluator.evaluate(rule.getConditionExpression());
            if (condResult == ConditionEvaluator.Result.UNSUPPORTED) {
                report.addSkippedRule(rule);
                continue;
            }

            for (DrcConstraint constraint : rule.getConstraints()) {
                DrcCheck check = checks.get(constraint.getType());
                if (check == null) {
                    // No check registered for this constraint type
                    continue;
                }

                List<DrcViolation> violations = check.check(rule, constraint, board);
                for (DrcViolation violation : violations) {
                    report.addViolation(violation);
                }
            }
        }

        return report;
    }
}
