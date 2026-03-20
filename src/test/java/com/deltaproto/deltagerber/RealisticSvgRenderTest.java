package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
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
 * Tests the realistic PCB rendering via MultiLayerSVGRenderer.renderRealistic().
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RealisticSvgRenderTest {

    private static final Path DEPR_TEST_DIR = Path.of("testdata/DEPR PR31 GBDR V04");
    private static final Path ARDUINO_TEST_DIR = Path.of("testdata/arduino-uno");
    private static final Path OUTPUT_DIR = Path.of("target/realistic-render-validation");

    private static final GerberParser gerberParser = new GerberParser();
    private static final ExcellonParser drillParser = new ExcellonParser();

    @BeforeAll
    static void setup() throws Exception {
        Files.createDirectories(OUTPUT_DIR);
    }

    @Test
    @Order(1)
    @DisplayName("Realistic render - top side with outline, copper, soldermask, silkscreen")
    void testRealisticTopSide() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        // Load specific layers needed for realistic rendering
        Map<String, GerberDocument> docs = loadGerberFiles(
            "uP-H Main PCBA Assy V04.GKO",  // Outline
            "uP-H Main PCBA Assy V04.GTL",  // Top copper
            "uP-H Main PCBA Assy V04.GTS",  // Top soldermask
            "uP-H Main PCBA Assy V04.GTO"   // Top silkscreen
        );

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();

        layers.add(new MultiLayerSVGRenderer.Layer("outline", docs.get("GKO"))
            .setLayerType(LayerType.OUTLINE));
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", docs.get("GTL"))
            .setLayerType(LayerType.COPPER_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("soldermask-top", docs.get("GTS"))
            .setLayerType(LayerType.SOLDERMASK_TOP)
            .setOpacity(0.75));
        layers.add(new MultiLayerSVGRenderer.Layer("silkscreen-top", docs.get("GTO"))
            .setLayerType(LayerType.SILKSCREEN_TOP));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);
        assertNotNull(svg);
        assertFalse(svg.isEmpty());

        // Save for visual inspection
        Files.writeString(OUTPUT_DIR.resolve("realistic-top.svg"), svg);
        System.out.println("Realistic top SVG saved to " + OUTPUT_DIR.resolve("realistic-top.svg"));

        // Parse and validate structure
        Document doc = parseSvg(svg);
        Element root = doc.getDocumentElement();
        assertEquals("svg", root.getTagName());

        // Should have viewBox
        String viewBox = root.getAttribute("viewBox");
        assertFalse(viewBox.isEmpty(), "Should have viewBox");

        // Should have defs section with board-outline clipPath
        NodeList clipPaths = doc.getElementsByTagName("clipPath");
        boolean hasOutlineClip = false;
        for (int i = 0; i < clipPaths.getLength(); i++) {
            Element cp = (Element) clipPaths.item(i);
            if ("board-outline".equals(cp.getAttribute("id"))) {
                hasOutlineClip = true;
                // Should contain a path element
                NodeList paths = cp.getElementsByTagName("path");
                assertTrue(paths.getLength() > 0, "Board outline clipPath should contain a path");
            }
        }
        assertTrue(hasOutlineClip, "Should have board-outline clipPath");

        // Should have soldermask mask
        NodeList masks = doc.getElementsByTagName("mask");
        boolean hasSoldermaskMask = false;
        for (int i = 0; i < masks.getLength(); i++) {
            Element mask = (Element) masks.item(i);
            if ("sm-top-mask".equals(mask.getAttribute("id"))) {
                hasSoldermaskMask = true;
            }
        }
        assertTrue(hasSoldermaskMask, "Should have sm-top-mask");

        // Should have viewport with Y-flip
        Element viewport = findElementById(doc, "viewport");
        assertNotNull(viewport, "Should have viewport group");
        assertTrue(viewport.getAttribute("transform").contains("scale(1,-1)"));

        // Should contain the soldermask mask reference
        assertTrue(svg.contains("mask=\"url(#sm-top-mask)\""),
            "Should have soldermask group referencing the mask");
        assertTrue(svg.contains("opacity=\"0.75\""),
            "Soldermask should have opacity");

        // Should have copper finish mask for exposed pads
        assertTrue(svg.contains("mask=\"url(#cf-top-mask)\""),
            "Should have copper finish mask for exposed pads");

        // Should have clip-path references for layers
        assertTrue(svg.contains("clip-path=\"url(#board-outline)\""),
            "Layers should be clipped to board outline");

        // Should have FR4 substrate color
        assertTrue(svg.contains("#666666"), "Should have FR4 substrate color");
        // Should have copper color (silver/gray under mask)
        assertTrue(svg.contains("#cccccc"), "Should have copper color");
        // Should have copper finish color (gold for exposed pads)
        assertTrue(svg.contains("#cc9933"), "Should have copper finish color");
        // Should have soldermask green
        assertTrue(svg.contains("#004200"), "Should have soldermask green");
        // Should have silkscreen white
        assertTrue(svg.contains("#ffffff"), "Should have silkscreen white");

        System.out.println("Realistic top-side rendering validated!");
    }

    @Test
    @Order(2)
    @DisplayName("Realistic render - custom colors")
    void testRealisticCustomColors() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        Map<String, GerberDocument> docs = loadGerberFiles(
            "uP-H Main PCBA Assy V04.GKO",
            "uP-H Main PCBA Assy V04.GTL",
            "uP-H Main PCBA Assy V04.GTS"
        );

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", docs.get("GKO"))
            .setLayerType(LayerType.OUTLINE));
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", docs.get("GTL"))
            .setLayerType(LayerType.COPPER_TOP)
            .setColor("#ffd700")); // Gold copper
        layers.add(new MultiLayerSVGRenderer.Layer("soldermask-top", docs.get("GTS"))
            .setLayerType(LayerType.SOLDERMASK_TOP)
            .setColor("#000066")   // Blue soldermask
            .setOpacity(0.90));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);

        // Realistic renderer always uses its own hardcoded colors (ignores layer display colors)
        assertTrue(svg.contains("#cccccc"), "Should use realistic copper color");
        assertTrue(svg.contains("#004200"), "Should use realistic soldermask color");
        assertTrue(svg.contains("opacity=\"0.75\""), "Should use default soldermask opacity for realistic view");

        Files.writeString(OUTPUT_DIR.resolve("realistic-custom-colors.svg"), svg);
        System.out.println("Custom color rendering validated!");
    }

    @Test
    @Order(3)
    @DisplayName("Realistic render - requires outline layer")
    void testRealisticRequiresOutline() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        Map<String, GerberDocument> docs = loadGerberFiles(
            "uP-H Main PCBA Assy V04.GTL"
        );

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", docs.get("GTL"))
            .setLayerType(LayerType.COPPER_TOP));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        assertThrows(IllegalArgumentException.class, () -> renderer.renderRealistic(layers),
            "Should throw when no outline layer is provided");

        System.out.println("Outline requirement validated!");
    }

    @Test
    @Order(4)
    @DisplayName("Realistic render - bottom side")
    void testRealisticBottomSide() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        Map<String, GerberDocument> docs = loadGerberFiles(
            "uP-H Main PCBA Assy V04.GKO",
            "uP-H Main PCBA Assy V04.GBL",
            "uP-H Main PCBA Assy V04.GBS",
            "uP-H Main PCBA Assy V04.GBO"
        );

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", docs.get("GKO"))
            .setLayerType(LayerType.OUTLINE));
        layers.add(new MultiLayerSVGRenderer.Layer("copper-bottom", docs.get("GBL"))
            .setLayerType(LayerType.COPPER_BOTTOM));
        layers.add(new MultiLayerSVGRenderer.Layer("soldermask-bottom", docs.get("GBS"))
            .setLayerType(LayerType.SOLDERMASK_BOTTOM)
            .setOpacity(0.75));
        layers.add(new MultiLayerSVGRenderer.Layer("silkscreen-bottom", docs.get("GBO"))
            .setLayerType(LayerType.SILKSCREEN_BOTTOM));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);
        assertNotNull(svg);

        // Should have bottom soldermask mask
        assertTrue(svg.contains("sm-bottom-mask"), "Should have bottom soldermask mask");

        Files.writeString(OUTPUT_DIR.resolve("realistic-bottom.svg"), svg);
        System.out.println("Bottom-side rendering validated!");
    }

    @Test
    @Order(5)
    @DisplayName("Realistic render - outline from draws (not regions)")
    void testOutlineFromDraws() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        // GKO file typically uses draws for the outline
        Map<String, GerberDocument> docs = loadGerberFiles(
            "uP-H Main PCBA Assy V04.GKO"
        );

        GerberDocument outlineDoc = docs.get("GKO");
        assertNotNull(outlineDoc);
        assertTrue(outlineDoc.getObjects().size() > 0,
            "Outline should have objects");

        // Create minimal realistic render with just outline
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", outlineDoc)
            .setLayerType(LayerType.OUTLINE));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);

        // Should produce valid SVG with board outline clipPath
        Document doc = parseSvg(svg);
        NodeList clipPaths = doc.getElementsByTagName("clipPath");
        assertTrue(clipPaths.getLength() > 0, "Should have clipPath");

        // The outline path should have actual coordinates (not empty)
        Element clipPath = (Element) clipPaths.item(0);
        Element path = (Element) clipPath.getElementsByTagName("path").item(0);
        String d = path.getAttribute("d");
        assertFalse(d.isEmpty(), "Outline path should have path data");
        assertTrue(d.contains("M"), "Path should start with M command");
        assertTrue(d.contains("Z"), "Path should be closed with Z");

        Files.writeString(OUTPUT_DIR.resolve("realistic-outline-only.svg"), svg);
        System.out.println("Outline extraction validated! Path: " + d.substring(0, Math.min(80, d.length())) + "...");
    }

    @Test
    @Order(6)
    @DisplayName("Arduino Uno - realistic top side")
    void testArduinoUnoTopSide() throws Exception {
        if (!Files.exists(ARDUINO_TEST_DIR)) {
            System.out.println("Arduino Uno test directory not found, skipping");
            return;
        }

        // Layer mapping from manifest.json:
        // .cmp = top copper, .gko = outline, .plc = top silkscreen,
        // .stc = top soldermask, .drd = drill
        Map<String, GerberDocument> docs = loadGerberFilesFrom(ARDUINO_TEST_DIR,
            "arduino-uno.gko",   // Outline
            "arduino-uno.cmp",   // Top copper
            "arduino-uno.stc",   // Top soldermask
            "arduino-uno.plc"    // Top silkscreen
        );

        DrillDocument drillDoc = drillParser.parse(
            Files.readString(ARDUINO_TEST_DIR.resolve("arduino-uno.drd")));

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", docs.get("GKO"))
            .setLayerType(LayerType.OUTLINE));
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", docs.get("CMP"))
            .setLayerType(LayerType.COPPER_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("soldermask-top", docs.get("STC"))
            .setLayerType(LayerType.SOLDERMASK_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("silkscreen-top", docs.get("PLC"))
            .setLayerType(LayerType.SILKSCREEN_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("drill", drillDoc)
            .setLayerType(LayerType.DRILL));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);
        assertNotNull(svg);
        assertFalse(svg.isEmpty());

        // Validate SVG structure
        Document doc = parseSvg(svg);
        assertEquals("svg", doc.getDocumentElement().getTagName());

        // Should have all the key elements
        assertTrue(svg.contains("board-outline"), "Should have board outline clip");
        assertTrue(svg.contains("sm-top-mask"), "Should have soldermask mask");
        assertTrue(svg.contains("cf-top-mask"), "Should have copper finish mask");

        Files.writeString(OUTPUT_DIR.resolve("arduino-uno-realistic-top.svg"), svg);
        System.out.println("Arduino Uno top SVG saved to " +
            OUTPUT_DIR.resolve("arduino-uno-realistic-top.svg"));
    }

    @Test
    @Order(7)
    @DisplayName("Arduino Uno - realistic bottom side")
    void testArduinoUnoBottomSide() throws Exception {
        if (!Files.exists(ARDUINO_TEST_DIR)) {
            System.out.println("Arduino Uno test directory not found, skipping");
            return;
        }

        // .sol = bottom copper, .sts = bottom soldermask
        Map<String, GerberDocument> docs = loadGerberFilesFrom(ARDUINO_TEST_DIR,
            "arduino-uno.gko",   // Outline
            "arduino-uno.sol",   // Bottom copper
            "arduino-uno.sts"    // Bottom soldermask
        );

        DrillDocument drillDoc = drillParser.parse(
            Files.readString(ARDUINO_TEST_DIR.resolve("arduino-uno.drd")));

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", docs.get("GKO"))
            .setLayerType(LayerType.OUTLINE));
        layers.add(new MultiLayerSVGRenderer.Layer("copper-bottom", docs.get("SOL"))
            .setLayerType(LayerType.COPPER_BOTTOM));
        layers.add(new MultiLayerSVGRenderer.Layer("soldermask-bottom", docs.get("STS"))
            .setLayerType(LayerType.SOLDERMASK_BOTTOM));
        layers.add(new MultiLayerSVGRenderer.Layer("drill", drillDoc)
            .setLayerType(LayerType.DRILL));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);
        assertNotNull(svg);

        assertTrue(svg.contains("sm-bottom-mask"), "Should have bottom soldermask mask");
        assertTrue(svg.contains("cf-bottom-mask"), "Should have bottom copper finish mask");

        Files.writeString(OUTPUT_DIR.resolve("arduino-uno-realistic-bottom.svg"), svg);
        System.out.println("Arduino Uno bottom SVG saved to " +
            OUTPUT_DIR.resolve("arduino-uno-realistic-bottom.svg"));
    }

    // --- Helpers ---

    private Map<String, GerberDocument> loadGerberFiles(String... filenames) throws Exception {
        return loadGerberFilesFrom(DEPR_TEST_DIR, filenames);
    }

    private Map<String, GerberDocument> loadGerberFilesFrom(Path dir, String... filenames) throws Exception {
        Map<String, GerberDocument> docs = new LinkedHashMap<>();
        for (String filename : filenames) {
            Path path = dir.resolve(filename);
            assertTrue(Files.exists(path), "Test file should exist: " + filename);
            String content = Files.readString(path);
            GerberDocument doc = gerberParser.parse(content);
            // Use extension as key (e.g., "GKO", "GTL")
            String ext = filename.substring(filename.lastIndexOf('.') + 1).toUpperCase();
            docs.put(ext, doc);
        }
        return docs;
    }

    private Document parseSvg(String svg) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(svg)));
    }

    private Element findElementById(Document doc, String id) {
        NodeList groups = doc.getElementsByTagName("g");
        for (int i = 0; i < groups.getLength(); i++) {
            Element g = (Element) groups.item(i);
            if (id.equals(g.getAttribute("id"))) {
                return g;
            }
        }
        return null;
    }
}
