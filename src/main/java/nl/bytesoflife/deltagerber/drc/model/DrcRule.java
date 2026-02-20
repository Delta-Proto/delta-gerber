package nl.bytesoflife.deltagerber.drc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DrcRule {

    private final String name;
    private Severity severity = Severity.ERROR;
    private LayerSelector layer;
    private final List<DrcConstraint> constraints = new ArrayList<>();
    private String conditionExpression;

    public DrcRule(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public LayerSelector getLayer() {
        return layer;
    }

    public void setLayer(LayerSelector layer) {
        this.layer = layer;
    }

    public List<DrcConstraint> getConstraints() {
        return Collections.unmodifiableList(constraints);
    }

    public void addConstraint(DrcConstraint constraint) {
        constraints.add(constraint);
    }

    public String getConditionExpression() {
        return conditionExpression;
    }

    public void setConditionExpression(String conditionExpression) {
        this.conditionExpression = conditionExpression;
    }

    @Override
    public String toString() {
        return "DrcRule{name='" + name + "', constraints=" + constraints.size() +
                (conditionExpression != null ? ", condition='" + conditionExpression + "'" : "") + "}";
    }
}
