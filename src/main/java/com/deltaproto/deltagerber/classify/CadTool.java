package com.deltaproto.deltagerber.classify;

/**
 * The EDA tool that produced a Gerber set. Detected from the {@code .GenerationSoftware}
 * attribute where present, otherwise guessed from filename conventions.
 *
 * <p>Knowing the tool matters because filename conventions collide across tools: {@code .G1} is an
 * inner copper layer to Protel and Altium but nothing to KiCad, and {@code .gbr} means everything
 * and nothing. The tool decides which naming convention to try first.
 */
public enum CadTool {
    ALTIUM,
    PROTEL,
    KICAD,
    EAGLE,
    ALLEGRO,
    /** No tool identified — try the generic conventions first. */
    GENERIC
}
