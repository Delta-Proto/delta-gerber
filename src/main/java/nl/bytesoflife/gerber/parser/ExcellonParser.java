package nl.bytesoflife.gerber.parser;

import nl.bytesoflife.gerber.model.drill.*;
import nl.bytesoflife.gerber.model.gerber.Unit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Excellon NC drill files.
 */
public class ExcellonParser {

    private DrillDocument document;
    private Tool currentTool;
    private double currentX = 0;
    private double currentY = 0;
    private boolean inHeader = true;

    // Pattern for tool definition: T<num>C<diameter> or with optional parameters
    // Order varies: T1C0.8 or T1F200S500C0.8 etc.
    private static final Pattern TOOL_DEF = Pattern.compile(
        "T(\\d+).*?C([\\d.]+)");

    // Pattern for tool selection: T<num>
    private static final Pattern TOOL_SELECT = Pattern.compile("^T(\\d+)$");

    // Pattern for coordinate: X<coord>Y<coord> or just X<coord> or Y<coord>
    private static final Pattern COORDINATE = Pattern.compile(
        "^(?:X([+-]?[\\d.]+))?(?:Y([+-]?[\\d.]+))?$");

    // Pattern for slot (G85): X<start_x>Y<start_y>G85X<end_x>Y<end_y>
    private static final Pattern SLOT = Pattern.compile(
        "X([+-]?[\\d.]+)?Y([+-]?[\\d.]+)?G85X([+-]?[\\d.]+)?Y([+-]?[\\d.]+)?");

    // Format specification patterns
    private static final Pattern FORMAT_METRIC = Pattern.compile("^METRIC[,\\s]*(LZ|TZ)?");
    private static final Pattern FORMAT_INCH = Pattern.compile("^INCH[,\\s]*(LZ|TZ)?");
    private static final Pattern FORMAT_FMAT = Pattern.compile("^FMAT,?(\\d)");
    // Format spec like 2.4 or %2.4 - must be standalone on the line
    private static final Pattern FORMAT_SPEC = Pattern.compile("^[%]?(\\d)\\.(\\d)$");

    public DrillDocument parse(String content) {
        document = new DrillDocument();
        currentTool = null;
        currentX = 0;
        currentY = 0;
        inHeader = true;

        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            parseLine(line);
        }

        return document;
    }

    private void parseLine(String line) {
        // Handle comments
        if (line.startsWith(";")) {
            document.addComment(line.substring(1).trim());
            return;
        }

        // Handle end of header marker
        if (line.equals("%")) {
            inHeader = false;
            return;
        }

        // Handle M codes
        if (line.startsWith("M")) {
            handleMCode(line);
            return;
        }

        // Handle G codes
        if (line.startsWith("G")) {
            handleGCode(line);
            return;
        }

        // Handle header commands
        if (inHeader) {
            if (parseHeaderCommand(line)) {
                return;
            }
        }

        // Handle tool definition in header
        Matcher toolDefMatcher = TOOL_DEF.matcher(line);
        if (toolDefMatcher.find()) {
            int toolNum = Integer.parseInt(toolDefMatcher.group(1));
            double diameter = Double.parseDouble(toolDefMatcher.group(2));
            Tool tool = new Tool(toolNum, diameter);
            document.addTool(tool);
            return;
        }

        // Handle tool selection
        Matcher toolSelectMatcher = TOOL_SELECT.matcher(line);
        if (toolSelectMatcher.matches()) {
            int toolNum = Integer.parseInt(toolSelectMatcher.group(1));
            currentTool = document.getTool(toolNum);
            return;
        }

        // Handle slot command
        Matcher slotMatcher = SLOT.matcher(line);
        if (slotMatcher.matches()) {
            handleSlot(slotMatcher);
            return;
        }

        // Handle coordinate (drill hit)
        Matcher coordMatcher = COORDINATE.matcher(line);
        if (coordMatcher.matches()) {
            handleCoordinate(coordMatcher);
            return;
        }
    }

    private boolean parseHeaderCommand(String line) {
        // Metric format
        Matcher metricMatcher = FORMAT_METRIC.matcher(line);
        if (metricMatcher.find()) {
            document.setUnit(Unit.MM);
            if (metricMatcher.group(1) != null) {
                document.setLeadingZeros(metricMatcher.group(1).equals("LZ"));
            }
            return true;
        }

        // Inch format
        Matcher inchMatcher = FORMAT_INCH.matcher(line);
        if (inchMatcher.find()) {
            document.setUnit(Unit.INCH);
            if (inchMatcher.group(1) != null) {
                document.setLeadingZeros(inchMatcher.group(1).equals("LZ"));
            }
            return true;
        }

        // FMAT (format version)
        Matcher fmatMatcher = FORMAT_FMAT.matcher(line);
        if (fmatMatcher.find()) {
            // FMAT,2 is the most common format
            return true;
        }

        // Format specification like %2.4 or 2.4
        Matcher formatMatcher = FORMAT_SPEC.matcher(line);
        if (formatMatcher.find()) {
            document.setIntegerDigits(Integer.parseInt(formatMatcher.group(1)));
            document.setDecimalDigits(Integer.parseInt(formatMatcher.group(2)));
            return true;
        }

        // ICI - Incremental input
        if (line.equals("ICI,ON") || line.equals("ICI")) {
            document.setCoordinateMode(CoordinateMode.INCREMENTAL);
            return true;
        }

        if (line.equals("ICI,OFF")) {
            document.setCoordinateMode(CoordinateMode.ABSOLUTE);
            return true;
        }

        return false;
    }

    private void handleMCode(String line) {
        if (line.startsWith("M48")) {
            // Start of header
            inHeader = true;
        } else if (line.startsWith("M95") || line.equals("%")) {
            // End of header / start of program
            inHeader = false;
        } else if (line.startsWith("M30") || line.startsWith("M00")) {
            // End of program
            inHeader = false;
        } else if (line.startsWith("M71")) {
            // Metric mode
            document.setUnit(Unit.MM);
        } else if (line.startsWith("M72")) {
            // Inch mode
            document.setUnit(Unit.INCH);
        }
    }

    private void handleGCode(String line) {
        if (line.startsWith("G90")) {
            document.setCoordinateMode(CoordinateMode.ABSOLUTE);
        } else if (line.startsWith("G91")) {
            document.setCoordinateMode(CoordinateMode.INCREMENTAL);
        } else if (line.startsWith("G05")) {
            // Drill mode (default)
        } else if (line.startsWith("G85")) {
            // Slot mode - will be handled by coordinate parser
        }
    }

    private void handleCoordinate(Matcher matcher) {
        if (currentTool == null) {
            return; // No tool selected
        }

        String xStr = matcher.group(1);
        String yStr = matcher.group(2);

        double x = xStr != null ? parseCoordinate(xStr) : currentX;
        double y = yStr != null ? parseCoordinate(yStr) : currentY;

        if (document.getCoordinateMode() == CoordinateMode.INCREMENTAL) {
            x = currentX + x;
            y = currentY + y;
        }

        DrillHit hit = new DrillHit(currentTool, x, y);
        document.addOperation(hit);

        currentX = x;
        currentY = y;
    }

    private void handleSlot(Matcher matcher) {
        if (currentTool == null) {
            return; // No tool selected
        }

        String startXStr = matcher.group(1);
        String startYStr = matcher.group(2);
        String endXStr = matcher.group(3);
        String endYStr = matcher.group(4);

        double startX = startXStr != null ? parseCoordinate(startXStr) : currentX;
        double startY = startYStr != null ? parseCoordinate(startYStr) : currentY;
        double endX = endXStr != null ? parseCoordinate(endXStr) : startX;
        double endY = endYStr != null ? parseCoordinate(endYStr) : startY;

        if (document.getCoordinateMode() == CoordinateMode.INCREMENTAL) {
            startX = currentX + startX;
            startY = currentY + startY;
            endX = startX + (endXStr != null ? parseCoordinate(endXStr) : 0);
            endY = startY + (endYStr != null ? parseCoordinate(endYStr) : 0);
        }

        DrillSlot slot = new DrillSlot(currentTool, startX, startY, endX, endY);
        document.addOperation(slot);

        currentX = endX;
        currentY = endY;
    }

    private double parseCoordinate(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }

        // If the value contains a decimal point, parse directly
        if (value.contains(".")) {
            return Double.parseDouble(value);
        }

        // Otherwise, use the document's format settings
        return document.parseCoordinate(value);
    }
}
