package nl.bytesoflife.gerber.model.drill;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.model.gerber.Unit;

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
    private CoordinateMode coordinateMode = CoordinateMode.ABSOLUTE;
    private int integerDigits = 2;
    private int decimalDigits = 4;
    private boolean leadingZeros = true;

    private final Map<Integer, Tool> tools = new LinkedHashMap<>();
    private final List<DrillOperation> operations = new ArrayList<>();
    private final List<String> comments = new ArrayList<>();

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
