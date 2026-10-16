package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.NegativeImageTest;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.BoardOutline;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An Altium plane layer declares {@code .FilePolarity,Negative} and draws its clearances. Both
 * the analysis and the renderer must read it as the copper it stands for: the board, pulled back
 * from the edge by half the stroke it draws there, with the antipads cut out.
 */
class NegativePlaneTest {

    /** A 10×10 mm board profile, traced once with a 0.05 mm aperture. */
    private static final String GKO = String.join("\n",
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

    /** Positive top copper: three 0.2 mm tracks well inside the board. */
    private static final String GTL = String.join("\n",
            "%FSLAX46Y46*%",
            "%MOMM*%",
            "%ADD11C,0.200000*%",
            "D11*",
            "X2000000Y2000000D02*",
            "X8000000Y8000000D01*",
            "X8000000Y2000000D01*",
            "X2000000Y8000000D01*",
            "M02*");

    private static List<PcbFile> syntheticSet() {
        return List.of(
                PcbFile.of("board.GKO", GKO),
                PcbFile.of("board.GTL", GTL),
                PcbFile.of("board.GP1", NegativeImageTest.PLANE));
    }

    private static AnalyzedLayer layer(BoardSpecification spec, String fileName) {
        return spec.getLayers().stream()
                .filter(l -> l.getFileName().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such layer: " + fileName));
    }

    @Nested
    @DisplayName("Analysis")
    class Analysis {

        @Test
        @DisplayName("The plane is copper over the board, not a stroke and a ring")
        void planeMeasuresAsCopper() {
            BoardSpecification spec = new PcbAnalyzer().analyze(syntheticSet());
            assertEquals(2, spec.getCopperLayerCount());

            AnalyzedLayer plane = layer(spec, "board.GP1");
            assertEquals(LayerFunction.COPPER, plane.getFunction());
            assertEquals(LayerSide.INNER, plane.getSide());

            // The copper's extent is the board (the clear pull-back's ink reaches 0.5 mm beyond).
            BoundingBox bounds = plane.getBounds();
            assertTrue(bounds.getMinX() <= 0 && bounds.getMaxX() >= 10, bounds.toString());
            assertTrue(bounds.getMinX() >= -0.5 - 1e-6 && bounds.getMaxX() <= 10.5 + 1e-6, bounds.toString());

            // The 1 mm pull-back is a clearance, not the layer's narrowest track.
            assertNull(plane.getMinTrackWidthUm());
            assertEquals(200.0, spec.getMinTrackWidthUm(), 1e-9);

            // Copper stops half a stroke short of the edge.
            assertNotNull(plane.getMinEdgeClearanceMm());
            assertEquals(0.5, plane.getMinEdgeClearanceMm(), 1e-3);

            // The plane's narrowest copper: from the antipad's edge (5.6) to the pull-back's (9.5).
            assertEquals(3900.0, plane.getMinConductorWidthUm(), 1.0);
        }

        @Test
        @DisplayName("analyzeParsed agrees with analyze")
        void parsedEntryPointAgrees() {
            PcbAnalyzer analyzer = new PcbAnalyzer();
            BoardSpecification fromFiles = analyzer.analyze(syntheticSet());
            List<ParsedLayer> parsed = new ArrayList<>();
            for (PcbFile file : syntheticSet()) {
                parsed.add(ParsedLayer.of(file.getFileName(), analyzer.classify(file),
                        new GerberParser().parse(file.getContent())));
            }
            BoardSpecification fromParsed = analyzer.analyzeParsed(parsed);
            AnalyzedLayer a = layer(fromFiles, "board.GP1");
            AnalyzedLayer b = layer(fromParsed, "board.GP1");
            assertEquals(a.getMinEdgeClearanceMm(), b.getMinEdgeClearanceMm(), 1e-9);
            assertEquals(a.getBounds().getMinX(), b.getBounds().getMinX(), 1e-9);
            assertEquals(a.getBounds().getMaxX(), b.getBounds().getMaxX(), 1e-9);
            assertEquals(fromFiles.getCopperLayerCount(), fromParsed.getCopperLayerCount());
        }
    }

    @Nested
    @DisplayName("Rendering")
    class Rendering {

        private List<MultiLayerSVGRenderer.Layer> layers() {
            List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
            layers.add(new MultiLayerSVGRenderer.Layer("board.GKO", new GerberParser().parse(GKO))
                    .setLayerType(LayerType.OUTLINE));
            layers.add(new MultiLayerSVGRenderer.Layer("board.GTL", new GerberParser().parse(GTL))
                    .setLayerType(LayerType.COPPER_TOP));
            layers.add(new MultiLayerSVGRenderer.Layer("board.GP1", new GerberParser().parse(NegativeImageTest.PLANE))
                    .setLayerType(LayerType.COPPER_INNER).setColor("#c08040").setOpacity(0.6));
            return layers;
        }

        @Test
        @DisplayName("positiveImages swaps the plane for its copper over the resolved outline, keeping the layer")
        void planeIsReplacedByItsCopper() {
            MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
            List<MultiLayerSVGRenderer.Layer> original = layers();
            List<MultiLayerSVGRenderer.Layer> positive = renderer.positiveImages(original);

            assertEquals(original.size(), positive.size());
            assertSame(original.get(0), positive.get(0));
            assertSame(original.get(1), positive.get(1));

            MultiLayerSVGRenderer.Layer plane = positive.get(2);
            GerberDocument doc = plane.getGerberDoc();
            assertTrue(doc.isInvertedImage());
            assertEquals("board.GP1", plane.getName());
            assertEquals(LayerType.COPPER_INNER, plane.getLayerType());
            assertEquals("#c08040", plane.getColor());
            assertEquals(0.6, plane.getOpacity(), 1e-9);
            BoundingBox field = doc.getObjects().get(0).getBoundingBox();
            assertEquals(0, field.getMinX(), 1e-6);
            assertEquals(10, field.getMaxX(), 1e-6);

            // A set without a negative image is handed back as it is.
            List<MultiLayerSVGRenderer.Layer> plain = original.subList(0, 2);
            assertSame(plain, renderer.positiveImages(plain));
        }

        @Test
        @DisplayName("The plane is no evidence for the board edge, before or after conversion")
        void planeDoesNotMoveTheOutline() {
            MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
            List<MultiLayerSVGRenderer.Layer> original = layers();
            BoardOutline without = renderer.resolveBoardOutline(original.subList(0, 2));
            BoardOutline with = renderer.resolveBoardOutline(original);
            BoardOutline converted = renderer.resolveBoardOutline(renderer.positiveImages(original));
            assertEquals(without.getSvgPath(), with.getSvgPath());
            assertEquals(without.getSvgPath(), converted.getSvgPath());
        }

        @Test
        @DisplayName("Every entry point renders the converted plane")
        void entryPointsRender() {
            MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
            String layerView = renderer.render(layers());
            assertTrue(layerView.contains("fill-rule=\"evenodd\""), "the dark field is a region");
            assertFalse(renderer.renderRealistic(layers()).isEmpty());
            assertFalse(renderer.renderInverse(layers(), "#000").isEmpty());
        }
    }

    /** danyhm's 6-layer Altium test set from issue #9, when it is present (customer data, gitignored). */
    @Nested
    @DisplayName("Altium 6-layer test set (excluded/)")
    @EnabledIf("com.deltaproto.deltagerber.spec.NegativePlaneTest#sixLayerSetIsPresent")
    class SixLayerSet {

        @Test
        void planesAreCopperOverTheBoard() throws IOException {
            BoardSpecification spec = new PcbAnalyzer().analyze(sixLayerSet());
            assertEquals(6, spec.getCopperLayerCount());
            assertEquals(18.034, spec.getSizeXMm(), 1e-3);
            assertEquals(13.843, spec.getSizeYMm(), 1e-3);
            for (String name : List.of("6layer_test.GP1", "6layer_test.GP2")) {
                AnalyzedLayer plane = layer(spec, name);
                assertEquals(LayerFunction.COPPER, plane.getFunction(), name);
                // 0.04" pull-back stroked on the edge: copper stops 0.508 mm short of it.
                assertEquals(0.508, plane.getMinEdgeClearanceMm(), 1e-3, name);
                assertNull(plane.getMinTrackWidthUm(), name);
                BoundingBox b = plane.getBounds();
                assertTrue(b.getWidth() >= 18.034 - 1e-3 && b.getHeight() >= 13.843 - 1e-3, b.toString());
            }
            // The positive layers still read their 0.1016 mm tracks.
            assertEquals(101.6, spec.getMinTrackWidthUm(), 1e-6);
        }
    }

    private static final Path SIX_LAYER = Paths.get("excluded/danyhm-issue9/6layer");

    static boolean sixLayerSetIsPresent() {
        return Files.isDirectory(SIX_LAYER);
    }

    private static List<PcbFile> sixLayerSet() throws IOException {
        List<PcbFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.walk(SIX_LAYER)) {
            entries.filter(Files::isRegularFile).sorted().forEach(path -> {
                try {
                    files.add(PcbFile.of(path.getFileName().toString(), Files.readAllBytes(path)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return files;
    }
}
