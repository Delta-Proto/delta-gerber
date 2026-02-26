package nl.bytesoflife.deltagerber.drc.advisor;

import java.util.Collections;
import java.util.List;

/**
 * Complete cost advisor report, containing board profile, tier classifications,
 * and optimization suggestions.
 */
public class CostAdvisorReport {

    private final String manufacturer;
    private final BoardProfile boardProfile;
    private final ManufacturingTier overallTier;
    private final List<TierClassification> classifications;
    private final List<OptimizationSuggestion> suggestions;

    public CostAdvisorReport(String manufacturer,
                             BoardProfile boardProfile,
                             ManufacturingTier overallTier,
                             List<TierClassification> classifications,
                             List<OptimizationSuggestion> suggestions) {
        this.manufacturer = manufacturer;
        this.boardProfile = boardProfile;
        this.overallTier = overallTier;
        this.classifications = List.copyOf(classifications);
        this.suggestions = List.copyOf(suggestions);
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public BoardProfile getBoardProfile() {
        return boardProfile;
    }

    public ManufacturingTier getOverallTier() {
        return overallTier;
    }

    public List<TierClassification> getClassifications() {
        return classifications;
    }

    public List<OptimizationSuggestion> getSuggestions() {
        return suggestions;
    }

    public boolean hasOptimizations() {
        return !suggestions.isEmpty();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Cost Advisor Report:\n");
        sb.append("  Manufacturer: ").append(manufacturer).append("\n");
        sb.append("  Copper layers: ").append(boardProfile.getCopperLayerCount()).append("\n");
        sb.append("  Overall tier: ").append(overallTier.name())
          .append(" (").append(overallTier.description()).append(")\n");

        sb.append("\n  Classifications:\n");
        for (TierClassification c : classifications) {
            sb.append("  - ").append(c.parameterName())
              .append(": ").append(c.currentTier().name())
              .append(String.format(" (measured min: %.4fmm", c.measuredMinMm()));
            if (c.tierThresholdMm() != null) {
                sb.append(String.format(", tier threshold: %.4fmm", c.tierThresholdMm()));
            }
            sb.append(")\n");
        }

        if (!suggestions.isEmpty()) {
            sb.append("\n  Optimization suggestions:\n");
            for (OptimizationSuggestion s : suggestions) {
                sb.append("  - ").append(s.parameterName())
                  .append(": ").append(s.currentTier().name())
                  .append(" -> ").append(s.targetTier().name())
                  .append(String.format(" (widen %d of %d features from %.4fmm to >= %.4fmm)",
                          s.featuresBelowTarget(), s.totalFeatures(),
                          s.currentMinMm(), s.targetMinMm()))
                  .append("\n");
                sb.append("    Impact: ").append(s.impact()).append("\n");
            }
        } else {
            sb.append("\n  No optimization suggestions — board is already at the cheapest tier.\n");
        }

        return sb.toString();
    }
}
