package nl.bytesoflife.deltagerber.drc.advisor;

import java.util.Collections;
import java.util.List;

/**
 * A manufacturer's tier ladder, ordered from cheapest to most expensive.
 * Used to classify a board design into cost tiers based on its minimum feature sizes.
 */
public class ManufacturerProfile {

    private final String name;
    private final List<ManufacturingTier> tiers;

    public ManufacturerProfile(String name, List<ManufacturingTier> tiers) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Profile name must not be blank");
        }
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("Profile must have at least one tier");
        }
        this.name = name;
        this.tiers = List.copyOf(tiers);
    }

    public String getName() {
        return name;
    }

    public List<ManufacturingTier> getTiers() {
        return tiers;
    }

    /**
     * Classify design parameters into the cheapest tier that supports them.
     * Each parameter is checked independently; the result is the most expensive (highest order)
     * tier required by any single parameter.
     *
     * @param minTraceMm     minimum trace width on the board (mm), or null to skip
     * @param minClearanceMm minimum clearance on the board (mm), or null to skip
     * @param minHoleMm      minimum hole diameter on the board (mm), or null to skip
     * @return the cheapest tier that accommodates all measured minimums
     */
    public ManufacturingTier classify(Double minTraceMm, Double minClearanceMm, Double minHoleMm) {
        ManufacturingTier worst = tiers.get(0);

        for (ManufacturingTier tier : tiers) {
            if (minTraceMm != null && tier.minTraceWidthMm() != null && minTraceMm < tier.minTraceWidthMm()) {
                continue;
            }
            if (minClearanceMm != null && tier.minSpaceMm() != null && minClearanceMm < tier.minSpaceMm()) {
                continue;
            }
            if (minHoleMm != null && tier.minHoleSizeMm() != null && minHoleMm < tier.minHoleSizeMm()) {
                continue;
            }
            // This tier supports all parameters
            return tier;
        }

        // No tier supports the parameters — return the most expensive
        return tiers.get(tiers.size() - 1);
    }

    /**
     * Classify a single parameter against tiers.
     * Returns the cheapest tier whose threshold for that parameter is met.
     */
    public ManufacturingTier classifyTrace(double minTraceMm) {
        return classify(minTraceMm, null, null);
    }

    public ManufacturingTier classifyClearance(double minClearanceMm) {
        return classify(null, minClearanceMm, null);
    }

    public ManufacturingTier classifyHole(double minHoleMm) {
        return classify(null, null, minHoleMm);
    }

    /**
     * Returns the next cheaper tier relative to the given tier, or null if already at the cheapest.
     */
    public ManufacturingTier nextCheaperTier(ManufacturingTier current) {
        if (current == null) return null;
        for (int i = 1; i < tiers.size(); i++) {
            if (tiers.get(i).order() == current.order()) {
                return tiers.get(i - 1);
            }
        }
        return null;
    }
}
