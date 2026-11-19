package com.deltaproto.deltagerber.model.drill;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The copper layers a drill program connects: from layer {@code from} to layer {@code to},
 * counted from the top as Gerber X2 counts them ({@code 1} is the top copper, the bottom copper is
 * the stack's layer count).
 *
 * <p>Only a file that says so has one. Gerber X2 states it in
 * {@code %TF.FileFunction,Plated,1,2,Blind*%} (and Altium in the {@code G04 #@!} comment form);
 * KiCad repeats the same attribute in an Excellon header comment,
 * {@code ; #@! TF.FileFunction,Plated,1,4,PTH}. A plain Excellon file says nothing about where its
 * holes start and stop — Altium's {@code .TX3} is one drill <em>pair</em> of a 12-layer HDI board,
 * and the file never says which — so its span is {@code null}, unknown, and a caller must not take
 * it for a through hole.
 *
 * @param from the upper copper layer the holes start on (1-based, {@code from <= to})
 * @param to   the lower copper layer they end on
 */
public record DrillSpan(int from, int to) {

    private static final Pattern X2_IN_COMMENT = Pattern.compile(
            "#@!\\s*TF\\.FileFunction,\\s*(?:Non)?Plated,\\s*(\\d+)\\s*,\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);

    public DrillSpan {
        if (from < 1 || to < 1) {
            throw new IllegalArgumentException("layers count from 1, was " + from + ".." + to);
        }
        if (from > to) {
            int t = from;
            from = to;
            to = t;
        }
    }

    /** Whether the holes reach copper layer {@code layer} — i.e. start or end on it, or pass it. */
    public boolean reaches(int layer) {
        return layer >= from && layer <= to;
    }

    /**
     * The span a Gerber X2 {@code .FileFunction} states for a drill layer, from its values
     * ({@code Plated,1,2,Blind}, {@code NonPlated,1,4,NPTH}), or {@code null} when it is not a drill
     * function or carries no layer numbers.
     */
    public static DrillSpan ofFileFunction(List<String> values) {
        if (values == null || values.size() < 3) {
            return null;
        }
        String function = values.get(0).trim();
        if (!function.equalsIgnoreCase("Plated") && !function.equalsIgnoreCase("NonPlated")) {
            return null;
        }
        return of(values.get(1), values.get(2));
    }

    /**
     * The span stated in an Excellon header comment carrying the X2 attribute
     * ({@code #@! TF.FileFunction,Plated,1,2,Blind} — KiCad writes one), or {@code null}.
     */
    public static DrillSpan ofComment(String comment) {
        if (comment == null) {
            return null;
        }
        Matcher m = X2_IN_COMMENT.matcher(comment);
        return m.find() ? of(m.group(1), m.group(2)) : null;
    }

    private static DrillSpan of(String from, String to) {
        try {
            int a = Integer.parseInt(from.trim());
            int b = Integer.parseInt(to.trim());
            return a >= 1 && b >= 1 ? new DrillSpan(a, b) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return from + "-" + to;
    }
}
