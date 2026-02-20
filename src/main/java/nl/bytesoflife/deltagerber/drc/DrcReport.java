package nl.bytesoflife.deltagerber.drc;

import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.drc.model.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DrcReport {

    private final List<DrcViolation> violations = new ArrayList<>();
    private final List<DrcRule> skippedRules = new ArrayList<>();

    public void addViolation(DrcViolation violation) {
        violations.add(violation);
    }

    public void addSkippedRule(DrcRule rule) {
        skippedRules.add(rule);
    }

    public List<DrcViolation> getViolations() {
        return Collections.unmodifiableList(violations);
    }

    public List<DrcViolation> getErrors() {
        return violations.stream()
                .filter(v -> v.getSeverity() == Severity.ERROR)
                .toList();
    }

    public List<DrcViolation> getWarnings() {
        return violations.stream()
                .filter(v -> v.getSeverity() == Severity.WARNING)
                .toList();
    }

    public boolean hasErrors() {
        return violations.stream().anyMatch(v -> v.getSeverity() == Severity.ERROR);
    }

    public List<DrcRule> getSkippedRules() {
        return Collections.unmodifiableList(skippedRules);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DRC Report:\n");
        sb.append("  Violations: ").append(violations.size())
          .append(" (").append(getErrors().size()).append(" errors, ")
          .append(getWarnings().size()).append(" warnings)\n");
        sb.append("  Skipped rules: ").append(skippedRules.size()).append("\n");
        for (DrcViolation v : violations) {
            sb.append("  - ").append(v).append("\n");
        }
        if (!skippedRules.isEmpty()) {
            sb.append("  Skipped:\n");
            for (DrcRule r : skippedRules) {
                sb.append("  - ").append(r.getName());
                if (r.getConditionExpression() != null) {
                    sb.append(" (condition: ").append(r.getConditionExpression()).append(")");
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
