package nl.bytesoflife.deltagerber.drc;

import nl.bytesoflife.deltagerber.drc.model.DrcConstraint;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.drc.model.Severity;

import java.util.Locale;

public class DrcViolation {

    private final DrcRule rule;
    private final DrcConstraint constraint;
    private final Severity severity;
    private final String description;
    private final Double measuredValueMm;
    private final Double requiredValueMm;
    private final double x;
    private final double y;
    private final String layer;

    public DrcViolation(DrcRule rule, DrcConstraint constraint, Severity severity,
                        String description, Double measuredValueMm, Double requiredValueMm,
                        double x, double y, String layer) {
        this.rule = rule;
        this.constraint = constraint;
        this.severity = severity;
        this.description = description;
        this.measuredValueMm = measuredValueMm;
        this.requiredValueMm = requiredValueMm;
        this.x = x;
        this.y = y;
        this.layer = layer;
    }

    public DrcRule getRule() { return rule; }
    public DrcConstraint getConstraint() { return constraint; }
    public Severity getSeverity() { return severity; }
    public String getDescription() { return description; }
    public Double getMeasuredValueMm() { return measuredValueMm; }
    public Double getRequiredValueMm() { return requiredValueMm; }
    public double getX() { return x; }
    public double getY() { return y; }
    public String getLayer() { return layer; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(severity).append("] ");
        sb.append(rule.getName()).append(": ");
        sb.append(description);
        if (measuredValueMm != null && requiredValueMm != null) {
            sb.append(String.format(Locale.US, " (measured=%.4fmm, required=%.4fmm)", measuredValueMm, requiredValueMm));
        }
        sb.append(String.format(Locale.US, " at (%.4f, %.4f)", x, y));
        if (layer != null) {
            sb.append(" on ").append(layer);
        }
        return sb.toString();
    }
}
