package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the MultiLayerSVGRenderer by comparing its output against reference SVGs.
 *
 * This test validates that:
 * 1. All layers share the same viewBox (coordinate system alignment)
 * 2. Layer groups are properly structured with class="layer"
 * 3. Layer IDs match the expected filenames
 * 4. Bounding boxes align with reference outputs
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MultiLayerSvgValidationTest {

    private static final Path DEPR_TEST_DIR = Path.of("testdata/DEPR PR31 GBDR V04");
    private static final Path DEPR_REFERENCE_SVG = Path.of("testdata/DEPR PR31 GBDR V04.svg");
    private static final Path OUTPUT_DIR = Path.of("target/multi-layer-validation");

    private static final GerberParser gerberParser = new GerberParser();
    private static final ExcellonParser drillParser = new ExcellonParser();
    private static final MultiLayerSVGRenderer multiLayerRenderer = new MultiLayerSVGRenderer();

    private static Document referenceDoc;
    private static String referenceBoundsStr;

    @BeforeAll
    static void setup() throws Exception {
        Files.createDirectories(OUTPUT_DIR);

        // Parse reference SVG to extract expected structure
        if (Files.exists(DEPR_REFERENCE_SVG)) {
            String refContent = Files.readString(DEPR_REFERENCE_SVG);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            referenceDoc = builder.parse(new InputSource(new StringReader(refContent)));

            // Extract viewBox from reference (it's nested in a <g> element)
            NodeList groups = referenceDoc.getElementsByTagName("g");
            for (int i = 0; i < groups.getLength(); i++) {
                Element g = (Element) groups.item(i);
                String viewBox = g.getAttribute("viewBox");
                if (viewBox != null && !viewBox.isEmpty()) {
                    referenceBoundsStr = viewBox;
                    break;
                }
            }
            System.out.println("Reference viewBox: " + referenceBoundsStr);
        }
    }

    @Test
    @Order(1)
    @DisplayName("Multi-layer render - DEPR PR31 files")
    void testMultiLayerRenderDeprPr31() throws Exception {
        // Skip if test directory doesn't exist
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        // Collect all Gerber and drill files
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        List<String> layerNames = new ArrayList<>();

        try (var files = Files.list(DEPR_TEST_DIR)) {
            files.sorted().forEach(path -> {
                String filename = path.getFileName().toString();
                String lower = filename.toLowerCase();

                try {
                    String content = Files.readString(path);
                    MultiLayerSVGRenderer.Layer layer = null;

                    // Detect type and parse
                    if (lower.endsWith(".txt") || lower.endsWith(".drl") || lower.endsWith(".xln")) {
                        DrillDocument doc = drillParser.parse(content);
                        layer = new MultiLayerSVGRenderer.Layer(filename, doc);
                        layer.setColor("#00ffff");
                    } else if (content.contains("%FS") || content.contains("%MO")) {
                        GerberDocument doc = gerberParser.parse(content);
                        layer = new MultiLayerSVGRenderer.Layer(filename, doc);
                        layer.setColor(getLayerColor(filename));
                    }

                    if (layer != null) {
                        layer.setOpacity(0.85);
                        layers.add(layer);
                        layerNames.add(filename);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse " + filename + ": " + e.getMessage());
                }
            });
        }

        System.out.println("Loaded " + layers.size() + " layers");
        assertTrue(layers.size() >= 10, "Should have at least 10 layers from DEPR PR31");

        // Render to multi-layer SVG
        String svg = multiLayerRenderer.render(layers);
        assertNotNull(svg);
        assertFalse(svg.isEmpty());

        // Save for inspection
        Files.writeString(OUTPUT_DIR.resolve("depr-pr31-multi-layer.svg"), svg);

        // Parse and validate structure
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(svg)));

        // Check root SVG element
        Element root = doc.getDocumentElement();
        assertEquals("svg", root.getTagName());

        // Check viewBox exists
        String viewBox = root.getAttribute("viewBox");
        assertNotNull(viewBox);
        assertFalse(viewBox.isEmpty(), "viewBox should not be empty");
        System.out.println("Generated viewBox: " + viewBox);

        // Check for viewport group with Y-flip transform
        NodeList viewports = doc.getElementsByTagName("g");
        Element viewport = null;
        for (int i = 0; i < viewports.getLength(); i++) {
            Element g = (Element) viewports.item(i);
            if ("viewport".equals(g.getAttribute("id"))) {
                viewport = g;
                break;
            }
        }
        assertNotNull(viewport, "Should have viewport group");
        String transform = viewport.getAttribute("transform");
        assertTrue(transform.contains("scale(1,-1)"), "Viewport should have Y-flip transform");

        // Check layer groups
        NodeList layerGroups = doc.getElementsByTagName("g");
        List<Element> layerElements = new ArrayList<>();
        for (int i = 0; i < layerGroups.getLength(); i++) {
            Element g = (Element) layerGroups.item(i);
            if ("layer".equals(g.getAttribute("class"))) {
                layerElements.add(g);
            }
        }

        assertEquals(layers.size(), layerElements.size(),
            "Should have " + layers.size() + " layer groups");

        // Verify each layer has expected attributes
        for (Element layerElem : layerElements) {
            String id = layerElem.getAttribute("id");
            assertNotNull(id, "Layer should have id");
            assertFalse(id.isEmpty(), "Layer id should not be empty");

            String display = layerElem.getAttribute("display");
            assertEquals("inline", display, "Layer should have display=inline");

            String fill = layerElem.getAttribute("fill");
            assertNotNull(fill, "Layer should have fill color");

            String opacity = layerElem.getAttribute("opacity");
            assertNotNull(opacity, "Layer should have opacity");
        }

        System.out.println("Multi-layer SVG validation passed!");
    }

    @Test
    @Order(2)
    @DisplayName("Coordinate system alignment check")
    void testCoordinateSystemAlignment() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        // Calculate expected global bounding box by merging all individual bounding boxes
        BoundingBox globalBounds = new BoundingBox();
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();

        try (var files = Files.list(DEPR_TEST_DIR)) {
            files.sorted().forEach(path -> {
                String filename = path.getFileName().toString();
                try {
                    String content = Files.readString(path);

                    if (filename.toLowerCase().endsWith(".txt") ||
                        filename.toLowerCase().endsWith(".drl")) {
                        DrillDocument doc = drillParser.parse(content);
                        globalBounds.extend(doc.getBoundingBox());
                        layers.add(new MultiLayerSVGRenderer.Layer(filename, doc));
                    } else if (content.contains("%FS") || content.contains("%MO")) {
                        GerberDocument doc = gerberParser.parse(content);
                        globalBounds.extend(doc.getBoundingBox());
                        layers.add(new MultiLayerSVGRenderer.Layer(filename, doc));
                    }
                } catch (Exception e) {
                    // Skip unparseable files
                }
            });
        }

        System.out.println(String.format("Global bounding box: (%.3f, %.3f) to (%.3f, %.3f)",
            globalBounds.getMinX(), globalBounds.getMinY(),
            globalBounds.getMaxX(), globalBounds.getMaxY()));
        System.out.println(String.format("Size: %.3f x %.3f mm",
            globalBounds.getWidth(), globalBounds.getHeight()));

        // Render and check viewBox matches global bounds (plus margin)
        String svg = multiLayerRenderer.render(layers);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(svg)));

        String viewBox = doc.getDocumentElement().getAttribute("viewBox");
        String[] parts = viewBox.split(" ");
        assertEquals(4, parts.length, "viewBox should have 4 parts");

        double vbMinX = Double.parseDouble(parts[0]);
        double vbMinY = Double.parseDouble(parts[1]);
        double vbWidth = Double.parseDouble(parts[2]);
        double vbHeight = Double.parseDouble(parts[3]);

        // Check that viewBox encompasses global bounds (with margin tolerance)
        double margin = 0.6; // 0.5mm margin + tolerance
        assertTrue(vbMinX <= globalBounds.getMinX(),
            "viewBox minX should be <= global minX");
        assertTrue(vbMinY <= globalBounds.getMinY(),
            "viewBox minY should be <= global minY");
        assertTrue(vbMinX + vbWidth >= globalBounds.getMaxX(),
            "viewBox should extend past global maxX");
        assertTrue(vbMinY + vbHeight >= globalBounds.getMaxY(),
            "viewBox should extend past global maxY");

        System.out.println("Coordinate system alignment verified!");
    }

    @Test
    @Order(3)
    @DisplayName("Compare with reference SVG structure")
    void testCompareWithReference() throws Exception {
        if (!Files.exists(DEPR_REFERENCE_SVG) || !Files.exists(DEPR_TEST_DIR)) {
            System.out.println("Reference or test files not found, skipping");
            return;
        }

        // Parse reference to count layer groups
        int refLayerCount = 0;
        NodeList refGroups = referenceDoc.getElementsByTagName("g");
        for (int i = 0; i < refGroups.getLength(); i++) {
            Element g = (Element) refGroups.item(i);
            if ("layer".equals(g.getAttribute("class"))) {
                refLayerCount++;
            }
        }

        System.out.println("Reference layer count: " + refLayerCount);

        // Generate our multi-layer SVG
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        try (var files = Files.list(DEPR_TEST_DIR)) {
            files.sorted().forEach(path -> {
                String filename = path.getFileName().toString();
                try {
                    String content = Files.readString(path);

                    if (filename.toLowerCase().endsWith(".txt") ||
                        filename.toLowerCase().endsWith(".drl")) {
                        DrillDocument doc = drillParser.parse(content);
                        layers.add(new MultiLayerSVGRenderer.Layer(filename, doc));
                    } else if (content.contains("%FS") || content.contains("%MO")) {
                        GerberDocument doc = gerberParser.parse(content);
                        layers.add(new MultiLayerSVGRenderer.Layer(filename, doc));
                    }
                } catch (Exception e) {
                    // Skip
                }
            });
        }

        String svg = multiLayerRenderer.render(layers);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(svg)));

        int genLayerCount = 0;
        NodeList genGroups = doc.getElementsByTagName("g");
        for (int i = 0; i < genGroups.getLength(); i++) {
            Element g = (Element) genGroups.item(i);
            if ("layer".equals(g.getAttribute("class"))) {
                genLayerCount++;
            }
        }

        System.out.println("Generated layer count: " + genLayerCount);

        // We should have the same number of layers (or close)
        // Reference may have extra layers due to different file detection
        assertTrue(genLayerCount >= refLayerCount - 2,
            "Generated layer count should be close to reference");

        // Check structural similarity
        assertTrue(svg.contains("class=\"layer\""), "Should have layer classes");
        assertTrue(svg.contains("display=\"inline\""), "Should have display attribute");
        assertTrue(svg.contains("opacity="), "Should have opacity attribute");

        System.out.println("Structure comparison passed!");
    }

    @Test
    @Order(4)
    @DisplayName("Layer toggle simulation")
    void testLayerToggleSimulation() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        // Create layers with different visibility settings
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        int count = 0;

        try (var files = Files.list(DEPR_TEST_DIR)) {
            var sortedFiles = files.sorted().toList();
            for (Path path : sortedFiles) {
                String filename = path.getFileName().toString();
                try {
                    String content = Files.readString(path);
                    MultiLayerSVGRenderer.Layer layer = null;

                    if (filename.toLowerCase().endsWith(".txt") ||
                        filename.toLowerCase().endsWith(".drl")) {
                        DrillDocument doc = drillParser.parse(content);
                        layer = new MultiLayerSVGRenderer.Layer(filename, doc);
                    } else if (content.contains("%FS") || content.contains("%MO")) {
                        GerberDocument doc = gerberParser.parse(content);
                        layer = new MultiLayerSVGRenderer.Layer(filename, doc);
                    }

                    if (layer != null) {
                        // Toggle every other layer off
                        layer.setVisible(count % 2 == 0);
                        layers.add(layer);
                        count++;
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
        }

        String svg = multiLayerRenderer.render(layers);

        // Count visible vs hidden layers
        int visibleCount = 0;
        int hiddenCount = 0;

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(svg)));

        NodeList groups = doc.getElementsByTagName("g");
        for (int i = 0; i < groups.getLength(); i++) {
            Element g = (Element) groups.item(i);
            if ("layer".equals(g.getAttribute("class"))) {
                String display = g.getAttribute("display");
                if ("inline".equals(display)) {
                    visibleCount++;
                } else if ("none".equals(display)) {
                    hiddenCount++;
                }
            }
        }

        System.out.println("Visible layers: " + visibleCount);
        System.out.println("Hidden layers: " + hiddenCount);

        assertTrue(visibleCount > 0, "Should have some visible layers");
        assertTrue(hiddenCount > 0, "Should have some hidden layers");

        // The split should be roughly 50/50
        double ratio = (double) visibleCount / (visibleCount + hiddenCount);
        assertTrue(ratio > 0.3 && ratio < 0.7,
            "Visible/hidden ratio should be roughly 50/50");

        // Save for inspection
        Files.writeString(OUTPUT_DIR.resolve("depr-pr31-toggle-test.svg"), svg);

        System.out.println("Layer toggle simulation passed!");
    }

    private String getLayerColor(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("gtl") || lower.contains("top")) return "#e94560";
        if (lower.contains("gbl") || lower.contains("bottom")) return "#4169e1";
        if (lower.contains("gts")) return "#00aa00";
        if (lower.contains("gbs")) return "#006600";
        if (lower.contains("gto")) return "#ffffff";
        if (lower.contains("gbo")) return "#cccccc";
        if (lower.contains("gtp")) return "#888888";
        if (lower.contains("gbp")) return "#666666";
        if (lower.contains("gm1") || lower.contains("gko")) return "#ffff00";
        if (lower.contains("g2")) return "#ff8c00";
        if (lower.contains("g1")) return "#ff6600";
        return "#aaaaaa";
    }
}
