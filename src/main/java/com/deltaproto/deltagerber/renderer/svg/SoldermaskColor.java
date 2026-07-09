package com.deltaproto.deltagerber.renderer.svg;

import java.util.Locale;

/**
 * Standard PCB soldermask colors offered by common fabricators (the JLCPCB
 * palette), each paired with the silkscreen color a fab prints on top of it.
 * White soldermask is printed with black silkscreen; every other color uses
 * white silkscreen. {@link #NONE} is a board ordered without soldermask.
 *
 * <p>Pass one to {@link MultiLayerSVGRenderer#setSoldermaskColor(SoldermaskColor)}
 * to color the realistic render (and the PNG paths built on it). {@link #GREEN}
 * is the default. The paired silkscreen color is only a default: override it with
 * {@link MultiLayerSVGRenderer#setSilkscreenColor(SilkscreenColor)}.
 *
 * <p><b>On the green shade:</b> the fab swatch green is a bright {@code #008635},
 * but this enum's {@code GREEN} keeps the renderer's long-standing darker
 * {@code #004200}. At the soldermask's semi-transparent opacity over the
 * copper/FR4 underneath, that darker base blends to a realistic board green —
 * the brighter swatch value renders as an unrealistically vivid green. The
 * other six colors use their swatch values directly.
 */
public enum SoldermaskColor {
    GREEN("#004200", "#ffffff"),
    PURPLE("#ac13a6", "#ffffff"),
    RED("#bf0100", "#ffffff"),
    YELLOW("#ffaa16", "#ffffff"),
    BLUE("#002d8c", "#ffffff"),
    WHITE("#f7f9fe", "#000000"),
    BLACK("#0f1010", "#ffffff"),
    /**
     * No soldermask applied — bare copper and laminate. {@link #getMaskColor()} is
     * {@code null} and no mask sheet is drawn. A legend printed straight onto the
     * laminate is still white unless overridden.
     */
    NONE(null, "#ffffff");

    /** The color rendered when no override is set. */
    public static final SoldermaskColor DEFAULT = GREEN;

    private final String maskColor;
    private final String silkscreenColor;

    SoldermaskColor(String maskColor, String silkscreenColor) {
        this.maskColor = maskColor;
        this.silkscreenColor = silkscreenColor;
    }

    /**
     * Hex fill (e.g. {@code "#004200"}) for the semi-transparent soldermask, or
     * {@code null} for {@link #NONE}.
     */
    public String getMaskColor() {
        return maskColor;
    }

    /** Whether a soldermask is applied at all — {@code false} only for {@link #NONE}. */
    public boolean isApplied() {
        return maskColor != null;
    }

    /**
     * Hex fill for silkscreen printed on this mask — {@code "#000000"} on
     * {@link #WHITE}, {@code "#ffffff"} on every other color. This is the default the
     * renderer uses when the caller does not choose a {@link SilkscreenColor}.
     */
    public String getSilkscreenColor() {
        return silkscreenColor;
    }

    /**
     * Case-insensitive lookup by enum name (e.g. {@code "red"}, {@code "GREEN"}).
     * Returns {@link #DEFAULT} for {@code null}, blank, or unrecognized names so
     * request handlers can pass user input straight through. Note that {@code "none"}
     * resolves to {@link #NONE} — only an unrecognized name falls back to green.
     */
    public static SoldermaskColor fromString(String name) {
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
