package com.deltaproto.deltagerber.spec;

/**
 * What one layer of the board's physical build-up is made of.
 *
 * <p>Deliberately <em>not</em>
 * {@link com.deltaproto.deltagerber.classify.LayerFunction}, which is the manufacturing role a
 * <em>file</em> plays. The two vocabularies overlap but neither contains the other: a core and a
 * prepreg are layers of the board that no file describes, and a drill file is a file that is no
 * layer of the board. Fusing them would put values into {@code LayerFunction} that every
 * {@code isCopper()}/{@code isDrill()} predicate — and every caller switching on it to pick a
 * parser — would then have to handle.
 *
 * @see StackEntry
 */
public enum StackFunction {

    /** Copper foil: an outer layer's plated foil or an inner layer's etched foil. */
    COPPER("Copper foil"),

    /**
     * The insulating layer between two copper layers — the substrate itself on a two-layer board,
     * and each bonding layer of a multilayer build.
     *
     * <p>Not split into core and prepreg, because nothing in a Gerber set says which a given layer
     * is. The job file format has one {@code Dielectric} type and no field that separates them, and
     * across a corpus of real job files not one names a core or a prepreg. The split could only
     * ever be inferred from position, and a fabricator's build-up is theirs to decide — so this
     * library states the dielectric it was told about and leaves it there.
     */
    DIELECTRIC("Dielectric"),

    SOLDERMASK("Soldermask"),

    /** The printed legend. {@code Legend} in a Gerber job file. */
    SILKSCREEN("Silkscreen or legend"),

    /**
     * Solder paste. Part of the stack as the CAD tools write it, though it is deposited during
     * assembly rather than fabrication and is not part of the bare board's thickness.
     */
    PASTE("Solder paste"),

    /** Surface finish — ENIG, HASL, immersion tin and the rest. */
    FINISH("Surface finish"),

    /**
     * A layer the job file states in a type this library does not model — a flex coverlay or a
     * bonding film, say. It keeps its position, name and thickness, so a stack that contains one
     * is still complete and still adds up to the board's thickness; dropping the entry instead
     * would quietly lose material from the middle of the board.
     */
    OTHER("Other");

    private final String description;

    StackFunction(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /** True for the copper layers that make up the electrical stack-up. */
    public boolean isCopper() {
        return this == COPPER;
    }

    /** True for the insulating layers between copper. */
    public boolean isDielectric() {
        return this == DIELECTRIC;
    }
}
