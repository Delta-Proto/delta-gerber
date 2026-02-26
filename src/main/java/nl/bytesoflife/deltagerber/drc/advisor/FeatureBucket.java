package nl.bytesoflife.deltagerber.drc.advisor;

/**
 * A histogram bucket for feature size distribution.
 *
 * @param lowerBoundMm lower bound of the bucket (inclusive), in mm
 * @param upperBoundMm upper bound of the bucket (exclusive), in mm; Double.MAX_VALUE for the last bucket
 * @param count        number of features in this bucket
 * @param label        human-readable label (e.g. "< 0.127mm (Standard)")
 */
public record FeatureBucket(
        double lowerBoundMm,
        double upperBoundMm,
        int count,
        String label
) {
}
