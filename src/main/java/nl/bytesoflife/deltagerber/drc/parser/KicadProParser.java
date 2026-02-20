package nl.bytesoflife.deltagerber.drc.parser;

import nl.bytesoflife.deltagerber.drc.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses KiCAD .kicad_pro project files (JSON format) to extract DRC rules
 * from the board.design_settings.rules section.
 * Used for NextPCB-style manufacturing rules that store constraints as
 * simple min values in the project file rather than as .kicad_dru S-expressions.
 */
public class KicadProParser {

    private static final Map<String, ConstraintType> RULE_MAPPINGS = Map.ofEntries(
            Map.entry("min_track_width", ConstraintType.TRACK_WIDTH),
            Map.entry("min_clearance", ConstraintType.CLEARANCE),
            Map.entry("min_through_hole_diameter", ConstraintType.HOLE_SIZE),
            Map.entry("min_hole_to_hole", ConstraintType.HOLE_TO_HOLE),
            Map.entry("min_copper_edge_clearance", ConstraintType.EDGE_CLEARANCE),
            Map.entry("min_via_annular_width", ConstraintType.ANNULAR_WIDTH),
            Map.entry("min_hole_clearance", ConstraintType.HOLE_CLEARANCE),
            Map.entry("min_text_height", ConstraintType.TEXT_HEIGHT),
            Map.entry("min_text_thickness", ConstraintType.TEXT_THICKNESS),
            Map.entry("min_silk_clearance", ConstraintType.SILK_CLEARANCE)
    );

    public DrcRuleSet parse(String content) {
        Map<String, Object> root = parseJson(content);
        return buildRuleSet(root);
    }

    public DrcRuleSet parse(InputStream is) throws IOException {
        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        return parse(content);
    }

    @SuppressWarnings("unchecked")
    private DrcRuleSet buildRuleSet(Map<String, Object> root) {
        DrcRuleSet ruleSet = new DrcRuleSet();
        ruleSet.setVersion(1);

        Map<String, Object> board = (Map<String, Object>) root.get("board");
        if (board == null) return ruleSet;

        Map<String, Object> designSettings = (Map<String, Object>) board.get("design_settings");
        if (designSettings == null) return ruleSet;

        Map<String, Object> rules = (Map<String, Object>) designSettings.get("rules");
        if (rules == null) return ruleSet;

        // Check if min_clearance is 0 — if so, use net_settings default class clearance
        double clearanceFromRules = toDouble(rules.get("min_clearance"));
        double clearanceFallback = getNetClassClearance(root);

        for (Map.Entry<String, ConstraintType> mapping : RULE_MAPPINGS.entrySet()) {
            String key = mapping.getKey();
            ConstraintType type = mapping.getValue();

            Object value = rules.get(key);
            if (value == null) continue;

            double mm = toDouble(value);

            // Use net class clearance as fallback when rules.min_clearance is 0
            if (type == ConstraintType.CLEARANCE && mm == 0.0 && clearanceFallback > 0) {
                mm = clearanceFallback;
            }

            if (mm <= 0.0) continue;

            String ruleName = formatRuleName(key);
            DrcRule rule = new DrcRule(ruleName);
            rule.addConstraint(new DrcConstraint(type, mm, null));
            ruleSet.addRule(rule);
        }

        return ruleSet;
    }

    @SuppressWarnings("unchecked")
    private double getNetClassClearance(Map<String, Object> root) {
        Map<String, Object> netSettings = (Map<String, Object>) root.get("net_settings");
        if (netSettings == null) return 0;

        List<Map<String, Object>> classes = (List<Map<String, Object>>) netSettings.get("classes");
        if (classes == null || classes.isEmpty()) return 0;

        Object clearance = classes.get(0).get("clearance");
        return clearance != null ? toDouble(clearance) : 0;
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) return Double.parseDouble(s);
        return 0;
    }

    private static String formatRuleName(String key) {
        // "min_track_width" -> "Min Track Width"
        String withoutPrefix = key.startsWith("min_") ? key.substring(4) : key;
        String[] parts = withoutPrefix.split("_");
        StringBuilder sb = new StringBuilder("Min");
        for (String part : parts) {
            sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1));
        }
        return sb.toString();
    }

    // --- Minimal JSON parser (no external dependencies) ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        JsonTokenizer tokenizer = new JsonTokenizer(json);
        Object result = parseValue(tokenizer);
        if (result instanceof Map) {
            return (Map<String, Object>) result;
        }
        throw new IllegalArgumentException("Expected JSON object at root");
    }

    private Object parseValue(JsonTokenizer t) {
        t.skipWhitespace();
        char c = t.peek();
        return switch (c) {
            case '{' -> parseObject(t);
            case '[' -> parseArray(t);
            case '"' -> parseString(t);
            case 't', 'f' -> parseBoolean(t);
            case 'n' -> parseNull(t);
            default -> parseNumber(t);
        };
    }

    private Map<String, Object> parseObject(JsonTokenizer t) {
        t.expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        t.skipWhitespace();
        if (t.peek() == '}') {
            t.advance();
            return map;
        }
        while (true) {
            t.skipWhitespace();
            String key = parseString(t);
            t.skipWhitespace();
            t.expect(':');
            Object value = parseValue(t);
            map.put(key, value);
            t.skipWhitespace();
            if (t.peek() == ',') {
                t.advance();
            } else {
                break;
            }
        }
        t.skipWhitespace();
        t.expect('}');
        return map;
    }

    private List<Object> parseArray(JsonTokenizer t) {
        t.expect('[');
        List<Object> list = new ArrayList<>();
        t.skipWhitespace();
        if (t.peek() == ']') {
            t.advance();
            return list;
        }
        while (true) {
            list.add(parseValue(t));
            t.skipWhitespace();
            if (t.peek() == ',') {
                t.advance();
            } else {
                break;
            }
        }
        t.skipWhitespace();
        t.expect(']');
        return list;
    }

    private String parseString(JsonTokenizer t) {
        t.expect('"');
        StringBuilder sb = new StringBuilder();
        while (t.peek() != '"') {
            char c = t.advance();
            if (c == '\\') {
                char esc = t.advance();
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        String hex = "" + t.advance() + t.advance() + t.advance() + t.advance();
                        sb.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> { sb.append('\\'); sb.append(esc); }
                }
            } else {
                sb.append(c);
            }
        }
        t.expect('"');
        return sb.toString();
    }

    private Number parseNumber(JsonTokenizer t) {
        StringBuilder sb = new StringBuilder();
        while (t.hasMore() && isNumberChar(t.peek())) {
            sb.append(t.advance());
        }
        String s = sb.toString();
        if (s.contains(".") || s.contains("e") || s.contains("E")) {
            return Double.parseDouble(s);
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Long.parseLong(s);
        }
    }

    private boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private Boolean parseBoolean(JsonTokenizer t) {
        if (t.peek() == 't') {
            t.expectWord("true");
            return Boolean.TRUE;
        } else {
            t.expectWord("false");
            return Boolean.FALSE;
        }
    }

    private Object parseNull(JsonTokenizer t) {
        t.expectWord("null");
        return null;
    }

    private static class JsonTokenizer {
        private final String input;
        private int pos;

        JsonTokenizer(String input) {
            this.input = input;
            this.pos = 0;
        }

        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (pos >= input.length()) throw new IllegalStateException("Unexpected end of JSON");
            return input.charAt(pos);
        }

        char advance() {
            if (pos >= input.length()) throw new IllegalStateException("Unexpected end of JSON");
            return input.charAt(pos++);
        }

        boolean hasMore() {
            return pos < input.length();
        }

        void expect(char c) {
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) != c) {
                throw new IllegalStateException("Expected '" + c + "' at position " + pos +
                        " but got " + (pos < input.length() ? "'" + input.charAt(pos) + "'" : "EOF"));
            }
            pos++;
        }

        void expectWord(String word) {
            for (int i = 0; i < word.length(); i++) {
                if (pos >= input.length() || input.charAt(pos) != word.charAt(i)) {
                    throw new IllegalStateException("Expected '" + word + "' at position " + (pos - i));
                }
                pos++;
            }
        }
    }
}
