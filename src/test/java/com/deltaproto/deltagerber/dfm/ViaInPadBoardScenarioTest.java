package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.spec.BoardSpecification;
import com.deltaproto.deltagerber.spec.PcbAnalyzer;
import com.deltaproto.deltagerber.spec.PcbFile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-board via-in-pad scenarios, driven through {@link PcbAnalyzer} the way a quoting flow calls
 * it: files in, one verdict out.
 *
 * <p>The two boards here are the two answers that matter, and they are built from the geometry that
 * separates real production sets rather than from any one of them — a heat-spreader board where
 * every hole in a pad is thermal and no via has to be plugged, and a fine-pitch board where the vias
 * sit in lands a fraction of a square millimetre and every one of them does. Both answer {@code TRUE}
 * to {@link BoardSpecification#hasViaInPad()}; only the second answers {@code TRUE} to
 * {@link BoardSpecification#requiresFilledAndCappedVias()}, and that gap is the whole point of the
 * check.
 *
 * <p>Fixtures are synthetic and self-contained — round pad sizes, a clean origin, generic file names
 * — so the test carries the shape of the problem and none of anyone's artwork.
 */
class ViaInPadBoardScenarioTest {

    // ------------------------------------------------------------------------
    // Board 1: every via in pad is thermal — no fill-and-cap process
    // ------------------------------------------------------------------------

    /**
     * A power board: two 8×4 mm heat-spreader lands each stitched with a pair of ⌀0.6 mm vias, and a
     * 4×2.5 mm land under a QFN carrying a 2×2 via field. Every hole sits in a pad, and every pad is
     * a heat spreader — by the via-field rule and by the area rule alike.
     */
    private static List<PcbFile> heatSpreaderBoard() {
        Paste paste = new Paste();
        int heatLand = paste.rectangle(8.0, 4.0);       // 32.0 mm²
        int qfnLand = paste.rectangle(4.0, 2.5);        // 10.0 mm²
        paste.flash(heatLand, 15, 10);
        paste.flash(heatLand, 15, 25);
        paste.flash(qfnLand, 40, 15);

        Drill drill = new Drill();
        drill.hits(0.6, 13, 10, 17, 10);                        // stitching the first heat land
        drill.hits(0.6, 13, 25, 17, 25);                        // ... and the second
        drill.hits(0.3, 39, 14, 41, 14, 39, 16, 41, 16);        // the QFN's via field
        drill.hits(0.8, 50, 30);                                // a plain through-hole, in no pad

        return set(outline(60, 40), paste.build(), drill.build());
    }

    @Test
    void aBoardWhoseViasInPadAreAllThermalNeedsNoFillProcess() {
        BoardSpecification spec = new PcbAnalyzer().analyze(heatSpreaderBoard());

        assertEquals(Boolean.TRUE, spec.hasViaInPad());                  // holes do sit in pads ...
        assertEquals(Boolean.FALSE, spec.requiresFilledAndCappedVias()); // ... and none needs plugging
        assertEquals(8, spec.getViaInPadCount());                        // the lone through-hole is not one
        assertEquals(3, spec.getViaInPadGroups().size());
        assertEquals(3, spec.getViaInPad().getThermalGroups().size());
        assertTrue(spec.getViaInPad().getFilledAndCappedGroups().isEmpty());
    }

    @Test
    void aStitchedHeatLandIsThermalByBothSignalsAtOnce() {
        BoardSpecification spec = new PcbAnalyzer().analyze(heatSpreaderBoard());
        ViaInPadGroup land = largestPad(spec);

        assertEquals(32.0, land.getPadAreaMm2(), 1e-6);
        assertEquals(2, land.getViaCount());                     // a via pair — nobody stitches a signal land
        assertEquals(0.6, land.getViaDiameterMm(), 1e-3);
        assertEquals(56.6, land.getPadToViaAreaRatio(), 0.1);    // ... and 56× the hole area besides
        assertTrue(land.isLikelyThermal());
    }

    // ------------------------------------------------------------------------
    // Board 2: fine-pitch lands — the vias have to be filled and capped
    // ------------------------------------------------------------------------

    /**
     * A dense board: a 5×5 BGA field on 0.5 mm pitch whose ⌀0.23 mm lands each take a ⌀0.15 mm via,
     * a handful of larger lands, and one true heat spreader. The BGA lands hold barely twice the
     * paste their own hole displaces, so the joints starve unless the vias are filled and capped.
     */
    private static List<PcbFile> finePitchBoard() {
        Paste paste = new Paste();
        int ball = paste.circle(0.23);                  // 0.0415 mm²
        int chip = paste.rectangle(0.7, 0.6);           // 0.42 mm²
        int wide = paste.rectangle(1.3, 0.7);           // 0.91 mm² — over the ratio, under the floor
        int spreader = paste.rectangle(2.1, 2.0);       // 4.2 mm²

        Drill drill = new Drill();
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 5; col++) {
                double x = 10 + col * 0.5;
                double y = 10 + row * 0.5;
                paste.flash(ball, x, y);
                drill.hits(0.15, x, y);
            }
        }
        paste.flash(chip, 20, 8);
        paste.flash(chip, 22, 8);
        drill.hits(0.2, 20, 8, 22, 8);
        paste.flash(wide, 20, 12);
        paste.flash(spreader, 24, 20);
        drill.hits(0.15, 20, 12, 24, 20);

        return set(outline(30, 30), paste.build(), drill.build());
    }

    @Test
    void aFinePitchBoardOfViasInSmallLandsForcesTheFillProcess() {
        BoardSpecification spec = new PcbAnalyzer().analyze(finePitchBoard());

        assertEquals(Boolean.TRUE, spec.hasViaInPad());
        assertEquals(Boolean.TRUE, spec.requiresFilledAndCappedVias());
        assertEquals(29, spec.getViaInPadCount());
        assertEquals(29, spec.getViaInPadGroups().size());
        // The one heat spreader among them does not rescue the other 28.
        assertEquals(1, spec.getViaInPad().getThermalGroups().size());
        assertEquals(28, spec.getViaInPad().getFilledAndCappedGroups().size());
    }

    @Test
    void aBallLandHoldsBarelyTwiceThePasteItsOwnHoleDisplaces() {
        BoardSpecification spec = new PcbAnalyzer().analyze(finePitchBoard());
        ViaInPadGroup ball = smallestPad(spec);

        assertEquals(Math.PI * 0.115 * 0.115, ball.getPadAreaMm2(), 1e-9);
        assertEquals(1, ball.getViaCount());
        assertEquals(0.15, ball.getViaDiameterMm(), 1e-3);
        assertEquals(2.4, ball.getPadToViaAreaRatio(), 0.1);
        assertTrue(ball.requiresFilledAndCapped());
    }

    @Test
    void aLandOverTheRatioButUnderTheAreaFloorStillNeedsFilling() {
        // 0.91 mm² against a ⌀0.15 hole is 51× the hole area — over the ratio on its own — yet the
        // land is under a square millimetre of paste, and the absolute floor is what catches it.
        BoardSpecification spec = new PcbAnalyzer().analyze(finePitchBoard());
        ViaInPadGroup land = padOfArea(spec, 0.91);

        assertTrue(land.getPadToViaAreaRatio() > ViaInPadPolicy.DEFAULT.getMinPadToViaAreaRatio());
        assertTrue(land.getPadAreaMm2() < ViaInPadPolicy.DEFAULT.getMinThermalPadAreaMm2());
        assertTrue(land.requiresFilledAndCapped());

        // Drop the floor below it and the same land reads as thermal — the floor is doing the work.
        assertTrue(land.isLikelyThermal(new ViaInPadPolicy(2, 25.0, 0.5)));
    }

    @Test
    void theHeatSpreaderOnTheFinePitchBoardIsTheOnePadLeftOpen() {
        BoardSpecification spec = new PcbAnalyzer().analyze(finePitchBoard());
        ViaInPadGroup spreader = spec.getViaInPad().getThermalGroups().get(0);

        assertEquals(4.2, spreader.getPadAreaMm2(), 1e-6);
        assertEquals(1, spreader.getViaCount());
        assertTrue(spreader.getPadToViaAreaRatio() > 200);   // paste to spare, many times over
    }

    // ------------------------------------------------------------------------
    // Fixture builders — plain Gerber and Excellon, in millimetres
    // ------------------------------------------------------------------------

    /** The three files the check needs: an outline to size the board, the paste, and the drill. */
    private static List<PcbFile> set(String edgeCuts, String paste, String drill) {
        List<PcbFile> files = new ArrayList<>();
        files.add(PcbFile.of("board-Edge_Cuts.gbr", edgeCuts));
        files.add(PcbFile.of("board-F_Paste.gbr", paste));
        files.add(PcbFile.of("board-PTH.drl", drill));
        return files;
    }

    /** A rectangular board outline stroked with a hairline aperture, from the origin. */
    private static String outline(double width, double height) {
        return "%FSLAX46Y46*%\n%MOMM*%\n%ADD10C,0.050000*%\nD10*\n"
                + xy(0, 0) + "D02*\n" + xy(width, 0) + "D01*\n" + xy(width, height) + "D01*\n"
                + xy(0, height) + "D01*\n" + xy(0, 0) + "D01*\nM02*\n";
    }

    /** Builds a top solder-paste layer: declare each pad shape once, then flash it where it goes. */
    private static final class Paste {
        private final StringBuilder apertures = new StringBuilder();
        private final StringBuilder flashes = new StringBuilder();
        private int nextCode = 10;

        int rectangle(double width, double height) {
            return declare(String.format(Locale.US, "R,%.6fX%.6f", width, height));
        }

        int circle(double diameter) {
            return declare(String.format(Locale.US, "C,%.6f", diameter));
        }

        private int declare(String body) {
            int code = nextCode++;
            apertures.append("%ADD").append(code).append(body).append("*%\n");
            return code;
        }

        void flash(int code, double x, double y) {
            flashes.append('D').append(code).append("*\n").append(xy(x, y)).append("D03*\n");
        }

        String build() {
            return "%FSLAX46Y46*%\n%MOMM*%\n" + apertures + flashes + "M02*\n";
        }
    }

    /** Builds a metric Excellon file, one tool per distinct diameter. */
    private static final class Drill {
        private final StringBuilder tools = new StringBuilder();
        private final StringBuilder body = new StringBuilder();
        private int nextTool = 1;

        /** Drill every {@code x, y} pair in {@code coordinates} with a {@code diameter} mm tool. */
        void hits(double diameter, double... coordinates) {
            int tool = nextTool++;
            tools.append(String.format(Locale.US, "T%dC%.3f%n", tool, diameter));
            body.append('T').append(tool).append('\n');
            for (int i = 0; i < coordinates.length; i += 2) {
                body.append(String.format("X%06dY%06d%n",
                        Math.round(coordinates[i] * 1000), Math.round(coordinates[i + 1] * 1000)));
            }
        }

        String build() {
            return "M48\nMETRIC,TZ\n" + tools + "%\n" + body + "M30\n";
        }
    }

    /** A coordinate pair in the 4.6 format the fixtures declare. */
    private static String xy(double x, double y) {
        return String.format("X%dY%d", Math.round(x * 1e6), Math.round(y * 1e6));
    }

    // ------------------------------------------------------------------------
    // Picking one pad out of a verdict
    // ------------------------------------------------------------------------

    private static ViaInPadGroup largestPad(BoardSpecification spec) {
        return spec.getViaInPadGroups().stream()
                .max((a, b) -> Double.compare(a.getPadAreaMm2(), b.getPadAreaMm2())).orElseThrow();
    }

    private static ViaInPadGroup smallestPad(BoardSpecification spec) {
        return spec.getViaInPadGroups().stream()
                .min((a, b) -> Double.compare(a.getPadAreaMm2(), b.getPadAreaMm2())).orElseThrow();
    }

    private static ViaInPadGroup padOfArea(BoardSpecification spec, double areaMm2) {
        return spec.getViaInPadGroups().stream()
                .filter(g -> Math.abs(g.getPadAreaMm2() - areaMm2) < 0.01).findFirst().orElseThrow();
    }
}
