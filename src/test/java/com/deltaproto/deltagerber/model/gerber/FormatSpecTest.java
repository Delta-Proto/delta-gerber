package com.deltaproto.deltagerber.model.gerber;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format a file states for its own numbers — what a fabricator asks for as "4:3, leading zeros
 * suppressed", and the one thing that survives normalising every coordinate to millimetres.
 */
class FormatSpecTest {

    private static GerberDocument gerber(String... lines) {
        return new GerberParser().parse(String.join("\n", lines));
    }

    private static DrillDocument drill(String... lines) {
        return new ExcellonParser().parse(String.join("\n", lines));
    }

    @Nested
    @DisplayName("Gerber")
    class Gerber {

        @Test
        @DisplayName("The %FS% digits and the file's own unit are both reported")
        void metricFourSix() {
            FormatSpec format = gerber("%FSLAX46Y46*%", "%MOMM*%", "M02*").getFormatSpec();

            assertEquals("4:6", format.digits());
            assertEquals(Unit.MM, format.unit());
            assertEquals(4, format.integerDigits());
            assertEquals(6, format.decimalDigits());
            assertTrue(format.declared(), "%FS% is mandatory, so a Gerber always declares one");
            assertEquals(1e-6, format.resolutionMm(), 1e-12);
        }

        /**
         * The unit is the file's, not the document's: parsing converts every coordinate to
         * millimetres and {@code getUnit()} says so, which would make a 2:4 inch file read as a
         * micrometre-grid metric one.
         */
        @Test
        @DisplayName("An inch file keeps its inches, though its geometry is in mm")
        void inchIsNotLostToNormalisation() {
            GerberDocument doc = gerber("%FSLAX24Y24*%", "%MOIN*%", "M02*");

            assertEquals(Unit.MM, doc.getUnit(), "the geometry is millimetres");
            assertEquals(Unit.INCH, doc.getFormatSpec().unit(), "the digits were tenths of a mil");
            assertEquals(0.00254, doc.getFormatSpec().resolutionMm(), 1e-9);
        }

        @Test
        @DisplayName("FSL omits the leading zeros, FST the trailing ones")
        void zeroSuppression() {
            assertEquals(FormatSpec.ZeroSuppression.LEADING,
                    gerber("%FSLAX34Y34*%", "%MOMM*%", "M02*").getFormatSpec().zeroSuppression());
            assertEquals(FormatSpec.ZeroSuppression.TRAILING,
                    gerber("%FSTAX34Y34*%", "%MOMM*%", "M02*").getFormatSpec().zeroSuppression());
        }

        @Test
        @DisplayName("A file with no %FS% at all states no format")
        void withoutFormatSpec() {
            assertNull(gerber("%MOMM*%", "M02*").getFormatSpec(),
                    "nothing to report — the coordinates could not be read either");
        }
    }

    @Nested
    @DisplayName("Excellon")
    class Excellon {

        @Test
        @DisplayName("A stated FILE_FORMAT is reported as declared")
        void declaredFormat() {
            FormatSpec format = drill(
                    "M48", "METRIC,TZ", ";FILE_FORMAT=3:3", "T1C0.300", "%", "T1",
                    "X010000Y010000", "M30").getFormatSpec();

            assertEquals("3:3", format.digits());
            assertEquals(Unit.MM, format.unit());
            assertTrue(format.declared());
            assertEquals(0.001, format.resolutionMm(), 1e-9);
        }

        /**
         * Excellon requires nothing, so a file that states no digits is read on convention — 2:4
         * for inch. That is this library's assumption, not the file's claim, and a fab reading the
         * same file is free to assume differently: it is exactly the case worth flagging.
         */
        @Test
        @DisplayName("An undeclared format is reported as assumed")
        void assumedFormat() {
            FormatSpec format = drill(
                    "M48", "INCH,LZ", "T1C0.0118", "%", "T1",
                    "X010000Y010000", "M30").getFormatSpec();

            assertEquals("2:4", format.digits());
            assertEquals(Unit.INCH, format.unit());
            assertFalse(format.declared(), "the file said INCH and nothing about digits");
            assertTrue(format.toString().contains("(assumed)"));
        }

        /**
         * The trap: Gerber's {@code L} and Excellon's {@code LZ} both start with the same letter
         * and mean opposite things — {@code FSL} drops the leading zeros, {@code LZ} keeps them and
         * drops the trailing ones. Reported by what is missing, they cannot be confused.
         */
        @Test
        @DisplayName("LZ suppresses the trailing zeros, TZ the leading ones — the opposite of Gerber's letters")
        void zeroSuppressionIsNotTheLetter() {
            assertEquals(FormatSpec.ZeroSuppression.TRAILING,
                    drill("M48", "METRIC,LZ", "T1C0.300", "%", "T1", "X010000", "M30")
                            .getFormatSpec().zeroSuppression());
            assertEquals(FormatSpec.ZeroSuppression.LEADING,
                    drill("M48", "METRIC,TZ", "T1C0.300", "%", "T1", "X010000", "M30")
                            .getFormatSpec().zeroSuppression());
            assertEquals(FormatSpec.ZeroSuppression.LEADING,
                    gerber("%FSLAX33Y33*%", "%MOMM*%", "M02*").getFormatSpec().zeroSuppression(),
                    "the same letter, the other meaning");
        }

        @Test
        @DisplayName("Coordinates carrying their own decimal point suppress nothing")
        void explicitDecimalPoint() {
            DrillDocument doc = drill(
                    "M48", "METRIC", "T1C0.300", "%", "T1", "X10.000Y10.000", "M30");

            assertTrue(doc.hasDecimalPointCoordinates());
            assertEquals(FormatSpec.ZeroSuppression.NONE, doc.getFormatSpec().zeroSuppression());
            assertTrue(doc.getFormatSpec().toString().contains("explicit decimal point"));
        }
    }

    @Test
    @DisplayName("Two files agree on a format whether or not both declared it")
    void agreementIgnoresDeclaredness() {
        FormatSpec declared = new FormatSpec(Unit.MM, 3, 3, FormatSpec.ZeroSuppression.LEADING, true);
        FormatSpec assumed = new FormatSpec(Unit.MM, 3, 3, FormatSpec.ZeroSuppression.LEADING, false);
        FormatSpec other = new FormatSpec(Unit.INCH, 3, 3, FormatSpec.ZeroSuppression.LEADING, true);

        assertTrue(declared.sameFormatAs(assumed), "the numbers are the same, whoever supplied them");
        assertFalse(declared.equals(assumed), "though the two records are not equal");
        assertFalse(declared.sameFormatAs(other));
        assertFalse(declared.sameFormatAs(null));
    }

    @Test
    @DisplayName("The description names what is missing, never the format's own letter")
    void humanReadable() {
        FormatSpec format = new FormatSpec(Unit.INCH, 2, 4, FormatSpec.ZeroSuppression.LEADING, true);
        assertEquals("2:4 inch, leading zeros suppressed", format.toString());
        assertNotNull(FormatSpec.ZeroSuppression.NONE.getDescription());
    }
}
