package nl.bytesoflife.gerber.model.gerber;

/**
 * Unit of measurement for Gerber coordinates.
 */
public enum Unit {
    MM(1.0),
    INCH(25.4);

    private final double mmFactor;

    Unit(double mmFactor) {
        this.mmFactor = mmFactor;
    }

    /**
     * Convert a value in this unit to millimeters.
     */
    public double toMm(double value) {
        return value * mmFactor;
    }

    /**
     * Convert a value from millimeters to this unit.
     */
    public double fromMm(double mm) {
        return mm / mmFactor;
    }
}
