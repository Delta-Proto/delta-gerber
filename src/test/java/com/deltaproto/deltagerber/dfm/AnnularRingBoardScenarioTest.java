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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole-board annular-ring scenarios, driven through {@link PcbAnalyzer} the way a quoting flow
 * calls it: files in, one number out.
 *
 * <p>Four boards, and each is a different answer. A standard four-layer board clears the rule
 * everywhere. A fine-feature board rings 0.05 mm on every via — nothing wrong with it, but it needs
 * a fabricator who can hold that. An Altium set whose drill was exported on a different origin has
 * to be pulled onto the copper before any of it means anything. And a KiCad set writes its drill
 * program as Gerber rather than Excellon, which changes nothing about the rings.
 *
 * <p>Fixtures are synthetic and self-contained — round pad sizes, a clean origin, generic file names
 * — so the test carries the shape of the problem and none of anyone's artwork.
 */
class AnnularRingBoardScenarioTest {

    // ------------------------------------------------------------------------
    // Board 1: a standard board that clears the rule
    // ------------------------------------------------------------------------

    /**
     * Four layers: ⌀0.9 via pads on a ⌀0.4 drill (ring 0.25) and ⌀1.8 component pads on a ⌀1.0
     * drill (ring 0.4), the inner layers carrying the via pads only. One ⌀3.2 non-plated mounting
     * hole, drilled straight through the copper with no pad at all.
     */
    private static List<PcbFile> standardBoard() {
        Copper outer = new Copper();
        int via = outer.circle(0.9);
        int pad = outer.circle(1.8);
        outer.flash(via, 10, 10);
        outer.flash(via, 12, 10);
        outer.flash(pad, 20, 10);
        outer.flash(pad, 22.54, 10);

        Copper inner = new Copper();
        int innerVia = inner.circle(0.9);
        inner.flash(innerVia, 10, 10);
        inner.flash(innerVia, 12, 10);

        Drill drill = new Drill();
        drill.plated();
        drill.hits(0.4, 10, 10, 12, 10);
        drill.hits(1.0, 20, 10, 22.54, 10);
        drill.nonPlated();
        drill.hits(3.2, 5, 20);

        List<PcbFile> files = new ArrayList<>(base(drill));
        files.add(PcbFile.of("board-F_Cu.gbr", outer.build()));
        files.add(PcbFile.of("board-In1_Cu.gbr", inner.build()));
        files.add(PcbFile.of("board-In2_Cu.gbr", inner.build()));
        files.add(PcbFile.of("board-B_Cu.gbr", outer.build()));
        return files;
    }

    @Test
    void aStandardBoardClearsTheRuleOnEveryLayer() {
        BoardSpecification spec = new PcbAnalyzer().analyze(standardBoard());

        assertEquals(0.25, spec.getMinAnnularRingMm(), 1e-9);
        assertEquals(Boolean.TRUE, spec.isAnnularRingWithinPolicy());
        assertTrue(spec.getAnnularRingViolations().isEmpty());
        assertFalse(spec.getAnnularRing().hasBreakout());
    }

    @Test
    void everyViaIsMeasuredOnAllFourLayersAndEveryComponentPadOnTwo() {
        AnnularRingResult rings = new PcbAnalyzer().analyze(standardBoard()).getAnnularRing();

        assertEquals(4, rings.getRings().size());
        AnnularRing via = rings.getWorstHole();
        assertEquals(4, via.getPads().size());                  // the inner layers carry via pads
        assertEquals(0.25, via.getMinRingMm(), 1e-9);

        AnnularRing componentPad = rings.getRings().stream()
                .filter(r -> r.getHoleDiameterMm() > 0.5).findFirst().orElseThrow();
        assertEquals(2, componentPad.getPads().size());         // outer layers only
        assertEquals(0.4, componentPad.getMinRingMm(), 1e-9);
    }

    @Test
    void theNonPlatedMountingHoleIsNotJudgedAtAll() {
        // It has no pad and needs none. Counting it would put a −1.6 mm "ring" on the board.
        AnnularRingResult rings = new PcbAnalyzer().analyze(standardBoard()).getAnnularRing();

        assertEquals(4, rings.getHoleCount());                  // the ⌀3.2 hole is not among them
        assertTrue(rings.getHolesWithoutPad().isEmpty());
        assertFalse(rings.hasBreakout());
    }

    // ------------------------------------------------------------------------
    // Board 2: fine features — every ring is legal, and none of it is standard capability
    // ------------------------------------------------------------------------

    /**
     * A dense two-layer board of ⌀0.25 pads on ⌀0.15 drills — a 0.05 mm ring on every via, which is
     * a real design and needs a fabricator who quotes it as one.
     */
    private static List<PcbFile> fineFeatureBoard() {
        Copper copper = new Copper();
        int via = copper.circle(0.25);
        Drill drill = new Drill();
        drill.plated();
        double[] holes = new double[40];
        for (int i = 0; i < 20; i++) {
            double x = 5 + i * 0.5;
            copper.flash(via, x, 10);
            holes[i * 2] = x;
            holes[i * 2 + 1] = 10;
        }
        drill.hits(0.15, holes);

        List<PcbFile> files = new ArrayList<>(base(drill));
        files.add(PcbFile.of("board-F_Cu.gbr", copper.build()));
        files.add(PcbFile.of("board-B_Cu.gbr", copper.build()));
        return files;
    }

    @Test
    void aFineFeatureBoardIsMeasuredHonestlyAndFailsTheStandardRule() {
        BoardSpecification spec = new PcbAnalyzer().analyze(fineFeatureBoard());

        assertEquals(0.05, spec.getMinAnnularRingMm(), 1e-9);
        assertEquals(Boolean.FALSE, spec.isAnnularRingWithinPolicy());
        assertEquals(20, spec.getAnnularRingViolations().size());
        assertFalse(spec.getAnnularRing().hasBreakout());        // tight is not broken out

        // It is nowhere near unmanufacturable, though — IPC-6012 Class 3 accepts 0.05 mm outside.
        assertEquals(Boolean.TRUE,
                spec.isAnnularRingWithinPolicy(AnnularRingPolicy.IPC_6012_CLASS_3));
    }

    // ------------------------------------------------------------------------
    // Board 3: the drill was exported on another origin
    // ------------------------------------------------------------------------

    /**
     * The standard board again, with every hole written 150 mm away in X and Y — the Altium export
     * that references the sheet origin while the Gerbers reference the board's.
     */
    private static List<PcbFile> boardWithDisplacedDrill() {
        List<PcbFile> files = new ArrayList<>();
        for (PcbFile file : standardBoard()) {
            files.add(file.getFileName().endsWith(".drl")
                    ? PcbFile.of(file.getFileName(), displaced(file.getContent()))
                    : file);
        }
        return files;
    }

    @Test
    void aDrillOnAForeignOriginIsPulledOntoTheCopperBeforeItIsMeasured() {
        BoardSpecification spec = new PcbAnalyzer().analyze(boardWithDisplacedDrill());

        // Exactly the answer the aligned board gives — the offset is recovered, not approximated.
        assertEquals(0.25, spec.getMinAnnularRingMm(), 1e-9);
        assertEquals(Boolean.TRUE, spec.isAnnularRingWithinPolicy());
        assertEquals(4, spec.getAnnularRing().getRings().size());
    }

    // ------------------------------------------------------------------------
    // Board 4: the drill program written as Gerber X2 rather than Excellon
    // ------------------------------------------------------------------------

    @Test
    void aDrillProgramWrittenAsGerberX2IsStillADrillProgram() {
        // KiCad can export the drill as an X2 Gerber, where each hole is a flashed circle of the
        // tool's diameter and the file declares itself with .FileFunction. The holes are the same
        // holes, so the rings are the same rings.
        Copper copper = new Copper();
        int via = copper.circle(0.9);
        copper.flash(via, 10, 10);
        copper.flash(via, 12, 10);

        String x2Drill = "%TF.FileFunction,Plated,1,2,PTH,Drill*%\n%FSLAX46Y46*%\n%MOMM*%\n"
                + "%TA.AperFunction,ViaDrill*%\n%ADD10C,0.400000*%\n%TD*%\nD10*\n"
                + xy(10, 10) + "D03*\n" + xy(12, 10) + "D03*\nM02*\n";

        List<PcbFile> files = new ArrayList<>();
        files.add(PcbFile.of("board-Edge_Cuts.gbr", outline(30, 30)));
        files.add(PcbFile.of("board-F_Cu.gbr", copper.build()));
        files.add(PcbFile.of("board-B_Cu.gbr", copper.build()));
        files.add(PcbFile.of("board-PTH-drl.gbr", x2Drill));

        BoardSpecification spec = new PcbAnalyzer().analyze(files);
        assertEquals(0.25, spec.getMinAnnularRingMm(), 1e-9);
        assertEquals(2, spec.getAnnularRing().getRings().size());
        // The X2 file states plating outright, so a hole without a pad would be a real fault.
        assertEquals(Boolean.TRUE, spec.getAnnularRing().getRings().get(0).getPlated());
    }

    // ------------------------------------------------------------------------
    // A set that cannot be judged
    // ------------------------------------------------------------------------

    @Test
    void aSetWithNoDrillLeavesTheRingUndetermined() {
        Copper copper = new Copper();
        copper.flash(copper.circle(0.9), 10, 10);
        List<PcbFile> files = new ArrayList<>();
        files.add(PcbFile.of("board-Edge_Cuts.gbr", outline(30, 30)));
        files.add(PcbFile.of("board-F_Cu.gbr", copper.build()));

        BoardSpecification spec = new PcbAnalyzer().analyze(files);
        assertNull(spec.getMinAnnularRingMm());
        assertNull(spec.getAnnularRing());
        assertNull(spec.isAnnularRingWithinPolicy());
    }

    @Test
    void aSpecificationRebuiltFromPersistedLayersCannotReDeriveTheRing() {
        // It is a relationship between the drill and the copper, not a per-layer measurement —
        // the same reason via-in-pad cannot be re-derived either.
        BoardSpecification spec = new PcbAnalyzer().analyze(standardBoard());
        BoardSpecification rebuilt = BoardSpecification.from(spec.getLayers());

        assertEquals(0.25, spec.getMinAnnularRingMm(), 1e-9);
        assertNull(rebuilt.getMinAnnularRingMm());
        assertEquals(spec.getSizeXMm(), rebuilt.getSizeXMm());       // everything else survives
    }

    // ------------------------------------------------------------------------
    // Fixture builders — plain Gerber and Excellon, in millimetres
    // ------------------------------------------------------------------------

    /** The outline and the drill, which every board here shares. */
    private static List<PcbFile> base(Drill drill) {
        List<PcbFile> files = new ArrayList<>();
        files.add(PcbFile.of("board-Edge_Cuts.gbr", outline(30, 30)));
        files.add(PcbFile.of("board-PTH.drl", drill.build()));
        return files;
    }

    /** A rectangular board outline stroked with a hairline aperture, from the origin. */
    private static String outline(double width, double height) {
        return "%FSLAX46Y46*%\n%MOMM*%\n%ADD10C,0.050000*%\nD10*\n"
                + xy(0, 0) + "D02*\n" + xy(width, 0) + "D01*\n" + xy(width, height) + "D01*\n"
                + xy(0, height) + "D01*\n" + xy(0, 0) + "D01*\nM02*\n";
    }

    /** Builds a copper layer: declare each pad shape once, then flash it where it goes. */
    private static final class Copper {
        private final StringBuilder apertures = new StringBuilder();
        private final StringBuilder flashes = new StringBuilder();
        private int nextCode = 10;

        int circle(double diameter) {
            int code = nextCode++;
            apertures.append(String.format(Locale.US, "%%ADD%dC,%.6f*%%%n", code, diameter));
            return code;
        }

        void flash(int code, double x, double y) {
            flashes.append('D').append(code).append("*\n").append(xy(x, y)).append("D03*\n");
        }

        String build() {
            return "%FSLAX46Y46*%\n%MOMM*%\n" + apertures + flashes + "M02*\n";
        }
    }

    /** Builds a metric Excellon file, one tool per call, with the plating it declares. */
    private static final class Drill {
        private final StringBuilder tools = new StringBuilder();
        private final StringBuilder body = new StringBuilder();
        private int nextTool = 1;

        void plated() {
            tools.append(";TYPE=PLATED\n");
        }

        void nonPlated() {
            tools.append(";TYPE=NON_PLATED\n");
        }

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

    /** Rewrite a drill file's coordinates 150 mm away in both axes. */
    private static String displaced(String drill) {
        StringBuilder out = new StringBuilder();
        for (String line : drill.split("\n")) {
            if (line.matches("X\\d+Y\\d+")) {
                int x = Integer.parseInt(line.substring(1, line.indexOf('Y')));
                int y = Integer.parseInt(line.substring(line.indexOf('Y') + 1));
                out.append(String.format("X%06dY%06d%n", x + 150_000, y + 150_000));
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /** A coordinate pair in the 4.6 format the fixtures declare. */
    private static String xy(double x, double y) {
        return String.format("X%dY%d", Math.round(x * 1e6), Math.round(y * 1e6));
    }
}
