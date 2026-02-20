package nl.bytesoflife.deltagerber.drc.model;

public class DrcConstraint {

    private final ConstraintType type;
    private final Double minMm;
    private final Double maxMm;
    private final String disallowValue;

    public DrcConstraint(ConstraintType type, Double minMm, Double maxMm) {
        this(type, minMm, maxMm, null);
    }

    public DrcConstraint(ConstraintType type, Double minMm, Double maxMm, String disallowValue) {
        this.type = type;
        this.minMm = minMm;
        this.maxMm = maxMm;
        this.disallowValue = disallowValue;
    }

    public ConstraintType getType() {
        return type;
    }

    public Double getMinMm() {
        return minMm;
    }

    public Double getMaxMm() {
        return maxMm;
    }

    public String getDisallowValue() {
        return disallowValue;
    }

    @Override
    public String toString() {
        if (type == ConstraintType.DISALLOW) {
            return "disallow " + disallowValue;
        }
        StringBuilder sb = new StringBuilder(type.name().toLowerCase());
        if (minMm != null) sb.append(" min=").append(minMm).append("mm");
        if (maxMm != null) sb.append(" max=").append(maxMm).append("mm");
        return sb.toString();
    }
}
