package com.deltaproto.deltagerber.web;

import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.classify.LayerClassifier;
import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The viewer sends {@code AUTO} when it has no opinion about a layer, and the library classifies.
 *
 * <p>This used to be a pile of regexes in the browser. They rejected an arc-drawn board profile
 * because it has as many moves as draws, and a set with no outline makes the realistic view derive
 * the board edge from copper — which took 76 seconds on a 32 mm board.
 */
class ViewerLayerTypeTest {

    private static final String HEADER = "%FSLAX44Y44*%\n%MOMM*%\nG75*\n%ADD11C,0.0500*%\nD11*\n";

    /** A circular profile: one full-circle arc. Three draws, three moves — like NDc.GKO. */
    private static final String ARC_OUTLINE = HEADER
            + "X160000Y0D02*\nG03*\nX160000Y0I-160000J0D01*\nG01*\n"
            + "X-9500Y-119624D02*\nG03*\nX9500Y-119624I9500J119624D01*\nG01*\n"
            + "D02*\nG03*\nX-9500Y-119624I-9500J119624D01*\nM02*\n";

    private static LayerType resolve(String layerTypeStr, String fileType, String name, String content) {
        GerberDocument document = "gerber".equals(fileType) ? new GerberParser().parse(content) : null;
        return GerberViewerServer.RenderHandler.resolveLayerType(
                layerTypeStr, fileType, LayerClassifier.classify(name, content), document);
    }

    @Test
    @DisplayName("An arc-drawn board profile is an outline")
    void arcOutline() {
        assertEquals(LayerType.OUTLINE, resolve("AUTO", "gerber", "NDc.GKO", ARC_OUTLINE));
    }

    @Test
    @DisplayName("An outline that draws nothing is not one — the realistic view would render an empty board")
    void emptyOutlineIsDemoted() {
        assertEquals(LayerType.OTHER, resolve("AUTO", "gerber", "NDc.GKO", HEADER + "M02*\n"));
    }

    @Test
    @DisplayName("Protel inner-copper extensions resolve, index and all")
    void innerCopper() {
        String copper = HEADER + "X0Y0D02*\nX10000Y10000D01*\nM02*\n";
        assertEquals(LayerType.COPPER_INNER, resolve("AUTO", "gerber", "NDc.G3", copper));
        assertEquals(LayerType.COPPER_TOP, resolve("AUTO", "gerber", "NDc.GTL", copper));
        assertEquals(LayerType.COPPER_BOTTOM, resolve("AUTO", "gerber", "NDc.GBL", copper));
    }

    @Test
    @DisplayName("A pick-and-place file is not a fabrication layer, so only the document can name it")
    void pickAndPlace() {
        String pnp = "%TF.FileFunction,Component,L1,Top*%\n%FSLAX46Y46*%\n%MOMM*%\n"
                + "%TO.C,R1*%\n%TO.CRot,90*%\n%ADD10C,0.100000*%\nD10*\nX1000000Y1000000D03*\nM02*\n";
        assertEquals(LayerType.PNP_TOP, resolve("AUTO", "gerber", "board-pnp.gbr", pnp));
    }

    @Test
    @DisplayName("Excellon content is a drill however the file is named")
    void excellonAlwaysDrills() {
        String drill = "M48\nMETRIC\n;TYPE=PLATED\nT01C0.1500\n%\nT01\nX0010000Y0010000\nM30\n";
        assertEquals(LayerType.DRILL_PLATED, resolve("AUTO", "drill", "NDc-Plated.TXT", drill));
        // Even when the user's stored choice says otherwise.
        assertEquals(LayerType.DRILL, resolve("SILKSCREEN_TOP", "drill", "weird.name", drill));
    }

    @Test
    @DisplayName("The user's choice from the dropdown wins over the classifier")
    void userChoiceStands() {
        assertEquals(LayerType.OTHER, resolve("OTHER", "gerber", "NDc.GKO", ARC_OUTLINE));
        assertEquals(LayerType.SILKSCREEN_BOTTOM, resolve("SILKSCREEN_BOTTOM", "gerber", "NDc.GTL", ARC_OUTLINE));
    }

    @Test
    @DisplayName("An unknown type string degrades to OTHER rather than throwing")
    void unknownTypeString() {
        assertEquals(LayerType.OTHER, resolve("NOT_A_LAYER_TYPE", "gerber", "board.gbr", HEADER + "M02*\n"));
    }

    @Nested
    @DisplayName("A layer's label is derived from its type, never carried over")
    class Labels {

        /** What the classifier read off "NDc.GTL" before the user retyped it. */
        private final LayerClassification detectedTopCopper =
                new LayerClassification("top copper", LayerFunction.COPPER, LayerSide.TOP);

        @Test
        @DisplayName("Retyping a layer relabels it — the label must not contradict the function")
        void retypingRelabels() {
            LayerClassification retyped = GerberViewerServer.RenderHandler.classify(
                    LayerType.SILKSCREEN_TOP, null, detectedTopCopper, "NDc.GTL");
            assertEquals(LayerFunction.SILKSCREEN, retyped.function());
            assertEquals("top silkscreen", retyped.name(), "would have read 'top copper'");
        }

        @Test
        @DisplayName("An inner layer's label follows the index the user picked")
        void innerCopperLabelFollowsTheIndex() {
            LayerClassification detectedInner3 =
                    new LayerClassification("inner copper 3", LayerFunction.COPPER, LayerSide.INNER, 3);

            LayerClassification picked = GerberViewerServer.RenderHandler.classify(
                    LayerType.COPPER_INNER, 9, detectedInner3, "NDc.G3");
            assertEquals(9, picked.number());
            assertEquals("inner copper 9", picked.name());

            // No index picked: the classifier's own reading stands.
            LayerClassification kept = GerberViewerServer.RenderHandler.classify(
                    LayerType.COPPER_INNER, null, detectedInner3, "NDc.G3");
            assertEquals(3, kept.number());
            assertEquals("inner copper 3", kept.name());

            // Retyped away from inner copper: the index goes, and so does the label.
            LayerClassification outer = GerberViewerServer.RenderHandler.classify(
                    LayerType.COPPER_TOP, 9, detectedInner3, "NDc.G3");
            assertNull(outer.number());
            assertEquals("top copper", outer.name());
        }

        @Test
        @DisplayName("Every layer type has a label")
        void everyTypeIsNamed() {
            for (LayerType type : LayerType.values()) {
                String label = GerberViewerServer.RenderHandler.label(type, null);
                assertNotNull(label, type.toString());
                assertFalse(label.isBlank(), type + " must have a label");
                assertNotEquals(type.name(), label, type + " must not fall back to its enum name");
            }
            assertEquals("inner copper 2", GerberViewerServer.RenderHandler.label(LayerType.COPPER_INNER, 2));
            assertEquals("inner copper", GerberViewerServer.RenderHandler.label(LayerType.COPPER_INNER, null));
        }
    }
}
