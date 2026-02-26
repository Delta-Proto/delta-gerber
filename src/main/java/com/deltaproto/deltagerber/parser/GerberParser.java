package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.lexer.GerberLexer;
import com.deltaproto.deltagerber.lexer.Token;
import com.deltaproto.deltagerber.lexer.TokenType;
import com.deltaproto.deltagerber.model.gerber.*;
import com.deltaproto.deltagerber.model.gerber.aperture.*;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroTemplate;
import com.deltaproto.deltagerber.model.gerber.attribute.FileAttribute;
import com.deltaproto.deltagerber.model.gerber.operation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Gerber files.
 */
public class GerberParser {

    private static final Logger log = LoggerFactory.getLogger(GerberParser.class);

    private GerberDocument document;
    private CoordinateFormat coordFormat;
    private Unit unit = Unit.MM;

    // Graphics state
    private double currentX = 0;
    private double currentY = 0;
    private Aperture currentAperture;
    private Polarity currentPolarity = Polarity.DARK;
    private boolean linearMode = true;
    private boolean clockwise = true;
    private boolean multiQuadrant = true;
    private boolean inRegion = false;
    private Region currentRegion;
    private Contour currentContour;

    // Aperture transformation state (LR, LS, LM)
    private double loadRotation = 0;       // Rotation in degrees
    private double loadScaling = 1.0;      // Scale factor
    private boolean loadMirrorX = false;   // Mirror X axis
    private boolean loadMirrorY = false;   // Mirror Y axis

    // Modal D-code: Gerber D-codes are modal — a coordinate without an explicit
    // D-code reuses the last active D-code (D01, D02, or D03).
    private TokenType lastDCode = null;

    private static final Pattern COORD_X = Pattern.compile("X([+-]?\\d+)");
    private static final Pattern COORD_Y = Pattern.compile("Y([+-]?\\d+)");
    private static final Pattern COORD_I = Pattern.compile("I([+-]?\\d+)");
    private static final Pattern COORD_J = Pattern.compile("J([+-]?\\d+)");

    public GerberDocument parse(String content) {
        long startTime = System.currentTimeMillis();
        log.trace("Starting Gerber parse, content length: {} chars", content.length());

        document = new GerberDocument();
        GerberLexer lexer = new GerberLexer();

        long lexStart = System.currentTimeMillis();
        List<Token> tokens = lexer.tokenize(content);
        log.trace("Lexer produced {} tokens in {}ms", tokens.size(), System.currentTimeMillis() - lexStart);

        long parseStart = System.currentTimeMillis();
        for (Token token : tokens) {
            processToken(token);
        }
        log.trace("Token processing took {}ms", System.currentTimeMillis() - parseStart);

        // All coordinates and dimensions have been normalized to mm during parsing.
        // Set the document unit to MM so downstream code knows the data is in mm.
        document.setUnit(Unit.MM);

        log.trace("Gerber parse complete in {}ms: {} objects, {} apertures",
            System.currentTimeMillis() - startTime, document.getObjects().size(), document.getApertures().size());

        return document;
    }

    private void processToken(Token token) {
        switch (token.getType()) {
            case FORMAT_SPEC -> parseFormatSpec(token);
            case UNIT -> parseUnit(token);
            case APERTURE_DEFINE -> parseApertureDefine(token);
            case APERTURE_MACRO -> parseApertureMacro(token);
            case APERTURE_SELECT -> parseApertureSelect(token);
            case FILE_ATTRIBUTE -> parseFileAttribute(token);
            case POLARITY -> parsePolarity(token);
            case LOAD_ROTATION -> parseLoadRotation(token);
            case LOAD_SCALING -> parseLoadScaling(token);
            case LOAD_MIRRORING -> parseLoadMirroring(token);
            case COORDINATE -> {
                // If there are already pending coordinates without an explicit D-code,
                // execute the modal (last active) D-code before parsing the new coordinate.
                if (hasPendingCoordinates() && lastDCode != null) {
                    executeModalDCode();
                }
                parseCoordinate(token);
            }
            case D01 -> { lastDCode = TokenType.D01; executeD01(); }
            case D02 -> { lastDCode = TokenType.D02; executeD02(); }
            case D03 -> { lastDCode = TokenType.D03; executeD03(); }
            case G01 -> linearMode = true;
            case G02 -> { linearMode = false; clockwise = true; }
            case G03 -> { linearMode = false; clockwise = false; }
            case G74 -> multiQuadrant = false;
            case G75 -> multiQuadrant = true;
            case G36 -> startRegion();
            case G37 -> endRegion();
            default -> { /* Ignore */ }
        }
    }

    private boolean hasPendingCoordinates() {
        return !Double.isNaN(pendingX) || !Double.isNaN(pendingY) ||
               !Double.isNaN(pendingI) || !Double.isNaN(pendingJ);
    }

    private void executeModalDCode() {
        switch (lastDCode) {
            case D01 -> executeD01();
            case D02 -> executeD02();
            case D03 -> executeD03();
            default -> clearPending();
        }
    }

    private void parseFormatSpec(Token token) {
        String content = token.getContent();
        // Format: FS[LT][AI]X<n><m>Y<n><m>
        Pattern pattern = Pattern.compile("FS([LT])([AI])X(\\d)(\\d)Y(\\d)(\\d)");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            boolean leadingZeroOmitted = matcher.group(1).equals("L");
            boolean absolute = matcher.group(2).equals("A");
            int intDigits = Integer.parseInt(matcher.group(3));
            int decDigits = Integer.parseInt(matcher.group(4));
            coordFormat = new CoordinateFormat(intDigits, decDigits, leadingZeroOmitted, absolute);
            document.setCoordinateFormat(coordFormat);
        }
    }

    private void parseUnit(Token token) {
        String content = token.getContent();
        if (content.contains("MM")) {
            unit = Unit.MM;
        } else if (content.contains("IN")) {
            unit = Unit.INCH;
        }
        document.setUnit(unit);
    }

    private void parseApertureMacro(Token token) {
        String content = token.getContent();
        // Format: AM<name>*<body>
        // Remove leading AM
        if (content.startsWith("AM")) {
            content = content.substring(2);
        }

        // Find the first * which separates name from body
        int starIndex = content.indexOf('*');
        if (starIndex == -1) {
            // Simple macro with no body yet (rare but possible)
            String name = content;
            MacroTemplate template = new MacroTemplate(name);
            document.addMacroTemplate(template);
            return;
        }

        String name = content.substring(0, starIndex);
        String body = content.substring(starIndex + 1);

        MacroTemplate template = new MacroTemplate(name);
        template.parse(body);
        document.addMacroTemplate(template);
    }

    private void parseApertureDefine(Token token) {
        String content = token.getContent();
        // Format: ADD<dcode><template>,<params>
        // Template can be C, R, O, P for standard (must be single letter followed by comma or end)
        // or a macro name (multiple characters)
        Pattern standardPattern = Pattern.compile("ADD(\\d+)([CROP])(?:,(.*))?$");
        Matcher standardMatcher = standardPattern.matcher(content);
        if (standardMatcher.find()) {
            int dCode = Integer.parseInt(standardMatcher.group(1));
            String template = standardMatcher.group(2);
            String params = standardMatcher.group(3) != null ? standardMatcher.group(3) : "";

            Aperture aperture = createAperture(dCode, template, params);
            if (aperture != null) {
                document.addAperture(aperture);
            }
            return;
        }

        // Try macro aperture: ADD<dcode><macroname>,<params>
        Pattern macroPattern = Pattern.compile("ADD(\\d+)([A-Za-z_][A-Za-z0-9_]*)(?:,(.*))?$");
        Matcher macroMatcher = macroPattern.matcher(content);
        if (macroMatcher.find()) {
            int dCode = Integer.parseInt(macroMatcher.group(1));
            String macroName = macroMatcher.group(2);
            String params = macroMatcher.group(3) != null ? macroMatcher.group(3) : "";

            MacroTemplate template = document.getMacroTemplate(macroName);
            if (template != null) {
                List<Double> paramValues = parseApertureParams(params);
                Aperture aperture = new MacroAperture(dCode, template, paramValues, unit.toMm(1.0));
                document.addAperture(aperture);
            }
        }
    }

    private List<Double> parseApertureParams(String params) {
        List<Double> values = new ArrayList<>();
        if (params == null || params.isEmpty()) {
            return values;
        }
        String[] parts = params.split("X");
        for (String part : parts) {
            if (!part.isEmpty()) {
                try {
                    values.add(Double.parseDouble(part));
                } catch (NumberFormatException e) {
                    // Skip invalid values
                }
            }
        }
        return values;
    }

    private Aperture createAperture(int dCode, String template, String params) {
        String[] parts = params.split("X");
        double[] raw = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            raw[i] = parts[i].isEmpty() ? 0 : Double.parseDouble(parts[i]);
        }
        double f = unit.toMm(1.0);

        // All dimensional values (diameter, width, height, hole) are converted to mm.
        // Non-dimensional values (vertex count, rotation in degrees) are kept as-is.
        return switch (template) {
            case "C" -> raw.length >= 2 ?
                new CircleAperture(dCode, raw[0] * f, raw[1] * f) :
                new CircleAperture(dCode, raw[0] * f);
            case "R" -> raw.length >= 3 ?
                new RectangleAperture(dCode, raw[0] * f, raw[1] * f, raw[2] * f) :
                new RectangleAperture(dCode, raw[0] * f, raw[1] * f);
            case "O" -> raw.length >= 3 ?
                new ObroundAperture(dCode, raw[0] * f, raw[1] * f, raw[2] * f) :
                new ObroundAperture(dCode, raw[0] * f, raw[1] * f);
            case "P" -> raw.length >= 4 ?
                new PolygonAperture(dCode, raw[0] * f, (int) raw[1], raw[2], raw[3] * f) :
                raw.length >= 3 ?
                    new PolygonAperture(dCode, raw[0] * f, (int) raw[1], raw[2]) :
                    new PolygonAperture(dCode, raw[0] * f, (int) raw[1]);
            default -> null;
        };
    }

    private void parseApertureSelect(Token token) {
        String content = token.getContent();
        int dCode = Integer.parseInt(content.substring(1));
        currentAperture = document.getAperture(dCode);
    }

    private void parseFileAttribute(Token token) {
        String content = token.getContent();
        if (content.startsWith("TF.")) {
            content = content.substring(3);
        } else if (content.startsWith("TF")) {
            content = content.substring(2);
        }
        if (content.startsWith(".")) {
            content = content.substring(1);
        }

        String[] parts = content.split(",");
        if (parts.length > 0) {
            String name = parts[0];
            List<String> values = parts.length > 1 ?
                Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length)) :
                Collections.emptyList();
            document.addFileAttribute(new FileAttribute("." + name, values));
        }
    }

    private void parsePolarity(Token token) {
        String content = token.getContent();
        if (content.contains("D")) {
            currentPolarity = Polarity.DARK;
        } else if (content.contains("C")) {
            currentPolarity = Polarity.CLEAR;
        }
    }

    private void parseLoadRotation(Token token) {
        // Format: LR<angle> e.g., LR45 or LR-90
        String content = token.getContent();
        if (content.startsWith("LR")) {
            try {
                loadRotation = Double.parseDouble(content.substring(2));
            } catch (NumberFormatException e) {
                loadRotation = 0;
            }
        }
    }

    private void parseLoadScaling(Token token) {
        // Format: LS<factor> e.g., LS1.5
        String content = token.getContent();
        if (content.startsWith("LS")) {
            try {
                loadScaling = Double.parseDouble(content.substring(2));
            } catch (NumberFormatException e) {
                loadScaling = 1.0;
            }
        }
    }

    private void parseLoadMirroring(Token token) {
        // Format: LM<mode> where mode is N (none), X, Y, or XY
        String content = token.getContent();
        if (content.startsWith("LM")) {
            String mode = content.substring(2).toUpperCase();
            loadMirrorX = mode.contains("X");
            loadMirrorY = mode.contains("Y");
        }
    }

    private double pendingX = Double.NaN;
    private double pendingY = Double.NaN;
    private double pendingI = Double.NaN;
    private double pendingJ = Double.NaN;

    private void parseCoordinate(Token token) {
        String content = token.getContent();
        double f = unit.toMm(1.0);

        Matcher xm = COORD_X.matcher(content);
        if (xm.find()) {
            pendingX = coordFormat.parseCoordinate(xm.group(1)) * f;
        }

        Matcher ym = COORD_Y.matcher(content);
        if (ym.find()) {
            pendingY = coordFormat.parseCoordinate(ym.group(1)) * f;
        }

        Matcher im = COORD_I.matcher(content);
        if (im.find()) {
            pendingI = coordFormat.parseCoordinate(im.group(1)) * f;
        }

        Matcher jm = COORD_J.matcher(content);
        if (jm.find()) {
            pendingJ = coordFormat.parseCoordinate(jm.group(1)) * f;
        }
    }

    private void executeD01() {
        double newX = Double.isNaN(pendingX) ? currentX : pendingX;
        double newY = Double.isNaN(pendingY) ? currentY : pendingY;

        if (inRegion) {
            if (currentContour == null) {
                currentContour = new Contour(currentX, currentY);
            }
            if (linearMode) {
                currentContour.addLineTo(newX, newY);
            } else {
                double centerX = currentX + (Double.isNaN(pendingI) ? 0 : pendingI);
                double centerY = currentY + (Double.isNaN(pendingJ) ? 0 : pendingJ);
                currentContour.addArcTo(newX, newY, centerX, centerY, clockwise);
            }
        } else if (currentAperture != null) {
            GraphicsObject obj;
            if (linearMode) {
                obj = new Draw(currentX, currentY, newX, newY, currentAperture);
            } else {
                double centerX = currentX + (Double.isNaN(pendingI) ? 0 : pendingI);
                double centerY = currentY + (Double.isNaN(pendingJ) ? 0 : pendingJ);
                obj = new Arc(currentX, currentY, newX, newY, centerX, centerY, clockwise, currentAperture);
            }
            obj.setPolarity(currentPolarity);
            document.addObject(obj);
        }

        currentX = newX;
        currentY = newY;
        clearPending();
    }

    private void executeD02() {
        double newX = Double.isNaN(pendingX) ? currentX : pendingX;
        double newY = Double.isNaN(pendingY) ? currentY : pendingY;

        if (inRegion && currentContour != null) {
            currentRegion.addContour(currentContour);
            currentContour = new Contour(newX, newY);
        }

        currentX = newX;
        currentY = newY;
        clearPending();
    }

    private void executeD03() {
        double newX = Double.isNaN(pendingX) ? currentX : pendingX;
        double newY = Double.isNaN(pendingY) ? currentY : pendingY;

        if (currentAperture != null && !inRegion) {
            Flash flash = new Flash(newX, newY, currentAperture, loadRotation, loadScaling, loadMirrorX, loadMirrorY);
            flash.setPolarity(currentPolarity);
            document.addObject(flash);
        }

        currentX = newX;
        currentY = newY;
        clearPending();
    }

    private void clearPending() {
        pendingX = Double.NaN;
        pendingY = Double.NaN;
        pendingI = Double.NaN;
        pendingJ = Double.NaN;
    }

    private void startRegion() {
        inRegion = true;
        currentRegion = new Region();
        currentRegion.setPolarity(currentPolarity);
        currentContour = null;
    }

    private void endRegion() {
        if (currentContour != null) {
            currentRegion.addContour(currentContour);
        }
        if (currentRegion != null && !currentRegion.getContours().isEmpty()) {
            document.addObject(currentRegion);
        }
        inRegion = false;
        currentRegion = null;
        currentContour = null;
    }
}
