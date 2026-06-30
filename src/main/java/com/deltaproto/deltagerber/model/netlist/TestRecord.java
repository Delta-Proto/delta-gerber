package com.deltaproto.deltagerber.model.netlist;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;

/**
 * A standard IPC-D-356A electrical test record ({@code 317} through-hole, {@code 327} SMD,
 * {@code 367} non-plated tooling hole, {@code 307} blind/buried via).
 *
 * <p>All lengths (hole diameter, location, feature size) are in millimetres — normalized from the
 * file's native 0.0001&nbsp;inch ({@code CUST}) or 0.001&nbsp;mm ({@code SI}) grid at parse time.
 * Rotation is in degrees counter-clockwise.
 *
 * <p>The core identity (net, ref-des/pin, location, …) is fixed at construction. A handful of
 * fields are filled in afterwards by the parser from <em>continuation</em> records that attach to
 * this feature — the hole definition from a {@code 017}/{@code 027} record, the probe location from
 * a {@code 099} record, and solder-mask clearance from an {@code 088} record.
 */
public class TestRecord {

    private final TestPointType type;
    private final String opCode;

    private final String netName;       // resolved (NNAME alias expanded); null when N/C
    private final String rawNetName;    // exactly as it appeared in columns 4-17
    private final boolean connected;    // false when the net field was "N/C"

    private final String refDes;        // null when blank or a via
    private final boolean via;          // ref-des field was "VIA"
    private final String pin;           // pin identifier (may be alphanumeric); null when blank
    private final boolean midNet;       // column 32 "M"

    private double holeDiameterMm;      // 0 when no hole defined
    private Plating plating;
    private final int accessSide;       // 0 = both sides, 1 = primary, n = layer n; -1 if absent
    private final String accessRaw;     // the raw "A00"/"A01"/… token

    private final double x;
    private final double y;
    private final double featureSizeXMm; // 0 when absent
    private final double featureSizeYMm; // 0 when absent (round pad)
    private final double rotationDegrees;

    private final int solderMask;       // 0/1/2/3 per spec; -1 when absent

    private final int viaStartLayer;    // 307 only: physical start layer; -1 otherwise
    private final int viaEndLayer;      // 307 only: physical end layer; -1 otherwise

    // Filled in from continuation records (099 / 088):
    private boolean hasTestPointLocation;
    private double testPointXMm;
    private double testPointYMm;
    private boolean hasSolderMaskClearance;
    private double solderMaskClearanceXMm;
    private double solderMaskClearanceYMm;

    // Filled in from a 017/027 continuation: the secondary-side access of this same point, and —
    // for a 307 blind/buried via — the surface feature the via passes through.
    private int secondaryAccessSide = -1;
    private String secondaryAccessRaw;
    private boolean hasAttachedFeature;
    private double attachedFeatureXMm;
    private double attachedFeatureYMm;
    private double attachedFeatureSizeXMm;
    private double attachedFeatureSizeYMm;

    private TestRecord(Builder b) {
        this.type = b.type;
        this.opCode = b.opCode;
        this.netName = b.netName;
        this.rawNetName = b.rawNetName;
        this.connected = b.connected;
        this.refDes = b.refDes;
        this.via = b.via;
        this.pin = b.pin;
        this.midNet = b.midNet;
        this.holeDiameterMm = b.holeDiameterMm;
        this.plating = b.plating;
        this.accessSide = b.accessSide;
        this.accessRaw = b.accessRaw;
        this.x = b.x;
        this.y = b.y;
        this.featureSizeXMm = b.featureSizeXMm;
        this.featureSizeYMm = b.featureSizeYMm;
        this.rotationDegrees = b.rotationDegrees;
        this.solderMask = b.solderMask;
        this.viaStartLayer = b.viaStartLayer;
        this.viaEndLayer = b.viaEndLayer;
    }

    public TestPointType getType() { return type; }
    public String getOpCode() { return opCode; }

    /** Resolved net name (NNAME alias expanded), or {@code null} for an isolated {@code N/C} point. */
    public String getNetName() { return netName; }
    /** The net field exactly as written in columns 4-17 (alias not expanded), or {@code null}/empty. */
    public String getRawNetName() { return rawNetName; }
    /** {@code false} when the net field was {@code N/C} (a single-point / isolated net). */
    public boolean isConnected() { return connected; }

    public String getRefDes() { return refDes; }
    public boolean isVia() { return via; }
    public String getPin() { return pin; }
    /** Column 32 {@code M}: this point is in the middle of a net rather than an end. */
    public boolean isMidNet() { return midNet; }

    /** Hole diameter in mm, or {@code 0} when no hole is defined. */
    public double getHoleDiameterMm() { return holeDiameterMm; }
    public boolean hasHole() { return holeDiameterMm > 0; }
    public Plating getPlating() { return plating; }

    /** Access side: {@code 0} = both, {@code 1} = primary, {@code n} = outer layer n; {@code -1} if absent. */
    public int getAccessSide() { return accessSide; }
    public String getAccessRaw() { return accessRaw; }

    /** Feature centre X in mm. */
    public double getX() { return x; }
    /** Feature centre Y in mm. */
    public double getY() { return y; }
    /** Feature X size in mm ({@code 0} if absent). */
    public double getFeatureSizeXMm() { return featureSizeXMm; }
    /** Feature Y size in mm ({@code 0} for a round pad / when absent). */
    public double getFeatureSizeYMm() { return featureSizeYMm; }
    public double getRotationDegrees() { return rotationDegrees; }

    /** Solder-mask code (0 none, 1 primary, 2 secondary, 3 both); {@code -1} when absent. */
    public int getSolderMask() { return solderMask; }

    /** Physical start layer of a {@code 307} blind/buried via, or {@code -1}. */
    public int getViaStartLayer() { return viaStartLayer; }
    /** Physical end layer of a {@code 307} blind/buried via, or {@code -1}. */
    public int getViaEndLayer() { return viaEndLayer; }

    // ---- continuation-supplied data ----

    /** A {@code 017}/{@code 027} continuation supplied the hole when the primary record had none. */
    public void applyContinuationHole(double diameterMm, Plating plating) {
        if (!hasHole() && diameterMm > 0) {
            this.holeDiameterMm = diameterMm;
            this.plating = plating;
        }
    }

    /** A {@code 099} record gave the actual probe (test-point) location. */
    public void setTestPointLocation(double xMm, double yMm) {
        this.hasTestPointLocation = true;
        this.testPointXMm = xMm;
        this.testPointYMm = yMm;
    }

    public boolean hasTestPointLocation() { return hasTestPointLocation; }
    public double getTestPointXMm() { return testPointXMm; }
    public double getTestPointYMm() { return testPointYMm; }

    /** An {@code 088} record described the solder-mask clearance of this feature. */
    public void setSolderMaskClearance(double xMm, double yMm) {
        this.hasSolderMaskClearance = true;
        this.solderMaskClearanceXMm = xMm;
        this.solderMaskClearanceYMm = yMm;
    }

    public boolean hasSolderMaskClearance() { return hasSolderMaskClearance; }
    public double getSolderMaskClearanceXMm() { return solderMaskClearanceXMm; }
    public double getSolderMaskClearanceYMm() { return solderMaskClearanceYMm; }

    /**
     * Secondary-side access supplied by a {@code 017}/{@code 027} continuation (e.g. the layer-{@code n}
     * access of a point already accessible from the primary side); {@code -1} when none was given.
     */
    public int getSecondaryAccessSide() { return secondaryAccessSide; }
    public String getSecondaryAccessRaw() { return secondaryAccessRaw; }

    /** A {@code 017}/{@code 027} continuation recorded the secondary-side access of this point. */
    public void setSecondaryAccess(int side, String raw) {
        this.secondaryAccessSide = side;
        this.secondaryAccessRaw = raw;
    }

    /**
     * True when a {@code 017}/{@code 027} continuation described an attached surface feature — the
     * surface pad/feature a {@code 307} blind/buried via passes through (spec: a blind via must have
     * a {@code 027} record attached). Lengths are in mm.
     */
    public boolean hasAttachedFeature() { return hasAttachedFeature; }
    public double getAttachedFeatureXMm() { return attachedFeatureXMm; }
    public double getAttachedFeatureYMm() { return attachedFeatureYMm; }
    public double getAttachedFeatureSizeXMm() { return attachedFeatureSizeXMm; }
    public double getAttachedFeatureSizeYMm() { return attachedFeatureSizeYMm; }

    /** A {@code 017}/{@code 027} continuation gave the surface feature (location + size) of this via. */
    public void setAttachedFeature(double xMm, double yMm, double sizeXMm, double sizeYMm) {
        this.hasAttachedFeature = true;
        this.attachedFeatureXMm = xMm;
        this.attachedFeatureYMm = yMm;
        this.attachedFeatureSizeXMm = sizeXMm;
        this.attachedFeatureSizeYMm = sizeYMm;
    }

    /** Bounding box of the feature: the pad extent if known, otherwise the bare point. */
    public BoundingBox getBoundingBox() {
        double halfX = Math.max(featureSizeXMm, holeDiameterMm) / 2;
        double halfY = Math.max(featureSizeYMm, holeDiameterMm) / 2;
        return new BoundingBox(x - halfX, y - halfY, x + halfX, y + halfY);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "TestRecord[%s %s %s%s @ (%.4f,%.4f)]",
            opCode, netName != null ? netName : "N/C",
            via ? "VIA" : (refDes != null ? refDes : ""),
            pin != null ? "-" + pin : "", x, y);
    }

    public static Builder builder(String opCode) {
        return new Builder(opCode);
    }

    /** Builds the immutable core of a {@link TestRecord} from a primary {@code 3x7} record. */
    public static final class Builder {
        private final String opCode;
        private final TestPointType type;
        private String netName;
        private String rawNetName;
        private boolean connected = true;
        private String refDes;
        private boolean via;
        private String pin;
        private boolean midNet;
        private double holeDiameterMm;
        private Plating plating = Plating.UNSPECIFIED;
        private int accessSide = -1;
        private String accessRaw;
        private double x;
        private double y;
        private double featureSizeXMm;
        private double featureSizeYMm;
        private double rotationDegrees;
        private int solderMask = -1;
        private int viaStartLayer = -1;
        private int viaEndLayer = -1;

        private Builder(String opCode) {
            this.opCode = opCode;
            this.type = TestPointType.fromOpCode(opCode);
        }

        public Builder net(String resolved, String raw, boolean connected) {
            this.netName = resolved; this.rawNetName = raw; this.connected = connected; return this;
        }
        public Builder refDes(String refDes) { this.refDes = refDes; return this; }
        public Builder via(boolean via) { this.via = via; return this; }
        public Builder pin(String pin) { this.pin = pin; return this; }
        public Builder midNet(boolean midNet) { this.midNet = midNet; return this; }
        public Builder hole(double diameterMm, Plating plating) {
            this.holeDiameterMm = diameterMm; this.plating = plating; return this;
        }
        public Builder access(int side, String raw) { this.accessSide = side; this.accessRaw = raw; return this; }
        public Builder location(double x, double y) { this.x = x; this.y = y; return this; }
        public Builder featureSize(double xMm, double yMm) { this.featureSizeXMm = xMm; this.featureSizeYMm = yMm; return this; }
        public Builder rotation(double degrees) { this.rotationDegrees = degrees; return this; }
        public Builder solderMask(int code) { this.solderMask = code; return this; }
        public Builder viaLayers(int start, int end) { this.viaStartLayer = start; this.viaEndLayer = end; return this; }

        public TestRecord build() { return new TestRecord(this); }
    }
}
