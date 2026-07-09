package com.deltaproto.deltagerber.classify;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A filename convention: a regex that recognises one layer role for one CAD tool.
 *
 * <p>Immutable, and so safe to hold in the shared per-tool tables {@link LayerClassifier} matches
 * against. A pattern that matches yields a fresh {@link LayerClassification}; nothing about the
 * table itself changes.
 */
public final class LayerPattern {

    /** Layer label; {@code %d} is replaced by the captured layer number, when there is one. */
    private final String name;
    private final LayerFunction function;
    private final LayerSide side;
    private final Pattern pattern;
    /** Captures an inner-copper layer number out of the filename; null when the role has none. */
    private final Pattern numberPattern;

    LayerPattern(String name, LayerFunction function, LayerSide side, String regex) {
        this(name, function, side, regex, null);
    }

    LayerPattern(String name, LayerFunction function, LayerSide side, String regex, String numberRegex) {
        this.name = name;
        this.function = function;
        this.side = side;
        this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        this.numberPattern = numberRegex == null
                ? null
                : Pattern.compile(numberRegex, Pattern.CASE_INSENSITIVE);
    }

    public String getName() {
        return name;
    }

    public LayerFunction getFunction() {
        return function;
    }

    public LayerSide getSide() {
        return side;
    }

    public String getRegex() {
        return pattern.pattern();
    }

    public String getNumberRegex() {
        return numberPattern == null ? null : numberPattern.pattern();
    }

    boolean matches(String fileName) {
        return pattern.matcher(fileName).find();
    }

    /**
     * Build the classification this pattern implies for {@code fileName}, resolving the layer
     * number and the {@code %d} in the name.
     */
    LayerClassification classify(String fileName) {
        if (numberPattern == null) {
            return new LayerClassification(name, function, side, null);
        }
        Matcher matcher = numberPattern.matcher(fileName);
        if (matcher.find()) {
            try {
                int number = Integer.parseInt(matcher.group());
                return new LayerClassification(name.replace("%d", matcher.group()), function, side, number);
            } catch (NumberFormatException ignored) {
                // Fall through to the unnumbered form below.
            }
        }
        return new LayerClassification(name.replace("%d", "").trim(), function, side, null);
    }

    @Override
    public String toString() {
        return String.format("LayerPattern[%s, %s/%s, /%s/]", name, function, side, pattern.pattern());
    }
}
