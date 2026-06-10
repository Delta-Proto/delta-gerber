package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.model.drill.*;
import com.deltaproto.deltagerber.model.gerber.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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

    // Cadence Allegro holesize-based tool tracking
    private final List<Tool> holesizeTools = new ArrayList<>();
    private int holesizeToolIndex = 0;
    private boolean holesizeMetric = false;

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

    // Repeat code: R<count>X<offset>Y<offset> (Cadence Allegro)
    private static final Pattern REPEAT_CODE = Pattern.compile(
        "^R(\\d+)(?:X([+-]?[\\d.]+))?(?:Y([+-]?[\\d.]+))?$");

    // Holesize comment: ;   Holesize N. = X.XXXXXX ... PLATED|NON_PLATED MM|INCH Quantity = N
    private static final Pattern HOLESIZE_COMMENT = Pattern.compile(
        "Holesize\\s+(\\d+)\\.\\s*=\\s*([\\d.]+).*?(PLATED|NON_PLATED)\\s+(MM|INCH)\\s+Quantity\\s*=\\s*(\\d+)");

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

        // Strip UTF-8 BOM if present
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

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
        holesizeTools.clear();
        holesizeToolIndex = 0;
        holesizeMetric = false;

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
            // Check for Holesize comment (Cadence Allegro format)
            Matcher holesizeMatcher = HOLESIZE_COMMENT.matcher(comment);
            if (holesizeMatcher.find()) {
                int toolNum = Integer.parseInt(holesizeMatcher.group(1));
                double diameter = safeParseDouble(holesizeMatcher.group(2), 0, "Holesize T" + toolNum + " diameter");
                String unitStr = holesizeMatcher.group(4);
                if ("INCH".equals(unitStr)) {
                    diameter = Unit.INCH.toMm(diameter);
                } else {
                    holesizeMetric = true;
                }
                Tool tool = new Tool(toolNum, diameter);
                document.addTool(tool);
                holesizeTools.add(tool);
                log.trace("Holesize tool T{}: {}mm", toolNum, diameter);
            }
            return;
        }

        // Handle end of header marker
        if (line.equals("%")) {
            inHeader = false;
            // If tools were defined via Holesize comments (Cadence Allegro),
            // set up format and select the first tool
            if (!holesizeTools.isEmpty() && currentTool == null) {
                if (holesizeMetric && !explicitFormatSet) {
                    document.setUnit(Unit.MM);
                    document.setIntegerDigits(3);
                    document.setDecimalDigits(5);
                    document.setLeadingZeros(true);
                }
                holesizeToolIndex = 0;
                currentTool = holesizeTools.get(0);
                log.trace("Cadence Allegro mode: selected first holesize tool T{}", currentTool.getNumber());
            }
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
            double rawDia = safeParseDouble(toolDefMatcher.group(2), 0, "tool T" + toolNum + " diameter");
            Tool tool = new Tool(toolNum, document.getUnit().toMm(rawDia));
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

        // Handle repeat code (Cadence Allegro: R<count>X<offset>Y<offset>)
        Matcher repeatMatcher = REPEAT_CODE.matcher(line);
        if (repeatMatcher.matches()) {
            handleRepeatCode(repeatMatcher);
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
        } else if (line.startsWith("M30")) {
            // End of program
            inHeader = false;
        } else if (line.startsWith("M00")) {
            // Tool change separator (Cadence Allegro) or program stop
            inHeader = false;
            if (!holesizeTools.isEmpty()) {
                holesizeToolIndex++;
                if (holesizeToolIndex < holesizeTools.size()) {
                    currentTool = holesizeTools.get(holesizeToolIndex);
                    log.trace("M00: advanced to holesize tool T{}", currentTool.getNumber());
                } else {
                    log.trace("M00: no more holesize tools");
                    currentTool = null;
                }
            }
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
        } else if (line.startsWith("G02")) {
            // Clockwise arc routing
            interpolationMode = InterpolationMode.CW_ARC;
            parseGCodeWithCoordinates(line.substring(3));
        } else if (line.startsWith("G03")) {
            // Counter-clockwise arc routing
            interpolationMode = InterpolationMode.CCW_ARC;
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
            document.addWarning("Drill coordinate seen before any tool was selected — hit(s) skipped");
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
            document.addWarning("Slot (G85) seen before any tool was selected — slot skipped");
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

    private void handleRepeatCode(Matcher matcher) {
        if (currentTool == null) {
            return;
        }

        int count = Integer.parseInt(matcher.group(1));
        if (count > MAX_REPEAT) {
            document.addWarning("Repeat count " + count + " exceeds limit " + MAX_REPEAT + " — clamped");
            count = MAX_REPEAT;
        }
        String xOffStr = matcher.group(2);
        String yOffStr = matcher.group(3);

        double xOffset = xOffStr != null ? parseCoordinate(xOffStr) : 0;
        double yOffset = yOffStr != null ? parseCoordinate(yOffStr) : 0;

        for (int i = 0; i < count; i++) {
            currentX += xOffset;
            currentY += yOffset;
            DrillHit hit = new DrillHit(currentTool, currentX, currentY);
            document.addOperation(hit);
        }
    }

    // A Cadence Allegro R<count> repeat replays a hit count times; a corrupt huge count
    // would balloon the operation list. Cap it and warn.
    private static final int MAX_REPEAT = 1_000_000;

    /**
     * Parse a double, rejecting non-finite results (NaN/Infinity) and malformed text.
     * Records a warning and returns {@code fallback} on failure rather than throwing —
     * a single bad token shouldn't abort parsing of the whole file.
     */
    private double safeParseDouble(String s, double fallback, String context) {
        try {
            double v = Double.parseDouble(s);
            if (!Double.isFinite(v)) {
                document.addWarning("Non-finite value '" + s + "' in " + context + " — using " + fallback);
                return fallback;
            }
            return v;
        } catch (NumberFormatException e) {
            document.addWarning("Invalid number '" + s + "' in " + context + " — using " + fallback);
            return fallback;
        }
    }

    private double parseCoordinate(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }

        double parsed;
        try {
            // If the value contains a decimal point, parse directly
            if (value.contains(".")) {
                parsed = Double.parseDouble(value);
            } else {
                // Otherwise, use the document's format settings
                parsed = document.parseCoordinate(value);
            }
        } catch (NumberFormatException e) {
            document.addWarning("Invalid drill coordinate '" + value + "' — treated as 0");
            return 0;
        }

        if (!Double.isFinite(parsed)) {
            document.addWarning("Non-finite drill coordinate '" + value + "' — treated as 0");
            return 0;
        }

        // Normalize to mm
        return document.getUnit().toMm(parsed);
    }
}
