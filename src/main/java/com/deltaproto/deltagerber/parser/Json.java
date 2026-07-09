package com.deltaproto.deltagerber.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, strict JSON reader — just enough for the Gerber job file, so that a Gerber parsing
 * library need not drag a JSON library behind it.
 *
 * <p>Values map to {@code Map<String,Object>}, {@code List<Object>}, {@link String},
 * {@link Double}, {@link Boolean} and null. Malformed input raises {@link ParserException}.
 * Objects preserve key order.
 */
final class Json {

    private final String source;
    private int position;

    private Json(String source) {
        this.source = source;
    }

    /** Parse a complete JSON document. Trailing content after the top-level value is an error. */
    static Object parse(String source) {
        Json json = new Json(source);
        json.skipWhitespace();
        Object value = json.readValue();
        json.skipWhitespace();
        if (json.position < source.length()) {
            throw json.error("trailing content after the top-level value");
        }
        return value;
    }

    // ------------------------------------------------------------------------

    private Object readValue() {
        if (position >= source.length()) {
            throw error("unexpected end of input");
        }
        char c = source.charAt(position);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> object = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return object;
            }
            if (c != ',') {
                throw error("expected ',' or '}' in object");
            }
        }
    }

    private List<Object> readArray() {
        List<Object> array = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return array;
            }
            if (c != ',') {
                throw error("expected ',' or ']' in array");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder text = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return text.toString();
            }
            if (c != '\\') {
                text.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"', '\\', '/' -> text.append(escape);
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'u' -> {
                    if (position + 4 > source.length()) {
                        throw error("truncated \\u escape");
                    }
                    try {
                        text.append((char) Integer.parseInt(source.substring(position, position + 4), 16));
                    } catch (NumberFormatException e) {
                        throw error("malformed \\u escape");
                    }
                    position += 4;
                }
                default -> throw error("unknown escape '\\" + escape + "'");
            }
        }
    }

    private Double readNumber() {
        int start = position;
        if (peek() == '-' || peek() == '+') {
            position++;
        }
        while (position < source.length() && isNumberChar(source.charAt(position))) {
            position++;
        }
        try {
            return Double.valueOf(source.substring(start, position));
        } catch (NumberFormatException e) {
            throw error("malformed number '" + source.substring(start, position) + "'");
        }
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private Object readLiteral(String literal, Object value) {
        if (!source.startsWith(literal, position)) {
            throw error("expected '" + literal + "'");
        }
        position += literal.length();
        return value;
    }

    // ------------------------------------------------------------------------

    private void skipWhitespace() {
        while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
            position++;
        }
    }

    /** The next character without consuming it, or {@code '\0'} at end of input. */
    private char peek() {
        return position < source.length() ? source.charAt(position) : '\0';
    }

    private char next() {
        if (position >= source.length()) {
            throw error("unexpected end of input");
        }
        return source.charAt(position++);
    }

    private void expect(char expected) {
        if (next() != expected) {
            position--;
            throw error("expected '" + expected + "'");
        }
    }

    private ParserException error(String message) {
        return new ParserException("Invalid JSON at offset " + position + ": " + message);
    }

    // ------------------------------------------------------------------------
    // Typed accessors — every one tolerates a missing or wrongly-typed value by returning null,
    // because a job file may legally omit any of the fields we read.
    // ------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String key) {
        if (value instanceof Map<?, ?> map && map.get(key) instanceof Map<?, ?> child) {
            return (Map<String, Object>) child;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value, String key) {
        if (value instanceof Map<?, ?> map && map.get(key) instanceof List<?> child) {
            return (List<Object>) child;
        }
        return null;
    }

    static String string(Object value, String key) {
        return value instanceof Map<?, ?> map && map.get(key) instanceof String text ? text : null;
    }

    static Double number(Object value, String key) {
        return value instanceof Map<?, ?> map && map.get(key) instanceof Double n ? n : null;
    }

    static Integer integer(Object value, String key) {
        Double n = number(value, key);
        return n == null ? null : n.intValue();
    }
}
