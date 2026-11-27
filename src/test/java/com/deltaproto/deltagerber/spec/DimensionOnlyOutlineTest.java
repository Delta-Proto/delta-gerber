package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerClassifier;
import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.BoardOutline;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A file classified as the outline that encloses no board. Cadence Allegro's
 * {@code BOARD GEOMETRY/DIMENSION} film can carry nothing but the dimension figures, stroked beside
 * a board drawn nowhere in it; taken as the edge, the realistic view showed only the digits filled
 * with board and the specification measured the text. Synthetic: the real set is a customer's and
 * is checked under {@code excluded/} by {@link AllegroDimensionOutlineSetTest}.
 */
class DimensionOnlyOutlineTest {

    private static final String ALLEGRO_OUTLINE_HEADER =
            "G04 ================== begin FILE IDENTIFICATION RECORD ==================*\n"
            + "G04 File Origin:  Cadence Allegro 25.1-P001*\n"
            + "G04 Layer:  BOARD GEOMETRY/DIMENSION*\n"
            + "G04 Layer:  BOARD GEOMETRY/DESIGN_OUTLINE*\n"
            + "G04 ================== end FILE IDENTIFICATION RECORD ====================*\n";

    /** "1.5" left of the board and "7" below it, in 0.1 mm strokes, the way Allegro writes dimension figures. */
    private static final String FIGURES =
            "%ADD10C,0.1000*%\nD10*\n"
            // the "1": an open polyline
            + "X-80000Y100000D02*\nX-70000Y110000D01*\nX-70000Y30000D01*\n"
            // the decimal point: a tiny closed loop
            + "X-60000Y30000D02*\nX-59000Y30000D01*\nX-59000Y31000D01*\nX-60000Y31000D01*\nX-60000Y30000D01*\n"
            // the "5": open
            + "X-40000Y110000D02*\nX-52000Y110000D01*\nX-52000Y75000D01*\nX-42000Y75000D01*\n"
            + "X-40000Y50000D01*\nX-46000Y30000D01*\nX-52000Y35000D01*\n"
            // and a "7" under the board for the other dimension, far from the first figure, as
            // Allegro places them — so no one figure spans the layer and passes for an open edge
            + "X180000Y-40000D02*\nX220000Y-40000D01*\nX195000Y-120000D01*\n";

    private static String dimensionOnlyOutline() {
        return ALLEGRO_OUTLINE_HEADER + "%FSLAX44Y44*%\n%MOMM*%\nG01*\n%LPD*%\n" + FIGURES + "M02*\n";
    }

    /** The same figures beside a closed 40 x 30 mm board edge: a real outline, and it stays one. */
    private static String edgeWithDimension() {
        return ALLEGRO_OUTLINE_HEADER + "%FSLAX44Y44*%\n%MOMM*%\nG01*\n%LPD*%\n" + FIGURES
                + "X0Y0D02*\nX400000Y0D01*\nX400000Y300000D01*\nX0Y300000D01*\nX0Y0D01*\nM02*\n";
    }

    /** Copper over 2..38 x 2..28 mm: a pour and a grid of pads. */
    private static String copper() {
        StringBuilder g = new StringBuilder(
                "G04 ================== begin FILE IDENTIFICATION RECORD ==================*\n"
                + "G04 Layer:  ETCH/TOP*\n"
                + "G04 ================== end FILE IDENTIFICATION RECORD ====================*\n"
                + "%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,1.0000*%\n%LPD*%\n"
                + "G36*\nX50000Y50000D02*\nX350000Y50000D01*\nX350000Y250000D01*\nX50000Y250000D01*\n"
                + "X50000Y50000D01*\nG37*\nD10*\n");
        for (int x = 25000; x <= 375000; x += 50000) {
            for (int y = 25000; y <= 275000; y += 50000) {
                g.append("X").append(x).append("Y").append(y).append("D03*\n");
            }
        }
        return g.append("M02*\n").toString();
    }

    private static BoardOutline resolve(String outline) {
        GerberParser parser = new GerberParser();
        return new MultiLayerSVGRenderer().resolveBoardOutline(List.of(
                new MultiLayerSVGRenderer.Layer("Board_outline.art", parser.parse(outline))
                        .setLayerType(LayerType.OUTLINE),
                new MultiLayerSVGRenderer.Layer("TOP.art", parser.parse(copper()))
                        .setLayerType(LayerType.COPPER_TOP)));
    }

    @Test
    void classifiedAsOutlineByItsHeader() {
        // The premise: nothing at classification time says this file is not the edge.
        assertEquals(LayerFunction.OUTLINE,
                LayerClassifier.classify("Board_outline.art", dimensionOnlyOutline()).function());
    }

    @Test
    void rendererDerivesTheEdgeFromCopperInstead() {
        BoardOutline outline = resolve(dimensionOnlyOutline());
        assertFalse(outline.isEmpty());
        assertTrue(outline.isDerived(), "dimension figures must not be taken as the board edge");
        // The derived board covers the copper, not the digits.
        var bounds = outline.toShape().getBounds2D();
        assertTrue(bounds.getMinX() > 0 && bounds.getMaxX() > 37 && bounds.getMaxY() > 27, bounds.toString());
    }

    @Test
    void anEdgeWithDimensionsBesideItIsStillTheEdge() {
        BoardOutline outline = resolve(edgeWithDimension());
        assertTrue(outline.isFromProfileLayer());
    }

    @Test
    void analyzerReclassifiesItAndSizesTheBoardFromTheArtwork() {
        BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                PcbFile.of("Board_outline.art", dimensionOnlyOutline()),
                PcbFile.of("TOP.art", copper())));

        AnalyzedLayer outline = spec.getLayers().stream()
                .filter(l -> l.getFileName().equals("Board_outline.art")).findFirst().orElseThrow();
        assertEquals(LayerFunction.MECHANICAL_DRAWING, outline.getFunction());
        assertFalse(spec.hasOutline());
        // The copper's 2..38 x 2..28 mm with half a pad either side, not the figures' extent.
        assertEquals(36.0, spec.getSizeXMm(), 0.01);
        assertEquals(26.0, spec.getSizeYMm(), 0.01);
    }

    @Test
    void analyzerKeepsARealEdgeWithDimensions() {
        BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
                PcbFile.of("Board_outline.art", edgeWithDimension()),
                PcbFile.of("TOP.art", copper())));
        assertTrue(spec.hasOutline());
    }

    @Test
    void parsedEntryPointAgrees() {
        GerberParser parser = new GerberParser();
        var outlineClass = LayerClassifier.classify("Board_outline.art", dimensionOnlyOutline());
        var copperClass = LayerClassifier.classify("TOP.art", copper());
        BoardSpecification parsed = new PcbAnalyzer().analyzeParsed(List.of(
                ParsedLayer.of("Board_outline.art", outlineClass, parser.parse(dimensionOnlyOutline())),
                ParsedLayer.of("TOP.art", copperClass, parser.parse(copper()))));
        assertFalse(parsed.hasOutline());
        assertEquals(36.0, parsed.getSizeXMm(), 0.01);
    }

    @Test
    void allegroSidecarTextFilesAreNotDrills() {
        String ncParam = "INTEGER-PLACES         3\nDECIMAL-PLACES         5\nX-OFFSET               0.000000\n"
                + "OUTPUT-UNITS           METRIC\nTOOL-ORDER             INCREASING\n";
        String placement = "UUNITS = MILLIMETERS\n\n#    refdes  !   symbol_x !   symbol_y ! rotation\n"
                + "D32          !   227.1740 !   106.6500 !       90\nT1           !    12.0000 !    10.0000 !        0\n"
                + "X1           !    64.6060 !   284.2500 !        0\n";
        assertNotDrill(LayerClassifier.classify("nc_param.txt", ncParam));
        assertNotDrill(LayerClassifier.classify("place_txt.txt", placement));

        // A real drill program by the same extension still is one.
        assertTrue(LayerClassifier.classify("board.txt", "M48\nMETRIC\nT1C0.800\n%\nT1\nX30.0Y30.0\nM30\n")
                .function().isDrill());
        assertTrue(LayerClassifier.classify("board.txt", "; drill\nT01\nX012345Y067890\nM30\n")
                .function().isDrill());
    }

    private static void assertNotDrill(com.deltaproto.deltagerber.classify.LayerClassification c) {
        assertTrue(c == null || !c.function().isDrill(), "classified " + c);
    }
}
