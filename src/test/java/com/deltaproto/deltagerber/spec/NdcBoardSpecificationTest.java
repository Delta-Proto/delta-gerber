package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check against a real six-layer Altium board with a circular profile.
 *
 * <p>The board is a customer design, so it lives in the gitignored {@code excluded/} directory
 * rather than in {@code src/test/resources}; this test skips when it is not there. The behaviours
 * it pins down are also covered by synthetic fixtures in {@link PcbAnalyzerTest} and
 * {@link com.deltaproto.deltagerber.classify.LayerClassifierTest}, which always run.
 *
 * <p>What makes it worth keeping: none of the interesting answers here can be reached by reading
 * coordinates out of the file. The profile is a single full-circle arc, so its 32 mm extent exists
 * nowhere in the text; the only X coordinate present is 16. And the file declares no
 * {@code .FileFunction}, so every layer has to be classified from its name.
 */
@EnabledIf("fixtureIsPresent")
class NdcBoardSpecificationTest {

    private static final Path FIXTURE = Paths.get("excluded/NDc");

    static boolean fixtureIsPresent() {
        return Files.isDirectory(FIXTURE);
    }

    private static BoardSpecification spec;

    @BeforeAll
    static void analyze() throws IOException {
        spec = new PcbAnalyzer().analyze(files());
    }

    private static List<PcbFile> files() throws IOException {
        List<PcbFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(FIXTURE)) {
            entries.sorted().forEach(path -> {
                try {
                    files.add(PcbFile.of(path.getFileName().toString(), Files.readAllBytes(path)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return files;
    }

    private static AnalyzedLayer layer(String fileName) {
        return spec.getLayers().stream()
                .filter(l -> l.getFileName().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such layer: " + fileName));
    }

    @Test
    @DisplayName("NDc.GKO is the board outline")
    void outlineIsRecognised() {
        assertTrue(spec.hasOutline());
        assertEquals(LayerFunction.OUTLINE, layer("NDc.GKO").getFunction());
    }

    @Test
    @DisplayName("The circular board is 32.000 x 32.000 mm")
    void boardSize() {
        // A single G03 full circle of radius 16 about the origin, stroked with a 0.05 mm aperture.
        // Measured to the centreline the board is 32 mm; measured to the ink it would be 32.05 mm.
        assertEquals(32.000, spec.getSizeXMm(), 1e-6);
        assertEquals(32.000, spec.getSizeYMm(), 1e-6);
    }

    @Test
    @DisplayName("The narrowest track is 0.100 mm")
    void minTrackWidth() {
        assertEquals(100.0, spec.getMinTrackWidthUm(), 1e-6);
    }

    @Test
    @DisplayName("The copper logo on the top layer is floating copper")
    void floatingCopper() {
        // The figure is drawn as regions that nothing joins to a net: no flash, no pin, no hole.
        assertEquals(5, layer("NDc.GTL").getFloatingCopper().getPieces().size());
        assertEquals(0, layer("NDc.G2").getFloatingCopper().getPieces().size());
    }

    @Test
    @DisplayName("Two nets come within 0.090 mm on the top layer, the floating logo left out")
    void minClearance() {
        assertEquals(0.0900, spec.getMinClearanceMm(), 5e-4);
        assertEquals(0.0900, layer("NDc.GTL").getMinClearanceMm(), 5e-4);
        assertEquals(0.0983, layer("NDc.G2").getMinClearanceMm(), 5e-4);
        assertTrue(layer("NDc.GBL").getMinClearanceMm() > 0.099);
    }

    @Test
    @DisplayName("With floating copper counted, the logo's eye sits 0.025 mm from its head")
    void minClearanceWithFloatingCopper() throws IOException {
        // The pupil of the figure sits 25 µm from the outline of its head — two pieces of
        // copper that nothing joins, so they only count when floating copper is put back.
        BoardSpecification withFloating = new PcbAnalyzer().floatingCopperInClearance(true)
                .analyze(files());
        assertEquals(0.0253, withFloating.getMinClearanceMm(), 5e-4);
    }

    @Test
    @DisplayName("The copper necks to 0.056 mm in an inner plane, though the narrowest track is 0.100 mm")
    void minConductorWidth() {
        // Exactly the disagreement issue #11 is about: the aperture table says 0.1 mm, a pour on
        // the first inner layer narrows to 56 µm. The logo's 30 µm strokes are floating copper
        // and are not conductors.
        assertEquals(56.3, spec.getMinConductorWidthUm(), 0.5);
        assertEquals(56.3, layer("NDc.G1").getMinConductorWidthUm(), 0.5);
        assertEquals(94.0, layer("NDc.GTL").getMinConductorWidthUm(), 0.5);
        assertEquals(100.0, layer("NDc.G2").getMinConductorWidthUm(), 1e-6);
    }

    @Test
    @DisplayName("The smallest drill is 0.150 mm")
    void minDrillDiameter() {
        assertEquals(0.150, spec.getMinDrillDiameterMm(), 1e-6);
    }

    @Test
    @DisplayName("Six copper layers, classified from Protel extensions alone")
    void stackUp() {
        // The Altium export declares .GenerationSoftware but no .FileFunction, so every layer here
        // is named, not declared.
        assertEquals(6, spec.getCopperLayerCount());
        assertEquals(LayerSide.TOP, layer("NDc.GTL").getSide());
        assertEquals(LayerSide.BOTTOM, layer("NDc.GBL").getSide());
        assertEquals(3, layer("NDc.G3").getLayerNumber());
        assertEquals(LayerSide.INNER, layer("NDc.G3").getSide());
    }

    @Test
    @DisplayName("Plating comes from the Excellon ;TYPE= header, not the filename")
    void drillPlating() {
        assertEquals(LayerFunction.DRILL_PLATED, layer("NDc-Plated.TXT").getFunction());
        assertTrue(spec.hasDrill());
    }

    @Test
    @DisplayName("Only the top side carries paste, so only the top needs a stencil")
    void processes() {
        assertEquals(BoardSide.BOTH, spec.getSolderMaskSide());
        assertEquals(BoardSide.BOTH, spec.getSilkscreenSide());
        assertEquals(BoardSide.TOP, spec.getStencilSide());
    }
}
