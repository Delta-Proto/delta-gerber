package nl.bytesoflife.deltagerber.drc.advisor;

/**
 * An actionable optimization suggestion — identifies features that, if widened,
 * would allow the board to drop to a cheaper manufacturing tier.
 *
 * @param parameterName      the parameter (e.g. "Trace Width", "Hole Size")
 * @param currentTier        current tier for this parameter
 * @param targetTier         cheaper tier to target
 * @param currentMinMm       current minimum measurement
 * @param targetMinMm        the minimum required by the target tier
 * @param featuresBelowTarget number of features below the target tier's threshold
 * @param totalFeatures      total number of features of this type
 * @param impact             human-readable impact description
 */
public record OptimizationSuggestion(
        String parameterName,
        ManufacturingTier currentTier,
        ManufacturingTier targetTier,
        double currentMinMm,
        double targetMinMm,
        int featuresBelowTarget,
        int totalFeatures,
        String impact
) {
}
