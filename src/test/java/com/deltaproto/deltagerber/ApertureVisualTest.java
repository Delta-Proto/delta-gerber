package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.SVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Visual test for standard aperture templates.
 * Generates an HTML page with SVG renders of all aperture types for visual inspection.
 * Shows both exact mode (native SVG elements) and polygonized mode (path approximations).
 */
public class ApertureVisualTest {

    private final GerberParser parser = new GerberParser();
    private final SVGRenderer renderer = new SVGRenderer()
        .setDarkColor("#FF0000")  // Red for visibility on dark background
        .setClearColor("#0f0f23")  // Holes show as dark background color
        .setBackgroundColor("#0f0f23")  // Dark SVG background
        .setFixedViewBoxSize(4.0)  // Fixed viewBox so different sizes are visible
        .setFlipY(true)  // Enable Y-flip for correct Gerber coordinate display
        .setPolygonizeMode();  // Polygonized output

    // Test case data class
    record ApertureTestCase(String name, String description, String gerber) {}

    @Test
    void generateApertureVisualReport() throws IOException {
        // Ensure US locale for consistent number formatting
        Locale.setDefault(Locale.US);

        List<ApertureTestCase> testCases = createTestCases();
        StringBuilder html = new StringBuilder();

        html.append("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>Gerber Aperture Catalog</title>
                <style>
                    body { font-family: -apple-system, sans-serif; background: #1a1a2e; color: #eee; padding: 20px; }
                    h1 { color: #4fc3f7; }
                    h2 { color: #81d4fa; border-bottom: 1px solid #333; padding-bottom: 10px; margin-top: 40px; }
                    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 20px; }
                    .symbol-card { background: #16213e; border-radius: 8px; padding: 15px; text-align: center; }
                    .symbol-card svg { background: #0f0f23; border-radius: 4px; margin: 10px 0; }
                    .symbol-name { font-size: 14px; color: #4fc3f7; margin-top: 10px; }
                    .symbol-desc { font-size: 12px; color: #888; }
                    .gerber-code { font-size: 11px; color: #666; font-family: monospace; margin-top: 8px; word-break: break-all; }
                    .error { color: #f87171; font-size: 12px; }
                </style>
            </head>
            <body>
                <h1>Gerber Aperture Catalog</h1>
                <p>Visual verification of all implemented aperture renderers.</p>
            """);


        // Group test cases by type
        String currentSection = "";
        int testIndex = 0;
        for (ApertureTestCase testCase : testCases) {
            testIndex++;
            String section = testCase.name.split(" ")[0];
            if (!section.equals(currentSection)) {
                if (!currentSection.isEmpty()) {
                    html.append("</div>\n");
                }
                currentSection = section;
                html.append("<h2>").append(section).append("</h2>\n");
                html.append("<div class='grid'>\n");
            }

            html.append("<div class='symbol-card'>");

            try {
                GerberDocument doc = parser.parse(testCase.gerber);
                String svg = renderer.render(doc);
                // Make SVG fixed size for consistent display
                svg = svg.replaceFirst("viewBox=", "width='150' height='150' viewBox=");
                // Make aperture IDs unique per test case to avoid ID collisions on the page
                svg = svg.replace("id=\"ap", "id=\"ap" + testIndex + "_");
                svg = svg.replace("href=\"#ap", "href=\"#ap" + testIndex + "_");
                html.append(svg);
            } catch (Exception e) {
                html.append("<div class='error'>Error: ")
                    .append(escapeHtml(e.getMessage()))
                    .append("</div>");
            }

            html.append("<div class='symbol-name'>").append(escapeHtml(testCase.name)).append("</div>");
            html.append("<div class='symbol-desc'>").append(escapeHtml(testCase.description)).append("</div>");

            // Show the aperture definition line
            String apertureLine = extractApertureLine(testCase.gerber);
            html.append("<div class='gerber-code'>").append(escapeHtml(apertureLine)).append("</div>");
            html.append("</div>\n");
        }

        if (!currentSection.isEmpty()) {
            html.append("</div>\n");
        }

        html.append("""
                <p style="margin-top: 40px; color: #666; text-align: center;">
                    Generated by ApertureVisualTest
                </p>
            </body>
            </html>
            """);

        // Write to file
        Path outputPath = Path.of("generated/aperture-visual-test.html");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, html.toString());

        System.out.println("Visual test report generated: " + outputPath.toAbsolutePath());
    }

    private List<ApertureTestCase> createTestCases() {
        List<ApertureTestCase> cases = new ArrayList<>();

        // ===== CIRCLE (C) =====
        cases.add(new ApertureTestCase(
            "Circle - Basic",
            "Solid circle, diameter 1.0mm",
            createGerber("%ADD10C,1.0*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Circle - Small",
            "Solid circle, diameter 0.5mm",
            createGerber("%ADD10C,0.5*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Circle - Large",
            "Solid circle, diameter 2.0mm",
            createGerber("%ADD10C,2.0*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Circle - With Hole",
            "Circle with center hole (spec example: C,0.5X0.25)",
            createGerber("%ADD10C,0.5X0.25*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Circle - Large with Hole",
            "Circle 2.0mm with 1.0mm hole",
            createGerber("%ADD10C,2.0X1.0*%", 10)
        ));

        // ===== RECTANGLE (R) =====
        cases.add(new ApertureTestCase(
            "Rectangle - Square",
            "Square 1.0 x 1.0mm",
            createGerber("%ADD10R,1.0X1.0*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Rectangle - Horizontal",
            "Rectangle 2.0 x 1.0mm (wider than tall)",
            createGerber("%ADD10R,2.0X1.0*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Rectangle - Vertical",
            "Rectangle 1.0 x 2.0mm (taller than wide)",
            createGerber("%ADD10R,1.0X2.0*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Rectangle - Spec Example",
            "From spec: R,0.044X0.025 (44x25 mil)",
            createGerber("%ADD10R,1.12X0.64*%", 10) // converted to mm approx
        ));

        cases.add(new ApertureTestCase(
            "Rectangle - With Hole",
            "Rectangle 2.0 x 1.5mm with 0.5mm hole",
            createGerber("%ADD10R,2.0X1.5X0.5*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Rectangle - Square with Hole",
            "Square 2.0 x 2.0mm with 1.0mm hole",
            createGerber("%ADD10R,2.0X2.0X1.0*%", 10)
        ));

        // ===== OBROUND (O) =====
        cases.add(new ApertureTestCase(
            "Obround - Horizontal",
            "Obround 2.0 x 1.0mm (pill shape horizontal)",
            createGerber("%ADD10O,2.0X1.0*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Obround - Vertical",
            "Obround 1.0 x 2.0mm (pill shape vertical)",
            createGerber("%ADD10O,1.0X2.0*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Obround - Square (Circle)",
            "Obround 1.0 x 1.0mm (becomes circle)",
            createGerber("%ADD10O,1.0X1.0*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Obround - Wide",
            "Wide obround 3.0 x 1.0mm",
            createGerber("%ADD10O,3.0X1.0*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Obround - With Hole",
            "Obround 2.0 x 1.0mm with 0.4mm hole",
            createGerber("%ADD10O,2.0X1.0X0.4*%", 10)
        ));

        // ===== POLYGON (P) =====
        cases.add(new ApertureTestCase(
            "Polygon - Triangle (3)",
            "Regular triangle, diameter 2.0mm",
            createGerber("%ADD10P,2.0X3*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - Square (4)",
            "Regular square (diamond), diameter 2.0mm",
            createGerber("%ADD10P,2.0X4*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - Pentagon (5)",
            "Regular pentagon, diameter 2.0mm",
            createGerber("%ADD10P,2.0X5*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - Hexagon (6)",
            "Regular hexagon, diameter 2.0mm (spec example)",
            createGerber("%ADD10P,2.0X6*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - Heptagon (7)",
            "Regular heptagon, diameter 2.0mm",
            createGerber("%ADD10P,2.0X7*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - Octagon (8)",
            "Regular octagon, diameter 2.0mm",
            createGerber("%ADD10P,2.0X8*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - 12-gon (12)",
            "Regular 12-sided polygon, diameter 2.0mm",
            createGerber("%ADD10P,2.0X12*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - Rotated 45°",
            "Square rotated 45° (appears as square)",
            createGerber("%ADD10P,2.0X4X45*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - Hex Rotated 30°",
            "Hexagon rotated 30°",
            createGerber("%ADD10P,2.0X6X30*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - Triangle Rotated",
            "Triangle rotated 180° (point down)",
            createGerber("%ADD10P,2.0X3X180*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - With Hole",
            "Hexagon with 0.5mm center hole",
            createGerber("%ADD10P,2.0X6X0X0.5*%", 10)
        ));

        cases.add(new ApertureTestCase(
            "Polygon - Octagon with Hole",
            "Octagon with 0.8mm center hole",
            createGerber("%ADD10P,2.0X8X0X0.8*%", 10)
        ));

        // ===== MACRO PRIMITIVES =====

        // Circle Primitive (Code 1)
        cases.add(new ApertureTestCase(
            "Macro Circle - Basic",
            "Circle primitive at origin (code 1)",
            createMacroGerber("%AMCircle1*1,1,1.5,0,0*%", "Circle1")
        ));

        cases.add(new ApertureTestCase(
            "Macro Circle - Offset",
            "Circle primitive offset from center",
            createMacroGerber("%AMCircleOffset*1,1,0.8,0.5,0.5*%", "CircleOffset")
        ));

        cases.add(new ApertureTestCase(
            "Macro Circle - Rotated",
            "Circle at (1,0) rotated 45° around origin",
            createMacroGerber("%AMCircleRot*1,1,0.5,1.0,0,45*%", "CircleRot")
        ));

        cases.add(new ApertureTestCase(
            "Macro Circle - Multiple",
            "Multiple circles forming a pattern",
            createMacroGerber("%AMMultiCircle*1,1,0.4,0.8,0*1,1,0.4,-0.8,0*1,1,0.4,0,0.8*1,1,0.4,0,-0.8*%", "MultiCircle")
        ));

        // Vector Line Primitive (Code 20)
        cases.add(new ApertureTestCase(
            "Macro Vector Line - Horizontal",
            "Vector line primitive (code 20)",
            createMacroGerber("%AMVecLine*20,1,0.3,0,0,2.0,0,0*%", "VecLine")
        ));

        cases.add(new ApertureTestCase(
            "Macro Vector Line - Diagonal",
            "Vector line from (0,0) to (1,1)",
            createMacroGerber("%AMVecLineDiag*20,1,0.2,0,0,1.5,1.5,0*%", "VecLineDiag")
        ));

        cases.add(new ApertureTestCase(
            "Macro Vector Line - Rotated 45°",
            "Horizontal line rotated 45°",
            createMacroGerber("%AMVecLineRot*20,1,0.3,-1.0,0,1.0,0,45*%", "VecLineRot")
        ));

        cases.add(new ApertureTestCase(
            "Macro Vector Line - Rotated 90°",
            "Horizontal line rotated 90° (vertical)",
            createMacroGerber("%AMVecLineRot90*20,1,0.3,-1.0,0,1.0,0,90*%", "VecLineRot90")
        ));

        // Center Line Primitive (Code 21)
        cases.add(new ApertureTestCase(
            "Macro Center Line - Basic",
            "Center line primitive (code 21)",
            createMacroGerber("%AMCenterLine*21,1,2.0,0.5,0,0,0*%", "CenterLine")
        ));

        cases.add(new ApertureTestCase(
            "Macro Center Line - Vertical",
            "Tall center line (0.5 x 2.0)",
            createMacroGerber("%AMCenterLineV*21,1,0.5,2.0,0,0,0*%", "CenterLineV")
        ));

        cases.add(new ApertureTestCase(
            "Macro Center Line - Offset",
            "Center line offset from origin",
            createMacroGerber("%AMCenterLineOff*21,1,1.5,0.6,0.5,0.3,0*%", "CenterLineOff")
        ));

        cases.add(new ApertureTestCase(
            "Macro Center Line - Rotated 30°",
            "Center line rotated 30° (spec example)",
            createMacroGerber("%AMCenterLineRot*21,1,1.7,0.3,0.85,0.15,30*%", "CenterLineRot")
        ));

        cases.add(new ApertureTestCase(
            "Macro Center Line - Rotated 45°",
            "Center line rotated 45°",
            createMacroGerber("%AMCenterLine45*21,1,2.0,0.4,0,0,45*%", "CenterLine45")
        ));

        // Outline Primitive (Code 4)
        cases.add(new ApertureTestCase(
            "Macro Outline - Triangle",
            "Triangle outline (code 4)",
            createMacroGerber("%AMTriangle*4,1,3,0,-0.8,0.8,0.5,-0.8,0.5,0,-0.8,0*%", "Triangle")
        ));

        cases.add(new ApertureTestCase(
            "Macro Outline - Triangle Rotated 30°",
            "Triangle rotated 30° (spec example)",
            createMacroGerber("%AMTriangle30*4,1,3,1,-1,1,1,2,1,1,-1,30*%", "Triangle30")
        ));

        cases.add(new ApertureTestCase(
            "Macro Outline - Diamond",
            "Diamond shape outline",
            createMacroGerber("%AMDiamond*4,1,4,0,-1,1,0,0,1,-1,0,0,-1,0*%", "Diamond")
        ));

        cases.add(new ApertureTestCase(
            "Macro Outline - Arrow",
            "Arrow shape",
            createMacroGerber("%AMArrow*4,1,6,0,0.3,0.8,0.3,0.8,0.6,1.5,0,0.8,-0.6,0.8,-0.3,0,0.3,0*%", "Arrow")
        ));

        cases.add(new ApertureTestCase(
            "Macro Outline - Arrow Rotated 90°",
            "Arrow pointing up",
            createMacroGerber("%AMArrowUp*4,1,6,0,0.3,0.8,0.3,0.8,0.6,1.5,0,0.8,-0.6,0.8,-0.3,0,0.3,90*%", "ArrowUp")
        ));

        // Polygon Primitive (Code 5)
        cases.add(new ApertureTestCase(
            "Macro Polygon - Triangle",
            "Macro polygon with 3 vertices",
            createMacroGerber("%AMMPoly3*5,1,3,0,0,1.5,0*%", "MPoly3")
        ));

        cases.add(new ApertureTestCase(
            "Macro Polygon - Pentagon",
            "Macro polygon with 5 vertices",
            createMacroGerber("%AMMPoly5*5,1,5,0,0,1.5,0*%", "MPoly5")
        ));

        cases.add(new ApertureTestCase(
            "Macro Polygon - Octagon",
            "Macro polygon with 8 vertices (spec example)",
            createMacroGerber("%AMPoly8*5,1,8,0,0,2.0,0*%", "Poly8")
        ));

        cases.add(new ApertureTestCase(
            "Macro Polygon - Hex Rotated 30°",
            "Hexagon rotated 30°",
            createMacroGerber("%AMMPolyRot*5,1,6,0,0,1.5,30*%", "MPolyRot")
        ));

        cases.add(new ApertureTestCase(
            "Macro Polygon - Offset",
            "Pentagon offset from center",
            createMacroGerber("%AMMPolyOff*5,1,5,0.5,0.3,1.2,0*%", "MPolyOff")
        ));

        // Thermal Primitive (Code 7)
        cases.add(new ApertureTestCase(
            "Macro Thermal - Basic",
            "Thermal relief primitive (code 7)",
            createMacroGerber("%AMThermal*7,0,0,2.0,1.2,0.3,0*%", "Thermal")
        ));

        cases.add(new ApertureTestCase(
            "Macro Thermal - Spec Example",
            "Thermal from spec: 0.95 outer, 0.75 inner, 0.175 gap",
            createMacroGerber("%AMThermalSpec*7,0,0,1.9,1.5,0.35,0*%", "ThermalSpec")
        ));

        cases.add(new ApertureTestCase(
            "Macro Thermal - Rotated 45°",
            "Thermal rotated 45°",
            createMacroGerber("%AMThermalRot*7,0,0,2.0,1.2,0.3,45*%", "ThermalRot")
        ));

        cases.add(new ApertureTestCase(
            "Macro Thermal - Thin Gap",
            "Thermal with thin gap",
            createMacroGerber("%AMThermalThin*7,0,0,2.0,1.4,0.15,0*%", "ThermalThin")
        ));

        cases.add(new ApertureTestCase(
            "Macro Thermal - Wide Gap",
            "Thermal with wide gap",
            createMacroGerber("%AMThermalWide*7,0,0,2.0,1.0,0.5,0*%", "ThermalWide")
        ));

        // Complex Macros (combining primitives)
        cases.add(new ApertureTestCase(
            "Macro Complex - Target",
            "Target: circle with crosshairs",
            createMacroGerber("%AMTarget*1,1,1.5,0,0*21,0,0.15,2.0,0,0,0*21,0,2.0,0.15,0,0,0*%", "Target")
        ));

        cases.add(new ApertureTestCase(
            "Macro Complex - Donut",
            "Donut: circle with hole (using exposure off)",
            createMacroGerber("%AMDonut*1,1,2.0,0,0*1,0,1.0,0,0*%", "Donut")
        ));

        cases.add(new ApertureTestCase(
            "Macro Complex - Square with Hole",
            "Square with round hole (spec example)",
            createMacroGerber("%AMSquareHole*21,1,2.5,2.5,0,0,0*1,0,1.2,0,0*%", "SquareHole")
        ));

        cases.add(new ApertureTestCase(
            "Macro Complex - Rounded Rect",
            "Rectangle with rounded corners",
            createMacroGerber("%AMRoundRect*21,1,2.0,1.0,0,0,0*1,1,0.5,-0.75,0.25*1,1,0.5,0.75,0.25*1,1,0.5,0.75,-0.25*1,1,0.5,-0.75,-0.25*%", "RoundRect")
        ));

        // ===== LINEAR PLOTTING (D01) =====
        cases.add(new ApertureTestCase(
            "Plot Linear - Horizontal",
            "Horizontal line using D01",
            createPlotGerber("%ADD10C,0.2*%", "G01*\nD10*\nX0Y0D02*\nX3000000Y0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Linear - Vertical",
            "Vertical line using D01",
            createPlotGerber("%ADD10C,0.2*%", "G01*\nD10*\nX0Y0D02*\nX0Y3000000D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Linear - Diagonal",
            "Diagonal line (45 degrees)",
            createPlotGerber("%ADD10C,0.2*%", "G01*\nD10*\nX0Y0D02*\nX2000000Y2000000D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Linear - Rectangle Path",
            "Rectangle drawn with D01",
            createPlotGerber("%ADD10C,0.15*%", "G01*\nD10*\nX0Y0D02*\nX2000000Y0D01*\nX2000000Y1500000D01*\nX0Y1500000D01*\nX0Y0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Linear - Triangle",
            "Triangle path",
            createPlotGerber("%ADD10C,0.15*%", "G01*\nD10*\nX0Y0D02*\nX2000000Y0D01*\nX1000000Y1500000D01*\nX0Y0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Linear - Zigzag",
            "Zigzag pattern",
            createPlotGerber("%ADD10C,0.1*%", "G01*\nD10*\nX0Y0D02*\nX500000Y1000000D01*\nX1000000Y0D01*\nX1500000Y1000000D01*\nX2000000Y0D01*\nX2500000Y1000000D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Linear - Rectangle Aperture",
            "Line with rectangular aperture",
            createPlotGerber("%ADD10R,0.3X0.15*%", "G01*\nD10*\nX0Y0D02*\nX2500000Y0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Linear - Multi-segment",
            "Multiple connected segments",
            createPlotGerber("%ADD10C,0.12*%", "G01*\nD10*\nX0Y0D02*\nX1000000Y500000D01*\nX2000000Y0D01*\nX2000000Y1500000D01*\nX1000000Y1000000D01*\nX0Y1500000D01*")
        ));

        // ===== CIRCULAR PLOTTING - CLOCKWISE (G02) =====
        cases.add(new ApertureTestCase(
            "Plot Arc CW - Quarter Circle",
            "90° clockwise arc (G02)",
            createPlotGerber("%ADD10C,0.15*%", "G75*\nD10*\nX0Y2000000D02*\nG02*\nX2000000Y0I0J-2000000D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Arc CW - Half Circle",
            "180° clockwise arc",
            createPlotGerber("%ADD10C,0.15*%", "G75*\nD10*\nX2000000Y1000000D02*\nG02*\nX0Y1000000I-1000000J0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Arc CW - Full Circle",
            "360° full circle clockwise",
            createPlotGerber("%ADD10C,0.15*%", "G75*\nD10*\nX2000000Y1000000D02*\nG02*\nX2000000Y1000000I-1000000J0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Arc CW - Small Arc",
            "45° clockwise arc",
            createPlotGerber("%ADD10C,0.12*%", "G75*\nD10*\nX2000000Y0D02*\nG02*\nX1414214Y-1414214I-2000000J0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Arc CW - Large Radius",
            "Arc with larger radius",
            createPlotGerber("%ADD10C,0.1*%", "G75*\nD10*\nX0Y1500000D02*\nG02*\nX1500000Y0I0J-1500000D01*")
        ));

        // ===== CIRCULAR PLOTTING - COUNTER-CLOCKWISE (G03) =====
        cases.add(new ApertureTestCase(
            "Plot Arc CCW - Quarter Circle",
            "90° counter-clockwise arc (G03)",
            createPlotGerber("%ADD10C,0.15*%", "G75*\nD10*\nX2000000Y0D02*\nG03*\nX0Y2000000I-2000000J0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Arc CCW - Half Circle",
            "180° counter-clockwise arc",
            createPlotGerber("%ADD10C,0.15*%", "G75*\nD10*\nX0Y1000000D02*\nG03*\nX2000000Y1000000I1000000J0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Arc CCW - Full Circle",
            "360° full circle counter-clockwise",
            createPlotGerber("%ADD10C,0.15*%", "G75*\nD10*\nX0Y1000000D02*\nG03*\nX0Y1000000I1000000J0D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Arc CCW - 270° Arc",
            "270° counter-clockwise arc",
            createPlotGerber("%ADD10C,0.12*%", "G75*\nD10*\nX1000000Y2000000D02*\nG03*\nX2000000Y1000000I0J-1000000D01*")
        ));

        // ===== COMBINED LINEAR AND CIRCULAR =====
        cases.add(new ApertureTestCase(
            "Plot Combined - Line + Arc",
            "Line followed by arc",
            createPlotGerber("%ADD10C,0.12*%", "G75*\nD10*\nX0Y0D02*\nG01*\nX1500000Y0D01*\nG02*\nX2500000Y1000000I0J1000000D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Combined - Arc + Line",
            "Arc followed by line",
            createPlotGerber("%ADD10C,0.12*%", "G75*\nD10*\nX1000000Y0D02*\nG02*\nX2000000Y1000000I0J1000000D01*\nG01*\nX2000000Y2000000D01*")
        ));

        cases.add(new ApertureTestCase(
            "Plot Combined - Rounded Rectangle",
            "Rectangle with rounded corners",
            createPlotGerber("%ADD10C,0.1*%",
                "G75*\nD10*\n" +
                "X500000Y0D02*\n" +        // Start
                "G01*\nX2000000Y0D01*\n" +  // Bottom edge
                "G03*\nX2500000Y500000I0J500000D01*\n" +  // Bottom-right corner (CCW for inward curve)
                "G01*\nX2500000Y1500000D01*\n" +  // Right edge
                "G03*\nX2000000Y2000000I-500000J0D01*\n" +  // Top-right corner (CCW for inward curve)
                "G01*\nX500000Y2000000D01*\n" +  // Top edge
                "G03*\nX0Y1500000I0J-500000D01*\n" +  // Top-left corner (CCW for inward curve)
                "G01*\nX0Y500000D01*\n" +  // Left edge
                "G03*\nX500000Y0I500000J0D01*")  // Bottom-left corner (CCW for inward curve)
        ));

        cases.add(new ApertureTestCase(
            "Plot Combined - S-Curve",
            "S-shaped curve using two arcs",
            createPlotGerber("%ADD10C,0.12*%",
                "G75*\nD10*\nX0Y0D02*\n" +
                "G03*\nX1000000Y1000000I1000000J0D01*\n" +  // First arc CCW
                "G02*\nX2000000Y2000000I1000000J0D01*")     // Second arc CW
        ));

        cases.add(new ApertureTestCase(
            "Plot Combined - Sine Wave",
            "Approximated sine wave pattern",
            createPlotGerber("%ADD10C,0.08*%",
                "G75*\nD10*\nX0Y1000000D02*\n" +
                "G02*\nX500000Y1500000I500000J0D01*\n" +      // CW 90° (left→up around center)
                "G02*\nX1000000Y1000000I0J-500000D01*\n" +    // CW 90° (up→right around same center)
                "G03*\nX1500000Y500000I500000J0D01*\n" +      // CCW 90° (left→down around center)
                "G03*\nX2000000Y1000000I0J500000D01*\n" +     // CCW 90° (down→right around same center)
                "G02*\nX2500000Y1500000I500000J0D01*")        // CW 90° (left→up around center)
        ));

        cases.add(new ApertureTestCase(
            "Plot Combined - Circle in Square",
            "Square with circle inside",
            createPlotGerber("%ADD10C,0.08*%",
                "G75*\nD10*\n" +
                // Square
                "X0Y0D02*\nG01*\n" +
                "X2500000Y0D01*\nX2500000Y2500000D01*\nX0Y2500000D01*\nX0Y0D01*\n" +
                // Circle inside
                "X2000000Y1250000D02*\n" +
                "G02*\nX2000000Y1250000I-750000J0D01*")
        ));

        // ===== REGIONS (G36/G37) =====
        cases.add(new ApertureTestCase(
            "Region - Simple Square",
            "Basic square region (G36/G37)",
            createRegionGerber(
                "G36*\n" +
                "X0Y0D02*\nG01*\n" +
                "X2000000Y0D01*\n" +
                "X2000000Y2000000D01*\n" +
                "X0Y2000000D01*\n" +
                "X0Y0D01*\n" +
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Region - Triangle",
            "Triangular region",
            createRegionGerber(
                "G36*\n" +
                "X0Y0D02*\nG01*\n" +
                "X2000000Y0D01*\n" +
                "X1000000Y1700000D01*\n" +
                "X0Y0D01*\n" +
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Region - Pentagon",
            "Five-sided region",
            createRegionGerber(
                "G36*\n" +
                "X1000000Y0D02*\nG01*\n" +
                "X2000000Y700000D01*\n" +
                "X1600000Y1800000D01*\n" +
                "X400000Y1800000D01*\n" +
                "X0Y700000D01*\n" +
                "X1000000Y0D01*\n" +
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Region - Arrow Shape",
            "Arrow-shaped region",
            createRegionGerber(
                "G36*\n" +
                "X0Y700000D02*\nG01*\n" +
                "X1500000Y700000D01*\n" +
                "X1500000Y0D01*\n" +
                "X2500000Y1000000D01*\n" +
                "X1500000Y2000000D01*\n" +
                "X1500000Y1300000D01*\n" +
                "X0Y1300000D01*\n" +
                "X0Y700000D01*\n" +
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Region - L-Shape",
            "L-shaped region",
            createRegionGerber(
                "G36*\n" +
                "X0Y0D02*\nG01*\n" +
                "X800000Y0D01*\n" +
                "X800000Y1200000D01*\n" +
                "X2000000Y1200000D01*\n" +
                "X2000000Y2000000D01*\n" +
                "X0Y2000000D01*\n" +
                "X0Y0D01*\n" +
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Region - With Arc",
            "Region with circular arc segment",
            createRegionGerber(
                "G75*\nG36*\n" +
                "X0Y1000000D02*\nG01*\n" +
                "X1000000Y1000000D01*\n" +
                "G03*\nX2000000Y0I0J-1000000D01*\n" +  // Quarter arc CCW
                "G01*\nX0Y0D01*\n" +
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Region - Semicircle",
            "Semicircular region",
            createRegionGerber(
                "G75*\nG36*\n" +
                "X0Y1000000D02*\nG01*\n" +
                "X2000000Y1000000D01*\n" +
                "G03*\nX0Y1000000I-1000000J0D01*\n" +  // Half circle arc CCW
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Region - Rounded Rect",
            "Rectangle with rounded corners (region)",
            createRegionGerber(
                "G75*\nG36*\n" +
                "X400000Y0D02*\nG01*\n" +
                "X1600000Y0D01*\n" +
                "G03*\nX2000000Y400000I0J400000D01*\n" +  // CCW for 90° inward corner
                "G01*\nX2000000Y1600000D01*\n" +
                "G03*\nX1600000Y2000000I-400000J0D01*\n" +  // CCW for 90° inward corner
                "G01*\nX400000Y2000000D01*\n" +
                "G03*\nX0Y1600000I0J-400000D01*\n" +  // CCW for 90° inward corner
                "G01*\nX0Y400000D01*\n" +
                "G03*\nX400000Y0I400000J0D01*\n" +  // CCW for 90° inward corner
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Region - Two Contours",
            "Two separate contours in one region",
            createRegionGerber(
                "G36*\n" +
                // First contour
                "X0Y0D02*\nG01*\n" +
                "X800000Y0D01*\nX800000Y800000D01*\nX0Y800000D01*\nX0Y0D01*\n" +
                // Second contour
                "X1200000Y0D02*\n" +
                "X2000000Y0D01*\nX2000000Y800000D01*\nX1200000Y800000D01*\nX1200000Y0D01*\n" +
                "G37*")
        ));

        // ===== POLARITY (LP) =====
        cases.add(new ApertureTestCase(
            "Polarity - Dark Square",
            "Dark polarity square (default)",
            createPolarityGerber(
                "%LPD*%\n" +
                "G36*\n" +
                "X0Y0D02*\nG01*\n" +
                "X2000000Y0D01*\nX2000000Y2000000D01*\nX0Y2000000D01*\nX0Y0D01*\n" +
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Polarity - Clear Circle Hole",
            "Dark square with clear circle (hole)",
            createPolarityGerber(
                "%LPD*%\n" +
                "G75*\nG36*\n" +
                "X0Y0D02*\nG01*\n" +
                "X2500000Y0D01*\nX2500000Y2500000D01*\nX0Y2500000D01*\nX0Y0D01*\n" +
                "G37*\n" +
                "%LPC*%\n" +  // Clear polarity
                "G36*\n" +
                "X1750000Y1250000D02*\n" +
                "G03*\nX1750000Y1250000I-500000J0D01*\n" +  // Clear circle
                "G37*")
        ));

        cases.add(new ApertureTestCase(
            "Polarity - Nested Rings",
            "Concentric rings with alternating polarity",
            createPolarityGerber(
                "%LPD*%\nG75*\n" +
                // Outer circle (dark)
                "G36*\nX2000000Y1000000D02*\n" +
                "G03*\nX2000000Y1000000I-1000000J0D01*\nG37*\n" +
                // Middle ring (clear)
                "%LPC*%\nG36*\nX1700000Y1000000D02*\n" +
                "G03*\nX1700000Y1000000I-700000J0D01*\nG37*\n" +
                // Inner circle (dark again)
                "%LPD*%\nG36*\nX1400000Y1000000D02*\n" +
                "G03*\nX1400000Y1000000I-400000J0D01*\nG37*")
        ));

        cases.add(new ApertureTestCase(
            "Polarity - Square with Square Hole",
            "Square with square hole using clear polarity",
            createPolarityGerber(
                "%LPD*%\n" +
                "G36*\nX0Y0D02*\nG01*\n" +
                "X2500000Y0D01*\nX2500000Y2500000D01*\nX0Y2500000D01*\nX0Y0D01*\nG37*\n" +
                "%LPC*%\n" +  // Clear polarity
                "G36*\nX750000Y750000D02*\n" +
                "X1750000Y750000D01*\nX1750000Y1750000D01*\nX750000Y1750000D01*\nX750000Y750000D01*\nG37*")
        ));

        cases.add(new ApertureTestCase(
            "Polarity - Cross Cutout",
            "Square with cross-shaped cutout",
            createPolarityGerber(
                "%LPD*%\n" +
                "G36*\nX0Y0D02*\nG01*\n" +
                "X2500000Y0D01*\nX2500000Y2500000D01*\nX0Y2500000D01*\nX0Y0D01*\nG37*\n" +
                "%LPC*%\n" +  // Clear vertical bar
                "G36*\nX1000000Y0D02*\n" +
                "X1500000Y0D01*\nX1500000Y2500000D01*\nX1000000Y2500000D01*\nX1000000Y0D01*\nG37*\n" +
                "G36*\nX0Y1000000D02*\n" +  // Clear horizontal bar
                "X2500000Y1000000D01*\nX2500000Y1500000D01*\nX0Y1500000D01*\nX0Y1000000D01*\nG37*")
        ));

        cases.add(new ApertureTestCase(
            "Polarity - Dark in Clear",
            "Clear square, dark circle inside",
            createPolarityGerber(
                // Start with a dark background
                "%LPD*%\nG75*\n" +
                "G36*\nX0Y0D02*\nG01*\n" +
                "X3000000Y0D01*\nX3000000Y2500000D01*\nX0Y2500000D01*\nX0Y0D01*\nG37*\n" +
                // Clear rectangle
                "%LPC*%\n" +
                "G36*\nX250000Y250000D02*\n" +
                "X2750000Y250000D01*\nX2750000Y2250000D01*\nX250000Y2250000D01*\nX250000Y250000D01*\nG37*\n" +
                // Dark circle inside
                "%LPD*%\n" +
                "G36*\nX2250000Y1250000D02*\n" +
                "G03*\nX2250000Y1250000I-750000J0D01*\nG37*")
        ));

        // ===== APERTURE TRANSFORMATIONS =====

        // Load Rotation (LR)
        cases.add(new ApertureTestCase(
            "Transform LR - Rectangle 0°",
            "Rectangle aperture, no rotation (reference)",
            createTransformGerber("%ADD10R,2.0X0.5*%",
                "%LPD*%\n%LR0*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LR - Rectangle 45°",
            "Rectangle aperture rotated 45°",
            createTransformGerber("%ADD10R,2.0X0.5*%",
                "%LPD*%\n%LR45*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LR - Rectangle 90°",
            "Rectangle aperture rotated 90°",
            createTransformGerber("%ADD10R,2.0X0.5*%",
                "%LPD*%\n%LR90*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LR - Rectangle 30°",
            "Rectangle aperture rotated 30°",
            createTransformGerber("%ADD10R,1.5X0.6*%",
                "%LPD*%\n%LR30*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LR - Polygon Rotated",
            "Hexagon rotated 45° using LR",
            createTransformGerber("%ADD10P,2.0X6*%",
                "%LPD*%\n%LR45*%\nD10*\nX1000000Y1000000D03*")
        ));

        // Load Scaling (LS)
        cases.add(new ApertureTestCase(
            "Transform LS - Circle 1.0x",
            "Circle aperture, scale 1.0 (reference)",
            createTransformGerber("%ADD10C,1.0*%",
                "%LPD*%\n%LS1.0*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LS - Circle 0.5x",
            "Circle aperture scaled to 50%",
            createTransformGerber("%ADD10C,1.0*%",
                "%LPD*%\n%LS0.5*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LS - Circle 1.5x",
            "Circle aperture scaled to 150%",
            createTransformGerber("%ADD10C,1.0*%",
                "%LPD*%\n%LS1.5*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LS - Rectangle 2.0x",
            "Rectangle aperture scaled to 200%",
            createTransformGerber("%ADD10R,1.0X0.5*%",
                "%LPD*%\n%LS2.0*%\nD10*\nX1000000Y1000000D03*")
        ));

        // Load Mirroring (LM)
        cases.add(new ApertureTestCase(
            "Transform LM - No Mirror",
            "L-shape aperture, no mirroring (reference)",
            createTransformGerber(
                "%AMLShape*21,1,1.0,0.3,0.15,0,0*21,1,0.3,1.0,-0.35,0.35,0*%\n%ADD10LShape*%",
                "%LPD*%\n%LMN*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LM - Mirror X",
            "L-shape mirrored along X axis",
            createTransformGerber(
                "%AMLShape*21,1,1.0,0.3,0.15,0,0*21,1,0.3,1.0,-0.35,0.35,0*%\n%ADD10LShape*%",
                "%LPD*%\n%LMX*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LM - Mirror Y",
            "L-shape mirrored along Y axis",
            createTransformGerber(
                "%AMLShape*21,1,1.0,0.3,0.15,0,0*21,1,0.3,1.0,-0.35,0.35,0*%\n%ADD10LShape*%",
                "%LPD*%\n%LMY*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform LM - Mirror XY",
            "L-shape mirrored along both axes",
            createTransformGerber(
                "%AMLShape*21,1,1.0,0.3,0.15,0,0*21,1,0.3,1.0,-0.35,0.35,0*%\n%ADD10LShape*%",
                "%LPD*%\n%LMXY*%\nD10*\nX1000000Y1000000D03*")
        ));

        // Combined Transformations
        cases.add(new ApertureTestCase(
            "Transform Combined - Rotate + Scale",
            "Rectangle rotated 45° and scaled 1.5x",
            createTransformGerber("%ADD10R,1.5X0.5*%",
                "%LPD*%\n%LR45*%\n%LS1.5*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform Combined - Mirror + Rotate",
            "L-shape mirrored X and rotated 90°",
            createTransformGerber(
                "%AMLShape*21,1,1.0,0.3,0.15,0,0*21,1,0.3,1.0,-0.35,0.35,0*%\n%ADD10LShape*%",
                "%LPD*%\n%LMX*%\n%LR90*%\nD10*\nX1000000Y1000000D03*")
        ));

        cases.add(new ApertureTestCase(
            "Transform - Draw with Scaled Aperture",
            "Line drawn with scaled circular aperture",
            createTransformGerber("%ADD10C,0.3*%",
                "%LPD*%\n%LS2.0*%\nD10*\nG01*\nX0Y0D02*\nX2000000Y1000000D01*")
        ));

        cases.add(new ApertureTestCase(
            "Transform - Multiple Flashes Rotated",
            "Multiple flashes with increasing rotation",
            createTransformGerber("%ADD10R,1.2X0.4*%",
                "%LPD*%\nD10*\n" +
                "%LR0*%\nX500000Y1000000D03*\n" +
                "%LR30*%\nX1250000Y1000000D03*\n" +
                "%LR60*%\nX2000000Y1000000D03*\n" +
                "%LR90*%\nX2750000Y1000000D03*")
        ));

        return cases;
    }

    private String createMacroGerber(String macroDef, String macroName) {
        return String.format("""
            G04 Macro Aperture Test*
            %%FSLAX26Y26*%%
            %%MOMM*%%
            %s
            %%ADD10%s*%%
            D10*
            X0Y0D03*
            M02*
            """, macroDef, macroName);
    }

    private String createPlotGerber(String apertureDef, String plotCommands) {
        return String.format("""
            G04 Plot Test*
            %%FSLAX26Y26*%%
            %%MOMM*%%
            %s
            %s
            M02*
            """, apertureDef, plotCommands);
    }

    private String createRegionGerber(String regionCommands) {
        return String.format("""
            G04 Region Test*
            %%FSLAX26Y26*%%
            %%MOMM*%%
            %%LPD*%%
            %s
            M02*
            """, regionCommands);
    }

    private String createPolarityGerber(String commands) {
        return String.format("""
            G04 Polarity Test*
            %%FSLAX26Y26*%%
            %%MOMM*%%
            %s
            M02*
            """, commands);
    }

    private String createTransformGerber(String apertureDef, String commands) {
        return String.format("""
            G04 Transform Test*
            %%FSLAX26Y26*%%
            %%MOMM*%%
            %s
            %s
            M02*
            """, apertureDef, commands);
    }

    private String createGerber(String apertureDef, int dCode) {
        return String.format("""
            G04 Aperture Test*
            %%FSLAX26Y26*%%
            %%MOMM*%%
            %s
            D%d*
            X0Y0D03*
            M02*
            """, apertureDef, dCode);
    }

    private String extractApertureLine(String gerber) {
        StringBuilder result = new StringBuilder();
        for (String line : gerber.split("\n")) {
            String trimmed = line.trim();
            // Capture aperture definitions
            if (trimmed.contains("%ADD") || trimmed.contains("%AM")) {
                if (result.length() > 0) result.append("\n");
                result.append(trimmed);
            }
            // Capture key commands for regions
            else if (trimmed.startsWith("G36") || trimmed.startsWith("G37")) {
                if (result.length() == 0) {
                    result.append(trimmed);
                }
            }
            // Capture polarity commands
            else if (trimmed.contains("%LP")) {
                if (result.length() > 0) result.append(" ");
                result.append(trimmed);
            }
            // Capture transformation commands
            else if (trimmed.contains("%LR") || trimmed.contains("%LS") || trimmed.contains("%LM")) {
                if (result.length() > 0) result.append(" ");
                result.append(trimmed);
            }
        }
        // If still empty, try to find first meaningful command
        if (result.length() == 0) {
            for (String line : gerber.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("G01") || trimmed.startsWith("G02") ||
                    trimmed.startsWith("G03") || trimmed.startsWith("G75")) {
                    return trimmed;
                }
            }
        }
        return result.toString();
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
