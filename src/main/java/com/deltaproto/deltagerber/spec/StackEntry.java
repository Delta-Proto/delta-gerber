package com.deltaproto.deltagerber.spec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * One layer of the board's physical build-up, at a known position in it.
 *
 * <p>A stack is a {@code List<StackEntry>} ordered top of the board first — see
 * {@link BoardSpecification#getStack()} and {@link BoardStack}. {@link #getOrdinal()} repeats that
 * position on the entry itself, densely from 0, so an entry stays meaningful once it is out of the
 * list.
 *
 * <p>Every measurement is nullable and null means "not determined", never zero — the convention
 * {@link AnalyzedLayer} sets. A stack synthesised from the files rather than read from a job file
 * has a thickness of null throughout, because no Gerber file states one.
 *
 * <h2>Why picometres</h2>
 *
 * <p>Thickness is a {@code Long} count of picometres, not a {@code double} of millimetres, so that
 * nominal values are exact and sums of them are exact. 1 µin = 25 400 pm and 1 mil = 25 400 000 pm,
 * so both the metric and the imperial values a fabricator quotes land on integers: 35 µm of
 * one-ounce foil is 35 000 000 pm and its 1.378 mil equivalent is the same integer. Add a stack of
 * them up and the total is the total, with no drift to explain away. Gerber job files quote
 * millimetres; the conversion happens once, where the file is read.
 */
public final class StackEntry {

    /** 1 mm = 10⁹ pm. */
    private static final BigDecimal PM_PER_MM = BigDecimal.valueOf(1_000_000_000L);

    private final int ordinal;
    private final StackFunction function;
    private final String name;
    private final Long thicknessPm;
    private final String material;
    private final boolean estimated;

    private StackEntry(int ordinal, StackFunction function, String name, Long thicknessPm,
                       String material, boolean estimated) {
        this.ordinal = ordinal;
        this.function = Objects.requireNonNull(function, "function");
        this.name = name;
        this.thicknessPm = thicknessPm;
        this.material = material;
        this.estimated = estimated;
    }

    /**
     * An entry with its thickness already in picometres.
     *
     * @param ordinal     position in the stack, 0 at the top of the board
     * @param function    what this layer is; never null
     * @param name        the layer's own name where it has one ({@code "F.Cu"}, {@code "top
     *                    copper"}), else null
     * @param thicknessPm thickness in picometres, or null when undetermined
     * @param material    the material named for this layer ({@code "FR4"}), or null
     * @param estimated   true when this entry was synthesised from the artwork rather than read
     *                    from a stack-up the CAD tool supplied
     */
    public static StackEntry of(int ordinal, StackFunction function, String name, Long thicknessPm,
                                String material, boolean estimated) {
        return new StackEntry(ordinal, function, name, thicknessPm, material, estimated);
    }

    /** As {@link #of}, converting a millimetre thickness at the boundary. A null stays null. */
    public static StackEntry ofMm(int ordinal, StackFunction function, String name, Double thicknessMm,
                                  String material, boolean estimated) {
        return new StackEntry(ordinal, function, name, toPicometres(thicknessMm), material, estimated);
    }

    /**
     * Millimetres to picometres, rounded to the nearest picometre. Goes through the decimal value
     * the {@code double} actually prints as, so a file's {@code 0.035} becomes exactly 35 000 000
     * rather than whatever the binary representation of 0.035 × 10⁹ lands on.
     *
     * @return null when {@code mm} is null or not a finite number
     */
    public static Long toPicometres(Double mm) {
        if (mm == null || !Double.isFinite(mm)) {
            return null;
        }
        return BigDecimal.valueOf(mm).multiply(PM_PER_MM).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /** Picometres back to millimetres, for callers working in the library's usual unit. */
    public static Double toMillimetres(Long pm) {
        return pm == null ? null : BigDecimal.valueOf(pm).divide(PM_PER_MM).doubleValue();
    }

    /** The same entry at a different position in the stack. */
    public StackEntry withOrdinal(int ordinal) {
        return new StackEntry(ordinal, function, name, thicknessPm, material, estimated);
    }

    /** Position in the stack, counted densely from 0 at the top of the board. */
    public int getOrdinal() {
        return ordinal;
    }

    /** What this layer is; never null. */
    public StackFunction getFunction() {
        return function;
    }

    /**
     * The layer's name — {@code "F.Cu"} or {@code "Top Solder Mask"} as a job file writes it, the
     * classified layer's label ({@code "inner copper 2"}) in a synthesised stack. Null when neither
     * names it, which is usual for a dielectric.
     */
    public String getName() {
        return name;
    }

    /** Thickness in picometres, or null when undetermined. */
    public Long getThicknessPm() {
        return thicknessPm;
    }

    /** {@link #getThicknessPm()} in millimetres, or null when undetermined. */
    public Double getThicknessMm() {
        return toMillimetres(thicknessPm);
    }

    /** The material named for this layer, e.g. {@code "FR4"}; null when unnamed. */
    public String getMaterial() {
        return material;
    }

    /**
     * Whether this entry was synthesised rather than read: true when the stack was inferred from
     * which layers the set contains, false when a {@code .gbrjob} stated it.
     *
     * <p>An estimated stack has the right layers in the right order and no thicknesses, and holds
     * no dielectrics at all — no Gerber file says a board has a core, let alone how thick.
     */
    public boolean isEstimated() {
        return estimated;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StackEntry entry)) {
            return false;
        }
        return ordinal == entry.ordinal && function == entry.function && estimated == entry.estimated
                && Objects.equals(name, entry.name) && Objects.equals(thicknessPm, entry.thicknessPm)
                && Objects.equals(material, entry.material);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ordinal, function, name, thicknessPm, material, estimated);
    }

    @Override
    public String toString() {
        return String.format("StackEntry[%d %s %s, %s mm%s]",
                ordinal, function, name, getThicknessMm(), estimated ? ", estimated" : "");
    }
}
