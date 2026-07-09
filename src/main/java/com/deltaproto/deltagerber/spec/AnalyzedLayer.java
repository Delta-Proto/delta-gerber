package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;

import java.util.List;

/**
 * One file of a set, classified and measured.
 *
 * <p>{@link PcbAnalyzer} produces these from file content; callers that persist the measurements
 * can rebuild them later with {@link #builder(String)} and feed them straight back into
 * {@link BoardSpecification#from(List)} without re-reading the files.
 *
 * <p>Every measurement is nullable: a file may be unparseable, or simply not the kind of file the
 * measurement applies to (there is no track width on a drill file). Null means "not known", never
 * "zero".
 */
public final class AnalyzedLayer {

    private final String fileName;
    private final LayerClassification classification;
    private final BoundingBox bounds;
    private final Double minTrackWidthUm;
    private final Double minDrillDiameterMm;
    private final Boolean hasGeometry;
    private final List<String> warnings;

    private AnalyzedLayer(Builder builder) {
        this.fileName = builder.fileName;
        this.classification = builder.classification;
        this.bounds = builder.bounds;
        this.minTrackWidthUm = builder.minTrackWidthUm;
        this.minDrillDiameterMm = builder.minDrillDiameterMm;
        this.hasGeometry = builder.hasGeometry;
        this.warnings = List.copyOf(builder.warnings);
    }

    public static Builder builder(String fileName) {
        return new Builder(fileName);
    }

    public String getFileName() {
        return fileName;
    }

    /** What this file is, or null when nothing recognised it. */
    public LayerClassification getClassification() {
        return classification;
    }

    /** Never null — {@link LayerFunction#UNKNOWN} when unclassified. */
    public LayerFunction getFunction() {
        return classification == null ? LayerFunction.UNKNOWN : classification.function();
    }

    /** Never null — {@link LayerSide#NA} when unclassified. */
    public LayerSide getSide() {
        return classification == null ? LayerSide.NA : classification.side();
    }

    /**
     * Stack-up index of an inner copper layer, counted from 1 whatever the generator counted from;
     * null for every other layer. See
     * {@link com.deltaproto.deltagerber.classify.LayerClassifier#normalizeInnerCopperNumbers}.
     */
    public Integer getLayerNumber() {
        return classification == null ? null : classification.number();
    }

    /**
     * Extent of this layer in millimetres, or null when the file could not be parsed.
     *
     * <p>For an {@link LayerFunction#OUTLINE} layer this is the profile <em>centreline</em> — the
     * line the board is cut along, and therefore the board's true size. For every other layer it
     * is the inked extent of the artwork, aperture width included, since that is the area the
     * layer actually covers.
     */
    public BoundingBox getBounds() {
        return bounds;
    }

    /** Narrowest track on this copper layer in micrometres, or null. */
    public Double getMinTrackWidthUm() {
        return minTrackWidthUm;
    }

    /** Smallest drill on this layer in millimetres, or null. */
    public Double getMinDrillDiameterMm() {
        return minDrillDiameterMm;
    }

    /**
     * Whether the file draws anything at all. A paste layer that exists but is empty needs no
     * stencil, and an outline layer that is empty is not an outline; null when not determined.
     */
    public Boolean getHasGeometry() {
        return hasGeometry;
    }

    /** Non-fatal problems found while parsing this file. */
    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return String.format("AnalyzedLayer[%s, %s/%s]", fileName, getFunction(), getSide());
    }

    public static final class Builder {
        private final String fileName;
        private LayerClassification classification;
        private BoundingBox bounds;
        private Double minTrackWidthUm;
        private Double minDrillDiameterMm;
        private Boolean hasGeometry;
        private List<String> warnings = List.of();

        private Builder(String fileName) {
            this.fileName = fileName;
        }

        public Builder classification(LayerClassification classification) {
            this.classification = classification;
            return this;
        }

        public Builder classification(LayerFunction function, LayerSide side, Integer number) {
            this.classification = new LayerClassification(fileName, function, side, number);
            return this;
        }

        /** See {@link AnalyzedLayer#getBounds()} for which extent belongs here. */
        public Builder bounds(BoundingBox bounds) {
            this.bounds = bounds != null && bounds.isValid() ? bounds : null;
            return this;
        }

        public Builder bounds(Double minX, Double minY, Double maxX, Double maxY) {
            this.bounds = (minX == null || minY == null || maxX == null || maxY == null)
                    ? null
                    : new BoundingBox(minX, minY, maxX, maxY);
            return this;
        }

        public Builder minTrackWidthUm(Double minTrackWidthUm) {
            this.minTrackWidthUm = minTrackWidthUm;
            return this;
        }

        public Builder minDrillDiameterMm(Double minDrillDiameterMm) {
            this.minDrillDiameterMm = minDrillDiameterMm;
            return this;
        }

        public Builder hasGeometry(Boolean hasGeometry) {
            this.hasGeometry = hasGeometry;
            return this;
        }

        public Builder warnings(List<String> warnings) {
            this.warnings = warnings == null ? List.of() : warnings;
            return this;
        }

        public AnalyzedLayer build() {
            return new AnalyzedLayer(this);
        }
    }
}
