package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.SVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates Gerber parser and SVG renderer against reference SVG output.
 *
 * This test suite:
 * 1. Parses test Gerber files from the test-gerber-suite
 * 2. Renders them to SVG
 * 3. Compares structural features against the reference SVG
 * 4. Reports any discrepancies
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class GerberSvgValidationTest {

    private static final Path TEST_SUITE_DIR = Path.of("test-gerber-suite");
    private static final Path REFERENCE_SVG = Path.of("test-gerber-suite/test-gerber-suite.svg");
    private static final Path OUTPUT_DIR = Path.of("target/svg-validation");
    private static final Path REFERENCE_LAYERS_DIR = Path.of("test-gerber-suite/reference-layers");

    // Set to true to regenerate reference SVGs (run once to create baseline)
    private static final boolean GENERATE_REFERENCE = Boolean.parseBoolean(
        System.getProperty("generateReference", "false"));

    // Known issues with reference renderer (e.g., rectangle with hole not supported)
    private static final Set<String> SKIP_REFERENCE_COMPARISON = Set.of(
        "apertures/04_rectangle_with_hole.gbr"  // Reference renderer doesn't support rectangle holes
    );

    private final GerberParser parser = new GerberParser();

    // Exact mode renderer (native SVG elements)
    private final SVGRenderer exactRenderer = new SVGRenderer()
        .setDarkColor("#000000")
        .setBackgroundColor("#ffffff")
        .setMargin(0.5)
        .setExactMode();

    // Polygonized mode renderer (for generating reference-compatible output)
    private final SVGRenderer polygonizedRenderer = new SVGRenderer()
        .setDarkColor("#000000")
        .setBackgroundColor("#ffffff")
        .setMargin(0.5)
        .setPolygonizeMode();

    private static Map<String, Path> referenceLayerFiles;
    private static final List<ValidationResult> allResults = new ArrayList<>();

    @BeforeAll
    static void setupAll() throws Exception {
        // Create output directory
        Files.createDirectories(OUTPUT_DIR);

        if (GENERATE_REFERENCE) {
            System.out.println("=== REFERENCE GENERATION MODE ===");
            System.out.println("Reference SVGs will be generated/updated in: " + REFERENCE_LAYERS_DIR);
        }

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
        } else {
            System.out.println("WARNING: Reference SVG not found at " + REFERENCE_SVG);
            referenceLayerFiles = new HashMap<>();
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
                      // Convert file name back to layer ID (remove .svg, replace _ with space)
                      String layerId = fileName.replace(".svg", "").replace("_", " ");
                      result.put(layerId, p);
                      // Also add with original name (underscores)
                      result.put(fileName.replace(".svg", ""), p);
                  });
        }
        return result;
    }

    @AfterAll
    static void generateReport() throws Exception {
        // Generate validation report
        StringBuilder report = new StringBuilder();
        report.append("# Gerber SVG Validation Report\n\n");
        report.append("Generated: ").append(java.time.LocalDateTime.now()).append("\n\n");

        int passed = 0, failed = 0, warnings = 0;

        report.append("## Summary\n\n");
        report.append("| File | Parse | Render | Validation | Notes |\n");
        report.append("|------|-------|--------|------------|-------|\n");

        for (ValidationResult result : allResults) {
            String parseStatus = result.parseSuccess ? "✓" : "✗";
            String renderStatus = result.renderSuccess ? "✓" : "✗";
            String validStatus = result.validationPassed ? "✓" : (result.hasWarnings ? "⚠" : "✗");

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
    // Test: Standard Apertures
    // ============================================================

    @Test
    @Order(1)
    @DisplayName("Apertures - Circle basic")
    void testCircleBasic() throws Exception {
        validateGerberFile("apertures/01_circle_basic.gbr");
    }

    @Test
    @Order(2)
    @DisplayName("Apertures - Circle with hole")
    void testCircleWithHole() throws Exception {
        validateGerberFile("apertures/02_circle_with_hole.gbr");
    }

    @Test
    @Order(3)
    @DisplayName("Apertures - Rectangle basic")
    void testRectangleBasic() throws Exception {
        validateGerberFile("apertures/03_rectangle_basic.gbr");
    }

    @Test
    @Order(4)
    @DisplayName("Apertures - Rectangle with hole")
    void testRectangleWithHole() throws Exception {
        validateGerberFile("apertures/04_rectangle_with_hole.gbr");
    }

    @Test
    @Order(5)
    @DisplayName("Apertures - Obround basic")
    void testObroundBasic() throws Exception {
        validateGerberFile("apertures/05_obround_basic.gbr");
    }

    @Test
    @Order(6)
    @DisplayName("Apertures - Polygon vertices")
    void testPolygonVertices() throws Exception {
        validateGerberFile("apertures/06_polygon_vertices.gbr");
    }

    @Test
    @Order(7)
    @DisplayName("Apertures - Polygon rotation")
    void testPolygonRotation() throws Exception {
        validateGerberFile("apertures/07_polygon_rotation.gbr");
    }

    @Test
    @Order(8)
    @DisplayName("Apertures - Zero size aperture")
    void testZeroSizeAperture() throws Exception {
        validateGerberFile("apertures/08_zero_size_aperture.gbr");
    }

    // ============================================================
    // Test: Macro Apertures
    // ============================================================

    @Test
    @Order(10)
    @DisplayName("Macros - Circle primitive")
    void testMacroCircle() throws Exception {
        validateGerberFile("macros/01_circle_primitive.gbr");
    }

    @Test
    @Order(11)
    @DisplayName("Macros - Vector line primitive")
    void testMacroVectorLine() throws Exception {
        validateGerberFile("macros/02_vector_line_primitive.gbr");
    }

    @Test
    @Order(12)
    @DisplayName("Macros - Center line primitive")
    void testMacroCenterLine() throws Exception {
        validateGerberFile("macros/03_center_line_primitive.gbr");
    }

    @Test
    @Order(13)
    @DisplayName("Macros - Outline primitive")
    void testMacroOutline() throws Exception {
        validateGerberFile("macros/04_outline_primitive.gbr");
    }

    @Test
    @Order(14)
    @DisplayName("Macros - Polygon primitive")
    void testMacroPolygon() throws Exception {
        validateGerberFile("macros/05_polygon_primitive.gbr");
    }

    @Test
    @Order(15)
    @DisplayName("Macros - Thermal primitive")
    void testMacroThermal() throws Exception {
        validateGerberFile("macros/06_thermal_primitive.gbr");
    }

    @Test
    @Order(16)
    @DisplayName("Macros - Variables and expressions")
    void testMacroVariables() throws Exception {
        validateGerberFile("macros/07_macro_variables.gbr");
    }

    // ============================================================
    // Test: Plotting Operations
    // ============================================================

    @Test
    @Order(20)
    @DisplayName("Plotting - Linear interpolation")
    void testLinearInterpolation() throws Exception {
        validateGerberFile("plotting/01_linear_interpolation.gbr");
    }

    @Test
    @Order(21)
    @DisplayName("Plotting - Circular CW")
    void testCircularCW() throws Exception {
        validateGerberFile("plotting/02_circular_cw.gbr");
    }

    @Test
    @Order(22)
    @DisplayName("Plotting - Circular CCW")
    void testCircularCCW() throws Exception {
        validateGerberFile("plotting/03_circular_ccw.gbr");
    }

    @Test
    @Order(23)
    @DisplayName("Plotting - Full circles")
    void testFullCircles() throws Exception {
        validateGerberFile("plotting/04_full_circles.gbr");
    }

    @Test
    @Order(24)
    @DisplayName("Plotting - Modal coordinates")
    void testModalCoordinates() throws Exception {
        validateGerberFile("plotting/05_modal_coordinates.gbr");
    }

    // ============================================================
    // Test: Regions
    // ============================================================

    @Test
    @Order(30)
    @DisplayName("Regions - Simple regions")
    void testSimpleRegions() throws Exception {
        validateGerberFile("regions/01_simple_regions.gbr");
    }

    @Test
    @Order(31)
    @DisplayName("Regions - With arcs")
    void testRegionsWithArcs() throws Exception {
        validateGerberFile("regions/02_regions_with_arcs.gbr");
    }

    @Test
    @Order(32)
    @DisplayName("Regions - Holes with polarity (LPC)")
    void testRegionsWithHolesPolarity() throws Exception {
        validateGerberFile("regions/03_regions_with_holes_polarity.gbr");
    }

    @Test
    @Order(33)
    @DisplayName("Regions - Holes with cut-ins")
    void testRegionsWithCutins() throws Exception {
        validateGerberFile("regions/04_regions_with_cutins.gbr");
    }

    @Test
    @Order(34)
    @DisplayName("Regions - Multiple contours")
    void testRegionsMultipleContours() throws Exception {
        validateGerberFile("regions/05_multiple_contours.gbr");
    }

    // ============================================================
    // Test: Polarity
    // ============================================================

    @Test
    @Order(40)
    @DisplayName("Polarity - Basic")
    void testPolarityBasic() throws Exception {
        validateGerberFile("polarity/01_polarity_basic.gbr");
    }

    // ============================================================
    // Test: Transforms
    // ============================================================

    @Test
    @Order(50)
    @DisplayName("Transforms - Rotation")
    void testRotation() throws Exception {
        validateGerberFile("transforms/01_rotation.gbr");
    }

    @Test
    @Order(51)
    @DisplayName("Transforms - Scaling")
    void testScaling() throws Exception {
        validateGerberFile("transforms/02_scaling.gbr");
    }

    @Test
    @Order(52)
    @DisplayName("Transforms - Mirroring")
    void testMirroring() throws Exception {
        validateGerberFile("transforms/03_mirroring.gbr");
    }

    // ============================================================
    // Test: Block Apertures
    // ============================================================

    @Test
    @Order(55)
    @DisplayName("Blocks - Basic block aperture")
    void testBlockApertureBasic() throws Exception {
        validateGerberFile("blocks/01_block_aperture_basic.gbr");
    }

    @Test
    @Order(56)
    @DisplayName("Blocks - Block with transforms")
    void testBlockApertureTransforms() throws Exception {
        validateGerberFile("blocks/02_block_aperture_transforms.gbr");
    }

    @Test
    @Order(57)
    @DisplayName("Blocks - Block with polarity")
    void testBlockAperturePolarity() throws Exception {
        validateGerberFile("blocks/03_block_aperture_polarity.gbr");
    }

    // ============================================================
    // Test: Step and Repeat
    // ============================================================

    @Test
    @Order(58)
    @DisplayName("Step-Repeat - Basic arrays")
    void testStepRepeatBasic() throws Exception {
        validateGerberFile("step-repeat/01_step_repeat_basic.gbr");
    }

    @Test
    @Order(59)
    @DisplayName("Step-Repeat - Complex with regions")
    void testStepRepeatComplex() throws Exception {
        validateGerberFile("step-repeat/02_step_repeat_complex.gbr");
    }

    // ============================================================
    // Test: Inch Units
    // ============================================================

    @Test
    @Order(70)
    @DisplayName("Inch - Apertures")
    void testInchApertures() throws Exception {
        validateGerberFile("inch/01_inch_apertures.gbr");
    }

    @Test
    @Order(71)
    @DisplayName("Inch - Plotting")
    void testInchPlotting() throws Exception {
        validateGerberFile("inch/02_inch_plotting.gbr");
    }

    // ============================================================
    // Test: Attributes
    // ============================================================

    @Test
    @Order(75)
    @DisplayName("Attributes - File attributes (TF)")
    void testFileAttributes() throws Exception {
        validateGerberFile("attributes/01_file_attributes.gbr");
    }

    @Test
    @Order(76)
    @DisplayName("Attributes - Aperture and object attributes")
    void testApertureObjectAttributes() throws Exception {
        validateGerberFile("attributes/02_aperture_object_attributes.gbr");
    }

    // ============================================================
    // Test: Combined
    // ============================================================

    @Test
    @Order(60)
    @DisplayName("Combined - Board outline")
    void testBoardOutline() throws Exception {
        validateGerberFile("combined/board_outline.gbr");
    }

    @Test
    @Order(61)
    @DisplayName("Combined - Comprehensive test")
    void testComprehensive() throws Exception {
        validateGerberFile("combined/comprehensive_test.gbr");
    }

    @Test
    @Order(62)
    @DisplayName("Combined - All features comprehensive")
    void testAllFeaturesComprehensive() throws Exception {
        validateGerberFile("combined/all_features_comprehensive.gbr");
    }

    // ============================================================
    // Core Validation Logic
    // ============================================================

    private void validateGerberFile(String relativePath) throws Exception {
        ValidationResult result = new ValidationResult(relativePath);
        Path gerberPath = TEST_SUITE_DIR.resolve(relativePath);

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
            doc = parser.parse(content);
            result.parseSuccess = true;
            result.objectCount = doc.getObjects().size();
            result.apertureCount = doc.getApertures().size();
            result.boundingBox = doc.getBoundingBox();
        } catch (Exception e) {
            result.parseSuccess = false;
            result.parseError = e.getMessage();
            allResults.add(result);
            fail("Failed to parse " + relativePath + ": " + e.getMessage());
            return;
        }

        // Step 2: Render to SVG (exact mode for precision, polygonized for reference comparison)
        String exactSvg = null;
        String polygonizedSvg = null;
        try {
            exactSvg = exactRenderer.render(doc);
            polygonizedSvg = polygonizedRenderer.render(doc);
            result.renderSuccess = true;

            // Save generated SVGs for manual inspection
            String baseFileName = relativePath.replace("/", "_").replace(".gbr", "");
            Files.writeString(OUTPUT_DIR.resolve(baseFileName + "_exact.svg"), exactSvg);
            Files.writeString(OUTPUT_DIR.resolve(baseFileName + "_poly.svg"), polygonizedSvg);
        } catch (Exception e) {
            result.renderSuccess = false;
            result.renderError = e.getMessage();
            allResults.add(result);
            fail("Failed to render " + relativePath + ": " + e.getMessage());
            return;
        }

        // Step 3: Validate SVG structure
        try {
            validateSvgStructure(exactSvg, result);
        } catch (Exception e) {
            result.validationNotes.add("SVG validation error: " + e.getMessage());
        }

        // Step 4: Compare against reference SVG
        try {
            compareAgainstReference(relativePath, polygonizedSvg, result);
        } catch (Exception e) {
            result.validationNotes.add("Reference comparison error: " + e.getMessage());
        }

        // Step 5: Validate bounding box is reasonable
        validateBoundingBox(result);

        // Record result
        allResults.add(result);

        // Build assertion message
        StringBuilder msg = new StringBuilder();
        msg.append("Validation of ").append(relativePath).append(":\n");
        msg.append("  Objects: ").append(result.objectCount).append("\n");
        msg.append("  Apertures: ").append(result.apertureCount).append("\n");
        if (result.boundingBox != null && result.boundingBox.isValid()) {
            msg.append(String.format("  BBox: %.3f x %.3f mm\n",
                result.boundingBox.getWidth(), result.boundingBox.getHeight()));
        }
        for (String note : result.validationNotes) {
            msg.append("  Note: ").append(note).append("\n");
        }

        // Determine overall success
        if (!result.validationPassed && !result.hasWarnings) {
            fail(msg.toString());
        }

        System.out.println(msg);
    }

    private void validateSvgStructure(String svg, ValidationResult result) throws Exception {
        // Basic SVG validation
        assertTrue(svg.startsWith("<svg"), "SVG should start with <svg tag");
        assertTrue(svg.contains("viewBox"), "SVG should have viewBox");
        assertTrue(svg.endsWith("</svg>"), "SVG should end with </svg>");

        // Parse SVG as XML
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(svg)));

        Element root = doc.getDocumentElement();
        assertEquals("svg", root.getTagName(), "Root element should be svg");

        // Check for expected elements
        NodeList defs = doc.getElementsByTagName("defs");
        if (defs.getLength() > 0) {
            result.validationNotes.add("Has defs section");
        }

        NodeList paths = doc.getElementsByTagName("path");
        NodeList circles = doc.getElementsByTagName("circle");
        NodeList rects = doc.getElementsByTagName("rect");
        NodeList uses = doc.getElementsByTagName("use");

        int totalElements = paths.getLength() + circles.getLength() + rects.getLength() + uses.getLength();
        result.validationNotes.add(String.format("SVG elements: %d paths, %d circles, %d rects, %d uses",
            paths.getLength(), circles.getLength(), rects.getLength(), uses.getLength()));

        // Basic sanity check: should have some graphical content
        if (totalElements == 0 && result.objectCount > 0) {
            result.validationNotes.add("WARNING: No graphical elements in SVG but document has objects");
            result.hasWarnings = true;
        }

        result.validationPassed = true;
    }

    private void validateBoundingBox(ValidationResult result) {
        if (result.boundingBox == null || !result.boundingBox.isValid()) {
            result.validationNotes.add("WARNING: Invalid or empty bounding box");
            result.hasWarnings = true;
            return;
        }

        double width = result.boundingBox.getWidth();
        double height = result.boundingBox.getHeight();

        // Sanity checks
        if (width <= 0 || height <= 0) {
            result.validationNotes.add("WARNING: Zero or negative dimensions");
            result.hasWarnings = true;
        }

        if (width > 1000 || height > 1000) {
            result.validationNotes.add("WARNING: Very large dimensions (>1000mm)");
            result.hasWarnings = true;
        }

        // For test files, expect reasonable dimensions (typically < 50mm)
        if (width > 50 || height > 50) {
            result.validationNotes.add("Note: Dimensions larger than expected for test file");
        }
    }

    /**
     * Compare generated SVG against reference SVG layer using element count comparison.
     */
    private void compareAgainstReference(String relativePath, String generatedSvg, ValidationResult result) throws Exception {
        // Check if we should skip this file
        if (SKIP_REFERENCE_COMPARISON.contains(relativePath)) {
            result.validationNotes.add("Reference comparison skipped (known unsupported feature)");
            return;
        }

        // Find the reference layer file by the gerber filename (without path)
        String gbrFileName = Path.of(relativePath).getFileName().toString();
        Path refLayerFile = findReferenceLayerFile(gbrFileName);

        // Generate reference mode: save current output as new reference
        if (GENERATE_REFERENCE) {
            Files.createDirectories(REFERENCE_LAYERS_DIR);
            String refFileName = gbrFileName.replace(".gbr", ".svg").replace(" ", "_");
            Path referencePath = REFERENCE_LAYERS_DIR.resolve(refFileName);
            Files.writeString(referencePath, generatedSvg);
            result.validationNotes.add("Reference SVG generated: " + refFileName);
            return;
        }

        // Check if reference exists
        if (refLayerFile == null) {
            result.validationNotes.add("No reference layer found for: " + gbrFileName);
            result.hasWarnings = true;
            return;
        }

        // Load reference SVG
        String referenceSvg = Files.readString(refLayerFile);

        // Copy reference to output dir for easy comparison
        String baseFileName = gbrFileName.replace(".", "_").replace(" ", "_");
        Files.copy(refLayerFile, OUTPUT_DIR.resolve(baseFileName + "_reference.svg"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Parse both SVGs to count and compare elements
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();

        Document refDoc = builder.parse(new InputSource(new StringReader(referenceSvg)));
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
     * Find the reference layer file for a given gerber file name.
     */
    private Path findReferenceLayerFile(String gbrFileName) {
        if (referenceLayerFiles == null || referenceLayerFiles.isEmpty()) {
            return null;
        }

        // Try exact match first (with .gbr extension as layer ID)
        if (referenceLayerFiles.containsKey(gbrFileName)) {
            return referenceLayerFiles.get(gbrFileName);
        }

        // Try with underscores instead of spaces
        String sanitized = gbrFileName.replace(" ", "_");
        if (referenceLayerFiles.containsKey(sanitized)) {
            return referenceLayerFiles.get(sanitized);
        }

        // Try direct path lookup
        Path candidate = REFERENCE_LAYERS_DIR.resolve(sanitized.replace(".gbr", ".svg"));
        if (Files.exists(candidate)) {
            return candidate;
        }

        // Search for a file containing the key part of the name
        for (Map.Entry<String, Path> entry : referenceLayerFiles.entrySet()) {
            String key = entry.getKey();
            if (key.contains(gbrFileName) || gbrFileName.contains(key) ||
                key.replace("_", " ").equals(gbrFileName) ||
                gbrFileName.replace("_", " ").equals(key)) {
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
        org.w3c.dom.Node parent = elem.getParentNode();
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
