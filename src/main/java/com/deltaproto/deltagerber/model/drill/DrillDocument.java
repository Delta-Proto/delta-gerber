package com.deltaproto.deltagerber.model.drill;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.FormatSpec;
import com.deltaproto.deltagerber.model.gerber.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a parsed Excellon drill file.
 */
public class DrillDocument {

    private String fileName;
    private Unit unit = Unit.MM;
    private Unit sourceUnit = Unit.MM;
    private CoordinateMode coordinateMode = CoordinateMode.ABSOLUTE;
    private int integerDigits = 2;
    private int decimalDigits = 4;
    private boolean leadingZeros = true;
    private boolean formatDeclared = false;
    private boolean decimalPointCoordinates = false;

    // Translation (mm) already baked into this document's coordinates to bring them into the
    // Gerber/board frame, set when DrillGerberAlignment corrects an origin mismatch. Zero for a
    // document straight from the parser. The fix is "done once" — every coordinate here is already
    // corrected — while this stamp keeps it reversible and explainable: the original exported
    // position of any point is (current - originOffset).
    private double originOffsetX = 0;
    private double originOffsetY = 0;

    private final Map<Integer, Tool> tools = new LinkedHashMap<>();
    private final List<DrillOperation> operations = new ArrayList<>();
    private final List<String> comments = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private BoundingBox boundingBox;

    public DrillDocument() {
    }

    public BoundingBox calculateBoundingBox() {
        boundingBox = new BoundingBox();
        for (DrillOperation op : operations) {
            boundingBox.include(op.getBoundingBox());
        }
        return boundingBox;
    }

    public BoundingBox getBoundingBox() {
        if (boundingBox == null) calculateBoundingBox();
        return boundingBox;
    }

    public void addTool(Tool tool) {
        tools.put(tool.getNumber(), tool);
    }

    public Tool getTool(int number) {
        return tools.get(number);
    }

    public void addOperation(DrillOperation operation) {
        operations.add(operation);
    }

    public void addComment(String comment) {
        comments.add(comment);
    }

    /**
     * Record a non-fatal parsing anomaly (e.g. a malformed coordinate that was skipped,
     * a drill hit before any tool was selected). Warnings let callers surface data-quality
     * problems to the user instead of silently dropping geometry. Duplicates are collapsed
     * so a repeated problem reports once rather than flooding the list.
     */
    public void addWarning(String warning) {
        if (warning != null && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    public List<String> getWarnings() {
        return warnings;
    }

    // Coordinate parsing helpers
    public double parseCoordinate(String value) {
        if (value == null || value.isEmpty()) {
            return Double.NaN;
        }

        // Handle sign
        boolean negative = value.startsWith("-");
        if (negative || value.startsWith("+")) {
            value = value.substring(1);
        }

        // Parse based on format
        double parsed;
        if (leadingZeros) {
            // Leading zeros format: decimal point is implicit at position
            // Pad with trailing zeros if necessary
            while (value.length() < integerDigits + decimalDigits) {
                value = value + "0";
            }
            String intPart = value.substring(0, value.length() - decimalDigits);
            String decPart = value.substring(value.length() - decimalDigits);
            parsed = Double.parseDouble(intPart + "." + decPart);
        } else {
            // Trailing zeros format: pad with leading zeros
            while (value.length() < integerDigits + decimalDigits) {
                value = "0" + value;
            }
            String intPart = value.substring(0, integerDigits);
            String decPart = value.substring(integerDigits);
            parsed = Double.parseDouble(intPart + "." + decPart);
        }

        return negative ? -parsed : parsed;
    }

    // Getters and setters

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Unit getUnit() {
        return unit;
    }

    public void setUnit(Unit unit) {
        this.unit = unit;
    }

    public CoordinateMode getCoordinateMode() {
        return coordinateMode;
    }

    public void setCoordinateMode(CoordinateMode coordinateMode) {
        this.coordinateMode = coordinateMode;
    }

    public int getIntegerDigits() {
        return integerDigits;
    }

    public void setIntegerDigits(int integerDigits) {
        this.integerDigits = integerDigits;
    }

    public int getDecimalDigits() {
        return decimalDigits;
    }

    public void setDecimalDigits(int decimalDigits) {
        this.decimalDigits = decimalDigits;
    }

    public boolean isLeadingZeros() {
        return leadingZeros;
    }

    public void setLeadingZeros(boolean leadingZeros) {
        this.leadingZeros = leadingZeros;
    }

    /**
     * The unit the file's own numbers were written in ({@code INCH}/{@code METRIC}, {@code M72}/
     * {@code M71}) — not {@link #getUnit()}, which is {@link Unit#MM} on every parsed document
     * because that is what the coordinates have been converted to. Kept only so
     * {@link #getFormatSpec()} can say what the digits meant.
     */
    public Unit getSourceUnit() {
        return sourceUnit;
    }

    public void setSourceUnit(Unit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    /**
     * Whether the file stated its digit format itself — a {@code ;FILE_FORMAT=} comment or a
     * standalone {@code 2.4}. Excellon does not require it, so it is often false and the digits are
     * then the parser's convention for the unit (2:4 inch, 3:3 metric).
     */
    public boolean isFormatDeclared() {
        return formatDeclared;
    }

    public void setFormatDeclared(boolean formatDeclared) {
        this.formatDeclared = formatDeclared;
    }

    /**
     * Whether the coordinates carry their own decimal point, as KiCad and others write them. Then
     * nothing is zero-suppressed and the digit counts describe nothing — the numbers are read as
     * written.
     */
    public boolean hasDecimalPointCoordinates() {
        return decimalPointCoordinates;
    }

    public void setDecimalPointCoordinates(boolean decimalPointCoordinates) {
        this.decimalPointCoordinates = decimalPointCoordinates;
    }

    /**
     * How this file wrote its coordinates — digits, zero suppression and the unit they were in.
     * Never null: unlike Gerber's mandatory {@code %FS%}, an Excellon file may declare nothing at
     * all, and the format is then the one the parser assumed to read it, flagged as
     * {@linkplain FormatSpec#declared() undeclared}.
     */
    public FormatSpec getFormatSpec() {
        FormatSpec.ZeroSuppression zeros = decimalPointCoordinates
                ? FormatSpec.ZeroSuppression.NONE
                // Excellon LZ keeps the leading zeros and drops the trailing ones — the opposite of
                // what Gerber's L means. See FormatSpec.
                : leadingZeros ? FormatSpec.ZeroSuppression.TRAILING : FormatSpec.ZeroSuppression.LEADING;
        return new FormatSpec(sourceUnit, integerDigits, decimalDigits, zeros, formatDeclared);
    }

    /** Millimetres added to the X coordinates to bring them into the Gerber frame (0 if none). */
    public double getOriginOffsetX() {
        return originOffsetX;
    }

    /** Millimetres added to the Y coordinates to bring them into the Gerber frame (0 if none). */
    public double getOriginOffsetY() {
        return originOffsetY;
    }

    public void setOriginOffset(double originOffsetX, double originOffsetY) {
        this.originOffsetX = originOffsetX;
        this.originOffsetY = originOffsetY;
    }

    /** True when this document's coordinates were shifted from their exported origin. */
    public boolean isOriginCorrected() {
        return originOffsetX != 0 || originOffsetY != 0;
    }

    public Map<Integer, Tool> getTools() {
        return tools;
    }

    public List<DrillOperation> getOperations() {
        return operations;
    }

    public List<String> getComments() {
        return comments;
    }

    @Override
    public String toString() {
        return String.format("DrillDocument[%s, %d tools, %d operations, %s]",
            fileName != null ? fileName : "unnamed",
            tools.size(), operations.size(), unit);
    }
}
