package nl.bytesoflife.deltagerber.drc.advisor;

/**
 * Per-parameter tier classification result.
 *
 * @param parameterName    the parameter being classified (e.g. "Trace Width", "Hole Size")
 * @param currentTier      the tier this parameter falls into
 * @param measuredMinMm    the measured minimum value in mm
 * @param tierThresholdMm  the tier's threshold for this parameter, or null
 */
public record TierClassification(
        String parameterName,
        ManufacturingTier currentTier,
        double measuredMinMm,
        Double tierThresholdMm
) {
}
