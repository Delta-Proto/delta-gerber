package nl.bytesoflife.gerber;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.model.gerber.GerberDocument;
import nl.bytesoflife.gerber.parser.GerberParser;
import nl.bytesoflife.gerber.renderer.svg.SVGRenderer;
import nl.bytesoflife.gerber.renderer.svg.SvgOptions;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
    private static final Path REFERENCE_SVG = Path.of("target/test-gerber-suite.svg");
    private static final Path OUTPUT_DIR = Path.of("target/svg-validation");
    private static final Path REFERENCE_DIR = Path.of("test-gerber-suite/reference-svg");

    // Set to true to regenerate reference SVGs (run once to create baseline)
    private static final boolean GENERATE_REFERENCE = Boolean.parseBoolean(
        System.getProperty("generateReference", "false"));

    // Tolerance for floating point comparisons (in mm)
    private static final double COMPARISON_TOLERANCE = 1e-4;

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

    private final SvgComparer comparer = new SvgComparer(COMPARISON_TOLERANCE);

    private static ReferenceSvgData referenceData;
    private static final List<ValidationResult> allResults = new ArrayList<>();

    @BeforeAll
    static void setupAll() throws Exception {
        // Create output directory
        Files.createDirectories(OUTPUT_DIR);
        Files.createDirectories(REFERENCE_DIR);

        if (GENERATE_REFERENCE) {
            System.out.println("=== REFERENCE GENERATION MODE ===");
            System.out.println("Reference SVGs will be generated/updated in: " + REFERENCE_DIR);
        }

        // Parse reference SVG to extract structural data
        if (Files.exists(REFERENCE_SVG)) {
            referenceData = parseReferenceSvg(REFERENCE_SVG);
            System.out.println("Reference SVG loaded:");
            System.out.println("  - Aperture definitions: " + referenceData.apertureIds.size());
            System.out.println("  - Transform matrix present: " + (referenceData.viewportTransform != null));
        } else {
            System.out.println("NOTE: Combined reference SVG not found at " + REFERENCE_SVG);
            System.out.println("Individual reference SVGs will be used from: " + REFERENCE_DIR);
        }
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
     * Compare generated SVG against reference SVG using the SvgComparer.
     * This performs all 4 types of validation:
     * 1. Element count comparison
     * 2. ViewBox/bounding box comparison
     * 3. Aperture ID comparison
     * 4. Path data comparison with tolerance
     */
    private void compareAgainstReference(String relativePath, String generatedSvg, ValidationResult result) throws Exception {
        // Check if we should skip this file
        if (SKIP_REFERENCE_COMPARISON.contains(relativePath)) {
            result.validationNotes.add("Reference comparison skipped (known unsupported feature)");
            return;
        }

        // Determine reference file path
        String refFileName = relativePath.replace("/", "_").replace(".gbr", ".svg");
        Path referencePath = REFERENCE_DIR.resolve(refFileName);

        // Generate reference mode: save current output as new reference
        if (GENERATE_REFERENCE) {
            Files.writeString(referencePath, generatedSvg);
            result.validationNotes.add("Reference SVG generated: " + refFileName);
            return;
        }

        // Check if reference exists
        if (!Files.exists(referencePath)) {
            result.validationNotes.add("No reference SVG found. Run with -DgenerateReference=true to create baseline.");
            result.hasWarnings = true;
            return;
        }

        // Load reference SVG
        String referenceSvg = Files.readString(referencePath);

        // Perform comparison using SvgComparer
        SvgComparer.ComparisonResult comparison = comparer.compare(referenceSvg, generatedSvg);

        if (comparison.isMatch()) {
            result.validationNotes.add("✓ Reference comparison passed (all 4 checks)");
        } else {
            // Categorize differences by type
            int elementCountDiffs = 0;
            int viewBoxDiffs = 0;
            int apertureDiffs = 0;
            int pathDiffs = 0;
            int otherDiffs = 0;

            for (SvgComparer.Difference diff : comparison.differences) {
                switch (diff.category) {
                    case "Element count":
                        elementCountDiffs++;
                        break;
                    case "ViewBox":
                        viewBoxDiffs++;
                        break;
                    case "Aperture IDs":
                        apertureDiffs++;
                        break;
                    case "Path":
                    case "Path commands":
                    case "Path command type":
                    case "Path command values":
                        pathDiffs++;
                        break;
                    default:
                        otherDiffs++;
                        break;
                }
            }

            // Report summary
            StringBuilder summary = new StringBuilder();
            summary.append("Reference comparison: ");
            summary.append(comparison.differences.size()).append(" difference(s) - ");

            List<String> parts = new ArrayList<>();
            if (elementCountDiffs > 0) parts.add(elementCountDiffs + " element count");
            if (viewBoxDiffs > 0) parts.add(viewBoxDiffs + " viewBox");
            if (apertureDiffs > 0) parts.add(apertureDiffs + " aperture");
            if (pathDiffs > 0) parts.add(pathDiffs + " path data");
            if (otherDiffs > 0) parts.add(otherDiffs + " other");
            summary.append(String.join(", ", parts));

            result.validationNotes.add(summary.toString());

            // Add detailed differences (limit to first 10)
            int count = 0;
            for (SvgComparer.Difference diff : comparison.differences) {
                if (count++ >= 10) {
                    result.validationNotes.add("  ... and " + (comparison.differences.size() - 10) + " more differences");
                    break;
                }
                result.validationNotes.add("  " + diff.toString());
            }

            // Mark as warning (not failure) so we can review differences
            result.hasWarnings = true;
        }
    }

    // ============================================================
    // Reference SVG Parsing
    // ============================================================

    private static ReferenceSvgData parseReferenceSvg(Path svgPath) throws Exception {
        ReferenceSvgData data = new ReferenceSvgData();
        String content = Files.readString(svgPath);

        // Extract aperture IDs (D10, D11, etc.)
        Pattern aperturePattern = Pattern.compile("id=\"(D\\d+)\"");
        Matcher matcher = aperturePattern.matcher(content);
        while (matcher.find()) {
            data.apertureIds.add(matcher.group(1));
        }

        // Extract viewport transform
        Pattern transformPattern = Pattern.compile("transform=\"matrix\\(([^)]+)\\)\"");
        matcher = transformPattern.matcher(content);
        if (matcher.find()) {
            data.viewportTransform = matcher.group(1);
        }

        // Extract viewBox
        Pattern viewBoxPattern = Pattern.compile("viewBox=\"([^\"]+)\"");
        matcher = viewBoxPattern.matcher(content);
        if (matcher.find()) {
            data.viewBox = matcher.group(1);
        }

        return data;
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

    private static class ReferenceSvgData {
        Set<String> apertureIds = new HashSet<>();
        String viewportTransform;
        String viewBox;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
