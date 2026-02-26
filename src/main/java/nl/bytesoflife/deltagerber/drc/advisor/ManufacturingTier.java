package nl.bytesoflife.deltagerber.drc.advisor;

/**
 * A single manufacturing tier level representing a cost breakpoint.
 * Dimensions are in millimeters. Null means the parameter is not a factor for this tier.
 *
 * @param name           human-readable tier name (e.g. "Standard", "Advanced", "HDI")
 * @param order          tier ordering, 0 = cheapest
 * @param minTraceWidthMm minimum trace width supported by this tier, or null
 * @param minSpaceMm     minimum copper-to-copper clearance, or null
 * @param minHoleSizeMm  minimum drill hole diameter, or null
 * @param description    human-readable description of the tier
 */
public record ManufacturingTier(
        String name,
        int order,
        Double minTraceWidthMm,
        Double minSpaceMm,
        Double minHoleSizeMm,
        String description
) {
    public ManufacturingTier {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tier name must not be blank");
        }
        if (order < 0) {
            throw new IllegalArgumentException("Tier order must be >= 0");
        }
    }
}
