package com.deltaproto.deltagerber.renderer.svg;

import java.util.Locale;

/**
 * Silkscreen (legend) ink colors a fabricator can print, plus {@link #NONE} for a board
 * ordered without any legend at all.
 *
 * <p>Pass one to {@link MultiLayerSVGRenderer#setSilkscreenColor(SilkscreenColor)} to choose
 * the legend independently of the soldermask. Left alone, the renderer prints the color the
 * chosen {@link SoldermaskColor} pairs with, which is the right answer for a board whose
 * legend the fab picks for you.
 */
public enum SilkscreenColor {
    WHITE("#ffffff"),
    BLACK("#000000"),
    YELLOW("#ffdd00"),
    /** No legend printed. {@link #getColor()} is {@code null} and nothing is drawn. */
    NONE(null);

    /** The color {@link #fromString} falls back to. */
    public static final SilkscreenColor DEFAULT = WHITE;

    private final String color;

    SilkscreenColor(String color) {
        this.color = color;
    }

    /** Hex fill (e.g. {@code "#ffffff"}) for the legend, or {@code null} for {@link #NONE}. */
    public String getColor() {
        return color;
    }

    /** Whether a legend is printed at all — {@code false} only for {@link #NONE}. */
    public boolean isPrinted() {
        return color != null;
    }

    /**
     * Case-insensitive lookup by enum name (e.g. {@code "white"}, {@code "NONE"}). Returns
     * {@link #DEFAULT} for {@code null}, blank, or unrecognized names so request handlers can
     * pass user input straight through. Note that {@code "none"} resolves to {@link #NONE} —
     * only an unrecognized name falls back to white.
     */
    public static SilkscreenColor fromString(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
