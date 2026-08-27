package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.GerberJobDocument;
import com.deltaproto.deltagerber.parser.GerberJobParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardStackTest {

    /** A KiCad 8 four-layer job file, stack-up only — the section every other field is irrelevant to. */
    private static final String FOUR_LAYER_JOB = """
            {
              "Header": { "GenerationSoftware": { "Vendor": "KiCad", "Application": "Pcbnew", "Version": "8.0.4" } },
              "GeneralSpecs": { "LayerNumber": 4, "BoardThickness": 1.6 },
              "MaterialStackup": [
                { "Type": "Legend", "Name": "Top Silk Screen" },
                { "Type": "SolderPaste", "Name": "Top Solder Paste" },
                { "Type": "SolderMask", "Thickness": 0.01, "Name": "Top Solder Mask" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "F.Cu" },
                { "Type": "Dielectric", "Thickness": 0.1, "Material": "FR4", "Name": "F.Cu/In1.Cu",
                  "Notes": "Type: dielectric layer 1 (from F.Cu to In1.Cu)" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In1.Cu" },
                { "Type": "Dielectric", "Thickness": 1.24, "Material": "FR4", "Name": "In1.Cu/In2.Cu",
                  "Notes": "Type: dielectric layer 2 (from In1.Cu to In2.Cu)" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In2.Cu" },
                { "Type": "Dielectric", "Thickness": 0.1, "Material": "FR4", "Name": "In2.Cu/B.Cu",
                  "Notes": "Type: dielectric layer 3 (from In2.Cu to B.Cu)" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "B.Cu" },
                { "Type": "SolderMask", "Thickness": 0.01, "Name": "Bottom Solder Mask" },
                { "Type": "SolderPaste", "Name": "Bottom Solder Paste" },
                { "Type": "Legend", "Name": "Bottom Silk Screen" }
              ]
            }
            """;

    /** One short track on top copper, so a set has something to analyse. */
    private static final String F_CU = String.join("\n",
            "%FSLAX46Y46*%", "%MOMM*%", "%ADD10C,0.200000*%", "D10*",
            "X0Y0D02*", "X5000000Y0D01*", "M02*");

    private static GerberJobDocument job(String content) {
        return new GerberJobParser().parse(content);
    }

    /** The layers of the stack a job file states. */
    private static List<StackEntry> entriesOf(GerberJobDocument job) {
        return BoardStack.from(job).getEntries();
    }

    private static List<StackFunction> functions(List<StackEntry> stack) {
        return stack.stream().map(StackEntry::getFunction).toList();
    }

    private static AnalyzedLayer layer(String fileName, LayerFunction function, LayerSide side, Integer number) {
        return AnalyzedLayer.builder(fileName).classification(function, side, number).build();
    }

    @Nested
    @DisplayName("Read from a job file's MaterialStackup")
    class FromJobFile {

        @Test
        void everyEntryInOrderTopToBottom() {
            List<StackEntry> stack = entriesOf(job(FOUR_LAYER_JOB));

            assertEquals(List.of(
                    StackFunction.SILKSCREEN,
                    StackFunction.PASTE,
                    StackFunction.SOLDERMASK,
                    StackFunction.COPPER,
                    StackFunction.DIELECTRIC,
                    StackFunction.COPPER,
                    StackFunction.DIELECTRIC,
                    StackFunction.COPPER,
                    StackFunction.DIELECTRIC,
                    StackFunction.COPPER,
                    StackFunction.SOLDERMASK,
                    StackFunction.PASTE,
                    StackFunction.SILKSCREEN), functions(stack));

            for (int i = 0; i < stack.size(); i++) {
                assertEquals(i, stack.get(i).getOrdinal(), "ordinals are dense and start at the top");
                assertFalse(stack.get(i).isEstimated(), "the job file states this stack");
            }
            assertEquals("F.Cu", stack.get(3).getName());
            assertEquals("FR4", stack.get(4).getMaterial());
        }

        @Test
        @DisplayName("Millimetres convert to exact picometres, and the entries sum exactly")
        void thicknessInPicometres() {
            List<StackEntry> stack = entriesOf(job(FOUR_LAYER_JOB));

            assertEquals(35_000_000L, stack.get(3).getThicknessPm(), "35 µm of one-ounce foil");
            assertEquals(100_000_000L, stack.get(4).getThicknessPm());
            assertEquals(1_240_000_000L, stack.get(6).getThicknessPm());
            assertEquals(10_000_000L, stack.get(2).getThicknessPm());
            assertNull(stack.get(0).getThicknessPm(), "the file gives the legend no thickness");

            long total = stack.stream().map(StackEntry::getThicknessPm)
                    .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
            assertEquals(1_600_000_000L, total, "the entries add up to the declared 1.6 mm board");
            assertEquals(0.035, stack.get(3).getThicknessMm(), 1e-12);
        }

        @Test
        @DisplayName("Every dielectric stays a dielectric — core and prepreg are not inferred")
        void dielectricsAreNotSplit() {
            StringBuilder json = new StringBuilder("{ \"MaterialStackup\": [ { \"Type\": \"Copper\" }");
            for (int i = 0; i < 5; i++) {
                json.append(", { \"Type\": \"Dielectric\", \"Material\": \"FR4\" }, { \"Type\": \"Copper\" }");
            }
            json.append(" ] }");

            List<StackEntry> stack = entriesOf(job(json.toString()));
            assertEquals(List.of(
                    StackFunction.COPPER, StackFunction.DIELECTRIC,
                    StackFunction.COPPER, StackFunction.DIELECTRIC,
                    StackFunction.COPPER, StackFunction.DIELECTRIC,
                    StackFunction.COPPER, StackFunction.DIELECTRIC,
                    StackFunction.COPPER, StackFunction.DIELECTRIC,
                    StackFunction.COPPER), functions(stack),
                    "a six-layer build says nothing about which layers are cores");
        }

        @Test
        @DisplayName("A type this library does not model keeps its place, name and thickness")
        void unmodelledTypeBecomesOther() {
            List<StackEntry> stack = entriesOf(job("""
                    { "MaterialStackup": [
                        { "Type": "Copper", "Thickness": 0.035 },
                        { "Type": "Coverlay", "Thickness": 0.025, "Name": "Top Coverlay" },
                        { "Type": "Dielectric", "Thickness": 0.1 },
                        { "Type": "Copper", "Thickness": 0.035 } ] }
                    """));
            assertEquals(StackFunction.OTHER, stack.get(1).getFunction());
            assertEquals("Top Coverlay", stack.get(1).getName());
            assertEquals(25_000_000L, stack.get(1).getThicknessPm(),
                    "dropping it would lose 25 µm from the middle of the board");
            assertEquals(4, stack.size());
        }

        @Test
        void noStackupYieldsNoStack() {
            assertTrue(BoardStack.from((GerberJobDocument) null).isEmpty());
            assertTrue(BoardStack.from(job("{\"FilesAttributes\": []}")).isEmpty());
        }
    }

    @Nested
    @DisplayName("Estimated from the artwork, for the sets that ship no job file")
    class Estimated {

        @Test
        @DisplayName("The synthesised stack runs legend, paste, mask, copper, and out again")
        void orderingOfAFourLayerSet() {
            // Deliberately shuffled: the stack's order comes from what each layer is, never from
            // the order the files arrived in.
            BoardSpecification spec = BoardSpecification.from(List.of(
                    layer("board-In2_Cu.gbr", LayerFunction.COPPER, LayerSide.INNER, 2),
                    layer("board-B_Mask.gbr", LayerFunction.SOLDERMASK, LayerSide.BOTTOM, null),
                    layer("board-F_Cu.gbr", LayerFunction.COPPER, LayerSide.TOP, null),
                    layer("board-Edge_Cuts.gbr", LayerFunction.OUTLINE, LayerSide.NA, null),
                    layer("board-B_Cu.gbr", LayerFunction.COPPER, LayerSide.BOTTOM, null),
                    layer("board-F_Silkscreen.gbr", LayerFunction.SILKSCREEN, LayerSide.TOP, null),
                    layer("board-PTH.drl", LayerFunction.DRILL_PLATED, LayerSide.NA, null),
                    layer("board-In1_Cu.gbr", LayerFunction.COPPER, LayerSide.INNER, 1),
                    layer("board-F_Mask.gbr", LayerFunction.SOLDERMASK, LayerSide.TOP, null),
                    layer("board-F_Paste.gbr", LayerFunction.PASTE, LayerSide.TOP, null)));

            List<StackEntry> stack = spec.getStack();
            assertEquals(List.of(
                    StackFunction.SILKSCREEN,
                    StackFunction.PASTE,
                    StackFunction.SOLDERMASK,
                    StackFunction.COPPER,
                    StackFunction.COPPER,
                    StackFunction.COPPER,
                    StackFunction.COPPER,
                    StackFunction.SOLDERMASK), functions(stack));
            assertEquals(List.of("board-F_Cu.gbr", "board-In1_Cu.gbr", "board-In2_Cu.gbr", "board-B_Cu.gbr"),
                    stack.stream().filter(e -> e.getFunction().isCopper()).map(StackEntry::getName).toList(),
                    "copper runs top, inner 1, inner 2, bottom");

            for (int i = 0; i < stack.size(); i++) {
                assertEquals(i, stack.get(i).getOrdinal());
                assertTrue(stack.get(i).isEstimated());
                assertNull(stack.get(i).getThicknessPm(), "no Gerber file states a thickness");
            }
            assertTrue(spec.isStackEstimated());
        }

        @Test
        @DisplayName("Nothing that has no z-position joins the stack — no dielectrics either")
        void onlyPhysicalLayers() {
            BoardSpecification spec = BoardSpecification.from(List.of(
                    layer("board.GKO", LayerFunction.OUTLINE, LayerSide.NA, null),
                    layer("board.TXT", LayerFunction.DRILL, LayerSide.NA, null),
                    layer("board.GM1", LayerFunction.MECHANICAL_DRAWING, LayerSide.NA, null),
                    layer("board.GTL", LayerFunction.COPPER, LayerSide.TOP, null),
                    layer("board.GBL", LayerFunction.COPPER, LayerSide.BOTTOM, null)));

            assertEquals(List.of(StackFunction.COPPER, StackFunction.COPPER), functions(spec.getStack()));
            assertTrue(spec.getStack().stream().noneMatch(e -> e.getFunction().isDielectric()));
        }

        @Test
        void anEmptySetHasNoStackAtAll() {
            BoardSpecification spec = BoardSpecification.from(List.of());
            assertTrue(spec.getStack().isEmpty());
            assertNull(spec.isStackEstimated(), "neither read nor estimated");
        }
    }

    @Nested
    @DisplayName("Supplied to the specification, the way persisted data comes back")
    class Supplied {

        @Test
        void aSuppliedStackIsUsedAndRenumbered() {
            List<StackEntry> stored = List.of(
                    StackEntry.of(7, StackFunction.COPPER, "F.Cu", 35_000_000L, null, false),
                    StackEntry.of(9, StackFunction.DIELECTRIC, null, 1_510_000_000L, "FR4", false),
                    StackEntry.of(11, StackFunction.COPPER, "B.Cu", 35_000_000L, null, false));

            BoardSpecification spec = BoardSpecification.from(
                    List.of(layer("board.GTL", LayerFunction.COPPER, LayerSide.TOP, null)), null,
                    BoardStack.of(stored, null));

            assertEquals(List.of(0, 1, 2), spec.getStack().stream().map(StackEntry::getOrdinal).toList());
            assertEquals(StackFunction.DIELECTRIC, spec.getStack().get(1).getFunction());
            assertFalse(spec.isStackEstimated(), "this stack was read from a job file, not guessed");
            assertEquals(1_580_000_000L, spec.getBoardThicknessPm(),
                    "no total was stored, so the layers themselves give it");
        }

        @Test
        @DisplayName("No stack supplied falls back to the estimate, which is the usual case")
        void nullStackFallsBackToTheEstimate() {
            List<AnalyzedLayer> layers = List.of(
                    layer("board.GTL", LayerFunction.COPPER, LayerSide.TOP, null),
                    layer("board.GBL", LayerFunction.COPPER, LayerSide.BOTTOM, null));

            assertTrue(BoardSpecification.from(layers, null, null).isStackEstimated());
            assertTrue(BoardSpecification.from(layers, null, BoardStack.empty()).isStackEstimated());
            assertTrue(BoardSpecification.from(layers).isStackEstimated());
            assertNull(BoardSpecification.from(layers).getBoardThicknessPm(),
                    "no Gerber file states how thick the board is");
        }

        @Test
        @DisplayName("A total with no layers is still a total — the EAGLE and ODB++ case")
        void aStoredTotalWithoutLayers() {
            BoardSpecification spec = BoardSpecification.from(
                    List.of(layer("board.GTL", LayerFunction.COPPER, LayerSide.TOP, null),
                            layer("board.GBL", LayerFunction.COPPER, LayerSide.BOTTOM, null)),
                    null, BoardStack.of(List.of(), 1_536_000_000L));

            assertEquals(1_536_000_000L, spec.getBoardThicknessPm());
            assertEquals(1.536, spec.getBoardThicknessMm(), 1e-12);
            assertTrue(spec.isStackEstimated(), "the layers are still only the ones the artwork shows");
            assertEquals(2, spec.getStack().size());
        }
    }

    @Nested
    @DisplayName("A whole set, analysed")
    class WholeSet {

        @Test
        @DisplayName("A .gbrjob in the set gives the board its real stack-up")
        void theJobFileInTheSetIsUsed() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-F_Cu.gbr", F_CU),
                    PcbFile.of("board-job.gbrjob", FOUR_LAYER_JOB)));

            assertFalse(spec.isStackEstimated());
            assertEquals(13, spec.getStack().size());
            assertEquals(StackFunction.DIELECTRIC, spec.getStack().get(6).getFunction());
            assertEquals(1_240_000_000L, spec.getStack().get(6).getThicknessPm());
        }

        @Test
        @DisplayName("A set without one — nearly all of them — still gets a stack, estimated")
        void withoutAJobFileTheStackIsEstimated() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-F_Cu.gbr", F_CU)));

            assertTrue(spec.isStackEstimated());
            assertEquals(List.of(StackFunction.COPPER), functions(spec.getStack()));
        }
    }
}
