package com.deltaproto.deltagerber.classify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LayerClassifierTest {

    private static void assertLayer(String fileName, String content,
                                    LayerFunction function, LayerSide side, Integer number) {
        LayerClassification actual = LayerClassifier.classify(fileName, content);
        assertNotNull(actual, "expected a classification for " + fileName);
        assertEquals(function, actual.function(), "function for " + fileName);
        assertEquals(side, actual.side(), "side for " + fileName);
        assertEquals(number, actual.number(), "layer number for " + fileName);
    }

    @Nested
    @DisplayName("Gerber X2 .FileFunction — the file declares what it is")
    class FileFunctionAttribute {

        @Test
        void topCopperCarriesNoLayerNumber() {
            // L1 on outer copper is a stack-up index, not a layer number. Callers that render
            // "side + number" turn a non-null number here into the meaningless label "TOP-1".
            assertLayer("test.gbr", "%TF.FileFunction,Copper,L1,Top*%\n",
                    LayerFunction.COPPER, LayerSide.TOP, null);
            assertLayer("test.gbr", "%TF.FileFunction,Copper,L6,Bot*%\n",
                    LayerFunction.COPPER, LayerSide.BOTTOM, null);
        }

        @Test
        void innerCopperCarriesItsLayerNumber() {
            assertLayer("test.gbr", "%TF.FileFunction,Copper,L2,Inr,Plane*%\n",
                    LayerFunction.COPPER, LayerSide.INNER, 2);
        }

        @Test
        void soldermaskAndProfile() {
            assertLayer("test.gbr", "%TF.FileFunction,Soldermask,Top*%\n",
                    LayerFunction.SOLDERMASK, LayerSide.TOP, null);
            assertLayer("test.gbr", "%TF.FileFunction,Profile,NP*%\n",
                    LayerFunction.OUTLINE, LayerSide.NA, null);
        }

        @Test
        void drills() {
            assertLayer("test.drl", "%TF.FileFunction,Plated,1,4,PTH*%\n",
                    LayerFunction.DRILL_PLATED, LayerSide.NA, null);
            assertLayer("test.drl", "%TF.FileFunction,NonPlated,1,4,NPTH*%\n",
                    LayerFunction.DRILL_NONPLATED, LayerSide.NA, null);
            assertLayer("test.drl", "%TF.FileFunction,Plated,1,2,Blind*%\n",
                    LayerFunction.DRILL_BLINDBURIED, LayerSide.NA, null);
        }

        @Test
        void vcutIsAScoreLineAndDepthRoutIsARout() {
            assertLayer("test.gbr", "%TF.FileFunction,Vcut,Top*%\n",
                    LayerFunction.SCORE, LayerSide.TOP, null);
            assertLayer("test.gbr", "%TF.FileFunction,DepthRout,Bot*%\n",
                    LayerFunction.ROUT, LayerSide.BOTTOM, null);
        }

        @Test
        @DisplayName("The attribute beats the filename, which cannot always be read")
        void attributeOverridesAnUnrecognisableFilename() {
            // Real KiCad 9.0.5 export: the "_SIGNAL" suffix matches no filename convention.
            String content = """
                    %TF.GenerationSoftware,KiCad,Pcbnew,9.0.5*%
                    %TF.CreationDate,2026-02-04T15:16:34+01:00*%
                    %TF.ProjectId,Zeway_BMS2,5a657761-795f-4424-9d53-322e6b696361,3.1*%
                    %TF.FileFunction,Copper,L1,Top*%
                    %FSLAX46Y46*%
                    """;
            assertLayer("Zeway_BMS2-F_Cu_SIGNAL.gbr", content, LayerFunction.COPPER, LayerSide.TOP, null);
        }

        @Test
        @DisplayName("Altium writes X2 attributes as G04 structured comments (X2 spec §5.1)")
        void commentFormIsEquivalent() {
            String content = """
                    G04*
                    G04 #@! TF.GenerationSoftware,Altium Limited,Altium Designer,25.8.1 (18)*
                    G04 #@! TF.FileFunction,Copper,L1,Top,Signal*
                    G04 #@! TF.FilePolarity,Positive*
                    %FSLAX44Y44*%
                    """;
            assertLayer("board_copper_top.gbr", content, LayerFunction.COPPER, LayerSide.TOP, null);
            assertLayer("board.gbr", "G04*\nG04 #@! TF.FileFunction,Soldermask,Bot*\n",
                    LayerFunction.SOLDERMASK, LayerSide.BOTTOM, null);
        }
    }

    @Nested
    @DisplayName("Excellon ;TYPE= header comment")
    class ExcellonDrillType {

        @Test
        @DisplayName("Plating comes from the header whatever the file is called")
        void typeCommentDecidesPlating() {
            assertLayer("anon.drl",
                    "M48\n;FILE_FORMAT=4:4\nMETRIC\n;TYPE=PLATED\nT01F00S00C0.1500\n%\nT01\nX00966250Y00787498\nM30\n",
                    LayerFunction.DRILL_PLATED, LayerSide.NA, null);
            assertLayer("anon.drl",
                    "M48\n;FILE_FORMAT=4:4\nMETRIC\n;TYPE=NON_PLATED\nT06F00S00C0.9000\n%\nT06\nX00200200Y00558590\nM30\n",
                    LayerFunction.DRILL_NONPLATED, LayerSide.NA, null);
        }

        @Test
        @DisplayName("A stray ;TYPE= token outside an M48 header must not hijack a Gerber file")
        void typeCommentIsIgnoredWithoutAnM48Header() {
            LayerClassification actual = LayerClassifier.classify(
                    "something.gbr", "G04 stray comment ; TYPE=PLATED\n%FSLAX46Y46*%\n%MOMM*%\n");
            if (actual != null) {
                assertNotEquals(LayerFunction.DRILL_PLATED, actual.function());
                assertNotEquals(LayerFunction.DRILL_NONPLATED, actual.function());
            }
        }
    }

    @Nested
    @DisplayName("Filename conventions, when the content declares nothing")
    class FilenamePatterns {

        /** Altium 25.8.1 export using Protel extensions — the whole set, from names alone. */
        private static final String PREFIX = "TDS3 Devkit Main V1.1";

        @Test
        void protelExtensions() {
            assertLayer(PREFIX + ".GTL", null, LayerFunction.COPPER, LayerSide.TOP, null);
            assertLayer(PREFIX + ".GBL", null, LayerFunction.COPPER, LayerSide.BOTTOM, null);
            assertLayer(PREFIX + ".G1", null, LayerFunction.COPPER, LayerSide.INNER, 1);
            assertLayer(PREFIX + ".G2", null, LayerFunction.COPPER, LayerSide.INNER, 2);
            assertLayer(PREFIX + ".GTS", null, LayerFunction.SOLDERMASK, LayerSide.TOP, null);
            assertLayer(PREFIX + ".GBS", null, LayerFunction.SOLDERMASK, LayerSide.BOTTOM, null);
            assertLayer(PREFIX + ".GTO", null, LayerFunction.SILKSCREEN, LayerSide.TOP, null);
            assertLayer(PREFIX + ".GBO", null, LayerFunction.SILKSCREEN, LayerSide.BOTTOM, null);
            assertLayer(PREFIX + ".GTP", null, LayerFunction.PASTE, LayerSide.TOP, null);
            assertLayer(PREFIX + ".GKO", null, LayerFunction.OUTLINE, LayerSide.NA, null);
            assertLayer(PREFIX + ".GM", null, LayerFunction.OUTLINE, LayerSide.NA, null);
        }

        @Test
        @DisplayName("A specific drill suffix must beat the generic drill pattern")
        void drillSuffixes() {
            assertLayer(PREFIX + "-Plated.TXT", null, LayerFunction.DRILL_PLATED, LayerSide.NA, null);
            assertLayer(PREFIX + "-NonPlated.TXT", null, LayerFunction.DRILL_NONPLATED, LayerSide.NA, null);
            assertLayer("board-PTH.drl", null, LayerFunction.DRILL_PLATED, LayerSide.NA, null);
            assertLayer("board-NPTH.drl", null, LayerFunction.DRILL_NONPLATED, LayerSide.NA, null);
            assertLayer("board.drl", null, LayerFunction.DRILL, LayerSide.NA, null);
        }

        @Test
        void kicadNames() {
            assertLayer("board-F_Cu.gbr", null, LayerFunction.COPPER, LayerSide.TOP, null);
            assertLayer("board-B_Cu.gbr", null, LayerFunction.COPPER, LayerSide.BOTTOM, null);
            assertLayer("board-In1_Cu.gbr", null, LayerFunction.COPPER, LayerSide.INNER, 1);
            assertLayer("board-Edge_Cuts.gbr", null, LayerFunction.OUTLINE, LayerSide.NA, null);
            // KiCad 7 spells silkscreen out in full.
            assertLayer("board-F_Silkscreen.gbr", null, LayerFunction.SILKSCREEN, LayerSide.TOP, null);
            assertLayer("board-F_SilkS.gbr", null, LayerFunction.SILKSCREEN, LayerSide.TOP, null);
        }

        @Test
        @DisplayName("A second extension is stripped, but only when it is not a rendered preview")
        void doubleExtensions() {
            // A gerber that picked up a suffix somewhere in transit still classifies.
            assertLayer("board.GTL.bak", null, LayerFunction.COPPER, LayerSide.TOP, null);
            // A preview rendered next to the layer it depicts is not that layer. Left to the
            // stripping retry, "board-B_Cu.gbr.svg" would count as a sixth copper layer.
            assertNull(LayerClassifier.classify("board-B_Cu.gbr.svg", null));
            assertNull(LayerClassifier.classify("board-F_Cu.gbr.png", null));
        }

        @Test
        @DisplayName("Matching a pattern never mutates it — the table is shared across calls")
        void patternTablesAreImmutable() {
            assertLayer("board.G2", null, LayerFunction.COPPER, LayerSide.INNER, 2);
            // If classification wrote the resolved name back into the shared pattern, the "%d"
            // placeholder would be gone and every later inner layer would read "inner copper 2".
            assertLayer("board.G3", null, LayerFunction.COPPER, LayerSide.INNER, 3);
            assertEquals("inner copper 3",
                    LayerClassifier.classify("board.G3", null).name());
        }

        @Test
        @DisplayName("An outline file that draws nothing is not an outline")
        void emptyOutlineIsRejectedSoARealOneCanWin() {
            String headerOnly = "%FSLAX46Y46*%\n%MOMM*%\n%ADD10C,0.050000*%\nM02*\n";
            assertNull(LayerClassifier.classify("board.GKO", headerOnly));

            String withGeometry = headerOnly.replace("M02*", "D10*\nX0Y0D02*\nX1000000Y0D01*\nM02*");
            assertLayer("board.GKO", withGeometry, LayerFunction.OUTLINE, LayerSide.NA, null);
        }
    }

    @Nested
    class CadToolDetection {

        @Test
        void fromGenerationSoftware() {
            assertEquals(CadTool.ALTIUM, LayerClassifier.detectCadTool(
                    "G04 #@! TF.GenerationSoftware,Altium Limited,Altium Designer,25.8.1 (18)*", "x.gbr"));
            assertEquals(CadTool.KICAD, LayerClassifier.detectCadTool(
                    "%TF.GenerationSoftware,KiCad,Pcbnew,9.0.5*%", "x.gbr"));
            assertEquals(CadTool.ALLEGRO, LayerClassifier.detectCadTool(
                    "G04 ====== begin FILE IDENTIFICATION RECORD ======*", "x.art"));
        }

        @Test
        void fromFilenameWhenNothingIsDeclared() {
            assertEquals(CadTool.PROTEL, LayerClassifier.detectCadTool(null, "board.gtl"));
            assertEquals(CadTool.KICAD, LayerClassifier.detectCadTool(null, "board-F_Cu.gbr"));
            assertEquals(CadTool.EAGLE, LayerClassifier.detectCadTool(null, "board.toplayer.ger"));
            assertEquals(CadTool.ALTIUM, LayerClassifier.detectCadTool(null, "board_Copper_Signal_Top.gbr"));
            assertEquals(CadTool.GENERIC, LayerClassifier.detectCadTool(null, "readme.md"));
        }

        @Test
        void generationSoftwareInBothSyntaxes() {
            GenerationSoftware standard = LayerClassifier.parseGenerationSoftware(
                    "%TF.GenerationSoftware,Ucamco,UcamX,2017.04*%");
            assertEquals(new GenerationSoftware("Ucamco", "UcamX", "2017.04"), standard);

            GenerationSoftware comment = LayerClassifier.parseGenerationSoftware("""
                    G04*
                    G04 #@! TF.GenerationSoftware,Altium Limited,Altium Designer,25.8.1 (18)*
                    %FSLAX44Y44*%
                    """);
            assertEquals(new GenerationSoftware("Altium Limited", "Altium Designer", "25.8.1 (18)"), comment);

            assertNull(LayerClassifier.parseGenerationSoftware("%FSLAX44Y44*%"));
        }
    }

    @Nested
    @DisplayName("Cadence Allegro FILE IDENTIFICATION RECORD")
    class AllegroRecord {

        private static String film(String... layers) {
            StringBuilder content = new StringBuilder(
                    "G04 ================== begin FILE IDENTIFICATION RECORD ==================*\n"
                            + "G04 File Origin:  Cadence Allegro 17.4*\n");
            for (String layer : layers) {
                content.append("G04 Layer:  ").append(layer).append("*\n");
            }
            return content.append("G04 ================== end FILE IDENTIFICATION RECORD ====================*\n")
                    .toString();
        }

        @Test
        @DisplayName("A copper film is recognised by its ETCH entry, not by its user-defined name")
        void copperFromEtchSubclass() {
            assertLayer("SSTP.art", film("ETCH/TOP", "PIN/TOP", "VIA CLASS/TOP"),
                    LayerFunction.COPPER, LayerSide.TOP, null);
            assertLayer("anything.art", film("ETCH/BOTTOM", "PIN/BOTTOM"),
                    LayerFunction.COPPER, LayerSide.BOTTOM, null);
            assertLayer("anything.art", film("ETCH/IN2", "PIN/IN2"),
                    LayerFunction.COPPER, LayerSide.INNER, 2);
        }

        @Test
        void otherFunctionsFromSubclass() {
            assertLayer("x.art", film("PACKAGE GEOMETRY/SILKSCREEN_TOP", "REF DES/SILKSCREEN_TOP"),
                    LayerFunction.SILKSCREEN, LayerSide.TOP, null);
            assertLayer("x.art", film("PACKAGE GEOMETRY/SOLDERMASK_BOTTOM"),
                    LayerFunction.SOLDERMASK, LayerSide.BOTTOM, null);
            assertLayer("x.art", film("PACKAGE GEOMETRY/PASTEMASK_TOP"),
                    LayerFunction.PASTE, LayerSide.TOP, null);
            assertLayer("x.art", film("MANUFACTURING/NCLEGEND-1-2"),
                    LayerFunction.FAB_DRAWING, LayerSide.NA, null);
        }

        @Test
        @DisplayName("Only a film that draws nothing but the outline is an outline")
        void outlineOnlyWhenTheFilmCarriesNothingElse() {
            assertLayer("x.art", film("BOARD GEOMETRY/DESIGN_OUTLINE"),
                    LayerFunction.OUTLINE, LayerSide.NA, null);
            // The same outline alongside assembly geometry is a drawing, not a profile.
            assertLayer("x.art", film("BOARD GEOMETRY/DESIGN_OUTLINE", "PACKAGE GEOMETRY/ASSEMBLY_TOP"),
                    LayerFunction.FAB_DRAWING, LayerSide.TOP, null);
        }
    }

    @Test
    void unrecognisedFilesClassifyToNull() {
        assertNull(LayerClassifier.classify("board.DRR", null));
        assertNull(LayerClassifier.classify("board.EXTREP", null));
        assertNull(LayerClassifier.classify("readme.md", null));
    }
}
