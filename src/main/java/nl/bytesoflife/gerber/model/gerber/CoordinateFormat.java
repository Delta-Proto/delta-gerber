package nl.bytesoflife.gerber.model.gerber;

/**
 * Coordinate format specification from FS command.
 * Defines how coordinate values are parsed.
 */
public class CoordinateFormat {

    private final int integerDigits;
    private final int decimalDigits;
    private final boolean leadingZeroOmitted;
    private final boolean absoluteNotation;

    public CoordinateFormat(int integerDigits, int decimalDigits,
                           boolean leadingZeroOmitted, boolean absoluteNotation) {
        this.integerDigits = integerDigits;
        this.decimalDigits = decimalDigits;
        this.leadingZeroOmitted = leadingZeroOmitted;
        this.absoluteNotation = absoluteNotation;
    }

    /**
     * Parse a coordinate string to a double value.
     */
    public double parseCoordinate(String coordStr) {
        if (coordStr == null || coordStr.isEmpty()) {
            return 0.0;
        }

        boolean negative = coordStr.startsWith("-");
        if (negative || coordStr.startsWith("+")) {
            coordStr = coordStr.substring(1);
        }

        // Pad or truncate to expected length
        int totalDigits = integerDigits + decimalDigits;

        if (leadingZeroOmitted) {
            // Leading zeros omitted - pad on the left
            while (coordStr.length() < totalDigits) {
                coordStr = "0" + coordStr;
            }
        } else {
            // Trailing zeros omitted - pad on the right
            while (coordStr.length() < totalDigits) {
                coordStr = coordStr + "0";
            }
        }

        // Split into integer and decimal parts
        String intPart = coordStr.substring(0, integerDigits);
        String decPart = coordStr.substring(integerDigits);

        double value = Double.parseDouble(intPart + "." + decPart);
        return negative ? -value : value;
    }

    public int getIntegerDigits() {
        return integerDigits;
    }

    public int getDecimalDigits() {
        return decimalDigits;
    }

    public boolean isLeadingZeroOmitted() {
        return leadingZeroOmitted;
    }

    public boolean isAbsoluteNotation() {
        return absoluteNotation;
    }

    public int getFractionalDigits() {
        return decimalDigits;
    }

    @Override
    public String toString() {
        return String.format("CoordinateFormat[%d.%d, %s, %s]",
            integerDigits, decimalDigits,
            leadingZeroOmitted ? "L" : "T",
            absoluteNotation ? "A" : "I");
    }
}
