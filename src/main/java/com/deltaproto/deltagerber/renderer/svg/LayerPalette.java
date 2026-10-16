package com.deltaproto.deltagerber.renderer.svg;

import java.util.Locale;

/**
 * The colour a layer is drawn in when layers are viewed individually, decided by what the layer
 * <em>is</em> — its {@link LayerType} and, for inner copper, its stack index — never by its file
 * name. A set named in plain words ("top layer.gbr", "bottom solder mask.gbr") classifies
 * perfectly from its X2 attributes and used to render every file in the same fallback grey,
 * because the colour was looked up from extension fragments the names did not contain.
 *
 * <p>The palette is KiCad's built-in default PCB theme ({@code s_defaultTheme} in
 * {@code common/settings/builtin_color_themes.h}): F.Cu, B.Cu, In1–In6.Cu, F/B.Mask, F/B.SilkS,
 * F/B.Paste, Edge.Cuts, the three hole colours, and the two courtyards for pick-and-place. It is
 * a palette many users already read fluently, and it was designed for KiCad's near-black board
 * canvas — which the viewer therefore also uses, in both of its themes: the silkscreen, mask and
 * outline pastels are 1.0–1.5:1 against a light grey and vanish on it. Two departures: KiCad gives In7 and In8 the silkscreen colours, and every colour here
 * is distinct — a layer that looks like another is a layer you misread — so the seventh and
 * eighth inner layers take the theme's Eco1/Eco2 user-layer colours instead. Beyond eight the
 * colours cycle, which no board in the corpus reaches.
 */
public final class LayerPalette {

    /** What a file the classifier could not place is drawn in: KiCad's F.Fab grey. */
    public static final String UNKNOWN = "#afafaf";

    private static final String[] INNER = {
        "#7fc87f",  // In1.Cu
        "#ce7d2c",  // In2.Cu
        "#4fcbcb",  // In3.Cu
        "#db628b",  // In4.Cu
        "#a7a5c6",  // In5.Cu
        "#28ccd9",  // In6.Cu
        "#b4dbd2",  // Eco1.User — In7.Cu would repeat B.SilkS
        "#d8c852",  // Eco2.User — In8.Cu would repeat F.SilkS
    };

    private LayerPalette() {}

    /**
     * The colour for a layer of this type. {@code innerNumber} is the stack index of an inner
     * copper layer (1-based, as {@code LayerClassification.number()} reports it after
     * normalisation) and is ignored for every other type; null draws inner copper as In1.
     */
    public static String colorOf(LayerType type, Integer innerNumber) {
        if (type == null) {
            return UNKNOWN;
        }
        return switch (type) {
            case COPPER_TOP -> "#c83434";         // F.Cu
            case COPPER_BOTTOM -> "#4d7fc4";      // B.Cu
            case COPPER_INNER -> inner(innerNumber);
            case SOLDERMASK_TOP -> "#d864ff";     // F.Mask
            case SOLDERMASK_BOTTOM -> "#02ffee";  // B.Mask
            case SILKSCREEN_TOP -> "#f2eda1";     // F.SilkS
            case SILKSCREEN_BOTTOM -> "#e8b2a7";  // B.SilkS
            case PASTE_TOP -> "#b4a09a";          // F.Paste
            case PASTE_BOTTOM -> "#00c2c2";       // B.Paste
            case OUTLINE -> "#d0d2cd";            // Edge.Cuts
            case DRILL -> "#e3b72e";              // via holes
            case DRILL_PLATED -> "#c2c200";       // pad plated holes
            case DRILL_NON_PLATED -> "#1ac4d2";   // non-plated holes
            case PNP_TOP -> "#ff26e2";            // F.CrtYd
            case PNP_BOTTOM -> "#26e9ff";         // B.CrtYd
            case OTHER -> UNKNOWN;
        };
    }

    /** {@link #colorOf(LayerType, Integer)} for a layer that is not inner copper. */
    public static String colorOf(LayerType type) {
        return colorOf(type, null);
    }

    /** The colour of the n-th inner copper layer, counted from 1. */
    public static String inner(Integer number) {
        int n = number == null || number < 1 ? 1 : number;
        return INNER[(n - 1) % INNER.length];
    }

    /** How many inner copper layers get a colour of their own before the palette cycles. */
    public static int innerColors() {
        return INNER.length;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "LayerPalette[%d inner]", INNER.length);
    }
}
