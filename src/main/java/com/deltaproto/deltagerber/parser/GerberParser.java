package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.lexer.GerberLexer;
import com.deltaproto.deltagerber.lexer.Token;
import com.deltaproto.deltagerber.lexer.TokenType;
import com.deltaproto.deltagerber.model.gerber.*;
import com.deltaproto.deltagerber.model.gerber.ComponentPlacement;
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

    // Component placement state (populated from %TO.*% object attributes)
    private String toRefdes;
    private String toValue;
    private String toFootprint;
    private String toMountType;
    private double toRotation;
    private boolean inComponentContext;
    private boolean hasPinAttribute;
    private boolean centroidRecorded;

    // Modal D-code: Gerber D-codes are modal — a coordinate without an explicit
    // D-code reuses the last active D-code (D01, D02, or D03).
    private TokenType lastDCode = null;

    // X2/X3 attribute dictionaries: the aperture attributes (TA) in scope, snapshotted onto each
    // AD; and the object attributes (TO) in scope, snapshotted onto each graphics object.
    private final Map<String, List<String>> apertureAttribs = new LinkedHashMap<>();
    private final Map<String, List<String>> objectAttribs = new LinkedHashMap<>();

    // Block-aperture definition stack. While non-empty, created graphics objects are captured into
    // the innermost open block instead of the document; flashing a block expands it (see emit()).
    private final Deque<BlockAperture> blockStack = new ArrayDeque<>();
    private static final Pattern AB_OPEN = Pattern.compile("ABD(\\d+)");

    private static final Pattern COORD_X = Pattern.compile("X([+-]?\\d+)");
    private static final Pattern COORD_Y = Pattern.compile("Y([+-]?\\d+)");
    private static final Pattern COORD_I = Pattern.compile("I([+-]?\\d+)");
    private static final Pattern COORD_J = Pattern.compile("J([+-]?\\d+)");

    private void resetState() {
        coordFormat = null;
        unit = Unit.MM;
        currentX = 0; currentY = 0;
        currentAperture = null;
        currentPolarity = Polarity.DARK;
        linearMode = true; clockwise = true; multiQuadrant = true;
        inRegion = false; currentRegion = null; currentContour = null;
        loadRotation = 0; loadScaling = 1.0; loadMirrorX = false; loadMirrorY = false;
        toRefdes = null; toValue = null; toFootprint = null; toMountType = null;
        toRotation = 0; inComponentContext = false; hasPinAttribute = false; centroidRecorded = false;
        lastDCode = null;
        pendingX = Double.NaN; pendingY = Double.NaN; pendingI = Double.NaN; pendingJ = Double.NaN;
        srStartIndex = -1; srRepeatX = 1; srRepeatY = 1; srStepX = 0; srStepY = 0;
        apertureAttribs.clear(); objectAttribs.clear(); blockStack.clear();
    }

    public GerberDocument parse(String content) {
        long startTime = System.currentTimeMillis();
        log.trace("Starting Gerber parse, content length: {} chars", content.length());

        // Strip UTF-8 BOM if present
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }

        resetState();
        document = new GerberDocument();
        GerberLexer lexer = new GerberLexer();

        long lexStart = System.currentTimeMillis();
        List<Token> tokens = lexer.tokenize(content);
        log.trace("Lexer produced {} tokens in {}ms", tokens.size(), System.currentTimeMillis() - lexStart);

        // Lexer-level anomalies (e.g. a truncated %...% block) surface as document warnings.
        for (String w : lexer.getWarnings()) {
            document.addWarning(w);
        }

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
            case APERTURE_ATTRIBUTE -> parseApertureAttribute(token);
            case OBJECT_ATTRIBUTE -> parseObjectAttribute(token);
            case DELETE_ATTRIBUTE -> parseDeleteAttribute(token);
            case POLARITY -> parsePolarity(token);
            case LOAD_ROTATION -> parseLoadRotation(token);
            case LOAD_SCALING -> parseLoadScaling(token);
            case LOAD_MIRRORING -> parseLoadMirroring(token);
            case IMAGE_POLARITY -> parseImagePolarity(token);
            case OFFSET -> parseOffset(token);
            case STEP_REPEAT -> parseStepRepeat(token);
            case BLOCK_APERTURE -> parseBlockAperture(token);
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
        // Standard format: FS[LT][AI]X<n><m>Y<n><m>.
        // Some EDA tools (observed: Altium Designer 25.8.1) omit the L/T zero-suppression
        // character, emitting "FSAX44Y44". Modern Gerber only uses leading-zero-omitted +
        // absolute notation, so defaulting to L/A when those flags are absent is safe and
        // matches what other Gerber viewers do.
        Pattern pattern = Pattern.compile("FS([LT]?)([AI]?)X(\\d)(\\d)Y(\\d)(\\d)");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String ltFlag = matcher.group(1);
            String aiFlag = matcher.group(2);
            boolean leadingZeroOmitted = !ltFlag.equals("T"); // default L (modern spec)
            boolean absolute = !aiFlag.equals("I");           // default A (modern spec)
            int intDigits = Integer.parseInt(matcher.group(3));
            int decDigits = Integer.parseInt(matcher.group(4));
            coordFormat = new CoordinateFormat(intDigits, decDigits, leadingZeroOmitted, absolute);
            document.setCoordinateFormat(coordFormat);
            if (ltFlag.isEmpty() || aiFlag.isEmpty()) {
                document.addWarning("Non-standard FS spec '" + content
                    + "' — missing " + (ltFlag.isEmpty() ? "zero-suppression" : "")
                    + (ltFlag.isEmpty() && aiFlag.isEmpty() ? "/" : "")
                    + (aiFlag.isEmpty() ? "notation" : "") + " flag, assuming L/A");
            }
        } else {
            document.addWarning("Failed to parse FS spec: " + content);
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
            registerAperture(aperture);
            return;
        }

        // Try non-standard aperture types (e.g., EAGLE's OC8 = octagon)
        // OC<n> = regular n-sided polygon, rotated so a flat edge is at the top
        Pattern ocPattern = Pattern.compile("ADD(\\d+)OC(\\d+),([\\d.]+)(?:\\*)?$");
        Matcher ocMatcher = ocPattern.matcher(content);
        if (ocMatcher.find()) {
            int dCode = Integer.parseInt(ocMatcher.group(1));
            int numVertices = Integer.parseInt(ocMatcher.group(2));
            double diameter = Double.parseDouble(ocMatcher.group(3)) * unit.toMm(1.0);
            // EAGLE octagons have a flat edge at the top, so rotate by half a vertex angle
            double rotation = 180.0 / numVertices;
            Aperture aperture = new PolygonAperture(dCode, diameter, numVertices, rotation);
            registerAperture(aperture);
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
                registerAperture(aperture);
            }
        }
    }

    /** Add an aperture to the document, attaching the X2/X3 aperture attributes in scope. */
    private void registerAperture(Aperture aperture) {
        if (aperture == null) {
            return;
        }
        aperture.setAttributes(apertureAttribs);
        document.addAperture(aperture);
    }

    /**
     * Parse a double from Gerber text, rejecting non-finite results (NaN/Infinity, which
     * {@link Double#parseDouble} happily accepts from literals like "NaN"/"Infinity" and which
     * then propagate silently through all downstream geometry math). On a malformed or
     * non-finite value, records a warning and returns {@code fallback}.
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

    private List<Double> parseApertureParams(String params) {
        List<Double> values = new ArrayList<>();
        if (params == null || params.isEmpty()) {
            return values;
        }
        String[] parts = params.split("X");
        for (String part : parts) {
            if (!part.isEmpty()) {
                try {
                    double v = Double.parseDouble(part);
                    if (Double.isFinite(v)) {
                        values.add(v);
                    } else {
                        document.addWarning("Non-finite macro aperture parameter '" + part + "' — skipped");
                    }
                } catch (NumberFormatException e) {
                    document.addWarning("Invalid macro aperture parameter '" + part + "' — skipped");
                }
            }
        }
        return values;
    }

    private Aperture createAperture(int dCode, String template, String params) {
        String[] parts = params.split("X");
        double[] raw = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            raw[i] = parts[i].isEmpty() ? 0 : safeParseDouble(parts[i], 0, "aperture D" + dCode + " parameters");
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
        int dCode;
        try {
            dCode = Integer.parseInt(content.substring(1));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            document.addWarning("Malformed aperture select '" + content + "' — ignored");
            return;
        }
        currentAperture = document.getAperture(dCode);
        if (currentAperture == null) {
            // Selecting an aperture that was never defined means every draw/flash that
            // follows is silently dropped (the executeD0x guards require a non-null aperture).
            // Surface it so the missing geometry is explainable.
            document.addWarning("Aperture D" + dCode + " selected but never defined — "
                + "geometry using it will not be rendered");
        }
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

    /**
     * Parse a {@code %TA%} aperture attribute and add it to the aperture attribute dictionary. The
     * attributes in scope are snapshotted onto each aperture as it is defined (see
     * {@link #registerAperture}).
     */
    private void parseApertureAttribute(Token token) {
        String content = token.getContent();
        if (content.startsWith("TA.")) content = content.substring(3);
        else if (content.startsWith("TA")) content = content.substring(2);
        if (content.startsWith(".")) content = content.substring(1);
        if (content.isEmpty()) {
            return;
        }
        int comma = content.indexOf(',');
        String name = comma >= 0 ? content.substring(0, comma) : content;
        String rest = comma >= 0 ? content.substring(comma + 1) : "";
        List<String> values = rest.isEmpty()
            ? new ArrayList<>()
            : new ArrayList<>(Arrays.asList(rest.split(",", -1)));
        String dotName = "." + name;
        apertureAttribs.put(dotName, toMmIfDimensional(dotName, values));
    }

    // Standard attributes whose values are expressed in the file's MO units (inch/mm) and must
    // therefore be normalized to mm like all other geometry: .DrillTolerance (TA) and .CHgt (TO).
    private static final Set<String> DIMENSIONAL_ATTRS = Set.of(".DrillTolerance", ".CHgt");

    /**
     * Convert the numeric values of a dimensional standard attribute from the file's native unit
     * to mm. Non-numeric trailing fields are left untouched. Returns the values unchanged for
     * non-dimensional attributes or when the file is already in mm.
     */
    private List<String> toMmIfDimensional(String dotName, List<String> values) {
        double f = unit.toMm(1.0);
        if (f == 1.0 || values.isEmpty() || !DIMENSIONAL_ATTRS.contains(dotName)) {
            return values;
        }
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            try {
                out.add(formatMm(Double.parseDouble(v.trim()) * f));
            } catch (NumberFormatException e) {
                out.add(v); // e.g. an informal label field — keep verbatim
            }
        }
        return out;
    }

    /** Compact decimal string for a mm value (trailing zeros stripped). */
    private static String formatMm(double v) {
        String s = String.format(Locale.US, "%.6f", v);
        if (s.indexOf('.') >= 0) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
        }
        return s;
    }

    private void parseObjectAttribute(Token token) {
        String content = token.getContent();
        // Strip leading "TO." or "TO" prefix
        if (content.startsWith("TO.")) content = content.substring(3);
        else if (content.startsWith("TO")) content = content.substring(2);

        int comma = content.indexOf(',');
        String key = comma >= 0 ? content.substring(0, comma) : content;
        String value = comma >= 0 ? content.substring(comma + 1) : "";

        // Maintain the general object-attribute dictionary (snapshotted onto each created object).
        List<String> values = value.isEmpty()
            ? new ArrayList<>()
            : new ArrayList<>(Arrays.asList(value.split(",", -1)));
        String dotKey = "." + key;
        objectAttribs.put(dotKey, toMmIfDimensional(dotKey, values));

        // Component-placement extraction (existing behaviour, preserved alongside the general model).
        switch (key) {
            case "C" -> {
                toRefdes = value;
                toValue = "";
                toFootprint = "";
                toMountType = "SMD";
                toRotation = 0;
                inComponentContext = true;
                hasPinAttribute = false;
                centroidRecorded = false;
            }
            case "CVal" -> toValue = value;
            case "CFtp"  -> toFootprint = value;
            case "CMnt"  -> toMountType = value;
            case "CRot"  -> {
                try { toRotation = Double.parseDouble(value); }
                catch (NumberFormatException e) { toRotation = 0; }
            }
            case "P" -> hasPinAttribute = true;
        }
    }

    private void parseDeleteAttribute(Token token) {
        // %TD% deletes all aperture+object attributes from the dictionaries; %TD.Name% deletes one.
        // File attributes are never affected (spec §5.5).
        String content = token.getContent();
        String name = content.length() > 2 ? content.substring(2) : "";
        if (name.isEmpty()) {
            apertureAttribs.clear();
            objectAttribs.clear();
        } else {
            apertureAttribs.remove(name);
            objectAttribs.remove(name);
        }

        // Any TD also ends the current component-placement context (existing behaviour).
        inComponentContext = false;
        hasPinAttribute = false;
        centroidRecorded = false;
        toRefdes = null;
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

    // Step and Repeat state
    private int srStartIndex = -1;
    private int srRepeatX = 1, srRepeatY = 1;
    private double srStepX = 0, srStepY = 0;

    private void parseImagePolarity(Token token) {
        String content = token.getContent();
        // %IPPOS*% (default) or %IPNEG*% (deprecated whole-image inversion). Retain it on the
        // document; the single-layer SVGRenderer inverts the image for the negative case.
        if (content.contains("NEG")) {
            document.setImagePolarity(ImagePolarity.NEGATIVE);
        } else {
            document.setImagePolarity(ImagePolarity.POSITIVE);
        }
    }

    private void parseOffset(Token token) {
        String content = token.getContent();
        // %OFA<x>B<y>*% — image offset, usually zero
        // Parse but ignore non-zero values with a warning
        Pattern pattern = Pattern.compile("OFA([\\d.+-]+)B([\\d.+-]+)");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            double offsetA = safeParseDouble(matcher.group(1), 0, "image offset A");
            double offsetB = safeParseDouble(matcher.group(2), 0, "image offset B");
            if (offsetA != 0 || offsetB != 0) {
                document.addWarning("Non-zero image offset detected: A=" + offsetA + " B=" + offsetB);
            }
        }
    }

    private void parseStepRepeat(Token token) {
        String content = token.getContent();
        // Close: "SR" with no parameters
        if (content.equals("SR") || !content.contains("X")) {
            if (srStartIndex >= 0) {
                List<GraphicsObject> allObjects = document.getObjects();
                List<GraphicsObject> srObjects = new ArrayList<>(
                    allObjects.subList(srStartIndex, allObjects.size()));
                for (int iy = 0; iy < srRepeatY; iy++) {
                    for (int ix = 0; ix < srRepeatX; ix++) {
                        if (ix == 0 && iy == 0) continue;
                        double offsetX = ix * srStepX;
                        double offsetY = iy * srStepY;
                        for (GraphicsObject obj : srObjects) {
                            document.addObject(obj.translate(offsetX, offsetY));
                        }
                    }
                }
                srStartIndex = -1;
            }
            return;
        }
        // Open: SRX<n>Y<n>I<step>J<step>
        Pattern pattern = Pattern.compile("SRX(\\d+)Y(\\d+)I([\\d.+-]+)J([\\d.+-]+)");
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            int rawX = Math.max(1, Integer.parseInt(matcher.group(1)));
            int rawY = Math.max(1, Integer.parseInt(matcher.group(2)));
            // A step-repeat replays the enclosed objects rawX*rawY times. A corrupt or
            // hostile SRX<huge>Y<huge> would multiply the object list into an OOM (a forked
            // test VM died with exactly this). Bound the *product*, not each axis: 10000x10000
            // clamped per-axis is still 100M replays. Real panels stay well under MAX_SR_GRID.
            if ((long) rawX * rawY > MAX_SR_GRID) {
                document.addWarning("Step-repeat grid " + rawX + "x" + rawY + " exceeds limit "
                    + MAX_SR_GRID + " replications — clamped to avoid exhausting memory");
                // Keep the smaller axis intact, clamp the larger so the product fits the budget.
                if (rawX >= rawY) {
                    rawY = Math.min(rawY, Math.max(1, MAX_SR_GRID / Math.max(1, rawX)));
                    rawX = Math.min(rawX, Math.max(1, MAX_SR_GRID / Math.max(1, rawY)));
                } else {
                    rawX = Math.min(rawX, Math.max(1, MAX_SR_GRID / Math.max(1, rawY)));
                    rawY = Math.min(rawY, Math.max(1, MAX_SR_GRID / Math.max(1, rawX)));
                }
            }
            srRepeatX = rawX;
            srRepeatY = rawY;
            double f = unit.toMm(1.0);
            srStepX = safeParseDouble(matcher.group(3), 0, "step-repeat I step") * f;
            srStepY = safeParseDouble(matcher.group(4), 0, "step-repeat J step") * f;
            srStartIndex = document.getObjects().size();
        }
    }

    // Maximum total step-repeat replications (rawX * rawY). 40k covers any realistic panel
    // (e.g. a 200x200 array) while keeping a malicious SR from ballooning the object list.
    private static final int MAX_SR_GRID = 40_000;

    private void parseBlockAperture(Token token) {
        String content = token.getContent();
        Matcher m = AB_OPEN.matcher(content);
        if (m.find()) {
            // Open: %ABDnn*% — start capturing graphics objects into a new block aperture.
            int dCode = Integer.parseInt(m.group(1));
            BlockAperture block = new BlockAperture(dCode);
            block.setAttributes(apertureAttribs);
            blockStack.push(block);
        } else {
            // Close: %AB*% — register the completed block in the aperture dictionary. If this block
            // was nested inside another, its content was already captured into the enclosing block.
            if (!blockStack.isEmpty()) {
                document.addAperture(blockStack.pop());
            } else {
                document.addWarning("Unmatched block aperture close (AB*) — ignored");
            }
        }
    }

    private double pendingX = Double.NaN;
    private double pendingY = Double.NaN;
    private double pendingI = Double.NaN;
    private double pendingJ = Double.NaN;

    private void parseCoordinate(Token token) {
        String content = token.getContent();

        // A coordinate cannot be interpreted without a format spec (FS). Some files put
        // coordinates before FS, or omit FS entirely (truncated/corrupt). Dereferencing a
        // null coordFormat here previously threw NPE, which the web layer swallowed into a
        // blank render. Warn and skip instead so the rest of the file still parses.
        if (coordFormat == null) {
            document.addWarning("Coordinate '" + content + "' appears before a format spec (FS) "
                + "— skipped. The file may be truncated or missing its %FS...% block.");
            return;
        }

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
            place(obj);
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

        if (currentAperture instanceof BlockAperture block && !inRegion) {
            // Flashing a block aperture adds the whole block at the flash point.
            expandBlock(block, newX, newY);
        } else if (currentAperture != null && !inRegion) {
            Flash flash = new Flash(newX, newY, currentAperture, loadRotation, loadScaling, loadMirrorX, loadMirrorY);
            flash.setPolarity(currentPolarity);
            place(flash);
        }

        // First D03 per component (before any %TO.P%) is the centroid.
        if (inComponentContext && !hasPinAttribute && !centroidRecorded && toRefdes != null) {
            document.addComponent(new ComponentPlacement(
                toRefdes, toValue, toFootprint, toMountType,
                newX, newY, toRotation, document.getComponentSide()));
            centroidRecorded = true;
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
            place(currentRegion);
        }
        inRegion = false;
        currentRegion = null;
        currentContour = null;
    }

    /**
     * Attach the object attributes in scope, then route the object to the document — or, while a
     * block aperture is being defined, into that block instead.
     */
    private void place(GraphicsObject obj) {
        obj.setAttributes(objectAttribs);
        emit(obj);
    }

    /** Route an already-prepared object to the document or the innermost open block. */
    private void emit(GraphicsObject obj) {
        if (blockStack.isEmpty()) {
            document.addObject(obj);
        } else {
            blockStack.peek().add(obj);
        }
    }

    /**
     * Expand a flashed block aperture: copy each of its objects to the flash point, applying the
     * active LM/LR/LS object transformation and combining the flash-time polarity (LPC toggles the
     * block's contents). The copies are emitted into the current sink (document, or the enclosing
     * block when a block is flashed from inside another).
     */
    private void expandBlock(BlockAperture block, double atX, double atY) {
        GraphicsTransform t = (loadRotation == 0 && loadScaling == 1.0 && !loadMirrorX && !loadMirrorY)
            ? GraphicsTransform.translation(atX, atY)
            : new GraphicsTransform(atX, atY, loadRotation, loadScaling, loadMirrorX, loadMirrorY);
        boolean invert = currentPolarity == Polarity.CLEAR;
        for (GraphicsObject child : block.getObjects()) {
            GraphicsObject moved = child.transform(t);
            if (invert) {
                moved.setPolarity(moved.getPolarity().inverse());
            }
            emit(moved);
        }
    }
}
