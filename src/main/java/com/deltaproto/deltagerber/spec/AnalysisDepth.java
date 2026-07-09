package com.deltaproto.deltagerber.spec;

/**
 * How much of a Gerber set {@link PcbAnalyzer} has to read.
 *
 * <p>Parsing a Gerber file costs memory proportional to the geometry it draws, and silkscreen is
 * routinely the largest file in a set by an order of magnitude — outlined text is millions of
 * draws. Most of that geometry cannot change a single field of a {@link BoardSpecification}.
 */
public enum AnalysisDepth {

    /**
     * Measure every layer. Each {@link AnalyzedLayer} comes back with its own bounds, whether or
     * not the board specification depends on them. Use this when you intend to keep the per-layer
     * measurements — to align rendered layers in a shared coordinate space, say.
     */
    FULL,

    /**
     * Read only what the board specification depends on.
     *
     * <p>The outline, copper and drill layers are always parsed: they carry the board size, the
     * track width and the drill diameter. Everything else is parsed only when the set has no
     * usable outline, because then — and only then — its extent contributes to the board size.
     *
     * <p>Layers skipped this way come back with null {@link AnalyzedLayer#getBounds()}; their
     * {@link AnalyzedLayer#getHasGeometry()} is answered by scanning for draw and flash commands
     * rather than by building the geometry.
     */
    SPECIFICATION
}
