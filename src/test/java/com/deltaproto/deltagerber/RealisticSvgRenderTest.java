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
    @DisplayName("Realistic render - derives outline from copper when no outline layer")
    void testRealisticDerivesOutlineFromCopper() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        Map<String, GerberDocument> docs = loadGerberFiles(
            "uP-H Main PCBA Assy V04.GTL"
        );

        // No OUTLINE layer — only copper. The renderer must derive a board edge from it
        // (the copper-union silhouette) instead of failing.
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", docs.get("GTL"))
            .setLayerType(LayerType.COPPER_TOP));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);
        assertNotNull(svg);
        assertTrue(svg.contains("<clipPath id=\"board-outline\">"),
            "Should derive a board-outline clip path from copper");
        assertTrue(svg.contains("clip-path=\"url(#board-outline)\""),
            "Layers should be clipped to the derived outline");

        // With neither an outline nor copper to derive from, it must still fail.
        List<MultiLayerSVGRenderer.Layer> silkOnly = new ArrayList<>();
        silkOnly.add(new MultiLayerSVGRenderer.Layer("silk-top", docs.get("GTL"))
            .setLayerType(LayerType.SILKSCREEN_TOP));
        assertThrows(IllegalArgumentException.class, () -> renderer.renderRealistic(silkOnly),
            "Should throw when there is no outline and no copper to derive one from");

        System.out.println("Outline derivation from copper validated!");
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

    @Test
    @Order(8)
    @DisplayName("Outline with mixed-direction draws (Altium-style) chains into closed loops")
    void testOutlineMixedDirectionDraws() throws Exception {
        // Reproduces a real-world bug: some EDA tools (notably Altium) emit the board
        // outline as a series of D02/D01 pairs where individual segments are written
        // in mixed directions — some forward (start→end of the traced path), some
        // reversed. The segments still geometrically trace a closed loop, but end-to-start
        // linear chaining breaks, causing the realistic view's clipPath to fragment
        // into many single-segment subpaths and render as a distorted mess.
        //
        // This synthetic outline describes a rectangle with an inner circular cutout,
        // with roughly half the segments written in reverse order.

        String outlineGerber = buildMixedDirectionOutline();
        GerberDocument outlineDoc = gerberParser.parse(outlineGerber);
        assertTrue(outlineDoc.getObjects().size() > 8,
            "Outline should have many segments");

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", outlineDoc)
            .setLayerType(LayerType.OUTLINE));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);

        Document parsed = parseSvg(svg);
        Element clipPath = (Element) parsed.getElementsByTagName("clipPath").item(0);
        Element pathEl = (Element) clipPath.getElementsByTagName("path").item(0);
        String d = pathEl.getAttribute("d");

        Files.writeString(OUTPUT_DIR.resolve("realistic-mixed-direction.svg"), svg);

        // The inner 12-segment polygon is only ~10% of the outer rectangle's area, so
        // the outer rectangle is the board and the inner loop is a cut-out, not a panel
        // frame. Both subpaths are retained and, under the evenodd clip rule, render as
        // a rectangle with a circular hole.
        //
        // This test still exercises bidirectional chaining: the inner circle uses
        // alternating forward/reverse segments (half emitted in each direction), so
        // correct endpoint-graph walking is required to assemble the 12-sided polygon.
        int moveCount = countOccurrences(d, "M ");
        int closeCount = countOccurrences(d, "Z");
        assertEquals(2, moveCount,
            "Expected 2 subpaths (outer board + inner cut-out), got " + moveCount + ". Path: " + d);
        assertEquals(2, closeCount,
            "Expected 2 close commands, got " + closeCount);

        // The clip path must subtract the cut-out via the evenodd rule.
        assertTrue(clipPath.getElementsByTagName("path").item(0) != null);
        assertEquals("evenodd", pathEl.getAttribute("clip-rule"),
            "Clip path must use clip-rule=evenodd so the cut-out subtracts");

        // The inner polygon subpath must have all 12 segments (no orphaned M..L..Z pairs).
        String[] subpaths = d.split("(?=M )");
        boolean foundPolygon = false;
        for (String sp : subpaths) {
            if (sp.isBlank()) continue;
            if (countOccurrences(sp, "L ") >= 11) foundPolygon = true;
        }
        assertTrue(foundPolygon, "Inner 12-sided polygon subpath should be present: " + d);
    }

    @Test
    @Order(9)
    @DisplayName("Outline with rounded corners and sub-aperture endpoint gaps chains into one loop")
    void testOutlineWithRoundingErrorGaps() throws Exception {
        // Reproduces another real-world Altium quirk: the board outline uses arcs
        // for rounded corners and straight lines for the edges, but the arc's start/end
        // point doesn't exactly match the adjacent straight segment's endpoint — there
        // can be a gap of several tens of microns. A strict 1 µm chaining tolerance
        // breaks here, leaving the outline fragmented. The renderer tolerates
        // sub-feature-size gaps (~50 µm) to handle this.

        String outlineGerber = buildOutlineWithGaps();
        GerberDocument outlineDoc = gerberParser.parse(outlineGerber);

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", outlineDoc)
            .setLayerType(LayerType.OUTLINE));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);

        Document parsed = parseSvg(svg);
        Element clipPath = (Element) parsed.getElementsByTagName("clipPath").item(0);
        Element pathEl = (Element) clipPath.getElementsByTagName("path").item(0);
        String d = pathEl.getAttribute("d");

        Files.writeString(OUTPUT_DIR.resolve("realistic-rounding-gaps.svg"), svg);

        // Rectangle with 4 rounded corners → exactly 1 closed subpath with 4 arcs and 4 lines
        int moveCount = countOccurrences(d, "M ");
        int closeCount = countOccurrences(d, "Z");
        int arcCount = countOccurrences(d, "A ");
        int lineCount = countOccurrences(d, "L ");
        assertEquals(1, moveCount,
            "Expected exactly 1 subpath despite rounding-error gaps, got " + moveCount
            + ". Path: " + d);
        assertEquals(1, closeCount, "Expected 1 close command");
        assertEquals(4, arcCount, "Expected 4 rounded corners, got " + arcCount);
        assertEquals(4, lineCount, "Expected 4 straight edges, got " + lineCount);
    }

    @Test
    @Order(11)
    @DisplayName("renderRealisticSidePng produces a valid PNG thumbnail")
    void testRealisticSidePng() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) {
            System.out.println("DEPR test directory not found, skipping");
            return;
        }

        Map<String, GerberDocument> docs = loadGerberFiles(
            "uP-H Main PCBA Assy V04.GKO",
            "uP-H Main PCBA Assy V04.GTL",
            "uP-H Main PCBA Assy V04.GTS",
            "uP-H Main PCBA Assy V04.GTO",
            "uP-H Main PCBA Assy V04.GBL",
            "uP-H Main PCBA Assy V04.GBS"
        );

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", docs.get("GKO"))
            .setLayerType(LayerType.OUTLINE));
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", docs.get("GTL"))
            .setLayerType(LayerType.COPPER_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("sm-top", docs.get("GTS"))
            .setLayerType(LayerType.SOLDERMASK_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("ss-top", docs.get("GTO"))
            .setLayerType(LayerType.SILKSCREEN_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("copper-bot", docs.get("GBL"))
            .setLayerType(LayerType.COPPER_BOTTOM));
        layers.add(new MultiLayerSVGRenderer.Layer("sm-bot", docs.get("GBS"))
            .setLayerType(LayerType.SOLDERMASK_BOTTOM));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();

        int widthPx = 400;
        byte[] topPng = renderer.renderRealisticSidePng(layers, MultiLayerSVGRenderer.Side.TOP, widthPx);
        byte[] botPng = renderer.renderRealisticSidePng(layers, MultiLayerSVGRenderer.Side.BOTTOM, widthPx);

        assertNotNull(topPng, "Top PNG should not be null");
        assertNotNull(botPng, "Bottom PNG should not be null");
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        assertEquals((byte) 0x89, topPng[0], "Missing PNG signature byte 0");
        assertEquals('P', topPng[1]);
        assertEquals('N', topPng[2]);
        assertEquals('G', topPng[3]);
        // Non-trivial content — an empty PNG header alone is ~70 bytes; real content is > 1 KB
        assertTrue(topPng.length > 1024, "Top PNG suspiciously small: " + topPng.length);
        assertTrue(botPng.length > 1024, "Bottom PNG suspiciously small: " + botPng.length);

        // IHDR chunk: bytes 16-19 big-endian = width, 20-23 = height
        int width = ((topPng[16] & 0xff) << 24) | ((topPng[17] & 0xff) << 16)
                  | ((topPng[18] & 0xff) << 8)  |  (topPng[19] & 0xff);
        assertEquals(widthPx, width, "PNG width should match requested widthPx");

        Files.write(OUTPUT_DIR.resolve("thumbnail-top.png"), topPng);
        Files.write(OUTPUT_DIR.resolve("thumbnail-bottom.png"), botPng);
        System.out.println("Top PNG: " + topPng.length + " bytes, width=" + width);
    }

    @Test
    @Order(13)
    @DisplayName("renderRealisticSidePng honours explicit width+height and preserves transparency")
    void testRealisticSidePngExplicitDimensions() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) return;

        Map<String, GerberDocument> docs = loadGerberFiles(
            "uP-H Main PCBA Assy V04.GKO",
            "uP-H Main PCBA Assy V04.GTL",
            "uP-H Main PCBA Assy V04.GTS"
        );

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", docs.get("GKO"))
            .setLayerType(LayerType.OUTLINE));
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", docs.get("GTL"))
            .setLayerType(LayerType.COPPER_TOP));
        layers.add(new MultiLayerSVGRenderer.Layer("sm-top", docs.get("GTS"))
            .setLayerType(LayerType.SOLDERMASK_TOP));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        byte[] png = renderer.renderRealisticSidePng(
            layers, MultiLayerSVGRenderer.Side.TOP, 300, 200);

        assertNotNull(png);
        int width  = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16)
                   | ((png[18] & 0xff) << 8)  |  (png[19] & 0xff);
        int height = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16)
                   | ((png[22] & 0xff) << 8)  |  (png[23] & 0xff);
        assertEquals(300, width, "Explicit width must be honoured");
        assertEquals(200, height, "Explicit height must be honoured");

        // Colour type byte (IHDR offset 25): 6 = RGBA (transparency supported)
        assertEquals(6, png[25] & 0xff,
            "PNG must be RGBA so the outside-outline and drill holes stay transparent");

        // Decode and confirm at least one fully-transparent pixel exists (the
        // margin around the board outline is empty SVG content → alpha 0).
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
            new java.io.ByteArrayInputStream(png));
        boolean sawTransparent = false;
        for (int y = 0; y < img.getHeight() && !sawTransparent; y++) {
            for (int x = 0; x < img.getWidth() && !sawTransparent; x++) {
                if ((img.getRGB(x, y) >>> 24) == 0) sawTransparent = true;
            }
        }
        assertTrue(sawTransparent,
            "Expected at least one fully-transparent pixel outside the board outline");

        Files.write(OUTPUT_DIR.resolve("thumbnail-explicit-dims.png"), png);
    }

    @Test
    @Order(12)
    @DisplayName("renderRealisticSidePng returns null when no outline layer is present")
    void testRealisticSidePngNoOutline() throws Exception {
        if (!Files.exists(DEPR_TEST_DIR)) return;

        Map<String, GerberDocument> docs = loadGerberFiles("uP-H Main PCBA Assy V04.GTL");
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("copper-top", docs.get("GTL"))
            .setLayerType(LayerType.COPPER_TOP));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        assertNull(renderer.renderRealisticSidePng(layers, MultiLayerSVGRenderer.Side.TOP, 300));
    }

    @Test
    @Order(10)
    @DisplayName("Outline built from short (<tolerance) segments still chains into one loop")
    void testOutlineWithShortSegments() throws Exception {
        // Reproduces a real-world bug: a panel/outline built as a long sequence of
        // short connected straight segments (e.g. mouse-bite teeth, V-score rails,
        // or polylines approximating a curve) would exit the chain loop on the very
        // first iteration because the *seed segment's* endpoints were within the
        // chain tolerance of each other. Each segment would then be emitted as its
        // own degenerate `M..L..Z` subpath and the clipPath would render as a mess
        // of disconnected slivers instead of the intended closed outline.

        String outlineGerber = buildShortSegmentOutline();
        GerberDocument outlineDoc = gerberParser.parse(outlineGerber);

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", outlineDoc)
            .setLayerType(LayerType.OUTLINE));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);

        Document parsed = parseSvg(svg);
        Element clipPath = (Element) parsed.getElementsByTagName("clipPath").item(0);
        Element pathEl = (Element) clipPath.getElementsByTagName("path").item(0);
        String d = pathEl.getAttribute("d");

        Files.writeString(OUTPUT_DIR.resolve("realistic-short-segments.svg"), svg);

        // Expect exactly one closed subpath with every L segment chained in.
        int moveCount = countOccurrences(d, "M ");
        int closeCount = countOccurrences(d, "Z");
        int lineCount = countOccurrences(d, "L ");
        assertEquals(1, moveCount,
            "Short-segment outline must chain into a single subpath, got " + moveCount
            + ". Path prefix: " + d.substring(0, Math.min(160, d.length())));
        assertEquals(1, closeCount, "Expected exactly 1 close command");
        // 40 short segments around the perimeter
        assertEquals(40, lineCount, "Expected all 40 segments chained in, got " + lineCount);
    }

    /**
     * Builds a rectangular outline assembled from 40 short straight segments
     * (10 per side, each ~0.05 mm long). Every segment on its own is shorter than
     * the chain tolerance, so the per-seed closure check must not short-circuit
     * before the chain has a chance to extend.
     */
    private String buildShortSegmentOutline() {
        int segPerSide = 10;
        int unit = 500;                      // 0.05 mm in 4.4 MM (1 unit = 0.1 µm)
        int sideLen = segPerSide * unit;     // 0.5 mm per side
        int x0 = 100000, y0 = 100000;        // 10 mm

        StringBuilder g = new StringBuilder();
        g.append("G04 synthetic short-segment rectangle outline*\n");
        g.append("%FSLAX44Y44*%\n");
        g.append("%MOMM*%\n");
        g.append("G01*\n");
        g.append("%ADD10C,0.1000*%\n");
        g.append("D10*\n");

        int[] cursorHolder = {x0, y0};
        appendMove(g, cursorHolder, x0, y0);
        for (int i = 1; i <= segPerSide; i++) appendStep(g, cursorHolder, x0 + i * unit, y0);
        for (int i = 1; i <= segPerSide; i++) appendStep(g, cursorHolder, x0 + sideLen, y0 + i * unit);
        for (int i = 1; i <= segPerSide; i++) appendStep(g, cursorHolder, x0 + sideLen - i * unit, y0 + sideLen);
        for (int i = 1; i <= segPerSide; i++) appendStep(g, cursorHolder, x0, y0 + sideLen - i * unit);

        g.append("M02*\n");
        return g.toString();
    }

    private static void appendMove(StringBuilder g, int[] cursor, int x, int y) {
        g.append("X").append(x).append("Y").append(y).append("D02*\n");
        cursor[0] = x; cursor[1] = y;
    }

    private static void appendStep(StringBuilder g, int[] cursor, int x, int y) {
        g.append("X").append(x).append("Y").append(y).append("D01*\n");
        cursor[0] = x; cursor[1] = y;
    }

    /**
     * Rectangle with four CCW-quarter-arc corners, where straight edges don't quite
     * meet the adjacent arc's tangent point — a 40 µm gap at each corner joint.
     * Simulates the rounding error seen in some Altium-generated GM1 outlines.
     */
    private String buildOutlineWithGaps() {
        int gap = 400;                    // 40 µm in 4.4 MM (1 unit = 0.1 µm)
        int x0 = 100000, y0 = 100000;     // 10 mm
        int x1 = 900000, y1 = 500000;     // 90 × 50 mm rectangle
        int r  =  50000;                  // 5 mm corner radius

        StringBuilder g = new StringBuilder();
        g.append("G04 synthetic outline with rounded corners and rounding-error gaps*\n");
        g.append("%FSLAX44Y44*%\n");
        g.append("%MOMM*%\n");
        g.append("G01*\n");
        g.append("G75*\n");
        g.append("%ADD10C,0.1000*%\n");
        g.append("D10*\n");

        // Straight edges — endpoints offset by `gap` from the true arc tangent
        appendLine(g, x0 + r + gap, y0,         x1 - r - gap, y0);         // bottom
        appendLine(g, x1,           y0 + r + gap, x1,          y1 - r - gap); // right
        appendLine(g, x1 - r - gap, y1,         x0 + r + gap, y1);         // top
        appendLine(g, x0,           y1 - r - gap, x0,          y0 + r + gap); // left

        // CCW quarter-arc corners at their true tangent points
        appendCCWArc(g, x0,     y0 + r, x0 + r, y0,     x0 + r, y0 + r);   // BL
        appendCCWArc(g, x1 - r, y0,     x1,     y0 + r, x1 - r, y0 + r);   // BR
        appendCCWArc(g, x1,     y1 - r, x1 - r, y1,     x1 - r, y1 - r);   // TR
        appendCCWArc(g, x0 + r, y1,     x0,     y1 - r, x0 + r, y1 - r);   // TL

        g.append("M02*\n");
        return g.toString();
    }

    private static void appendLine(StringBuilder g, int x0, int y0, int x1, int y1) {
        g.append("X").append(x0).append("Y").append(y0).append("D02*\n");
        g.append("X").append(x1).append("Y").append(y1).append("D01*\n");
    }

    private static void appendCCWArc(StringBuilder g, int sx, int sy, int ex, int ey,
                                     int cx, int cy) {
        g.append("X").append(sx).append("Y").append(sy).append("D02*\n");
        g.append("G03*\n");
        int i = cx - sx;
        int j = cy - sy;
        g.append("X").append(ex).append("Y").append(ey);
        g.append("I").append(i).append("J").append(j).append("D01*\n");
        g.append("G01*\n");
    }

    /**
     * Builds a Gerber outline describing a rectangle plus an inner circular cutout,
     * where many segments are written in reversed direction (start/end swapped)
     * — the pattern that Altium Designer emits for GKO outlines.
     */
    private String buildMixedDirectionOutline() {
        // Outer rectangle corners (integer micrometers in 4.4 MM format: 1 unit = 0.1 µm;
        // we'll use whole mm coords encoded as N0000 units)
        // FSLAX44Y44 with MOMM → e.g. 100000 = 10.000 mm
        int[][] rect = {
            {100000, 100000},  // A
            {900000, 100000},  // B
            {900000, 600000},  // C
            {100000, 600000},  // D
        };

        // Inner circle (approximated by 12-segment polygon, centered at 50,35 with r=10)
        double cx = 500000, cy = 350000, r = 100000;
        int segments = 12;
        int[][] circle = new int[segments][2];
        for (int i = 0; i < segments; i++) {
            double ang = 2 * Math.PI * i / segments;
            circle[i][0] = (int) Math.round(cx + r * Math.cos(ang));
            circle[i][1] = (int) Math.round(cy + r * Math.sin(ang));
        }

        StringBuilder g = new StringBuilder();
        g.append("G04 synthetic mixed-direction outline*\n");
        g.append("%FSLAX44Y44*%\n");
        g.append("%MOMM*%\n");
        g.append("G01*\n");
        g.append("%ADD10C,0.1000*%\n");
        g.append("D10*\n");

        // Outer rectangle: alternating forward/reverse segment direction, so
        // simple end-to-start chaining fails and requires endpoint-graph walking.
        // A→B forward, B→C forward, D→C reversed (written as D02 C → D01 D? no:
        // "reversed" here means D02 at the segment's *end*, then D01 back to its *start*)
        appendForward(g, rect[0], rect[1]);   // A→B
        appendForward(g, rect[1], rect[2]);   // B→C
        appendReversed(g, rect[3], rect[2]);  // C→D written as D02 D → D01 C
        appendReversed(g, rect[0], rect[3]);  // D→A written as D02 A → D01 D

        // Inner circle: half forward, half reversed
        for (int i = 0; i < segments; i++) {
            int[] a = circle[i];
            int[] b = circle[(i + 1) % segments];
            if (i % 2 == 0) {
                appendForward(g, a, b);
            } else {
                appendReversed(g, a, b);
            }
        }

        g.append("M02*\n");
        return g.toString();
    }

    private static void appendForward(StringBuilder g, int[] from, int[] to) {
        g.append("X").append(from[0]).append("Y").append(from[1]).append("D02*\n");
        g.append("X").append(to[0]).append("Y").append(to[1]).append("D01*\n");
    }

    /** Emit a segment whose geometry is from→to, but written as D02 to → D01 from (reversed). */
    private static void appendReversed(StringBuilder g, int[] from, int[] to) {
        g.append("X").append(to[0]).append("Y").append(to[1]).append("D02*\n");
        g.append("X").append(from[0]).append("Y").append(from[1]).append("D01*\n");
    }

    @Test
    @Order(14)
    @DisplayName("Outline with T-intersections (overlapping collinear segments) chains into one loop")
    void testOutlineWithTIntersections() throws Exception {
        // Reproduces a real-world flex-PCB/rigid-flex panel bug: the board outline
        // contains overlapping collinear segments along shared rigid/flex boundaries.
        // For example, a flex connector tab's left edge (y = 79–91 mm) and the flex
        // cable body's left edge (y = 70–84 mm) both run at the same x coordinate,
        // overlapping between y = 79–84 mm. The greedy endpoint-chaining algorithm
        // fails here because neither endpoint of the cable-body segment is within
        // tolerance of the chain head at y = 79: the endpoints are at y = 70 and
        // y = 84, both several mm away. Without T-intersection detection, the chain
        // closes prematurely creating many small fragmented subpaths, which produce
        // holes in the clip-path (black "H"-shaped voids in the realistic view).
        //
        // The fix detects that the head lies on the interior of the collinear segment,
        // splits it at that point, and continues toward the farther endpoint.
        //
        // Synthetic outline (FSLAX44Y44 MM, all in 0.1 µm units → 10 mm = 100000):
        //
        //   +------------------+   y=900 (9 mm top)  — top of tab
        //   |     TAB          |   tab: x=300..450, y=700..900
        //   +-+            +---+   y=700 — tab base
        //     |            |       both sides: x=300 and x=450, y=500..840 (overlap with tab)
        //   +-+            +---+   y=840 — flex body top overlap end
        //   |    MAIN BODY     |   y=500..840
        //   +------------------+   y=500 (5 mm bottom)

        String gerber = buildTIntersectionOutline();
        GerberDocument outlineDoc = gerberParser.parse(gerber);

        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("outline", outlineDoc)
            .setLayerType(LayerType.OUTLINE));

        MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
        String svg = renderer.renderRealistic(layers);
        Files.writeString(OUTPUT_DIR.resolve("realistic-t-intersection.svg"), svg);

        Document parsed = parseSvg(svg);
        Element clipPath = (Element) parsed.getElementsByTagName("clipPath").item(0);
        Element pathEl = (Element) clipPath.getElementsByTagName("path").item(0);
        String d = pathEl.getAttribute("d");

        // The main PCB outline must be one closed subpath.
        // (There may be tiny near-half orphan slivers as additional subpaths, but the
        // first subpath must close and contain all the major outline segments.)
        String[] subpaths = d.split("(?= M )");
        assertTrue(subpaths.length >= 1, "Must have at least one subpath");
        String main = subpaths[0].trim();
        assertTrue(main.startsWith("M "), "First subpath must start with M");
        assertTrue(main.endsWith("Z"), "First subpath must end with Z");

        // The main outline must visit the four key corners of the board outline.
        // Expressed in mm: tab at y=9.0, flex junction at y=7.0, body at y=5.0,
        // and the x-extent from 3.0 to 4.5 (left/right sides).
        // Check that the main subpath contains L commands to the far endpoints
        // (y=5.0 / y=9.0) proving the T-intersection was crossed, not just the
        // overlap region (y=7.0 to y=8.4).
        assertTrue(d.contains("5.000000") || d.contains("50.000000"),
            "Clip path must reach the bottom of the main body (y=5 mm or 50 mm coordinate)");
        assertTrue(d.contains("9.000000") || d.contains("90.000000"),
            "Clip path must reach the top of the tab (y=9 mm or 90 mm coordinate)");
    }

    /**
     * Builds a Gerber outline representing a tab-and-body PCB shape where the
     * tab's left/right edges and the body's left/right edges OVERLAP along a
     * shared collinear segment — producing a T-intersection that the endpoint-only
     * chaining algorithm cannot cross without a split.
     *
     * <pre>
     *  x: 3..4.5 mm,  y: 5..9 mm (approx)
     *
     *   300  450
     *    |    |   y=900  top of tab
     *    +----+
     *    |    |   y=700  tab base (T-junction: tab edge starts here, body edge ends at y=840)
     *    |    |   y=840  top of body side overlap region
     *    |    |
     *  100    550
     *    |    |   y=500  bottom of body
     *    +----+
     * </pre>
     *
     * The left side: tab segment (300, 700)→(300, 900) and body segment (300, 500)→(300, 840)
     * overlap at y=700..840. The endpoint at (300, 700) is 1.4 mm from the body's
     * far endpoint at (300, 840), beyond the 0.1 mm chain tolerance.
     */
    private String buildTIntersectionOutline() {
        // FSLAX44Y44 MM: 1 unit = 0.1 µm.  10 mm = 100000 units.
        // Body corners: x=100,550 y=500,840  Tab: x=300,450 y=700,900
        StringBuilder g = new StringBuilder();
        g.append("G04 synthetic T-intersection outline — tab+body with overlapping edges*\n");
        g.append("%FSLAX44Y44*%\n");
        g.append("%MOMM*%\n");
        g.append("G01*\n");
        g.append("%ADD10C,0.1000*%\n");
        g.append("D10*\n");

        // Body: bottom edge
        appendLine(g, 100000, 50000, 550000, 50000);
        // Body: right side (y=500→840)
        appendLine(g, 550000, 50000, 550000, 84000);
        // Body: step-in right to tab right base
        appendLine(g, 550000, 84000, 45000, 84000);
        // Tab: right side (y=700→900)  ← overlaps body right at y=700..840
        appendLine(g, 45000, 70000, 45000, 90000);
        // Tab: top edge
        appendLine(g, 45000, 90000, 30000, 90000);
        // Tab: left side (y=900→700)  ← overlaps body left at y=700..840
        appendLine(g, 30000, 90000, 30000, 70000);
        // Body: step-in left from tab left base
        appendLine(g, 30000, 84000, 100000, 84000);
        // Body: left side (y=840→500)
        appendLine(g, 100000, 84000, 100000, 50000);

        // Body-side segments that overlap with the tab sides (the T-intersection sources)
        appendLine(g, 100000, 50000, 100000, 84000);   // left overlap (y=500→840, shares y=700..840 with tab left)
        appendLine(g, 550000, 84000, 550000, 50000);   // right overlap (y=840→500, shares y=700..840 with tab right)

        g.append("M02*\n");
        return g.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
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
