package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.model.ipc2581.Ipc2581StackupDocument;
import com.deltaproto.deltagerber.model.ipc2581.Ipc2581StackupDocument.Function;
import com.deltaproto.deltagerber.spec.BoardSpecification;
import com.deltaproto.deltagerber.spec.BoardStack;
import com.deltaproto.deltagerber.spec.PcbAnalyzer;
import com.deltaproto.deltagerber.spec.PcbFile;
import com.deltaproto.deltagerber.spec.StackEntry;
import com.deltaproto.deltagerber.spec.StackFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IPC-2581 is the only format in our corpus that states a thickness <em>per layer</em> as well as
 * for the board. The fixture is the shape of a real Altium-authored Rev B file — its sections,
 * attributes and quirks — cut down to four copper layers and stripped of everything identifying.
 *
 * <p>Two of those quirks are load-bearing. The stack-up group lists the documentation layers
 * alongside the material ones, each with {@code thickness="0"}; and every dielectric is written
 * {@code layerFunction="DIELCORE"} even where the material plainly says prepreg — which is why the
 * library reports one kind of dielectric and lets the material name speak for itself.
 */
class Ipc2581StackupParserTest {

    private static final String FOUR_LAYER_INCH = """
            <?xml version="1.0" encoding="utf-8"?>
            <IPC-2581 xmlns="http://webstds.ipc.org/2581" revision="B">
              <Content roleRef="Owner">
                <FunctionMode mode="USERDEF" level="1" />
                <StepRef name="board" />
              </Content>
              <Ecad name="output PCB cad-data">
                <CadHeader units="INCH">
                  <Spec name="Top Solder"><General type="MATERIAL"><Property text="SM-001" /></General></Spec>
                  <Spec name="Top Layer"><General type="MATERIAL"><Property text="Copper" /></General></Spec>
                  <Spec name="Dielectric 1"><General type="MATERIAL"><Property text="PP-001" /></General></Spec>
                  <Spec name="Dielectric 2"><General type="MATERIAL"><Property text="Core-027" /></General></Spec>
                  <Spec name="Dielectric 3"><General type="MATERIAL"><Property text="PP-001" /></General></Spec>
                </CadHeader>
                <CadData>
                  <Layer name="Top Paste" layerFunction="PASTEMASK" side="TOP" polarity="POSITIVE" />
                  <Layer name="Top Overlay" layerFunction="LEGEND" side="TOP" polarity="POSITIVE" />
                  <Layer name="Top Solder" layerFunction="SOLDERMASK" side="TOP" polarity="POSITIVE" />
                  <Layer name="Top Layer" layerFunction="SIGNAL" side="TOP" polarity="POSITIVE" />
                  <Layer name="Dielectric 1" layerFunction="DIELCORE" side="NONE" polarity="POSITIVE" />
                  <Layer name="Int1 (GND)" layerFunction="SIGNAL" side="INTERNAL" polarity="POSITIVE" />
                  <Layer name="Dielectric 2" layerFunction="DIELCORE" side="NONE" polarity="POSITIVE" />
                  <Layer name="Int2 (PWR)" layerFunction="SIGNAL" side="INTERNAL" polarity="POSITIVE" />
                  <Layer name="Dielectric 3" layerFunction="DIELCORE" side="NONE" polarity="POSITIVE" />
                  <Layer name="Bottom Layer" layerFunction="SIGNAL" side="BOTTOM" polarity="POSITIVE" />
                  <Layer name="Bottom Solder" layerFunction="SOLDERMASK" side="BOTTOM" polarity="POSITIVE" />
                  <Layer name="Bottom Overlay" layerFunction="LEGEND" side="BOTTOM" polarity="POSITIVE" />
                  <Layer name="Bottom Paste" layerFunction="PASTEMASK" side="BOTTOM" polarity="POSITIVE" />
                  <Layer name="M2 - Outline" layerFunction="DOCUMENT" side="INTERNAL" polarity="POSITIVE" />
                  <Layer name="Drill Guide" layerFunction="DRILL" side="INTERNAL" polarity="POSITIVE" />
                  <Stackup name="Stackup" overallThickness="0.022268" tolPlus="0" tolMinus="0" whereMeasured="OTHER">
                    <StackupGroup name="board_AllStackupLayers" thickness="0.022268" tolPlus="0" tolMinus="0">
                      <StackupLayer layerOrGroupRef="Top Paste" thickness="0" sequence="1">
                        <SpecRef id="Top Paste" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Top Overlay" thickness="0" sequence="2">
                        <SpecRef id="Top Overlay" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Top Solder" thickness="0.001" sequence="3">
                        <SpecRef id="Top Solder" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Top Layer" thickness="0.001378" sequence="4">
                        <SpecRef id="Top Layer" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Dielectric 1" thickness="0.002" sequence="5">
                        <SpecRef id="Dielectric 1" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Int1 (GND)" thickness="0.002756" sequence="6">
                        <SpecRef id="Int1 (GND)" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Dielectric 2" thickness="0.008" sequence="7">
                        <SpecRef id="Dielectric 2" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Int2 (PWR)" thickness="0.002756" sequence="8">
                        <SpecRef id="Int2 (PWR)" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Dielectric 3" thickness="0.002" sequence="9">
                        <SpecRef id="Dielectric 3" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Bottom Layer" thickness="0.001378" sequence="10">
                        <SpecRef id="Bottom Layer" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Bottom Solder" thickness="0.001" sequence="11">
                        <SpecRef id="Bottom Solder" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Bottom Overlay" thickness="0" sequence="12">
                        <SpecRef id="Bottom Overlay" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Bottom Paste" thickness="0" sequence="13">
                        <SpecRef id="Bottom Paste" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="M2 - Outline" thickness="0" sequence="14">
                        <SpecRef id="M2 - Outline" /></StackupLayer>
                      <StackupLayer layerOrGroupRef="Drill Guide" thickness="0" sequence="15">
                        <SpecRef id="Drill Guide" /></StackupLayer>
                    </StackupGroup>
                  </Stackup>
                </CadData>
              </Ecad>
            </IPC-2581>
            """;

    /** The same board written in millimetres, which the standard equally allows. */
    private static final String TWO_LAYER_MM = """
            <?xml version="1.0" encoding="utf-8"?>
            <IPC-2581 xmlns="http://webstds.ipc.org/2581" revision="C">
              <Ecad name="board">
                <CadHeader units="MILLIMETER" />
                <CadData>
                  <Layer name="Top Layer" layerFunction="CONDUCTOR" side="TOP" />
                  <Layer name="Dielectric 1" layerFunction="DIELPREG" side="NONE" />
                  <Layer name="Bottom Layer" layerFunction="CONDUCTOR" side="BOTTOM" />
                  <Stackup name="Stackup" overallThickness="1.6">
                    <StackupGroup name="all" thickness="1.6">
                      <StackupLayer layerOrGroupRef="Top Layer" thickness="0.035" sequence="1" />
                      <StackupLayer layerOrGroupRef="Dielectric 1" thickness="1.53" sequence="2" />
                      <StackupLayer layerOrGroupRef="Bottom Layer" thickness="0.035" sequence="3" />
                    </StackupGroup>
                  </Stackup>
                </CadData>
              </Ecad>
            </IPC-2581>
            """;

    private final Ipc2581StackupParser parser = new Ipc2581StackupParser();

    @Test
    @DisplayName("Every layer of the stack-up, in sequence, with its function and material")
    void readsTheStackup() {
        Ipc2581StackupDocument stackup = parser.parse(FOUR_LAYER_INCH);
        assertNotNull(stackup);
        assertEquals("B", stackup.getRevision());
        assertEquals("Stackup", stackup.getStackupName());
        assertEquals(15, stackup.getLayers().size(), "documentation layers are in the group too");

        Ipc2581StackupDocument.StackupLayer topCopper = stackup.getLayers().get(3);
        assertEquals("Top Layer", topCopper.name());
        assertEquals(Function.CONDUCTOR, topCopper.function());
        assertEquals("TOP", topCopper.side());
        assertEquals("Copper", topCopper.material());
        assertEquals(4, topCopper.sequence());

        Ipc2581StackupDocument.StackupLayer dielectric = stackup.getLayers().get(4);
        assertEquals(Function.DIELECTRIC, dielectric.function());
        assertEquals("DIELCORE", dielectric.rawFunction(), "the file's own word is kept");
        assertEquals("PP-001", dielectric.material(), "...even where it plainly means prepreg");

        assertFalse(stackup.getLayers().get(14).function().isPhysical(), "a drill guide is not a layer");
    }

    @Test
    @DisplayName("Inches become millimetres at the parse boundary, and stay exact as picometres")
    void inchesConvertExactly() {
        Ipc2581StackupDocument stackup = parser.parse(FOUR_LAYER_INCH);

        // 0.001378 inch of half-ounce foil, plated. 1 µin is 25 400 pm exactly, so nothing rounds.
        assertEquals(35_001_200L, StackEntry.toPicometres(stackup.getLayers().get(3).thicknessMm()));
        assertEquals(203_200_000L, StackEntry.toPicometres(stackup.getLayers().get(6).thicknessMm()));
        assertEquals(0.5656072, stackup.getBoardThicknessMm(), 1e-12,
                "0.022268 inch, to the picometre");
        assertEquals(565_607_200L, StackEntry.toPicometres(stackup.getBoardThicknessMm()));
    }

    @Test
    @DisplayName("A zero thickness means the layer has none, not that it measures zero")
    void zeroThicknessIsNotAMeasurement() {
        Ipc2581StackupDocument stackup = parser.parse(FOUR_LAYER_INCH);
        assertNull(stackup.getLayers().get(0).thicknessMm(), "solder paste is not part of the board");
        assertNull(stackup.getLayers().get(13).thicknessMm());
    }

    @Test
    void millimetreFilesNeedNoConversion() {
        Ipc2581StackupDocument stackup = parser.parse(TWO_LAYER_MM);
        assertEquals(1.6, stackup.getBoardThicknessMm(), 1e-12);
        assertEquals(0.035, stackup.getLayers().get(0).thicknessMm(), 1e-12);
        assertEquals(Function.DIELECTRIC, stackup.getLayers().get(1).function());
        assertEquals("DIELPREG", stackup.getLayers().get(1).rawFunction());
    }

    @Test
    @DisplayName("Anything that is not IPC-2581 is declined, and broken XML with it")
    void rejectsWhatItCannotRead() {
        assertNull(parser.parse(null));
        assertNull(parser.parse(""));
        assertNull(parser.parse("%TF.FileFunction,Copper,L1,Top*%\n"));
        assertNull(parser.parse("{\"GeneralSpecs\": {}}"));
        assertNull(parser.parse("<?xml version=\"1.0\"?><other><Stackup/></other>"));
        assertNull(parser.parse("<IPC-2581 revision=\"B\"><Ecad><CadData><Stackup "),
                "truncated mid-document");
        assertNull(parser.parse("<IPC-2581 revision=\"B\"><Ecad><CadData/></Ecad></IPC-2581>"),
                "an IPC-2581 file that states no stack-up has nothing for us");
    }

    @Nested
    @DisplayName("As a board stack")
    class AsABoardStack {

        @Test
        @DisplayName("Only the material layers make the stack; the documentation layers do not")
        void documentationLayersAreLeftOut() {
            BoardStack stack = BoardStack.from(parser.parse(FOUR_LAYER_INCH));

            assertEquals(List.of(
                    StackFunction.PASTE,
                    StackFunction.SILKSCREEN,
                    StackFunction.SOLDERMASK,
                    StackFunction.COPPER,
                    StackFunction.DIELECTRIC,
                    StackFunction.COPPER,
                    StackFunction.DIELECTRIC,
                    StackFunction.COPPER,
                    StackFunction.DIELECTRIC,
                    StackFunction.COPPER,
                    StackFunction.SOLDERMASK,
                    StackFunction.SILKSCREEN,
                    StackFunction.PASTE),
                    stack.getEntries().stream().map(StackEntry::getFunction).toList());

            for (int i = 0; i < stack.getEntries().size(); i++) {
                assertEquals(i, stack.getEntries().get(i).getOrdinal());
                assertFalse(stack.getEntries().get(i).isEstimated());
            }
            assertEquals("Core-027", stack.getEntries().get(6).getMaterial());
        }

        @Test
        @DisplayName("The layers add up to the stated board thickness, to the picometre")
        void thicknessesAddUp() {
            BoardStack stack = BoardStack.from(parser.parse(FOUR_LAYER_INCH));

            long sum = stack.getEntries().stream().map(StackEntry::getThicknessPm)
                    .filter(Objects::nonNull).mapToLong(Long::longValue).sum();
            assertEquals(565_607_200L, sum);
            assertEquals(565_607_200L, stack.getBoardThicknessPm(), "and the file says so itself");
            assertEquals(0.5656072, stack.getBoardThicknessMm(), 1e-12);
        }

        @Test
        @DisplayName("A set that ships an IPC-2581 file gets its stack-up and its thickness")
        void throughTheAnalyzer() {
            BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                    PcbFile.of("board-F_Cu.gbr", String.join("\n",
                            "%FSLAX46Y46*%", "%MOMM*%", "%ADD10C,0.200000*%", "D10*",
                            "X0Y0D02*", "X5000000Y0D01*", "M02*")),
                    PcbFile.of("board.cvg", FOUR_LAYER_INCH)));

            assertFalse(spec.isStackEstimated());
            assertEquals(13, spec.getStack().size());
            assertEquals(565_607_200L, spec.getBoardThicknessPm());
            assertEquals(4, spec.getStack().stream().filter(e -> e.getFunction().isCopper()).count(),
                    "four copper layers, from a set that shipped one copper Gerber");
        }

        @Test
        @DisplayName("A millimetre file needs no special case anywhere downstream")
        void millimetreFileThroughTheStack() {
            BoardStack stack = BoardStack.from(parser.parse(TWO_LAYER_MM));

            assertEquals(3, stack.getEntries().size());
            assertEquals(1_600_000_000L, stack.getBoardThicknessPm());
            assertEquals(1_530_000_000L, stack.getEntries().get(1).getThicknessPm());
            assertTrue(stack.getEntries().get(1).getFunction().isDielectric());
        }
    }
}
