package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.model.drill.*;
import com.deltaproto.deltagerber.model.gerber.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Excellon NC drill files.
 */
public class ExcellonParser {

    private static final Logger log = LoggerFactory.getLogger(ExcellonParser.class);

    private DrillDocument document;
    private Tool currentTool;
    private double currentX = 0;
    private double currentY = 0;
    private boolean inHeader = true;
    private boolean inRoutingMode = false;
    private boolean explicitFormatSet = false;
    private double routeStartX = 0;
    private double routeStartY = 0;
    private InterpolationMode interpolationMode = InterpolationMode.LINEAR;

    private enum InterpolationMode {
        LINEAR,      // G01
        RAPID,       // G00
        CW_ARC,      // G02
        CCW_ARC      // G03
    }

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
    // FILE_FORMAT comment (e.g. ;FILE_FORMAT=4:4) - used by Altium and others
    private static final Pattern FILE_FORMAT_COMMENT = Pattern.compile("FILE_FORMAT\\s*=\\s*(\\d):(\\d)");

    public DrillDocument parse(String content) {
        long startTime = System.currentTimeMillis();
        log.trace("Starting Excellon parse, content length: {} chars", content.length());

        document = new DrillDocument();
        currentTool = null;
        currentX = 0;
        currentY = 0;
        inHeader = true;
        inRoutingMode = false;
        routeStartX = 0;
        routeStartY = 0;
        interpolationMode = InterpolationMode.LINEAR;
        explicitFormatSet = false;

        String[] lines = content.split("\n");
        log.trace("Processing {} lines", lines.length);

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            parseLine(line);
        }

        // All coordinates and tool diameters have been normalized to mm during parsing
        document.setUnit(Unit.MM);

        log.trace("Excellon parse complete in {}ms: {} operations, {} tools",
            System.currentTimeMillis() - startTime, document.getOperations().size(), document.getTools().size());

        return document;
    }

    private void parseLine(String line) {
        // Handle comments
        if (line.startsWith(";")) {
            String comment = line.substring(1).trim();
            document.addComment(comment);
            // Check for FILE_FORMAT comment (e.g. ;FILE_FORMAT=4:4)
            Matcher fileFormatMatcher = FILE_FORMAT_COMMENT.matcher(comment);
            if (fileFormatMatcher.find()) {
                document.setIntegerDigits(Integer.parseInt(fileFormatMatcher.group(1)));
                document.setDecimalDigits(Integer.parseInt(fileFormatMatcher.group(2)));
                explicitFormatSet = true;
                log.trace("Explicit FILE_FORMAT set to {}:{}", fileFormatMatcher.group(1), fileFormatMatcher.group(2));
            }
            return;
        }

        // Handle end of header marker
        if (line.equals("%")) {
            inHeader = false;
            return;
        }

        // Handle header commands first (includes METRIC, INCH which start with M/I)
        if (inHeader) {
            if (parseHeaderCommand(line)) {
                return;
            }
        }

        // Handle M codes (M48, M30, M71, M72, etc.)
        if (line.startsWith("M")) {
            handleMCode(line);
            return;
        }

        // Handle G codes
        if (line.startsWith("G")) {
            handleGCode(line);
            return;
        }

        // Handle tool definition in header
        Matcher toolDefMatcher = TOOL_DEF.matcher(line);
        if (toolDefMatcher.find()) {
            int toolNum = Integer.parseInt(toolDefMatcher.group(1));
            double diameter = document.getUnit().toMm(Double.parseDouble(toolDefMatcher.group(2)));
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
            // Metric files typically use 3.3 format (3 integer, 3 decimal digits)
            // But only set defaults if no explicit format was specified (e.g. FILE_FORMAT=4:4)
            if (!explicitFormatSet) {
                document.setIntegerDigits(3);
                document.setDecimalDigits(3);
            }
            String zeroMode = metricMatcher.group(1);
            if (zeroMode != null) {
                document.setLeadingZeros(zeroMode.equals("LZ"));
            }
            return true;
        }

        // Inch format
        Matcher inchMatcher = FORMAT_INCH.matcher(line);
        if (inchMatcher.find()) {
            document.setUnit(Unit.INCH);
            // Inch files typically use 2.4 format (2 integer, 4 decimal digits)
            // But only set defaults if no explicit format was specified
            if (!explicitFormatSet) {
                document.setIntegerDigits(2);
                document.setDecimalDigits(4);
            }
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
            // Metric mode - use 3.3 format (3 integer, 3 decimal) unless explicit format set
            document.setUnit(Unit.MM);
            if (!explicitFormatSet) {
                document.setIntegerDigits(3);
                document.setDecimalDigits(3);
            }
        } else if (line.startsWith("M72")) {
            // Inch mode - use 2.4 format (2 integer, 4 decimal) unless explicit format set
            document.setUnit(Unit.INCH);
            if (!explicitFormatSet) {
                document.setIntegerDigits(2);
                document.setDecimalDigits(4);
            }
        } else if (line.startsWith("M15")) {
            // Start of routing mode
            inRoutingMode = true;
            routeStartX = currentX;
            routeStartY = currentY;
        } else if (line.startsWith("M16") || line.startsWith("M17")) {
            // End of routing mode
            inRoutingMode = false;
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
        } else if (line.startsWith("G00")) {
            // Rapid move - parse coordinates if present
            interpolationMode = InterpolationMode.RAPID;
            parseGCodeWithCoordinates(line.substring(3));
        } else if (line.startsWith("G01")) {
            // Linear move - parse coordinates if present
            interpolationMode = InterpolationMode.LINEAR;
            parseGCodeWithCoordinates(line.substring(3));
        } else if (line.startsWith("G40")) {
            // Cutter compensation off - just ignore
        }
    }

    private void parseGCodeWithCoordinates(String remainder) {
        if (remainder == null || remainder.isEmpty()) {
            return;
        }

        // Parse X and Y coordinates from the remainder
        Matcher coordMatcher = COORDINATE.matcher(remainder);
        if (coordMatcher.matches()) {
            String xStr = coordMatcher.group(1);
            String yStr = coordMatcher.group(2);

            double x = xStr != null ? parseCoordinate(xStr) : currentX;
            double y = yStr != null ? parseCoordinate(yStr) : currentY;

            if (document.getCoordinateMode() == CoordinateMode.INCREMENTAL) {
                x = currentX + x;
                y = currentY + y;
            }

            // If in routing mode and linear interpolation, this is a slot
            if (inRoutingMode && interpolationMode == InterpolationMode.LINEAR && currentTool != null) {
                // Create slot from route start to this position
                DrillSlot slot = new DrillSlot(currentTool, routeStartX, routeStartY, x, y);
                document.addOperation(slot);
                routeStartX = x;
                routeStartY = y;
            }

            currentX = x;
            currentY = y;
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

        // If in routing mode with linear interpolation, create a slot
        if (inRoutingMode && interpolationMode == InterpolationMode.LINEAR) {
            DrillSlot slot = new DrillSlot(currentTool, routeStartX, routeStartY, x, y);
            document.addOperation(slot);
            routeStartX = x;
            routeStartY = y;
        } else if (!inRoutingMode || interpolationMode == InterpolationMode.RAPID) {
            // Either not in routing mode (drill hit) or rapid move (position update only)
            if (!inRoutingMode) {
                DrillHit hit = new DrillHit(currentTool, x, y);
                document.addOperation(hit);
            }
        }

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

        double parsed;
        // If the value contains a decimal point, parse directly
        if (value.contains(".")) {
            parsed = Double.parseDouble(value);
        } else {
            // Otherwise, use the document's format settings
            parsed = document.parseCoordinate(value);
        }

        // Normalize to mm
        return document.getUnit().toMm(parsed);
    }
}
