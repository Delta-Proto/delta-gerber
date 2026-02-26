package nl.bytesoflife.deltagerber.drc.advisor;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;

import java.util.ArrayList;
import java.util.List;

/**
 * Main entry point for manufacturing cost analysis.
 * Scans a board, classifies it into manufacturing tiers, and identifies
 * optimization opportunities to reduce manufacturing cost.
 *
 * <pre>
 * CostAdvisorReport report = new CostAdvisor()
 *     .withProfile(BuiltinProfiles.nextPcb2Layer())
 *     .withClearanceAnalysis(false)
 *     .analyze(board);
 * </pre>
 */
public class CostAdvisor {

    private ManufacturerProfile profile;
    private boolean clearanceAnalysis;

    public CostAdvisor withProfile(ManufacturerProfile profile) {
        this.profile = profile;
        return this;
    }

    public CostAdvisor withClearanceAnalysis(boolean enabled) {
        this.clearanceAnalysis = enabled;
        return this;
    }

    /**
     * Analyze a board and produce a cost advisor report.
     */
    public CostAdvisorReport analyze(DrcBoardInput board) {
        if (profile == null) {
            throw new IllegalStateException("ManufacturerProfile must be set before analysis");
        }

        // Scan the board
        BoardProfile boardProfile = new BoardProfileScanner()
                .withClearanceAnalysis(clearanceAnalysis)
                .withTierBoundaries(profile)
                .scan(board);

        // Classify each parameter
        List<TierClassification> classifications = new ArrayList<>();
        ManufacturingTier worstTier = profile.getTiers().get(0);

        if (boardProfile.getTraceCount() > 0) {
            ManufacturingTier traceTier = profile.classifyTrace(boardProfile.getMinTraceWidthMm());
            classifications.add(new TierClassification(
                    "Trace Width", traceTier,
                    boardProfile.getMinTraceWidthMm(),
                    traceTier.minTraceWidthMm()));
            if (traceTier.order() > worstTier.order()) {
                worstTier = traceTier;
            }
        }

        if (boardProfile.getMinClearanceMm() != null) {
            ManufacturingTier clearanceTier = profile.classifyClearance(boardProfile.getMinClearanceMm());
            classifications.add(new TierClassification(
                    "Clearance", clearanceTier,
                    boardProfile.getMinClearanceMm(),
                    clearanceTier.minSpaceMm()));
            if (clearanceTier.order() > worstTier.order()) {
                worstTier = clearanceTier;
            }
        }

        if (boardProfile.getHoleCount() > 0) {
            ManufacturingTier holeTier = profile.classifyHole(boardProfile.getMinHoleSizeMm());
            classifications.add(new TierClassification(
                    "Hole Size", holeTier,
                    boardProfile.getMinHoleSizeMm(),
                    holeTier.minHoleSizeMm()));
            if (holeTier.order() > worstTier.order()) {
                worstTier = holeTier;
            }
        }

        // Generate suggestions
        List<OptimizationSuggestion> suggestions = new ArrayList<>();

        for (TierClassification classification : classifications) {
            ManufacturingTier cheaper = profile.nextCheaperTier(classification.currentTier());
            if (cheaper == null) continue;

            double targetThreshold = getTargetThreshold(cheaper, classification.parameterName());
            if (targetThreshold <= 0) continue;

            int belowTarget = countFeaturesBelow(boardProfile, classification.parameterName(), targetThreshold);
            int totalFeatures = getTotalFeatures(boardProfile, classification.parameterName());

            if (belowTarget > 0) {
                String impact = String.format(
                        "Widening %d %s feature%s to >= %.4fmm would drop from %s to %s tier",
                        belowTarget,
                        classification.parameterName().toLowerCase(),
                        belowTarget == 1 ? "" : "s",
                        targetThreshold,
                        classification.currentTier().name(),
                        cheaper.name());

                suggestions.add(new OptimizationSuggestion(
                        classification.parameterName(),
                        classification.currentTier(),
                        cheaper,
                        classification.measuredMinMm(),
                        targetThreshold,
                        belowTarget,
                        totalFeatures,
                        impact));
            }
        }

        return new CostAdvisorReport(
                profile.getName(),
                boardProfile,
                worstTier,
                classifications,
                suggestions);
    }

    private double getTargetThreshold(ManufacturingTier tier, String parameterName) {
        return switch (parameterName) {
            case "Trace Width" -> tier.minTraceWidthMm() != null ? tier.minTraceWidthMm() : 0;
            case "Clearance" -> tier.minSpaceMm() != null ? tier.minSpaceMm() : 0;
            case "Hole Size" -> tier.minHoleSizeMm() != null ? tier.minHoleSizeMm() : 0;
            default -> 0;
        };
    }

    private int countFeaturesBelow(BoardProfile profile, String parameterName, double threshold) {
        List<FeatureBucket> distribution = switch (parameterName) {
            case "Trace Width" -> profile.getTraceWidthDistribution();
            case "Clearance" -> profile.getClearanceDistribution();
            case "Hole Size" -> profile.getHoleSizeDistribution();
            default -> null;
        };
        if (distribution == null) return 0;

        int count = 0;
        for (FeatureBucket bucket : distribution) {
            if (bucket.upperBoundMm() <= threshold) {
                count += bucket.count();
            } else if (bucket.lowerBoundMm() < threshold) {
                // Bucket straddles the threshold — count all features in it as below
                count += bucket.count();
            }
        }
        return count;
    }

    private int getTotalFeatures(BoardProfile profile, String parameterName) {
        return switch (parameterName) {
            case "Trace Width" -> profile.getTraceCount();
            case "Clearance" -> profile.getClearancePairCount();
            case "Hole Size" -> profile.getHoleCount();
            default -> 0;
        };
    }
}
