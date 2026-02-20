package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.drill.*;
import org.w3c.dom.Node;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.SVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import org.junit.jupiter.api.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates Gerber/Excellon parser and SVG renderer against reference SVG output
 * for the DEPR PR31 GBDR V04 test files (real PCB project).
 *
 * This test suite:
 * 1. Parses test Gerber/Drill files from testdata/DEPR PR31 GBDR V04/
 * 2. Renders them to SVG
 * 3. Extracts the corresponding layer from the reference SVG
 * 4. Compares structural features
 * 5. Reports any discrepancies
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DeprPR31ValidationTest {

    private static final Path TEST_DIR = Path.of("testdata/DEPR PR31 GBDR V04");
    private static final Path REFERENCE_SVG = Path.of("testdata/DEPR PR31 GBDR V04.svg");
    private static final Path OUTPUT_DIR = Path.of("target/depr-pr31-validation");

    // Tolerance for floating point comparisons (in mm)
    private static final double COMPARISON_TOLERANCE = 1e-3;  // Looser tolerance for real PCB files

    // Files to skip (not in reference SVG or not supported)
    private static final Set<String> SKIP_FILES = Set.of(
        "uP-H Main PCBA Assy V04.GKO",  // Board outline - not in reference
        "uP-H Main PCBA Assy V04-macro.APR_LIB",  // Aperture library - not a gerber file
        "layer_report.rpt",  // Report file
        "drill_report.rpt",  // Report file
        "nctools.rpt"  // Report file
    );

    private final GerberParser gerberParser = new GerberParser();
    private final ExcellonParser excellonParser = new ExcellonParser();

    // Polygonized mode renderer (for generating reference-compatible output)
    private final SVGRenderer polygonizedRenderer = new SVGRenderer()
        .setDarkColor("#000000")
        .setBackgroundColor("#ffffff")
        .setMargin(0)
        .setPolygonizeMode();

    private final SvgComparer comparer = new SvgComparer(COMPARISON_TOLERANCE);

    private static final Path REFERENCE_LAYERS_DIR = Path.of("testdata/DEPR PR31 GBDR V04-layers");
    private static Map<String, Path> referenceLayerFiles;
    private static final List<ValidationResult> allResults = new ArrayList<>();

    @BeforeAll
    static void setupAll() throws Exception {
        // Create output directory
        Files.createDirectories(OUTPUT_DIR);

        // Split reference SVG into individual layer files (if not already done)
        if (Files.exists(REFERENCE_SVG)) {
            if (!Files.exists(REFERENCE_LAYERS_DIR) || !hasLayerFiles(REFERENCE_LAYERS_DIR)) {
                System.out.println("Splitting reference SVG into individual layer files...");
                referenceLayerFiles = SvgLayerSplitter.split(REFERENCE_SVG, REFERENCE_LAYERS_DIR);
            } else {
                System.out.println("Using existing reference layer files from: " + REFERENCE_LAYERS_DIR);
                referenceLayerFiles = loadExistingLayerFiles(REFERENCE_LAYERS_DIR);
            }
            System.out.println("Reference layers available: " + referenceLayerFiles.size());
            for (String layerId : referenceLayerFiles.keySet()) {
                System.out.println("  - " + layerId);
            }
        } else {
            System.out.println("ERROR: Reference SVG not found at " + REFERENCE_SVG);
            fail("Reference SVG not found");
        }
    }

    private static boolean hasLayerFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (var stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".svg"));
        }
    }

    private static Map<String, Path> loadExistingLayerFiles(Path dir) throws IOException {
        Map<String, Path> result = new HashMap<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".svg"))
                  .forEach(p -> {
                      String fileName = p.getFileName().toString();
                      // Convert file name back to layer ID
                      String layerId = fileName.replace(".svg", "").replace("_", " ");
                      // Also try with original dots for extensions
                      if (Files.exists(p)) {
                          result.put(layerId, p);
                      }
                  });
        }
        return result;
    }

    @AfterAll
    static void generateReport() throws Exception {
        // Generate validation report
        StringBuilder report = new StringBuilder();
        report.append("# DEPR PR31 GBDR V04 Validation Report\n\n");
        report.append("Generated: ").append(java.time.LocalDateTime.now()).append("\n\n");

        int passed = 0, failed = 0, warnings = 0;

        report.append("## Summary\n\n");
        report.append("| File | Parse | Render | Validation | Notes |\n");
        report.append("|------|-------|--------|------------|-------|\n");

        for (ValidationResult result : allResults) {
            String parseStatus = result.parseSuccess ? "PASS" : "FAIL";
            String renderStatus = result.renderSuccess ? "PASS" : "FAIL";
            String validStatus = result.validationPassed ? "PASS" : (result.hasWarnings ? "WARN" : "FAIL");

            report.append(String.format("| %s | %s | %s | %s | %s |\n",
                result.fileName, parseStatus, renderStatus, validStatus,
                truncate(result.notes, 50)));

            if (result.parseSuccess && result.renderSuccess && result.validationPassed) {
                passed++;
            } else if (result.hasWarnings) {
                warnings++;
            } else {
                failed++;
            }
        }

        report.append("\n## Statistics\n\n");
        report.append(String.format("- **Passed**: %d\n", passed));
        report.append(String.format("- **Warnings**: %d\n", warnings));
        report.append(String.format("- **Failed**: %d\n", failed));
        report.append(String.format("- **Total**: %d\n", allResults.size()));

        // Write detailed results
        report.append("\n## Detailed Results\n\n");
        for (ValidationResult result : allResults) {
            report.append("### ").append(result.fileName).append("\n\n");
            if (!result.parseSuccess) {
                report.append("**Parse Error**: ").append(result.parseError).append("\n\n");
            }
            if (!result.renderSuccess) {
                report.append("**Render Error**: ").append(result.renderError).append("\n\n");
            }
            if (result.boundingBox != null) {
                report.append(String.format("**Bounding Box**: (%.3f, %.3f) to (%.3f, %.3f) - Size: %.3f x %.3f mm\n\n",
                    result.boundingBox.getMinX(), result.boundingBox.getMinY(),
                    result.boundingBox.getMaxX(), result.boundingBox.getMaxY(),
                    result.boundingBox.getWidth(), result.boundingBox.getHeight()));
            }
            if (result.objectCount > 0) {
                report.append(String.format("**Objects**: %d, **Apertures**: %d\n\n",
                    result.objectCount, result.apertureCount));
            }
            if (!result.validationNotes.isEmpty()) {
                report.append("**Validation Notes**:\n");
                for (String note : result.validationNotes) {
                    report.append("- ").append(note).append("\n");
                }
                report.append("\n");
            }
        }

        Files.writeString(OUTPUT_DIR.resolve("validation-report.md"), report.toString());
        System.out.println("\nValidation report written to: " + OUTPUT_DIR.resolve("validation-report.md"));
        System.out.println(String.format("Results: %d passed, %d warnings, %d failed", passed, warnings, failed));
    }

    // ============================================================
    // Test: Gerber Layers
    // ============================================================

    @Test
    @Order(1)
    @DisplayName("GM1 - Mechanical layer")
    void testGM1() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.GM1");
    }

    @Test
    @Order(2)
    @DisplayName("GBP - Bottom Paste")
    void testGBP() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.GBP");
    }

    @Test
    @Order(3)
    @DisplayName("GBO - Bottom Silk")
    void testGBO() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.GBO");
    }

    @Test
    @Order(4)
    @DisplayName("GBS - Bottom Solder Mask")
    void testGBS() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.GBS");
    }

    @Test
    @Order(5)
    @DisplayName("GBL - Bottom Copper")
    void testGBL() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.GBL");
    }

    @Test
    @Order(6)
    @DisplayName("G2 - Internal Layer 2")
    void testG2() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.G2");
    }

    @Test
    @Order(7)
    @DisplayName("G1 - Internal Layer 1")
    void testG1() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.G1");
    }

    @Test
    @Order(8)
    @DisplayName("GTL - Top Copper")
    void testGTL() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.GTL");
    }

    @Test
    @Order(9)
    @DisplayName("GTS - Top Solder Mask")
    void testGTS() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.GTS");
    }

    @Test
    @Order(10)
    @DisplayName("GTO - Top Silk")
    void testGTO() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.GTO");
    }

    @Test
    @Order(11)
    @DisplayName("GTP - Top Paste")
    void testGTP() throws Exception {
        validateGerberFile("uP-H Main PCBA Assy V04.GTP");
    }

    // ============================================================
    // Test: Drill Files
    // ============================================================

    @Test
    @Order(20)
    @DisplayName("RoundHoles.TXT - Drill ThruHole")
    void testRoundHoles() throws Exception {
        validateDrillFile("uP-H Main PCBA Assy V04-RoundHoles.TXT");
    }

    @Test
    @Order(21)
    @DisplayName("SlotHoles.TXT - Rout/Mill")
    void testSlotHoles() throws Exception {
        validateDrillFile("uP-H Main PCBA Assy V04-SlotHoles.TXT");
    }

    // ============================================================
    // Core Validation Logic
    // ============================================================

    private void validateGerberFile(String fileName) throws Exception {
        ValidationResult result = new ValidationResult(fileName);
        Path gerberPath = TEST_DIR.resolve(fileName);

        // Check file exists
        if (!Files.exists(gerberPath)) {
            result.parseSuccess = false;
            result.parseError = "File not found: " + gerberPath;
            allResults.add(result);
            fail("Test file not found: " + gerberPath);
            return;
        }

        // Step 1: Parse the Gerber file
        GerberDocument doc = null;
        try {
            String content = Files.readString(gerberPath);
            doc = gerberParser.parse(content);
            result.parseSuccess = true;
            result.objectCount = doc.getObjects().size();
            result.apertureCount = doc.getApertures().size();
            result.boundingBox = doc.getBoundingBox();
        } catch (Exception e) {
            result.parseSuccess = false;
            result.parseError = e.getMessage();
            e.printStackTrace();
            allResults.add(result);
            fail("Failed to parse " + fileName + ": " + e.getMessage());
            return;
        }

        // Step 2: Render to SVG
        String generatedSvg = null;
        try {
            generatedSvg = polygonizedRenderer.render(doc);
            result.renderSuccess = true;

            // Save generated SVG for manual inspection
            String baseFileName = fileName.replace(".", "_");
            Files.writeString(OUTPUT_DIR.resolve(baseFileName + "_generated.svg"), generatedSvg);
        } catch (Exception e) {
            result.renderSuccess = false;
            result.renderError = e.getMessage();
            e.printStackTrace();
            allResults.add(result);
            fail("Failed to render " + fileName + ": " + e.getMessage());
            return;
        }

        // Step 3: Compare against reference layer
        try {
            compareAgainstReferenceLayer(fileName, generatedSvg, result);
        } catch (Exception e) {
            result.validationNotes.add("Reference comparison error: " + e.getMessage());
            e.printStackTrace();
        }

        // Record result
        allResults.add(result);

        // Build assertion message
        StringBuilder msg = new StringBuilder();
        msg.append("Validation of ").append(fileName).append(":\n");
        msg.append("  Objects: ").append(result.objectCount).append("\n");
        msg.append("  Apertures: ").append(result.apertureCount).append("\n");
        if (result.boundingBox != null && result.boundingBox.isValid()) {
            msg.append(String.format("  BBox: %.3f x %.3f mm\n",
                result.boundingBox.getWidth(), result.boundingBox.getHeight()));
        }
        for (String note : result.validationNotes) {
            msg.append("  Note: ").append(note).append("\n");
        }

        // Determine overall success - for now just check it parsed and rendered
        if (!result.parseSuccess || !result.renderSuccess) {
            fail(msg.toString());
        }

        System.out.println(msg);
    }

    private void validateDrillFile(String fileName) throws Exception {
        ValidationResult result = new ValidationResult(fileName);
        Path drillPath = TEST_DIR.resolve(fileName);

        // Check file exists
        if (!Files.exists(drillPath)) {
            result.parseSuccess = false;
            result.parseError = "File not found: " + drillPath;
            allResults.add(result);
            fail("Test file not found: " + drillPath);
            return;
        }

        // Step 1: Parse the Drill file
        DrillDocument doc = null;
        try {
            String content = Files.readString(drillPath);
            doc = excellonParser.parse(content);
            result.parseSuccess = true;
            result.objectCount = doc.getOperations().size();
            result.apertureCount = doc.getTools().size();
            result.boundingBox = doc.getBoundingBox();
        } catch (Exception e) {
            result.parseSuccess = false;
            result.parseError = e.getMessage();
            e.printStackTrace();
            allResults.add(result);
            fail("Failed to parse " + fileName + ": " + e.getMessage());
            return;
        }

        // Step 2: Render to SVG (using drill-specific renderer)
        String generatedSvg = null;
        try {
            generatedSvg = renderDrillToSvg(doc);
            result.renderSuccess = true;

            // Save generated SVG for manual inspection
            String baseFileName = fileName.replace(".", "_").replace("-", "_");
            Files.writeString(OUTPUT_DIR.resolve(baseFileName + "_generated.svg"), generatedSvg);
        } catch (Exception e) {
            result.renderSuccess = false;
            result.renderError = e.getMessage();
            e.printStackTrace();
            allResults.add(result);
            fail("Failed to render " + fileName + ": " + e.getMessage());
            return;
        }

        // Step 3: Compare against reference layer
        try {
            compareAgainstReferenceLayer(fileName, generatedSvg, result);
        } catch (Exception e) {
            result.validationNotes.add("Reference comparison error: " + e.getMessage());
            e.printStackTrace();
        }

        // Record result
        allResults.add(result);

        // Build assertion message
        StringBuilder msg = new StringBuilder();
        msg.append("Validation of ").append(fileName).append(":\n");
        msg.append("  Hits: ").append(result.objectCount).append("\n");
        msg.append("  Tools: ").append(result.apertureCount).append("\n");
        if (result.boundingBox != null && result.boundingBox.isValid()) {
            msg.append(String.format("  BBox: %.3f x %.3f mm\n",
                result.boundingBox.getWidth(), result.boundingBox.getHeight()));
        }
        for (String note : result.validationNotes) {
            msg.append("  Note: ").append(note).append("\n");
        }

        // Determine overall success - for now just check it parsed and rendered
        if (!result.parseSuccess || !result.renderSuccess) {
            fail(msg.toString());
        }

        System.out.println(msg);
    }

    private String renderDrillToSvg(DrillDocument doc) {
        BoundingBox bbox = doc.getBoundingBox();
        if (!bbox.isValid()) {
            return "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>";
        }

        StringBuilder svg = new StringBuilder();
        double margin = 0.5;
        double minX = bbox.getMinX() - margin;
        double minY = bbox.getMinY() - margin;
        double width = bbox.getWidth() + 2 * margin;
        double height = bbox.getHeight() + 2 * margin;

        svg.append(String.format(Locale.US,
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"%.6f %.6f %.6f %.6f\">\n",
            minX, minY, width, height));

        // Render drill operations
        svg.append("<g fill=\"#000000\" stroke=\"#000000\">\n");
        for (DrillOperation op : doc.getOperations()) {
            svg.append("  ").append(op.toSvg()).append("\n");
        }
        svg.append("</g>\n");

        svg.append("</svg>");
        return svg.toString();
    }

    /**
     * Compare generated SVG against the corresponding layer in the reference SVG.
     */
    private void compareAgainstReferenceLayer(String fileName, String generatedSvg, ValidationResult result) throws Exception {
        // Find the reference layer file
        Path refLayerFile = findReferenceLayerFile(fileName);
        if (refLayerFile == null) {
            result.validationNotes.add("No reference layer found for: " + fileName);
            result.hasWarnings = true;
            return;
        }

        // Read reference layer SVG
        String refLayerSvg = Files.readString(refLayerFile);

        // Copy reference to output dir for easy comparison
        String baseFileName = fileName.replace(".", "_").replace("-", "_").replace(" ", "_");
        Files.copy(refLayerFile, OUTPUT_DIR.resolve(baseFileName + "_reference.svg"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Parse both SVGs to count and compare elements
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document refDoc = builder.parse(new InputSource(new StringReader(refLayerSvg)));
        Document genDoc = builder.parse(new InputSource(new StringReader(generatedSvg)));

        // Count elements in reference (excluding defs)
        int refPathCount = countLayerElements(refDoc, "path");
        int refCircleCount = countLayerElements(refDoc, "circle");
        int refUseCount = countLayerElements(refDoc, "use");

        int genPathCount = genDoc.getElementsByTagName("path").getLength();
        int genCircleCount = genDoc.getElementsByTagName("circle").getLength();
        int genUseCount = genDoc.getElementsByTagName("use").getLength();

        // Report element counts
        result.validationNotes.add(String.format("Reference: %d paths, %d circles, %d uses",
            refPathCount, refCircleCount, refUseCount));
        result.validationNotes.add(String.format("Generated: %d paths, %d circles, %d uses",
            genPathCount, genCircleCount, genUseCount));

        // Check for significant differences
        int totalRef = refPathCount + refCircleCount + refUseCount;
        int totalGen = genPathCount + genCircleCount + genUseCount;

        if (totalRef > 0 && totalGen > 0) {
            double ratio = (double) totalGen / totalRef;
            if (ratio < 0.5 || ratio > 2.0) {
                result.validationNotes.add(String.format("WARNING: Element count ratio %.2f (expected ~1.0)", ratio));
                result.hasWarnings = true;
            } else {
                result.validationPassed = true;
            }
        } else if (totalRef == 0 && totalGen == 0) {
            result.validationPassed = true;
            result.validationNotes.add("Both reference and generated are empty");
        } else {
            result.validationNotes.add("WARNING: Mismatched empty/non-empty content");
            result.hasWarnings = true;
        }
    }

    /**
     * Find the reference layer file for a given file name.
     */
    private Path findReferenceLayerFile(String fileName) {
        // Try exact match first
        if (referenceLayerFiles.containsKey(fileName)) {
            return referenceLayerFiles.get(fileName);
        }

        // Try with underscores instead of spaces
        String sanitized = fileName.replace(" ", "_");
        Path candidate = REFERENCE_LAYERS_DIR.resolve(sanitized + ".svg");
        if (Files.exists(candidate)) {
            return candidate;
        }

        // Search for a file containing the key part of the name
        for (Map.Entry<String, Path> entry : referenceLayerFiles.entrySet()) {
            if (entry.getKey().contains(fileName) || fileName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Count elements in a layer (excluding defs section).
     */
    private int countLayerElements(Document doc, String tagName) {
        int count = 0;
        NodeList elements = doc.getElementsByTagName(tagName);
        for (int i = 0; i < elements.getLength(); i++) {
            Element elem = (Element) elements.item(i);
            // Skip elements inside defs
            if (!isInsideDefs(elem)) {
                count++;
            }
        }
        return count;
    }

    private boolean isInsideDefs(Element elem) {
        Node parent = elem.getParentNode();
        while (parent != null) {
            if (parent instanceof Element && "defs".equals(((Element) parent).getTagName())) {
                return true;
            }
            parent = parent.getParentNode();
        }
        return false;
    }

    // ============================================================
    // Helper Classes
    // ============================================================

    private static class ValidationResult {
        String fileName;
        boolean parseSuccess = false;
        boolean renderSuccess = false;
        boolean validationPassed = false;
        boolean hasWarnings = false;
        String parseError;
        String renderError;
        String notes = "";
        List<String> validationNotes = new ArrayList<>();
        int objectCount = 0;
        int apertureCount = 0;
        BoundingBox boundingBox;

        ValidationResult(String fileName) {
            this.fileName = fileName;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
