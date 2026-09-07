package com.deltaproto.deltagerber.model.gerber;

/**
 * How a file writes its numbers: how many digits sit before and after the implied decimal point,
 * which zeros it leaves out, and the unit those digits are in. This is Gerber's {@code %FS%}
 * <em>format specification</em> and Excellon's {@code INCH}/{@code METRIC} plus digit format,
 * reduced to the one shape both share, so a set's artwork and its drill program can be compared.
 *
 * <p>Fabricators ask for the format ("4:3, leading zeros suppressed") and expect the drill program
 * to be stated in the same terms as the artwork — see
 * {@link com.deltaproto.deltagerber.spec.BoardSpecification#isFormatConsistent()}.
 *
 * <p>The unit here is the file's <em>own</em>: every coordinate this library hands back has already
 * been normalised to millimetres, and this is what the digits in the file meant before that. It is
 * the only reason the native unit is kept at all.
 *
 * <p><b>Never report the raw L/T letters.</b> They mean opposite things in the two formats: Gerber's
 * {@code FSL} says leading zeros are <em>omitted</em>, while Excellon's {@code LZ} says leading
 * zeros are <em>kept</em> and the trailing ones dropped. {@link ZeroSuppression} names what is left
 * out instead, so both formats land in one vocabulary.
 *
 * @param unit            the unit the digits are in, as the file declared it
 * @param integerDigits   digits before the implied decimal point — the coordinate range
 * @param decimalDigits   digits after it — the file's resolution
 * @param zeroSuppression which zeros the coordinates leave out
 * @param declared        whether the file stated this format. A Gerber always does ({@code %FS%} is
 *                        mandatory); an Excellon file often states nothing, and then the digits are
 *                        this library's assumption — 2:4 for an inch file, 3:3 for a metric one —
 *                        rather than something the file said. Not part of
 *                        {@link #sameFormatAs(FormatSpec)}: two files agree or not on the numbers,
 *                        whoever supplied them.
 */
public record FormatSpec(Unit unit, int integerDigits, int decimalDigits,
                         ZeroSuppression zeroSuppression, boolean declared) {

    /** Which zeros a coordinate leaves out, named for what is missing rather than what is kept. */
    public enum ZeroSuppression {

        /** Leading zeros are dropped — Gerber's {@code FSL}, Excellon's {@code TZ}. */
        LEADING("leading zeros suppressed"),

        /** Trailing zeros are dropped — Gerber's {@code FST}, Excellon's {@code LZ}. */
        TRAILING("trailing zeros suppressed"),

        /** Nothing is dropped: the coordinates carry an explicit decimal point. */
        NONE("explicit decimal point");

        private final String description;

        ZeroSuppression(String description) {
            this.description = description;
        }

        /** Wording safe to show a user, unlike the format's own letter. */
        public String getDescription() {
            return description;
        }
    }

    /** The {@code 4:3} a fabricator asks for: integer digits, then decimal digits. */
    public String digits() {
        return integerDigits + ":" + decimalDigits;
    }

    /**
     * The smallest step these coordinates can express, in millimetres — 2.54 µm for the 2:4 inch
     * format, 1 µm for 3:3 metric. Meaningless when nothing is suppressed and the coordinates carry
     * their own decimal point, since then the digit counts do not bound anything.
     */
    public double resolutionMm() {
        return unit.toMm(Math.pow(10, -decimalDigits));
    }

    /**
     * Whether {@code other} writes its numbers the same way: same unit, same digits, same zeros
     * left out. Whether either file {@linkplain #declared() said so} is not part of the question.
     */
    public boolean sameFormatAs(FormatSpec other) {
        return other != null
                && unit == other.unit
                && integerDigits == other.integerDigits
                && decimalDigits == other.decimalDigits
                && zeroSuppression == other.zeroSuppression;
    }

    /** e.g. {@code "4:6 mm, leading zeros suppressed"}, with "(assumed)" when nothing declared it. */
    @Override
    public String toString() {
        return digits() + " " + (unit == Unit.INCH ? "inch" : "mm")
                + ", " + zeroSuppression.getDescription()
                + (declared ? "" : " (assumed)");
    }
}
