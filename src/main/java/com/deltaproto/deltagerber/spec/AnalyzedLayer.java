package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.Beta;
import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.dfm.ClearanceResult;
import com.deltaproto.deltagerber.dfm.ConductorWidthResult;
import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.FormatSpec;

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
    private final Double minClearanceMm;
    private final Double minConductorWidthUm;
    private final ClearanceResult clearance;
    private final ConductorWidthResult conductorWidth;
    private final Boolean hasGeometry;
    private final FormatSpec formatSpec;
    private final List<String> warnings;

    private AnalyzedLayer(Builder builder) {
        this.fileName = builder.fileName;
        this.classification = builder.classification;
        this.bounds = builder.bounds;
        this.minTrackWidthUm = builder.minTrackWidthUm;
        this.minDrillDiameterMm = builder.minDrillDiameterMm;
        this.minClearanceMm = builder.minClearanceMm;
        this.minConductorWidthUm = builder.minConductorWidthUm;
        this.clearance = builder.clearance;
        this.conductorWidth = builder.conductorWidth;
        this.hasGeometry = builder.hasGeometry;
        this.formatSpec = builder.formatSpec;
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
     * Tightest gap between two nets on this copper layer in millimetres, measured from the geometry
     * ({@link ClearanceResult}); null when not measured, or when no two nets come within the check's
     * cutoff of each other.
     */
    @Beta("not yet validated against an external DFM tool")
    public Double getMinClearanceMm() {
        return minClearanceMm;
    }

    /**
     * Narrowest copper on this layer in micrometres, measured from the geometry
     * ({@link ConductorWidthResult}) — strokes of any aperture shape and the necks of pours. Not the
     * same as {@link #getMinTrackWidthUm()}, which reads the aperture table; where the two disagree
     * the copper necks somewhere.
     */
    @Beta("not yet validated against an external DFM tool")
    public Double getMinConductorWidthUm() {
        return minConductorWidthUm;
    }

    /** The clearance check in full, or null when it did not run (persisted layers, non-copper). */
    @Beta("not yet validated against an external DFM tool")
    public ClearanceResult getClearance() {
        return clearance;
    }

    /** The conductor-width measurement in full, or null when it did not run. */
    @Beta("not yet validated against an external DFM tool")
    public ConductorWidthResult getConductorWidth() {
        return conductorWidth;
    }

    /**
     * Whether the file draws anything at all. A paste layer that exists but is empty needs no
     * stencil, and an outline layer that is empty is not an outline; null when not determined.
     */
    public Boolean getHasGeometry() {
        return hasGeometry;
    }

    /**
     * How this file writes its coordinates — digits, zero suppression and the unit they are in.
     * Null when the file was not parsed (see {@link AnalysisDepth#SPECIFICATION}) or carried no
     * format at all. See {@link BoardSpecification#isFormatConsistent()} for what a set makes of
     * them together.
     */
    public FormatSpec getFormatSpec() {
        return formatSpec;
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
        private Double minClearanceMm;
        private Double minConductorWidthUm;
        private ClearanceResult clearance;
        private ConductorWidthResult conductorWidth;
        private Boolean hasGeometry;
        private FormatSpec formatSpec;
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

        /** The summary figure alone — for a layer re-created from persisted measurements. */
        public Builder minClearanceMm(Double minClearanceMm) {
            this.minClearanceMm = minClearanceMm;
            return this;
        }

        /** The summary figure alone — for a layer re-created from persisted measurements. */
        public Builder minConductorWidthUm(Double minConductorWidthUm) {
            this.minConductorWidthUm = minConductorWidthUm;
            return this;
        }

        /** The clearance check; also sets {@link #minClearanceMm} from it. */
        public Builder clearance(ClearanceResult clearance) {
            this.clearance = clearance;
            this.minClearanceMm = clearance == null ? null : clearance.getMinMm();
            return this;
        }

        /** The conductor-width measurement; also sets {@link #minConductorWidthUm} from it. */
        public Builder conductorWidth(ConductorWidthResult conductorWidth) {
            this.conductorWidth = conductorWidth;
            Double mm = conductorWidth == null ? null : conductorWidth.getMinMm();
            this.minConductorWidthUm = mm == null ? null : mm * 1000.0;
            return this;
        }

        public Builder hasGeometry(Boolean hasGeometry) {
            this.hasGeometry = hasGeometry;
            return this;
        }

        public Builder formatSpec(FormatSpec formatSpec) {
            this.formatSpec = formatSpec;
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
