package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.model.gerber.GerberJobDocument;
import com.deltaproto.deltagerber.model.gerber.GerberJobDocument.StackupType;
import com.deltaproto.deltagerber.parser.GerberJobParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes a Gerber job file actually takes in the wild.
 *
 * <p>Drawn from a corpus of 29 real {@code .gbrjob} files off customer boards, which between them
 * cover KiCad 6 through 10 and EAGLE/Fusion. Every fixture here is a <em>shape</em>: the sections,
 * types, thicknesses and quirks those files have, with everything that identifies a board or a
 * person — project names, GUIDs, file paths, owners, dates — replaced. What the corpus established:
 *
 * <ul>
 *   <li>Only five {@code MaterialStackup} types occur at all: Copper, Dielectric, SolderMask,
 *       Legend, SolderPaste. No file in the corpus states a surface finish as a stack entry, and
 *       none states a type outside those five.
 *   <li>Not one names a core or a prepreg — anywhere, in any field. That is why
 *       {@link StackFunction} has a single {@link StackFunction#DIELECTRIC} and does not guess.
 *   <li>KiCad states a stack-up from version 6 on, but thicknesses only from version 8. A KiCad 6
 *       or 7 file gives the layers and their names with no thickness at all.
 *   <li>Where thicknesses are stated they sum to exactly the declared {@code BoardThickness}, on
 *       every board, which is what {@linkplain StackEntry#getThicknessPm() picometres} preserve.
 *   <li>The number of copper entries always equals the declared {@code LayerNumber}, and there is
 *       always one fewer dielectric than copper.
 *   <li>EAGLE/Fusion writes a job file with no stack-up and no {@code GeneralSpecs} at all — it
 *       puts the same fields under {@code Overall}.
 * </ul>
 */
class JobFileCorpusTest {

    private static List<StackEntry> entriesOf(GerberJobDocument job) {
        return BoardStack.from(job).getEntries();
    }

    /**
     * A stack as a string, one letter per entry, so a seventeen-layer build stays readable:
     * <b>C</b>opper, <b>D</b>ielectric, solder<b>M</b>ask, <b>S</b>ilkscreen, <b>P</b>aste,
     * <b>F</b>inish, <b>O</b>ther.
     */
    private static String shape(List<StackEntry> stack) {
        StringBuilder shape = new StringBuilder(stack.size());
        for (StackEntry entry : stack) {
            shape.append(switch (entry.getFunction()) {
                case COPPER -> 'C';
                case DIELECTRIC -> 'D';
                case SOLDERMASK -> 'M';
                case SILKSCREEN -> 'S';
                case PASTE -> 'P';
                case FINISH -> 'F';
                case OTHER -> 'O';
            });
        }
        return shape.toString();
    }

    /**
     * One real-world shape.
     *
     * @param label      what tool and board shape this came from
     * @param json       the job file, stripped of everything identifying
     * @param layers     the declared {@code LayerNumber}
     * @param thicknessMm the declared {@code BoardThickness}
     * @param shape      the stack this must produce, in {@link #shape} letters
     * @param sumPm      what the stated thicknesses must add up to, or null when the file states none
     */
    private record Fixture(String label, String json, Integer layers, Double thicknessMm,
                           String shape, Long sumPm) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** KiCad 6: a stack-up with names and materials, and not one thickness. */
    private static final String KICAD_6_TWO_LAYER = """
            {
              "Header": {
                "GenerationSoftware": { "Vendor": "KiCad", "Application": "Pcbnew", "Version": "(6.0.6)" },
                "CreationDate": "2022-01-01T00:00:00+01:00"
              },
              "GeneralSpecs": {
                "ProjectId": { "Name": "board", "GUID": "00000000-0000-0000-0000-000000000000", "Revision": "rev?" },
                "Size": { "X": 50.0, "Y": 40.0 },
                "LayerNumber": 2,
                "BoardThickness": 1.6,
                "Finish": "None"
              },
              "MaterialStackup": [
                { "Type": "Legend", "Name": "Top Silk Screen" },
                { "Type": "SolderPaste", "Name": "Top Solder Paste" },
                { "Type": "SolderMask", "Name": "Top Solder Mask" },
                { "Type": "Copper", "Name": "F.Cu" },
                { "Type": "Dielectric", "Material": "FR4", "Name": "F.Cu/B.Cu",
                  "Notes": "Type: dielectric layer 1 (from F.Cu to B.Cu)" },
                { "Type": "Copper", "Name": "B.Cu" },
                { "Type": "SolderMask", "Name": "Bottom Solder Mask" },
                { "Type": "SolderPaste", "Name": "Bottom Solder Paste" },
                { "Type": "Legend", "Name": "Bottom Silk Screen" }
              ]
            }
            """;

    /** KiCad 8, two layers, nothing to solder on the bottom — so no bottom paste entry at all. */
    private static final String KICAD_8_TWO_LAYER = """
            {
              "Header": {
                "GenerationSoftware": { "Vendor": "KiCad", "Application": "Pcbnew", "Version": "8.0.2" }
              },
              "GeneralSpecs": { "Size": { "X": 50.0, "Y": 40.0 }, "LayerNumber": 2, "BoardThickness": 1.6 },
              "MaterialStackup": [
                { "Type": "Legend", "Color": "White", "Name": "Top Silk Screen" },
                { "Type": "SolderPaste", "Name": "Top Solder Paste" },
                { "Type": "SolderMask", "Color": "Green", "Thickness": 0.01, "Name": "Top Solder Mask" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "F.Cu" },
                { "Type": "Dielectric", "Color": "R109G116B75", "Thickness": 1.51, "Material": "FR4",
                  "Name": "F.Cu/B.Cu", "Notes": "Type: dielectric layer 1 (from F.Cu to B.Cu)" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "B.Cu" },
                { "Type": "SolderMask", "Color": "Green", "Thickness": 0.01, "Name": "Bottom Solder Mask" },
                { "Type": "Legend", "Color": "White", "Name": "Bottom Silk Screen" }
              ]
            }
            """;

    /**
     * KiCad 8, four layers, a stack-up the designer edited: dielectrics to four decimals and a
     * bottom copper five times the weight of the other three. It still adds up exactly.
     */
    private static final String KICAD_8_FOUR_LAYER = """
            {
              "Header": {
                "GenerationSoftware": { "Vendor": "KiCad", "Application": "Pcbnew", "Version": "8.0.2" }
              },
              "GeneralSpecs": { "Size": { "X": 80.0, "Y": 60.0 }, "LayerNumber": 4, "BoardThickness": 1.7605 },
              "MaterialStackup": [
                { "Type": "Legend", "Name": "Top Silk Screen" },
                { "Type": "SolderPaste", "Name": "Top Solder Paste" },
                { "Type": "SolderMask", "Thickness": 0.01, "Name": "Top Solder Mask" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "F.Cu" },
                { "Type": "Dielectric", "Thickness": 0.1785, "Material": "FR4", "Name": "F.Cu/In1.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In1.Cu" },
                { "Type": "Dielectric", "Thickness": 1.1, "Material": "FR4", "Name": "In1.Cu/In2.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In2.Cu" },
                { "Type": "Dielectric", "Thickness": 0.1785, "Material": "FR4", "Name": "In2.Cu/B.Cu" },
                { "Type": "Copper", "Thickness": 0.1785, "Name": "B.Cu" },
                { "Type": "SolderMask", "Thickness": 0.01, "Name": "Bottom Solder Mask" },
                { "Type": "SolderPaste", "Name": "Bottom Solder Paste" },
                { "Type": "Legend", "Name": "Bottom Silk Screen" }
              ]
            }
            """;

    /** KiCad 9, six layers, ENIG: five dielectrics alternating thin and thick. */
    private static final String KICAD_9_SIX_LAYER = """
            {
              "Header": {
                "GenerationSoftware": { "Vendor": "KiCad", "Application": "Pcbnew", "Version": "9.0.5" }
              },
              "GeneralSpecs": { "Size": { "X": 60.0, "Y": 45.0 }, "LayerNumber": 6, "BoardThickness": 1.6,
                                "Finish": "ENIG" },
              "MaterialStackup": [
                { "Type": "Legend", "Name": "Top Silk Screen" },
                { "Type": "SolderPaste", "Name": "Top Solder Paste" },
                { "Type": "SolderMask", "Thickness": 0.01, "Name": "Top Solder Mask" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "F.Cu" },
                { "Type": "Dielectric", "Thickness": 0.112, "Material": "FR4", "Name": "F.Cu/In1.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In1.Cu" },
                { "Type": "Dielectric", "Thickness": 0.517, "Material": "FR4", "Name": "In1.Cu/In2.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In2.Cu" },
                { "Type": "Dielectric", "Thickness": 0.112, "Material": "FR4", "Name": "In2.Cu/In3.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In3.Cu" },
                { "Type": "Dielectric", "Thickness": 0.517, "Material": "FR4", "Name": "In3.Cu/In4.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In4.Cu" },
                { "Type": "Dielectric", "Thickness": 0.112, "Material": "FR4", "Name": "In4.Cu/B.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "B.Cu" },
                { "Type": "SolderMask", "Thickness": 0.01, "Name": "Bottom Solder Mask" },
                { "Type": "SolderPaste", "Name": "Bottom Solder Paste" },
                { "Type": "Legend", "Name": "Bottom Silk Screen" }
              ]
            }
            """;

    /**
     * KiCad 10, four layers: an impedance-controlled stack-up that states its dielectric constant
     * and loss tangent as <em>strings</em>, decimal comma and all, from the machine's locale.
     */
    private static final String KICAD_10_FOUR_LAYER = """
            {
              "Header": {
                "GenerationSoftware": { "Vendor": "KiCad", "Application": "Pcbnew", "Version": "10.0.0" }
              },
              "GeneralSpecs": { "Size": { "X": 70.0, "Y": 30.0 }, "LayerNumber": 4, "BoardThickness": 1.598,
                                "Finish": "ENIG", "ImpedanceControlled": true },
              "MaterialStackup": [
                { "Type": "Legend", "Color": "White", "Name": "Top Silk Screen" },
                { "Type": "SolderPaste", "Name": "Top Solder Paste" },
                { "Type": "SolderMask", "Color": "Green", "Thickness": 0.01, "Name": "Top Solder Mask" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "F.Cu" },
                { "Type": "Dielectric", "Color": "R109G116B75", "Thickness": 0.119, "Material": "FR4",
                  "DielectricConstant": "4,2", "LossTangent": "0,021", "Name": "F.Cu/In1.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In1.Cu" },
                { "Type": "Dielectric", "Color": "R109G116B75", "Thickness": 1.2, "Material": "FR4",
                  "DielectricConstant": "4,2", "LossTangent": "0,021", "Name": "In1.Cu/In2.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "In2.Cu" },
                { "Type": "Dielectric", "Color": "R109G116B75", "Thickness": 0.119, "Material": "FR4",
                  "DielectricConstant": "4,2", "LossTangent": "0,021", "Name": "In2.Cu/B.Cu" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "B.Cu" },
                { "Type": "SolderMask", "Color": "Green", "Thickness": 0.01, "Name": "Bottom Solder Mask" },
                { "Type": "SolderPaste", "Name": "Bottom Solder Paste" },
                { "Type": "Legend", "Color": "White", "Name": "Bottom Silk Screen" }
              ]
            }
            """;

    /**
     * EAGLE/Fusion: a job file that is nothing but a header and an {@code Overall} block — no
     * stack-up, no file list, and the general specs under a different name.
     */
    private static final String EAGLE_OVERALL = """
            {
              "Header": {
                "Comment": "All values are metric (mm)",
                "CreationDate": "2019-01-01T00:00:00Z",
                "GenerationSoftware": { "Vendor": "Autodesk", "Application": "EAGLE", "Version": "9.3.1" },
                "Part": "Single"
              },
              "Overall": {
                "BoardThickness": 1.536,
                "LayerNumber": 4,
                "Name": { "ProjectId": "board" },
                "Size": { "X": 83.49, "Y": 72.38 }
              }
            }
            """;

    private static List<Fixture> corpus() {
        return List.of(
                new Fixture("KiCad 6, two layers, no thicknesses stated",
                        KICAD_6_TWO_LAYER, 2, 1.6, "SPMCDCMPS", null),
                new Fixture("KiCad 8, two layers, top paste only",
                        KICAD_8_TWO_LAYER, 2, 1.6, "SPMCDCMS", 1_600_000_000L),
                new Fixture("KiCad 8, four layers, heavy bottom copper",
                        KICAD_8_FOUR_LAYER, 4, 1.7605, "SPMCDCDCDCMPS", 1_760_500_000L),
                new Fixture("KiCad 9, six layers, ENIG",
                        KICAD_9_SIX_LAYER, 6, 1.6, "SPMCDCDCDCDCDCMPS", 1_600_000_000L),
                new Fixture("KiCad 10, four layers, impedance controlled",
                        KICAD_10_FOUR_LAYER, 4, 1.598, "SPMCDCDCDCMPS", 1_598_000_000L),
                new Fixture("EAGLE, no stack-up at all",
                        EAGLE_OVERALL, 4, 1.536, "", null));
    }

    private final GerberJobParser parser = new GerberJobParser();

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    @DisplayName("Every job file shape the corpus contains reads back the board it describes")
    void readsEveryShape(Fixture fixture) {
        GerberJobDocument job = parser.parse(fixture.json());
        assertNotNull(job, "a real job file must be recognised as one");
        assertEquals(fixture.layers(), job.getLayerCount());
        assertEquals(fixture.thicknessMm(), job.getBoardThicknessMm());

        List<StackEntry> stack = entriesOf(job);
        assertEquals(fixture.shape(), shape(stack));

        for (int i = 0; i < stack.size(); i++) {
            assertEquals(i, stack.get(i).getOrdinal(), "ordinals are dense, top of the board first");
            assertFalse(stack.get(i).isEstimated(), "the file states this stack");
        }

        long copper = stack.stream().filter(e -> e.getFunction().isCopper()).count();
        long dielectrics = stack.stream().filter(e -> e.getFunction().isDielectric()).count();
        if (!stack.isEmpty()) {
            assertEquals(fixture.layers().intValue(), copper, "copper entries match the declared layer count");
            assertEquals(copper - 1, dielectrics, "one fewer dielectric than copper, always");
        }

        Long sum = stack.stream().map(StackEntry::getThicknessPm).filter(Objects::nonNull)
                .reduce(0L, Long::sum);
        assertEquals(fixture.sumPm() == null ? 0L : fixture.sumPm(), sum,
                "the stated thicknesses add up to the declared board thickness, exactly");
    }

    @Test
    @DisplayName("Not one real file names a core or a prepreg, so no dielectric is ever split")
    void dielectricsStayDielectrics() {
        for (Fixture fixture : corpus()) {
            for (StackEntry entry : entriesOf(parser.parse(fixture.json()))) {
                assertTrue(entry.getFunction() != StackFunction.OTHER,
                        fixture.label() + " uses only types this library models");
            }
        }
        assertEquals(List.of(StackFunction.COPPER, StackFunction.DIELECTRIC, StackFunction.SOLDERMASK,
                        StackFunction.SILKSCREEN, StackFunction.PASTE, StackFunction.FINISH,
                        StackFunction.OTHER),
                List.of(StackFunction.values()),
                "no CORE or PREPREG: the files never say which a dielectric is");
    }

    @Test
    @DisplayName("The five Type values the corpus actually contains, and nothing else")
    void theTypesThatOccurInTheWild() {
        assertEquals(StackupType.COPPER, StackupType.of("Copper"));
        assertEquals(StackupType.DIELECTRIC, StackupType.of("Dielectric"));
        assertEquals(StackupType.SOLDERMASK, StackupType.of("SolderMask"));
        assertEquals(StackupType.LEGEND, StackupType.of("Legend"));
        assertEquals(StackupType.SOLDERPASTE, StackupType.of("SolderPaste"));
    }

    @Test
    @DisplayName("KiCad states thicknesses only from version 8 — before that, the layers alone")
    void kicad6StatesNoThicknesses() {
        List<StackEntry> stack = entriesOf(parser.parse(KICAD_6_TWO_LAYER));

        assertTrue(stack.stream().allMatch(e -> e.getThicknessPm() == null));
        assertEquals("F.Cu", stack.get(3).getName(), "the layers and their names are all there");
        assertEquals("FR4", stack.get(4).getMaterial());
    }

    @Test
    @DisplayName("A locale-formatted number written as a string is ignored, not tripped over")
    void impedanceFieldsWithADecimalComma() {
        // KiCad 10 writes "DielectricConstant": "4,2" on a Dutch machine. We do not read the field,
        // but a strict JSON reader still has to swallow it whole.
        List<StackEntry> stack = entriesOf(parser.parse(KICAD_10_FOUR_LAYER));
        assertEquals(13, stack.size());
        assertEquals(119_000_000L, stack.get(4).getThicknessPm());
    }

    @Test
    @DisplayName("An EAGLE job file states the board under Overall, and no stack-up at all")
    void eagleJobFile() {
        GerberJobDocument job = parser.parse(EAGLE_OVERALL);
        assertNotNull(job, "a header and an Overall block is still a job file");
        assertEquals("Autodesk", job.getVendor());
        assertEquals(4, job.getLayerCount());
        assertEquals(1.536, job.getBoardThicknessMm(), 1e-9);
        assertEquals(83.49, job.getSizeXMm(), 1e-9);
        assertEquals("board", job.getProjectName());
        assertTrue(job.getMaterialStackup().isEmpty());
        assertTrue(job.getFiles().isEmpty());
    }

    @Test
    @DisplayName("A set whose job file has no stack-up still gets one, estimated from its layers")
    void aSetWithAnEagleJobFileFallsBackToTheEstimate() {
        BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                PcbFile.of("board-F_Cu.gbr", String.join("\n",
                        "%FSLAX46Y46*%", "%MOMM*%", "%ADD10C,0.200000*%", "D10*",
                        "X0Y0D02*", "X5000000Y0D01*", "M02*")),
                PcbFile.of("board-job.gbrjob", EAGLE_OVERALL)));

        assertTrue(spec.isStackEstimated());
        assertEquals("C", shape(spec.getStack()));
        assertNull(spec.getStack().get(0).getThicknessPm());
    }
}
