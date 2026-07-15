package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcbAnalyzerTest {

    /** A 10×10 mm board profile, traced once with a 0.05 mm aperture. */
    private static final String EDGE_CUTS = String.join("\n",
            "%FSLAX46Y46*%",
            "%MOMM*%",
            "%ADD10C,0.050000*%",
            "D10*",
            "X0Y0D02*",
            "X10000000Y0D01*",
            "X10000000Y10000000D01*",
            "X0Y10000000D01*",
            "X0Y0D01*",
            "M02*");

    /**
     * Top copper for the same 10×10 mm board, with two apertures:
     * D10 (0.05 mm) traces the perimeter, exactly on the board edge; D11 (0.20 mm) draws three
     * tracks well inside it. The narrowest track is 0.2 mm — the 0.05 mm perimeter is not a track.
     */
    private static final String F_CU = String.join("\n",
            "%FSLAX46Y46*%",
            "%MOMM*%",
            "%ADD10C,0.050000*%",
            "%ADD11C,0.200000*%",
            "D10*",
            "X0Y0D02*",
            "X10000000Y0D01*",
            "X10000000Y10000000D01*",
            "X0Y10000000D01*",
            "X0Y0D01*",
            "D11*",
            "X2000000Y2000000D02*",
            "X8000000Y8000000D01*",
            "X8000000Y2000000D01*",
            "X2000000Y8000000D01*",
            "M02*");

    private static GerberDocument gerber(String content) {
        return new GerberParser().parse(content);
    }

    @Nested
    @DisplayName("Minimum track width")
    class MinTrackWidth {

        @Test
        @DisplayName("Without an outline, the smallest drawn aperture wins")
        void withoutOutline() {
            assertEquals(50.0, PcbAnalyzer.minTrackWidthUm(gerber(F_CU), null), 1e-6);
        }

        @Test
        @DisplayName("With an outline, the perimeter trace is excluded")
        void withOutline() {
            // Every endpoint of D10 sits on the profile, so after the 0.01 mm inward shrink it has
            // no fully-inside draw and drops out. D11's tracks run inside (2..8, 2..8) and win.
            BoundingBox outline = gerber(EDGE_CUTS).calculatePathBoundingBox();
            assertEquals(200.0, PcbAnalyzer.minTrackWidthUm(gerber(F_CU), outline), 1e-6);
        }

        @Test
        @DisplayName("An outline from another board is not trusted — too few draws land inside it")
        void mismatchedOutlineFallsBack() {
            BoundingBox elsewhere = new BoundingBox(100, 100, 101, 101);
            assertEquals(50.0, PcbAnalyzer.minTrackWidthUm(gerber(F_CU), elsewhere), 1e-6);
        }

        @Test
        @DisplayName("Pads and pours are not tracks")
        void flashesAndRegionsAreIgnored() {
            // A 0.1 mm flashed pad and a region, plus one 0.3 mm track. Only the track counts.
            String layer = String.join("\n",
                    "%FSLAX46Y46*%",
                    "%MOMM*%",
                    "%ADD10C,0.100000*%",
                    "%ADD11C,0.300000*%",
                    "D10*",
                    "X1000000Y1000000D03*",
                    "G36*",
                    "X5000000Y5000000D02*",
                    "X6000000Y5000000D01*",
                    "X6000000Y6000000D01*",
                    "X5000000Y5000000D01*",
                    "G37*",
                    "D11*",
                    "X2000000Y2000000D02*",
                    "X3000000Y3000000D01*",
                    "M02*");
            assertEquals(300.0, PcbAnalyzer.minTrackWidthUm(gerber(layer), null), 1e-6);
        }

        @Test
        @DisplayName("A layer that draws no track has no minimum — a plane, for instance")
        void noTracksMeansNull() {
            String planeLayer = String.join("\n",
                    "%FSLAX46Y46*%", "%MOMM*%", "%ADD10C,0.250000*%",
                    "D10*", "X1000000Y1000000D03*", "M02*");
            assertNull(PcbAnalyzer.minTrackWidthUm(gerber(planeLayer), null));
        }
    }

    @Nested
    @DisplayName("Minimum drill diameter")
    class MinDrill {

        private Double minDrill(String content) {
            return PcbAnalyzer.minDrillDiameterMm(new ExcellonParser().parse(content));
        }

        @Test
        void metricToolTable() {
            String drill = """
                    M48
                    ;FILE_FORMAT=4:4
                    METRIC,LZ
                    ;TYPE=PLATED
                    T01F00S00C0.2000
                    T02F00S00C0.3000
                    T03F00S00C0.9200
                    %
                    T01
                    X003086Y00077
                    M30
                    """;
            assertEquals(0.2, minDrill(drill), 1e-6);
        }

        @Test
        @DisplayName("An imperial tool table is normalised to millimetres")
        void imperialToolTable() {
            String drill = """
                    M48
                    ;FILE_FORMAT=2:4
                    INCH,TZ
                    ;TYPE=PLATED
                    T01F00S00C0.0118
                    T02F00S00C0.0120
                    T03F00S00C0.0236
                    %
                    T01
                    X15475Y2275
                    M30
                    """;
            assertEquals(0.0118 * 25.4, minDrill(drill), 1e-6);   // 0.29972 mm
        }

        @Test
        @DisplayName("A Gerber X2 drill file states its holes as apertures, not Excellon tools")
        void gerberX2DrillFile() {
            String drill = String.join("\n",
                    "%TF.FileFunction,Plated,1,2,PTH,Drill*%",
                    "%FSLAX46Y46*%",
                    "%MOMM*%",
                    "%ADD10C,0.330200*%",
                    "%ADD11C,0.750000*%",
                    "D10*",
                    "X1000000Y1000000D03*",
                    "M02*");
            assertEquals(0.3302, PcbAnalyzer.minDrillDiameterMm(gerber(drill)), 1e-6);

            // …and it reaches the board specification, despite being named and shaped like a Gerber.
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(PcbFile.of("board-PTH.gbr", drill)));
            assertEquals(LayerFunction.DRILL_PLATED, spec.getLayers().get(0).getFunction());
            assertTrue(spec.hasDrill());
            assertEquals(0.3302, spec.getMinDrillDiameterMm(), 1e-6);
        }

        @Test
        @DisplayName("An X2 non-plated drill file is told apart from a plated one")
        void gerberX2NonPlatedDrillFile() {
            String drill = String.join("\n",
                    "%TF.FileFunction,NonPlated,1,4,NPTH,Drill*%",
                    "%FSLAX45Y45*%",
                    "%MOMM*%",
                    "%ADD81C,3.00000*%",
                    "D81*",
                    "X6713046Y9085317D03*",
                    "M02*");
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(PcbFile.of("test_NPTH_Drill.gbr", drill)));
            assertEquals(LayerFunction.DRILL_NONPLATED, spec.getLayers().get(0).getFunction());
            assertEquals(3.0, spec.getMinDrillDiameterMm(), 1e-6);
        }
    }

    @Nested
    @DisplayName("Board size")
    class BoardSize {

        @Test
        @DisplayName("The outline measures to its centreline, not to the edge of its own trace")
        void outlineMeasuresToCentreline() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-Edge_Cuts.gbr", EDGE_CUTS),
                    PcbFile.of("board-F_Cu.gbr", F_CU)));
            // Inked, the 0.05 mm profile trace spans 10.05 mm. The board is 10 mm.
            assertEquals(10.0, spec.getSizeXMm(), 1e-6);
            assertEquals(10.0, spec.getSizeYMm(), 1e-6);
        }

        @Test
        @DisplayName("Arcs are measured exactly — a circular board is as wide as its diameter")
        void circularOutline() {
            // A full circle of radius 16 mm, written the way Gerber writes one: start where you end.
            String circle = String.join("\n",
                    "%FSLAX44Y44*%", "%MOMM*%", "G75*",
                    "%ADD11C,0.0500*%",
                    "D11*",
                    "X160000Y0D02*",
                    "G03*",
                    "X160000Y0I-160000J0D01*",
                    "M02*");
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(PcbFile.of("board.GKO", circle)));
            assertEquals(32.0, spec.getSizeXMm(), 1e-6);
            assertEquals(32.0, spec.getSizeYMm(), 1e-6);
        }

        @Test
        @DisplayName("An imperial rounded-rectangle outline")
        void imperialRoundedRectangle() {
            String outline = String.join("\n",
                    "G04 #@! TF.GenerationSoftware,Altium Limited,Altium Designer,25.1.2 (22)*",
                    "%FSLAX24Y24*%", "%MOIN*%", "G70*", "G01*", "G75*",
                    "%ADD11C,0.0039*%",
                    "D11*",
                    "X315Y0D02*", "G03*", "X0Y315I-315J0D01*", "G01*",
                    "X29252D02*", "G03*", "X28937Y0I0J-315D01*", "G01*",
                    "Y21466D02*", "G03*", "X29252Y21151I315J0D01*", "G01*",
                    "X0D02*", "G03*", "X315Y21466I0J315D01*", "G01*",
                    "X-0Y21142D02*", "X0Y315D01*",
                    "X315Y21466D02*", "X28937D01*",
                    "X29252Y21151D02*", "X29252Y315D01*",
                    "X315Y0D02*", "X28937D01*",
                    "M02*");
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(PcbFile.of("board.GKO", outline)));
            assertTrue(spec.hasOutline());
            assertEquals(2.9252 * 25.4, spec.getSizeXMm(), 1e-4);   // 74.300 mm
            assertEquals(2.1466 * 25.4, spec.getSizeYMm(), 1e-4);   // 54.524 mm
        }

        @Test
        @DisplayName("Without an outline, the board is the inked extent of the artwork")
        void fallsBackToArtworkExtent() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(PcbFile.of("board-F_Cu.gbr", F_CU)));
            assertFalse(spec.hasOutline());
            // The 0.05 mm perimeter trace inks 0.025 mm past the path on each side.
            assertEquals(10.05, spec.getSizeXMm(), 1e-6);
        }

        @Test
        @DisplayName("An outline file that draws nothing must not leave the board sizeless")
        void emptyOutlineDoesNotWin() {
            String empty = "%FSLAX46Y46*%\n%MOMM*%\n%ADD10C,0.050000*%\nM02*\n";
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board.GKO", empty),
                    PcbFile.of("board-F_Cu.gbr", F_CU)));
            assertFalse(spec.hasOutline(), "an empty .GKO is not an outline");
            assertEquals(10.05, spec.getSizeXMm(), 1e-6);
        }
    }

    @Nested
    @DisplayName("Processes the board needs")
    class Processes {

        private static final String HEADER = "%FSLAX46Y46*%\n%MOMM*%\n%ADD10C,0.200000*%\n";
        private static final String WITH_PADS = HEADER + "D10*\nX1000000Y1000000D03*\nM02*\n";
        private static final String EMPTY = HEADER + "M02*\n";

        @Test
        void sidesAreDerivedFromWhichLayersArePresent() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board.GTS", WITH_PADS),
                    PcbFile.of("board.GBS", WITH_PADS),
                    PcbFile.of("board.GTO", WITH_PADS)));
            assertEquals(BoardSide.BOTH, spec.getSolderMaskSide());
            assertEquals(BoardSide.TOP, spec.getSilkscreenSide());
            assertEquals(BoardSide.NONE, spec.getStencilSide());
        }

        @Test
        @DisplayName("An empty paste layer needs no stencil")
        void stencilNeedsPasteThatActuallyCarriesPads() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board.GTP", WITH_PADS),
                    PcbFile.of("board.GBP", EMPTY)));
            assertEquals(BoardSide.TOP, spec.getStencilSide());
        }

        @Test
        void copperLayersAreCounted() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board.GTL", WITH_PADS),
                    PcbFile.of("board.G1", WITH_PADS),
                    PcbFile.of("board.G2", WITH_PADS),
                    PcbFile.of("board.GBL", WITH_PADS)));
            assertEquals(4, spec.getCopperLayerCount());
            assertTrue(spec.hasCopper());
            assertFalse(spec.hasDrill());
        }
    }

    @Nested
    @DisplayName("Inner copper numbering")
    class InnerCopperNumbering {

        private static final String GEOMETRY = "D10*\nX0Y0D02*\nX1000000Y1000000D01*\nM02*\n";

        /** KiCad states the absolute stack position, so its first inner layer is L2. */
        private static String x2Copper(String function) {
            return "%TF.FileFunction," + function + "*%\n%FSLAX46Y46*%\n%MOMM*%\n%ADD10C,0.200000*%\n" + GEOMETRY;
        }

        private Integer numberOf(BoardSpecification spec, String file) {
            return spec.getLayers().stream().filter(l -> l.getFileName().equals(file))
                    .findFirst().orElseThrow().getLayerNumber();
        }

        @Test
        @DisplayName("A KiCad six-layer board numbers its inner layers 1..4, not 2..5")
        void gerberX2IsShiftedDown() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-F_Cu.gbr", x2Copper("Copper,L1,Top")),
                    PcbFile.of("board-In1_Cu.gbr", x2Copper("Copper,L2,Inr")),
                    PcbFile.of("board-In2_Cu.gbr", x2Copper("Copper,L3,Inr")),
                    PcbFile.of("board-In3_Cu.gbr", x2Copper("Copper,L4,Inr")),
                    PcbFile.of("board-In4_Cu.gbr", x2Copper("Copper,L5,Inr")),
                    PcbFile.of("board-B_Cu.gbr", x2Copper("Copper,L6,Bot"))));

            assertEquals(6, spec.getCopperLayerCount());
            assertEquals(1, numberOf(spec, "board-In1_Cu.gbr"));
            assertEquals(2, numberOf(spec, "board-In2_Cu.gbr"));
            assertEquals(3, numberOf(spec, "board-In3_Cu.gbr"));
            assertEquals(4, numberOf(spec, "board-In4_Cu.gbr"));
            assertNull(numberOf(spec, "board-F_Cu.gbr"), "outer copper carries no index");
            assertNull(numberOf(spec, "board-B_Cu.gbr"));
        }

        @Test
        @DisplayName("A Protel board already counts from 1 and is left alone")
        void protelIsUnchanged() {
            String copper = "%FSLAX46Y46*%\n%MOMM*%\n%ADD10C,0.200000*%\n" + GEOMETRY;
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board.GTL", copper),
                    PcbFile.of("board.G1", copper),
                    PcbFile.of("board.G2", copper),
                    PcbFile.of("board.GBL", copper)));
            assertEquals(1, numberOf(spec, "board.G1"));
            assertEquals(2, numberOf(spec, "board.G2"));
        }

        @Test
        @DisplayName("A classification handed in by the caller is final, and is not renumbered")
        void suppliedClassificationsAreNotTouched() {
            // What dp-1 passes when a person has already typed the layer, or an earlier analysis
            // stored it. Renumbering here would overwrite the very answer we were asked to honour.
            LayerClassification stored =
                    new LayerClassification("inner copper 3", LayerFunction.COPPER, LayerSide.INNER, 3);
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-In1_Cu.gbr", x2Copper("Copper,L2,Inr"), stored)));
            assertEquals(3, numberOf(spec, "board-In1_Cu.gbr"));
        }
    }

    @Nested
    @DisplayName("Analysis depth")
    class Depth {

        /** Stands in for the silkscreen: geometry that contributes nothing to the specification. */
        private static final String SILKSCREEN = String.join("\n",
                "%FSLAX46Y46*%",
                "%MOMM*%",
                "%ADD10C,0.150000*%",
                "D10*",
                "X1000000Y1000000D02*",
                "X9000000Y9000000D01*",
                "M02*");

        private List<PcbFile> set(String outline) {
            List<PcbFile> files = new java.util.ArrayList<>();
            if (outline != null) {
                files.add(PcbFile.of("board-Edge_Cuts.gbr", outline));
            }
            files.add(PcbFile.of("board-F_Cu.gbr", F_CU));
            files.add(PcbFile.of("board-F_SilkS.gbr", SILKSCREEN));
            files.add(PcbFile.of("board-F_Paste.gbr", SILKSCREEN));
            return files;
        }

        private AnalyzedLayer layer(BoardSpecification spec, String name) {
            return spec.getLayers().stream().filter(l -> l.getFileName().equals(name)).findFirst().orElseThrow();
        }

        @Test
        @DisplayName("The specification is the same either way")
        void bothDepthsAgree() {
            BoardSpecification full = new PcbAnalyzer().analyze(set(EDGE_CUTS), AnalysisDepth.FULL);
            BoardSpecification lean = new PcbAnalyzer().analyze(set(EDGE_CUTS), AnalysisDepth.SPECIFICATION);

            assertEquals(full.getSizeXMm(), lean.getSizeXMm());
            assertEquals(full.getSizeYMm(), lean.getSizeYMm());
            assertEquals(full.getCopperLayerCount(), lean.getCopperLayerCount());
            assertEquals(full.getMinTrackWidthUm(), lean.getMinTrackWidthUm());
            assertEquals(full.getMinDrillDiameterMm(), lean.getMinDrillDiameterMm());
            assertEquals(full.getSilkscreenSide(), lean.getSilkscreenSide());
            assertEquals(full.getStencilSide(), lean.getStencilSide(), "an empty paste layer still needs no stencil");
        }

        @Test
        @DisplayName("A layer that cannot change the answer is never parsed")
        void silkscreenIsSkippedWhenAnOutlineExists() {
            BoardSpecification lean = new PcbAnalyzer().analyze(set(EDGE_CUTS), AnalysisDepth.SPECIFICATION);

            assertNull(layer(lean, "board-F_SilkS.gbr").getBounds(), "silkscreen must not be measured");
            // Still answered, because the stencil side depends on it — just without building geometry.
            assertTrue(layer(lean, "board-F_Paste.gbr").getHasGeometry());
            // The layers the specification does depend on are measured as always.
            assertNotNull(layer(lean, "board-Edge_Cuts.gbr").getBounds());
            assertNotNull(layer(lean, "board-F_Cu.gbr").getBounds());
            assertEquals(200.0, lean.getMinTrackWidthUm(), 1e-6);
        }

        @Test
        @DisplayName("Without an outline, every layer bounds the board and every layer is parsed")
        void nothingIsSkippedWhenTheSizeDependsOnIt() {
            BoardSpecification lean = new PcbAnalyzer().analyze(set(null), AnalysisDepth.SPECIFICATION);

            assertFalse(lean.hasOutline());
            assertNotNull(layer(lean, "board-F_SilkS.gbr").getBounds());
            // The silkscreen reaches (9,9) and inks 0.075 past it; the copper's 0.05 trace inks to
            // 10.025. Both bound the board, so dropping the silkscreen would have been wrong.
            assertEquals(new PcbAnalyzer().analyze(set(null), AnalysisDepth.FULL).getSizeXMm(),
                    lean.getSizeXMm());
        }

        @Test
        @DisplayName("FULL is the default")
        void defaultIsFull() {
            BoardSpecification spec = new PcbAnalyzer().analyze(set(EDGE_CUTS));
            assertNotNull(layer(spec, "board-F_SilkS.gbr").getBounds());
        }
    }

    @Nested
    @DisplayName("Via in pad")
    class ViaInPad {

        /** Top paste with two openings: a 1×1 mm pad at (10,10) and a ⌀1 mm pad at (20,20). */
        private static final String F_PASTE = String.join("\n",
                "%FSLAX46Y46*%",
                "%MOMM*%",
                "%ADD10R,1.000000X1.000000*%",
                "%ADD11C,1.000000*%",
                "D10*",
                "X10000000Y10000000D03*",
                "D11*",
                "X20000000Y20000000D03*",
                "M02*");

        /** Excellon drill, 3.3 format, full-width coordinates so the origin is unambiguous. */
        private static String drill(String... xyMm) {
            StringBuilder sb = new StringBuilder("M48\nMETRIC,TZ\nT1C0.300\n%\nT1\n");
            for (String xy : xyMm) {
                sb.append(xy).append('\n');
            }
            return sb.append("M30\n").toString();
        }

        @Test
        @DisplayName("Holes inside paste openings are flagged, with count and side")
        void detectsViaInPad() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-Edge_Cuts.gbr", EDGE_CUTS),
                    PcbFile.of("board-F_Cu.gbr", F_CU),
                    PcbFile.of("board-F_Paste.gbr", F_PASTE),
                    // Two vias land on the pads at (10,10) and (20,20).
                    PcbFile.of("board-PTH.drl", drill("X010000Y010000", "X020000Y020000"))));

            assertEquals(Boolean.TRUE, spec.hasViaInPad());
            assertEquals(2, spec.getViaInPadCount());
            assertEquals(BoardSide.TOP, spec.getViaInPadSide());
        }

        @Test
        @DisplayName("A drill clear of every pad is not a via in pad")
        void noViaInPadWhenHolesMissThePads() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-Edge_Cuts.gbr", EDGE_CUTS),
                    PcbFile.of("board-F_Paste.gbr", F_PASTE),
                    PcbFile.of("board-PTH.drl", drill("X005000Y005000", "X015000Y015000"))));

            assertEquals(Boolean.FALSE, spec.hasViaInPad());
            assertEquals(0, spec.getViaInPadCount());
            assertEquals(BoardSide.NONE, spec.getViaInPadSide());
        }

        @Test
        @DisplayName("Without a paste layer or a drill, via-in-pad is left unknown")
        void unknownWhenInputsMissing() {
            BoardSpecification noDrill = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-Edge_Cuts.gbr", EDGE_CUTS),
                    PcbFile.of("board-F_Paste.gbr", F_PASTE)));
            assertNull(noDrill.hasViaInPad());
            assertNull(noDrill.getViaInPadSide());

            BoardSpecification noPaste = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-Edge_Cuts.gbr", EDGE_CUTS),
                    PcbFile.of("board-PTH.drl", drill("X010000Y010000"))));
            assertNull(noPaste.hasViaInPad());
        }

        @Test
        @DisplayName("Detected even at SPECIFICATION depth, where paste is otherwise skipped")
        void detectedAtSpecificationDepth() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-Edge_Cuts.gbr", EDGE_CUTS),
                    PcbFile.of("board-F_Paste.gbr", F_PASTE),
                    PcbFile.of("board-PTH.drl", drill("X010000Y010000"))),
                    AnalysisDepth.SPECIFICATION);
            assertEquals(Boolean.TRUE, spec.hasViaInPad());
            assertEquals(1, spec.getViaInPadCount());
        }
    }

    @Nested
    @DisplayName("Degenerate input")
    class Degenerate {

        @Test
        void emptySetYieldsAnEmptySpecification() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of());
            assertNull(spec.getSizeXMm());
            assertNull(spec.getCopperLayerCount());
            assertNull(spec.getSolderMaskSide(), "no files means the question is unanswered");
            assertFalse(spec.hasOutline());
        }

        @Test
        void nullContentStillClassifiesByName() {
            BoardSpecification spec = new PcbAnalyzer().analyze(
                    List.of(PcbFile.of("board.GTL", (byte[]) null)));
            assertEquals(1, spec.getCopperLayerCount());
            assertNull(spec.getSizeXMm());
        }

        @Test
        void unparseableContentKeepsItsClassification() {
            BoardSpecification spec = new PcbAnalyzer().analyze(
                    List.of(PcbFile.of("board.GTL", "this is not a gerber file at all")));
            AnalyzedLayer layer = spec.getLayers().get(0);
            assertEquals(LayerFunction.COPPER, layer.getFunction());
            assertNull(layer.getMinTrackWidthUm());
        }

        @Test
        @DisplayName("Measurements can be rebuilt from storage without the files")
        void specificationDerivesFromPersistedMeasurements() {
            AnalyzedLayer outline = AnalyzedLayer.builder("board.GKO")
                    .classification(LayerFunction.OUTLINE, com.deltaproto.deltagerber.classify.LayerSide.NA, null)
                    .bounds(0.0, 0.0, 32.0, 32.0)
                    .build();
            AnalyzedLayer copper = AnalyzedLayer.builder("board.GTL")
                    .classification(LayerFunction.COPPER, com.deltaproto.deltagerber.classify.LayerSide.TOP, null)
                    .minTrackWidthUm(100.0)
                    .build();

            BoardSpecification spec = BoardSpecification.from(List.of(outline, copper));
            assertNotNull(spec.getBounds());
            assertEquals(32.0, spec.getSizeXMm(), 1e-9);
            assertEquals(100.0, spec.getMinTrackWidthUm(), 1e-9);
            assertEquals(1, spec.getCopperLayerCount());
        }
    }
}
