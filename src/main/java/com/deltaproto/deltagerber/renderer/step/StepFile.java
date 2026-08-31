package com.deltaproto.deltagerber.renderer.step;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * The DATA section of an ISO 10303-21 (STEP part 21) file, under construction.
 *
 * <p>An exchange file is a flat list of numbered entity instances — {@code #12=LINE('',#3,#4);}
 * — that reference each other by number. This class hands out those numbers and appends the
 * text; it knows nothing about B-reps.
 *
 * <p>{@link #share} is what keeps the file to a sane size. Geometry primitives are pure values:
 * two {@code DIRECTION}s with the same components are the same direction, and every entity that
 * references one may reference the same instance. A rectilinear board silhouette has thousands
 * of edges and four distinct directions, so interning by entity text collapses them. Topology
 * ({@code EDGE_LOOP}, {@code ADVANCED_FACE}, …) is emitted with {@link #emit} instead — sharing
 * would be harmless where the text matches, but it never does, and identity there is meaningful.
 */
final class StepFile {

    private final StringBuilder data = new StringBuilder();
    private final Map<String, Integer> shared = new HashMap<>();
    private int nextId = 1;

    /** Append an entity instance and return its number. */
    int emit(String body) {
        int id = nextId++;
        data.append('#').append(id).append('=').append(body).append(";\n");
        return id;
    }

    /** As {@link #emit}, but reuse an identical instance if one was already written. */
    int share(String body) {
        Integer existing = shared.get(body);
        if (existing != null) return existing;
        int id = emit(body);
        shared.put(body, id);
        return id;
    }

    /** The entity instances written so far, one per line. */
    String body() {
        return data.toString();
    }

    /** How many entity instances have been written. */
    int size() {
        return nextId - 1;
    }

    /**
     * A STEP real literal. The grammar requires a decimal point, so an integral value is
     * written {@code 1.} — and values are rounded to the nanometre, which is far below any
     * fabrication tolerance and keeps a large silhouette's file from doubling in size on
     * digits that mean nothing.
     */
    static String num(double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("Non-finite coordinate in board outline: " + v);
        }
        String s = BigDecimal.valueOf(v)
            .setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
        return s.indexOf('.') < 0 ? s + "." : s;
    }

    /**
     * A STEP string literal. Part 21 quotes with apostrophes and doubles an embedded one;
     * anything outside printable ASCII would need control-directive encoding, so it is
     * dropped rather than written raw (a board name is decoration, not data).
     */
    static String str(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder("'");
        for (char c : s.toCharArray()) {
            if (c == '\'') sb.append("''");
            else if (c >= 32 && c < 127) sb.append(c);
        }
        return sb.append('\'').toString();
    }
}
