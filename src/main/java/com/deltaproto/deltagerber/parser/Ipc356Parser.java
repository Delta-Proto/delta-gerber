package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.model.netlist.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for IPC-D-356A bare-board electrical-test netlist files.
 *
 * <p>Stateful, single {@code parse(String) -> }{@link Ipc356Document} entry point, mirroring
 * {@link ExcellonParser} and {@link GerberParser}: BOM is stripped, and malformed input produces
 * non-fatal warnings collected on the document rather than thrown exceptions.
 *
 * <p>IPC-D-356A is a fixed-width, 80-column ASCII format. A record's meaning is set by the 3-digit
 * operation code in columns 1-3; a leading {@code 0} (e.g. {@code 017}, {@code 078}) marks a
 * continuation of the previous record. Coordinates and sizes are in the file's native grid —
 * 0.0001&nbsp;inch when the header declares {@code P UNITS CUST}, or 0.001&nbsp;mm when it declares
 * {@code P UNITS SI} — and are <strong>normalized to millimetres at parse time</strong>, so the
 * result shares one coordinate space with {@code GerberDocument}/{@code DrillDocument}.
 */
public class Ipc356Parser {

    private static final Logger log = LoggerFactory.getLogger(Ipc356Parser.class);

    private static final double MM_PER_INCH_COUNT = 0.0001 * 25.4; // 0.0001 inch grid -> mm
    private static final double MM_PER_MM_COUNT = 0.001;           // 0.001 mm grid -> mm

    private static final Pattern COORD_X = Pattern.compile("X([+-]?\\d+)");
    private static final Pattern COORD_Y = Pattern.compile("Y([+-]?\\d+)");

    private Ipc356Document document;
    private double scaleMm = MM_PER_INCH_COUNT; // default to CUST (inch) until P UNITS says otherwise
    private boolean unitsDeclared;
    private boolean unitsWarned;
    private boolean endReached;

    // Continuation targets — the most recent feature each continuation code attaches to.
    private TestRecord lastTestRecord;
    private Conductor lastConductor;
    private Adjacency lastAdjacency;
    private Outline lastOutline;

    // Modal coordinate state for conductor/outline chains (mm). Persists across continuation lines.
    private double modalX = Double.NaN;
    private double modalY = Double.NaN;
    private boolean coordFirst;   // next coord begins the element's first chain
    private boolean pendingNew;   // a trailing '*' marked the next coord as a new chain start

    public Ipc356Document parse(String content) {
        long start = System.currentTimeMillis();

        // Strip UTF-8 BOM if present
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }

        document = new Ipc356Document();
        scaleMm = MM_PER_INCH_COUNT;
        unitsDeclared = false;
        unitsWarned = false;
        endReached = false;
        lastTestRecord = null;
        lastConductor = null;
        lastAdjacency = null;
        lastOutline = null;

        String[] lines = content.split("\n");
        for (String raw : lines) {
            // Strip the line terminator but NOT leading spaces — columns are significant.
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank()) continue;
            try {
                parseLine(line);
            } catch (RuntimeException e) {
                // A single unexpected line must never abort the whole parse.
                document.addWarning("Skipped unparseable record: " + truncate(line) + " (" + e.getClass().getSimpleName() + ")");
            }
        }

        log.trace("IPC-356 parse complete in {}ms: {} test records, {} conductors, {} adjacencies, {} outlines",
            System.currentTimeMillis() - start, document.getTestRecords().size(), document.getConductors().size(),
            document.getAdjacencies().size(), document.getOutlines().size());

        return document;
    }

    private void parseLine(String line) {
        char c0 = line.charAt(0);
        if (c0 == 'C') { parseComment(line); return; }
        if (c0 == 'P') { parseParameter(line); return; }

        String code = line.length() >= 3 ? line.substring(0, 3) : line;

        if (endReached && isRecordCode(code)) {
            document.addWarning("Record(s) found after 999 end-of-job marker");
        }

        switch (code) {
            case "317", "327", "367", "307" -> parseTestPrimary(line, code);
            case "017", "027"               -> parseTestContinuation(line);
            case "099"                      -> parseTestPointLocation(line);
            case "088"                      -> parseSolderMaskClearance(line);
            case "378"                      -> parseConductor(line);
            case "078"                      -> parseConductorContinuation(line);
            case "379"                      -> parseAdjacency(line);
            case "079"                      -> parseAdjacencyContinuation(line);
            case "389"                      -> parseOutline(line);
            case "089"                      -> parseOutlineContinuation(line);
            case "999"                      -> endReached = true;
            // Known record types not yet modelled — record once and skip (never throw).
            case "309", "370", "070", "380", "080", "390", "090" ->
                document.addWarning("Unsupported IPC-356 record type " + code + " — skipped");
            default ->
                document.addWarning("Unknown IPC-356 operation code '" + code + "' — line skipped");
        }
    }

    private static boolean isRecordCode(String code) {
        return code.length() == 3 && Character.isDigit(code.charAt(0))
            && Character.isDigit(code.charAt(1)) && Character.isDigit(code.charAt(2));
    }

    // ------------------------------------------------------------------ headers

    private void parseComment(String line) {
        String text = line.length() > 1 ? line.substring(1).strip() : "";
        document.addComment(text);

        // Allegro emits long-net-name aliases as non-standard comments, e.g.
        //   C  NNAMEm0000 UNNAMED_2_CN2P_I277_N1
        // where the netlist records reference the alias key "m0000" (NNAME stripped).
        if (text.startsWith("NNAME")) {
            String first = firstToken(text);
            String alias = first.length() > 5 ? first.substring(5) : "";
            String full = text.substring(first.length()).strip();
            if (!alias.isEmpty() && !full.isEmpty()) {
                document.addNetNameAlias(alias, full);
                document.addWarning("Non-standard Allegro NNAME alias in comment: " + alias + " -> " + full);
            }
        }
    }

    private void parseParameter(String line) {
        String content = line.length() > 1 ? line.substring(1).strip() : "";
        if (content.isEmpty()) return;
        String keyword = firstToken(content);
        String value = content.substring(keyword.length()).strip();

        if (keyword.equals("UNITS")) {
            setUnits(value);
            document.setUnitsDeclaration(value);
        } else if (keyword.equals("JOB")) {
            document.setJob(value);
            document.putParameter(keyword, value);
        } else if (keyword.equals("VER")) {
            document.setVersion(value);
            document.putParameter(keyword, value);
        } else if (keyword.equals("IMAGE")) {
            document.addImage(value);
            document.putParameter(keyword, value);
        } else if (keyword.startsWith("NNAME")) {
            // EAGLE form: "P  NNAME1 LONG_NAME" — the netlist references the full token "NNAME1".
            // Spaced form: "P  NNAME 001 LONG_NAME" — alias is the next token.
            String alias;
            String full;
            if (keyword.length() > 5) {
                alias = keyword;
                full = value;
            } else {
                alias = firstToken(value);
                full = value.substring(alias.length()).strip();
            }
            document.addNetNameAlias(alias, full);
        } else {
            document.putParameter(keyword, value);
        }
    }

    private void setUnits(String value) {
        unitsDeclared = true;
        String v = value.toUpperCase(java.util.Locale.ROOT).strip();
        if (v.startsWith("SI")) {
            scaleMm = MM_PER_MM_COUNT;
        } else if (v.startsWith("CUST")) {
            // CUST / CUST 0 / CUST 2 = inch (0.0001"); CUST 1 = millimetre (0.001 mm).
            String variant = v.length() > 4 ? v.substring(4).strip() : "0";
            scaleMm = variant.startsWith("1") ? MM_PER_MM_COUNT : MM_PER_INCH_COUNT;
            if (variant.startsWith("2")) {
                // CUST 2 is inches *and radians*. Lengths are handled (inch); the rotation field is
                // left as the raw value — flag it so a consumer doesn't read radians as degrees.
                document.addWarning("P UNITS CUST 2 expresses angles in radians — rotation values are "
                    + "reported as the raw field, not converted to degrees");
            }
        } else {
            scaleMm = MM_PER_INCH_COUNT;
            document.addWarning("Unrecognized P UNITS value '" + value + "' — assuming CUST (inch)");
        }
    }

    /** Warn once if coordinate data is reached before any {@code P UNITS} declaration. */
    private void checkUnits() {
        if (!unitsDeclared && !unitsWarned) {
            document.addWarning("P UNITS not declared before data — assuming CUST (inch)");
            unitsWarned = true;
        }
    }

    // ------------------------------------------------------------- test records

    private void parseTestPrimary(String line, String code) {
        checkUnits();

        String rawNet = col(line, 4, 17).strip();
        boolean connected = true;
        String resolvedNet;
        // "N/C" is an explicit isolated point; a blank net field (e.g. a tooling hole) likewise has
        // no net — neither is a connection.
        if (rawNet.equals("N/C") || rawNet.isEmpty()) {
            connected = false;
            resolvedNet = null;
        } else {
            resolvedNet = document.resolveNet(rawNet);
        }

        TestRecord.Builder b = TestRecord.builder(code)
            .net(resolvedNet, rawNet, connected);

        String refDes = col(line, 21, 26).strip();
        if (refDes.equals("VIA")) {
            b.via(true);
        } else if (!refDes.isEmpty()) {
            b.refDes(refDes);
        }
        String pin = col(line, 28, 31).strip();
        if (!pin.isEmpty()) b.pin(pin);
        b.midNet(col(line, 32, 32).equals("M"));

        applyHole(line, b);

        if (col(line, 39, 39).equals("A")) {
            String accDigits = col(line, 40, 41).strip();
            b.access(parseInt(accDigits, -1), "A" + accDigits);
        }

        b.location(nz(rawToMm(col(line, 43, 49))), nz(rawToMm(col(line, 51, 57))));
        b.featureSize(nz(rawToMm(col(line, 59, 62))), nz(rawToMm(col(line, 64, 67))));
        if (col(line, 68, 68).equals("R")) {
            b.rotation(parseInt(col(line, 69, 71).strip(), 0));
        }
        if (col(line, 73, 73).equals("S")) {
            b.solderMask(parseInt(col(line, 74, 74).strip(), -1));
        }

        if (code.equals("307")) {
            // Blind/buried via layer span follows the solder-mask field (AN40 §307, e.g. the
            // documented "…S0L01L03" ending): start layer at cols 75-77, end layer at cols 78-80.
            int startLayer = parseLayerTag(col(line, 75, 77));
            int endLayer = parseLayerTag(col(line, 78, 80));
            b.viaLayers(startLayer, endLayer);
        }

        TestRecord record = b.build();
        document.addTestRecord(record);
        lastTestRecord = record;
    }

    /**
     * A {@code 017}/{@code 027} continuation augments the preceding feature: it supplies the
     * through-hole, the point's secondary-side access, and — for a {@code 307} blind/buried via —
     * the surface feature the via passes through.
     */
    private void parseTestContinuation(String line) {
        if (lastTestRecord == null) {
            document.addWarning("Continuation record (017/027) with no preceding test record — skipped");
            return;
        }
        // Through-hole definition (cols 33-38).
        if (col(line, 33, 33).equals("D")) {
            double dia = nz(rawToMm(col(line, 34, 37)));
            lastTestRecord.applyContinuationHole(dia, parsePlating(col(line, 38, 38)));
        }
        // Secondary-side access of the same point (cols 39-41).
        if (col(line, 39, 39).equals("A")) {
            String accDigits = col(line, 40, 41).strip();
            lastTestRecord.setSecondaryAccess(parseInt(accDigits, -1), "A" + accDigits);
        }
        // Attached surface feature location + size (cols 42-67), e.g. the pad a blind via passes through.
        double fx = rawToMm(col(line, 43, 49));
        double fy = rawToMm(col(line, 51, 57));
        if (!Double.isNaN(fx) || !Double.isNaN(fy)) {
            lastTestRecord.setAttachedFeature(nz(fx), nz(fy),
                nz(rawToMm(col(line, 59, 62))), nz(rawToMm(col(line, 64, 67))));
        }
    }

    private void parseTestPointLocation(String line) {
        if (lastTestRecord == null) {
            document.addWarning("Test-point location record (099) with no preceding test record — skipped");
            return;
        }
        lastTestRecord.setTestPointLocation(nz(rawToMm(col(line, 43, 49))), nz(rawToMm(col(line, 51, 57))));
    }

    private void parseSolderMaskClearance(String line) {
        if (lastTestRecord == null) {
            document.addWarning("Solder-mask clearance record (088) with no preceding test record — skipped");
            return;
        }
        lastTestRecord.setSolderMaskClearance(nz(rawToMm(col(line, 59, 62))), nz(rawToMm(col(line, 64, 67))));
    }

    private void applyHole(String line, TestRecord.Builder b) {
        if (col(line, 33, 33).equals("D")) {
            double dia = nz(rawToMm(col(line, 34, 37)));
            b.hole(dia, parsePlating(col(line, 38, 38)));
        }
    }

    private static Plating parsePlating(String c) {
        return switch (c) {
            case "P" -> Plating.PLATED;
            case "U" -> Plating.UNPLATED;
            default  -> Plating.UNSPECIFIED;
        };
    }

    private int parseLayerTag(String tag) {
        String t = tag.strip();
        if (t.startsWith("L") && t.length() > 1) return parseInt(t.substring(1), -1);
        return -1;
    }

    // -------------------------------------------------------------- conductors

    private void parseConductor(String line) {
        checkUnits();
        String rawNet = col(line, 4, 17).strip();
        int layer = parseLayerTag(col(line, 19, 21));

        // Columns 22+ : aperture token followed by the coordinate chain.
        String rest = (line.length() > 21 ? line.substring(21) : "").strip();
        String apertureToken = firstToken(rest);
        String coordPart = rest.substring(apertureToken.length()).strip();

        Long apX = matchVal(COORD_X, apertureToken);
        Long apY = matchVal(COORD_Y, apertureToken);
        boolean round = apY == null;
        double width = apX != null ? apX * scaleMm : 0;
        double height = round ? 0 : apY * scaleMm;

        Conductor conductor = new Conductor(document.resolveNet(rawNet), rawNet, layer, width, height, round);
        document.addConductor(conductor);
        lastConductor = conductor;

        resetCoordState();
        feedConductorCoords(coordPart, conductor);
    }

    private void parseConductorContinuation(String line) {
        if (lastConductor == null) {
            document.addWarning("Conductor continuation (078) with no preceding conductor — skipped");
            return;
        }
        String coordPart = (line.length() > 3 ? line.substring(3) : "").strip();
        feedConductorCoords(coordPart, lastConductor);
    }

    private void feedConductorCoords(String coordPart, Conductor conductor) {
        feedCoords(coordPart, conductor::startChain, conductor::addPoint);
    }

    // ----------------------------------------------------------------- outlines

    private void parseOutline(String line) {
        checkUnits();
        String type = col(line, 4, 17).strip();

        // Columns 18+ : an optional round drawing-size aperture, then the coordinate chain.
        String rest = (line.length() > 17 ? line.substring(17) : "").strip();
        double drawingWidth = 0;
        String first = firstToken(rest);
        Long apX = matchVal(COORD_X, first);
        Long apY = matchVal(COORD_Y, first);
        if (apX != null && apY == null) {
            // X-only leading token = round drawing size; consume it.
            drawingWidth = apX * scaleMm;
            rest = rest.substring(first.length()).strip();
        }

        Outline outline = new Outline(type, drawingWidth);
        document.addOutline(outline);
        lastOutline = outline;

        resetCoordState();
        feedCoords(rest, outline::startChain, outline::addPoint);
    }

    private void parseOutlineContinuation(String line) {
        if (lastOutline == null) {
            document.addWarning("Outline continuation (089) with no preceding outline — skipped");
            return;
        }
        String coordPart = (line.length() > 3 ? line.substring(3) : "").strip();
        feedCoords(coordPart, lastOutline::startChain, lastOutline::addPoint);
    }

    /**
     * Drive a modal coordinate stream into a chain builder. Tokens are separated by spaces
     * (continue the current chain) or asterisks (start a new chain); a missing X or Y repeats the
     * previous (modal) value. Modal state persists across continuation records.
     */
    private void feedCoords(String coordPart, CoordSink startChain, CoordSink addPoint) {
        if (coordPart.isEmpty()) return;
        for (String word : coordPart.split("\\s+")) {
            if (word.isEmpty()) continue;
            String[] parts = word.split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part.isEmpty()) {
                    pendingNew = true; // asterisk boundary
                    continue;
                }
                boolean newChain = coordFirst || pendingNew || i > 0;

                Long rx = matchVal(COORD_X, part);
                Long ry = matchVal(COORD_Y, part);
                if (rx == null && ry == null) {
                    document.addWarning("Unrecognized coordinate token '" + part + "' — skipped");
                    continue;
                }
                double x = rx != null ? rx * scaleMm : modalX;
                double y = ry != null ? ry * scaleMm : modalY;
                if (Double.isNaN(x) || Double.isNaN(y)) {
                    // A modal axis with no previous value — only happens on a malformed first point
                    // (the spec requires the first coordinate of a chain to be complete).
                    document.addWarning("Coordinate chain started with an incomplete point '" + part
                        + "' — missing axis treated as 0");
                    if (Double.isNaN(x)) x = 0;
                    if (Double.isNaN(y)) y = 0;
                }
                modalX = x;
                modalY = y;

                if (newChain) startChain.at(x, y); else addPoint.at(x, y);
                coordFirst = false;
                pendingNew = false;
            }
        }
    }

    private void resetCoordState() {
        coordFirst = true;
        pendingNew = false;
        modalX = Double.NaN;
        modalY = Double.NaN;
    }

    @FunctionalInterface
    private interface CoordSink {
        void at(double x, double y);
    }

    // ---------------------------------------------------------------- adjacency

    private void parseAdjacency(String line) {
        String rest = (line.length() > 3 ? line.substring(3) : "").strip();
        if (rest.isEmpty()) {
            document.addWarning("Adjacency record (379) with no net name — skipped");
            return;
        }
        String[] tokens = rest.split("\\s+");
        Adjacency adjacency = new Adjacency(document.resolveNet(tokens[0]), tokens[0]);
        for (int i = 1; i < tokens.length; i++) {
            adjacency.addAdjacentNet(document.resolveNet(tokens[i]));
        }
        document.addAdjacency(adjacency);
        lastAdjacency = adjacency;
    }

    private void parseAdjacencyContinuation(String line) {
        if (lastAdjacency == null) {
            document.addWarning("Adjacency continuation (079) with no preceding adjacency — skipped");
            return;
        }
        String rest = (line.length() > 3 ? line.substring(3) : "").strip();
        for (String token : rest.split("\\s+")) {
            if (!token.isEmpty()) lastAdjacency.addAdjacentNet(document.resolveNet(token));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** 1-based inclusive column slice, clamped to the line length (returns "" when out of range). */
    private static String col(String line, int from1, int to1) {
        int from = from1 - 1;
        if (from >= line.length()) return "";
        int to = Math.min(to1, line.length());
        if (to <= from) return "";
        return line.substring(from, to);
    }

    private static String firstToken(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static Long matchVal(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Parse a (possibly space-padded) integer digit field into mm; {@code NaN} when blank/invalid. */
    private double rawToMm(String digits) {
        String s = digits.replaceAll("\\s", "");
        if (s.isEmpty()) return Double.NaN;
        try {
            return Long.parseLong(s) * scaleMm;
        } catch (NumberFormatException e) {
            document.addWarning("Invalid IPC-356 numeric field '" + digits.strip() + "' — treated as absent");
            return Double.NaN;
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double nz(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static String truncate(String line) {
        return line.length() <= 40 ? line : line.substring(0, 40) + "…";
    }
}
