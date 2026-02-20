# Java Gerber/NC Drill Parser Implementation Plan

## Executive Summary

This document outlines a comprehensive plan to implement a Gerber (RS-274X / X2 / X3) and Excellon NC drill file parser in Java, with SVG export capabilities. The implementation builds upon the existing `GerberService.java` layer detection code and draws inspiration from tracespace (JavaScript) and pygerber (Python) reference implementations.

## 1. Project Overview

### 1.1 Goals
1. **Parse Gerber files** (RS-274X, Gerber X2, X3) to extract manufacturing data
2. **Parse NC drill files** (Excellon/XNC format) for drill hole information
3. **Generate SVG output** to visualize all gerber layers
4. **Extract manufacturing data** for PCB fabrication specifications
5. **Maintain backwards compatibility** with existing `PcbSpecificationFile` data model

### 1.2 Reference Implementations
- **tracespace** (JavaScript): Streaming parser with regex-based command parsing
- **pygerber** (Python): AST-based parser with visitor pattern and VM renderer

### 1.3 Specification Documents
- Gerber Layer Format Specification (Revision 2024.05) - see `docs/gerber-spec-chapters/`
- NC Drill Format (Excellon) - see Wikipedia reference in `docs/SPECS.md`

---

## 2. Architecture

### 2.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         GerberParser                            │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐   ┌──────────────┐   ┌───────────────────────┐ │
│  │   Lexer     │──▶│   Parser     │──▶│  Command Processor    │ │
│  │ (Tokenizer) │   │ (AST Builder)│   │  (Graphics State)     │ │
│  └─────────────┘   └──────────────┘   └───────────────────────┘ │
│                                                │                │
│                    ┌───────────────────────────┘                │
│                    ▼                                            │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │              Graphical Objects Stream                       ││
│  │   (Draws, Arcs, Flashes, Regions with Attributes)          ││
│  └─────────────────────────────────────────────────────────────┘│
│                                │                                │
│           ┌────────────────────┼────────────────────┐           │
│           ▼                    ▼                    ▼           │
│  ┌─────────────────┐  ┌───────────────┐  ┌───────────────────┐ │
│  │  SVG Renderer   │  │ Data Extractor │  │ Bounds Calculator │ │
│  └─────────────────┘  └───────────────┘  └───────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Detailed Module Structure

```
com.deltaproto.deltagerber/
├── lexer/
│   ├── GerberLexer.java           # Tokenizes Gerber files
│   ├── ExcellonLexer.java         # Tokenizes Excellon files
│   ├── Token.java                 # Token representation
│   └── TokenType.java             # Token type enumeration
│
├── parser/
│   ├── GerberParser.java          # Parses Gerber tokens to AST
│   ├── ExcellonParser.java        # Parses Excellon tokens to AST
│   ├── CoordinateParser.java      # Handles coordinate format
│   ├── ApertureMacroParser.java   # Parses AM command macros
│   └── ParserException.java       # Parser error handling
│
├── model/
│   ├── gerber/
│   │   ├── GerberDocument.java         # Root document model
│   │   ├── GraphicsState.java          # Current graphics state
│   │   ├── CoordinateFormat.java       # FS command parameters
│   │   ├── Unit.java                   # MM or INCH
│   │   │
│   │   ├── aperture/
│   │   │   ├── Aperture.java           # Base aperture class
│   │   │   ├── CircleAperture.java     # C template
│   │   │   ├── RectangleAperture.java  # R template
│   │   │   ├── ObroundAperture.java    # O template
│   │   │   ├── PolygonAperture.java    # P template
│   │   │   ├── MacroAperture.java      # AM template instance
│   │   │   └── BlockAperture.java      # AB block aperture
│   │   │
│   │   ├── macro/
│   │   │   ├── ApertureMacro.java      # Macro definition
│   │   │   ├── MacroPrimitive.java     # Base primitive
│   │   │   ├── CirclePrimitive.java    # Code 1
│   │   │   ├── VectorLinePrimitive.java # Code 20
│   │   │   ├── CenterLinePrimitive.java # Code 21
│   │   │   ├── OutlinePrimitive.java   # Code 4
│   │   │   ├── PolygonPrimitive.java   # Code 5
│   │   │   ├── ThermalPrimitive.java   # Code 7
│   │   │   └── MacroExpression.java    # Variable expressions
│   │   │
│   │   ├── operation/
│   │   │   ├── GraphicsObject.java     # Base graphics object
│   │   │   ├── Flash.java              # D03 flash operation
│   │   │   ├── Draw.java               # D01 linear draw
│   │   │   ├── Arc.java                # D01 circular arc
│   │   │   ├── Region.java             # G36/G37 region
│   │   │   └── Contour.java            # Region contour
│   │   │
│   │   └── attribute/
│   │       ├── FileAttribute.java      # TF attributes
│   │       ├── ApertureAttribute.java  # TA attributes
│   │       ├── ObjectAttribute.java    # TO attributes
│   │       └── StandardAttributes.java # .FileFunction, .Part, etc.
│   │
│   └── drill/
│       ├── DrillDocument.java          # Root drill document
│       ├── ToolDefinition.java         # Tool number + diameter
│       ├── DrillHit.java               # Single drill coordinate
│       ├── DrillSlot.java              # G85 routed slot
│       └── RoutePath.java              # G00-G03 routing
│
├── renderer/
│   ├── svg/
│   │   ├── SVGRenderer.java            # Main SVG renderer
│   │   ├── SVGDocument.java            # SVG DOM builder
│   │   ├── ApertureSVGGenerator.java   # Aperture to SVG defs
│   │   ├── PathBuilder.java            # SVG path d= builder
│   │   ├── TransformHelper.java        # SVG transform attrs
│   │   └── ColorScheme.java            # Layer color mapping
│   │
│   └── stackup/
│       ├── StackupRenderer.java        # Multi-layer composite
│       ├── LayerCompositor.java        # Layer ordering/masking
│       └── BoardPreview.java           # Top/bottom view gen
│
├── extraction/
│   ├── ManufacturingDataExtractor.java # Extract fab data
│   ├── BoundingBoxCalculator.java      # Calculate dimensions
│   ├── ApertureAnalyzer.java           # Track width analysis
│   └── NetlistExtractor.java           # Extract net info (X2)
│
├── util/
│   ├── CoordinateUtils.java            # Coordinate math
│   ├── ArcUtils.java                   # Arc calculations
│   ├── PolygonUtils.java               # Polygon operations
│   └── UnitConverter.java              # Unit conversions
│
└── GerberProcessor.java                # High-level API facade
```

---

## 3. Implementation Phases

### Phase 1: Core Parser Infrastructure

**Goal**: Parse basic Gerber commands and build AST

**Tasks**:
1. Implement `GerberLexer` to tokenize Gerber files
   - Handle word commands (ending with `*`)
   - Handle extended commands (`%...%`)
   - Handle comments (`G04`)

2. Implement basic `GerberParser`
   - Parse FS (Format Specification)
   - Parse MO (Unit Mode)
   - Parse AD (Aperture Definition) for standard apertures
   - Parse D01/D02/D03 operations
   - Parse G01/G02/G03/G75 plot modes
   - Parse M02 (End of file)

3. Create AST node classes for all commands

**Reference**: `docs/gerber-spec-chapters/04-syntax/README.md`

### Phase 2: Graphics Processing

**Goal**: Convert AST to graphical objects stream

**Tasks**:
1. Implement `GraphicsState` class
   - Track current point
   - Track current aperture
   - Track plot mode (linear/CW/CCW)
   - Track polarity, mirroring, rotation, scaling

2. Implement `CommandProcessor`
   - Process operations to create graphical objects
   - Handle aperture transformations (LP, LM, LR, LS)

3. Implement aperture classes
   - Standard apertures (C, R, O, P)
   - Aperture with holes

**Reference**:
- `docs/gerber-spec-chapters/03-overview/README.md` (Section 2.7 Graphics State)
- `docs/gerber-spec-chapters/05-graphics/README.md`

### Phase 3: Advanced Gerber Features

**Goal**: Support regions, blocks, macros, and attributes

**Tasks**:
1. Implement region parsing (G36/G37)
   - Contour construction
   - Multi-contour regions

2. Implement aperture macros (AM)
   - Primitive parsing (Circle, Vector Line, Center Line, etc.)
   - Macro variables and expressions
   - Arithmetic expression evaluation

3. Implement block apertures (AB) and step-repeat (SR)

4. Implement attributes (TF, TA, TO, TD)
   - File attributes (.FileFunction, .Part, etc.)
   - Aperture attributes (.AperFunction, .DrillTolerance)
   - Object attributes (.N, .P, .C)

**Reference**:
- `docs/gerber-spec-chapters/05-graphics/README.md` (Sections 4.5, 4.10, 4.11)
- `docs/gerber-spec-chapters/06-attributes/README.md`

### Phase 4: NC Drill Parser

**Goal**: Parse Excellon/XNC drill files

**Tasks**:
1. Implement `DrillLexer`
   - Handle format hints in comments (KiCad, Altium)
   - Handle INCH/METRIC units
   - Handle leading/trailing zero suppression

2. Implement `DrillParser`
   - Tool definitions (T##C##)
   - Coordinate parsing
   - Slot routing (G85)
   - Drill modes (G05, G81)

3. Create drill-specific model classes
   - DrillHole, DrillTool, SlotRoute

**Reference**:
- `docs/tracespace/packages/gerber-parser/lib/_parse-drill.js`
- Wikipedia NC formats documentation

### Phase 5: Manufacturing Data Extraction

**Goal**: Extract data currently parsed by GerberService.java

**Tasks**:
1. Implement layer type detection
   - From filename patterns (Protel, KiCad, Eagle, Altium)
   - From .FileFunction attribute (Gerber X2)

2. Implement board bounds calculation
   - Min/max X/Y coordinates
   - Board size extraction

3. Implement manufacturing analysis
   - Minimum track width detection
   - Minimum drill size detection
   - Layer count determination

**Reference**: `docs/current-gerber-tools/GerberService.java`

### Phase 6: SVG Rendering

**Goal**: Generate SVG output for visualization

**Tasks**:
1. Implement `SvgRenderer`
   - Convert graphical objects to SVG elements
   - Handle polarity (dark/clear) with masking
   - Support all aperture shapes

2. Implement layer styling
   - Color scheme per layer type
   - Configurable opacity

3. Implement multi-layer view
   - Stack layers with proper z-order
   - Toggle layer visibility

**Reference**:
- `docs/tracespace/packages/gerber-to-svg/`
- Ucamco reference viewer: https://gerber-viewer.ucamco.com

---

## 4. Key Implementation Details

### 4.1 Coordinate Handling

From Format Specification (FS command):
```
%FSLAX26Y26*%
```
- L = Leading zeros omitted
- A = Absolute coordinates
- X26 = 2 integer digits, 6 decimal digits
- Y26 = Same for Y

**Implementation**:
```java
public class CoordinateFormat {
    private boolean leadingZeroOmit;
    private boolean absolute;
    private int integerDigits;
    private int decimalDigits;

    public double parse(String value) {
        // Handle zero suppression
        // Convert to actual coordinate value
    }
}
```

### 4.2 Aperture Macro Expression Parser

Macro apertures support arithmetic expressions:
```
%AMDONUT*1,1,$1,$2,$3*1,0,$4,$2,$3*%
```

Need expression evaluator for:
- Variables ($1, $2, etc.)
- Arithmetic (+, -, *, /)
- Comparison operators (for exposure)

### 4.3 Region Fill Algorithm

Regions are defined by contours. For SVG output:
- Use SVG path with fill-rule="evenodd"
- Handle holes with inner contours wound opposite direction

### 4.4 Polarity Handling in SVG

Dark/Clear polarity requires compositing:
```xml
<defs>
    <mask id="clearMask">
        <!-- dark objects as white, clear as black -->
    </mask>
</defs>
<g mask="url(#clearMask)">
    <!-- content -->
</g>
```

---

## 5. Testing Strategy

### 5.1 Unit Tests
- Parser tests for each command type
- Coordinate conversion tests
- Aperture macro evaluation tests
- Graphics state transition tests

### 5.2 Integration Tests
- Parse sample Gerber files from pygerber test assets
- Compare output with Ucamco reference viewer
- Validate SVG output renders correctly

### 5.3 Test Files
- Use test files from `docs/pygerber-main/test/`
- Create test files for edge cases

---

## 6. Dependencies

### Required
- Java 11+ (for modern features)
- No external dependencies for core parser

### Optional
- Apache Batik (SVG manipulation)
- JUnit 5 (testing)
- Lombok (boilerplate reduction)

---

## 7. Output Specifications

### 7.1 Manufacturing Data Structure

```java
public class ManufacturingData {
    // Board dimensions
    private double boardWidth;      // mm
    private double boardHeight;     // mm
    private double minX, minY, maxX, maxY;

    // Layer information
    private List<LayerInfo> layers;
    private int copperLayerCount;

    // Manufacturing constraints
    private double minTrackWidth;   // mm
    private double minDrillSize;    // mm
    private double minAnnularRing;  // mm

    // Drill information
    private List<DrillHole> drillHoles;
    private Map<Double, Integer> drillSizeCount;

    // Component information (if X2 attributes present)
    private List<ComponentPlacement> components;
}
```

### 7.2 SVG Output

```xml
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg"
     viewBox="0 0 100 100"
     width="100mm" height="100mm">

    <!-- Layer groups -->
    <g id="top-copper" class="copper" fill="#c87533">
        <!-- Flashes, draws, regions -->
    </g>

    <g id="top-soldermask" class="soldermask" fill="#004200" opacity="0.7">
        <!-- Soldermask openings -->
    </g>

    <!-- etc. -->
</svg>
```

---

## 8. Migration from Current Implementation

### Current Data Extracted (GerberService.java)
- [x] Layer type detection (filename patterns)
- [x] File function attribute (.FileFunction)
- [x] Generation software attribute
- [x] Board size (min/max coordinates)
- [x] Minimum track width
- [x] Minimum drill size
- [x] SVG generation (current implementation)

### New Capabilities
- [ ] Full AST representation
- [ ] Complete attribute parsing (X2/X3)
- [ ] Aperture macro support
- [ ] Region support
- [ ] Block apertures
- [ ] Step and repeat
- [ ] Proper arc handling
- [ ] Multi-layer SVG with correct stacking
- [ ] Component placement data extraction

---

## 9. File Format Quick Reference

### Gerber Commands

| Command | Description | Example |
|---------|-------------|---------|
| FS | Format specification | `%FSLAX26Y26*%` |
| MO | Unit mode | `%MOMM*%` |
| AD | Aperture define | `%ADD10C,0.5*%` |
| AM | Aperture macro | `%AMCIRC*1,1,$1,0,0*%` |
| D10+ | Select aperture | `D10*` |
| D01 | Plot (draw/arc) | `X100Y100D01*` |
| D02 | Move | `X100Y100D02*` |
| D03 | Flash | `X100Y100D03*` |
| G01 | Linear mode | `G01*` |
| G02 | CW circular mode | `G02*` |
| G03 | CCW circular mode | `G03*` |
| G36/37 | Region start/end | `G36*...G37*` |
| LP | Load polarity | `%LPD*%` or `%LPC*%` |
| TF | File attribute | `%TF.FileFunction,Copper,L1,Top*%` |
| M02 | End of file | `M02*` |

### NC Drill Commands

| Command | Description | Example |
|---------|-------------|---------|
| T##C## | Tool define | `T01C0.3` |
| T## | Tool select | `T01` |
| X##Y## | Drill coordinate | `X100Y200` |
| G85 | Slot route | `X100Y100G85X200Y100` |
| INCH/METRIC | Unit mode | `METRIC` |
| M30 | End of file | `M30` |

---

## 10. Resources

### Documentation
- `docs/gerber-spec-chapters/` - Gerber specification (converted from PDF)
- `docs/tracespace/` - JavaScript reference implementation
- `docs/pygerber-main/` - Python reference implementation

### Online Tools
- https://gerber-viewer.ucamco.com - Ucamco reference viewer
- https://github.com/Argmaster/pygerber - PyGerber source

### Current Implementation
- `docs/current-gerber-tools/GerberService.java` - Existing parser to migrate from
- `docs/current-gerber-tools/PcbSpecificationFile.java` - Data model

---

## 11. Detailed Code Examples

### 11.1 Coordinate Parser Implementation

Based on the existing `getGerberSize()` in GerberService.java:

```java
public class CoordinateParser {
    private int integerDigits = 2;
    private int decimalDigits = 6;
    private boolean leadingZeroSuppression = true;  // L format
    private boolean absoluteNotation = true;        // A format
    private Unit unit = Unit.MM;

    public void setFormat(String fsCommand) {
        // Parse: %FSLAX26Y26*%
        // L = leading zero suppression (T = trailing, deprecated)
        // A = absolute coordinates (I = incremental)
        // X26 = 2 integer, 6 decimal digits
        Pattern pattern = Pattern.compile("FSLA([XI]?)X(\\d)(\\d)Y\\d\\d");
        Matcher matcher = pattern.matcher(fsCommand);
        if (matcher.find()) {
            absoluteNotation = !"I".equals(matcher.group(1));
            integerDigits = Integer.parseInt(matcher.group(2));
            decimalDigits = Integer.parseInt(matcher.group(3));
        }
    }

    public double parseCoordinate(String value) {
        // Handle sign
        boolean negative = value.startsWith("-");
        if (negative || value.startsWith("+")) {
            value = value.substring(1);
        }

        // With leading zero suppression, pad left to total length
        int totalDigits = integerDigits + decimalDigits;
        while (value.length() < totalDigits) {
            value = "0" + value;
        }

        // Split at decimal point position
        String intPart = value.substring(0, value.length() - decimalDigits);
        String decPart = value.substring(value.length() - decimalDigits);

        double result = Double.parseDouble(intPart + "." + decPart);
        return negative ? -result : result;
    }
}
```

### 11.2 Graphics State Machine

```java
public class GraphicsState {
    // Coordinate system
    private CoordinateParser coordinateParser;
    private Unit unit = Unit.MM;

    // Current position
    private double currentX = 0;
    private double currentY = 0;

    // Plot state
    private PlotMode plotMode = PlotMode.LINEAR;  // G01/G02/G03
    private boolean inRegion = false;              // G36 active
    private Aperture currentAperture;

    // Aperture transformations
    private Polarity polarity = Polarity.DARK;    // LPD/LPC
    private MirrorMode mirror = MirrorMode.NONE;   // LM
    private double rotation = 0;                   // LR
    private double scale = 1.0;                    // LS

    // Attribute dictionaries
    private Map<String, ApertureAttribute> apertureAttributes = new HashMap<>();
    private Map<String, ObjectAttribute> objectAttributes = new HashMap<>();

    public enum PlotMode { LINEAR, CLOCKWISE, COUNTER_CLOCKWISE }
    public enum Polarity { DARK, CLEAR }
    public enum MirrorMode { NONE, X, Y, XY }
}
```

### 11.3 Operation Processing

```java
public class OperationProcessor {
    private GraphicsState state;
    private List<GraphicsObject> objects = new ArrayList<>();
    private List<ContourSegment> currentContour;

    public void process(String command) {
        // Parse coordinates from command like X1000Y2000D01*
        Double x = parseCoord(command, "X");
        Double y = parseCoord(command, "Y");
        Double i = parseCoord(command, "I");
        Double j = parseCoord(command, "J");

        // Modal coordinates: use current if not specified
        x = x != null ? x : state.getCurrentX();
        y = y != null ? y : state.getCurrentY();

        if (command.contains("D01")) {
            handlePlot(x, y, i, j);
        } else if (command.contains("D02")) {
            handleMove(x, y);
        } else if (command.contains("D03")) {
            handleFlash(x, y);
        }

        state.setCurrentX(x);
        state.setCurrentY(y);
    }

    private void handlePlot(double x, double y, Double i, Double j) {
        if (state.isInRegion()) {
            // Add to current contour
            addContourSegment(x, y, i, j);
        } else {
            // Create draw or arc object
            if (state.getPlotMode() == PlotMode.LINEAR) {
                objects.add(new Draw(
                    state.getCurrentX(), state.getCurrentY(), x, y,
                    state.getCurrentAperture(),
                    state.getPolarity()
                ));
            } else {
                objects.add(new Arc(
                    state.getCurrentX(), state.getCurrentY(), x, y,
                    i != null ? i : 0, j != null ? j : 0,
                    state.getPlotMode() == PlotMode.CLOCKWISE,
                    state.getCurrentAperture(),
                    state.getPolarity()
                ));
            }
        }
    }

    private void handleFlash(double x, double y) {
        objects.add(new Flash(
            x, y,
            state.getCurrentAperture(),
            state.getPolarity(),
            state.getMirror(),
            state.getRotation(),
            state.getScale()
        ));
    }
}
```

### 11.4 SVG Renderer Core

```java
public class SVGRenderer {
    private ColorScheme colors;

    public String render(GerberDocument doc) {
        BoundingBox bounds = calculateBounds(doc);

        StringBuilder svg = new StringBuilder();
        svg.append(String.format(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\\n" +
            "<svg xmlns=\"http://www.w3.org/2000/svg\" " +
            "width=\"%fmm\" height=\"%fmm\" " +
            "viewBox=\"%f %f %f %f\">\\n",
            bounds.getWidth(), bounds.getHeight(),
            bounds.getMinX(), -bounds.getMaxY(),  // Flip Y axis
            bounds.getWidth(), bounds.getHeight()
        ));

        // Aperture definitions
        svg.append("  <defs>\\n");
        for (var entry : doc.getApertures().entrySet()) {
            svg.append(renderApertureDef(entry.getKey(), entry.getValue()));
        }
        svg.append("  </defs>\\n");

        // Main content with Y-axis flip
        svg.append("  <g transform=\"scale(1,-1)\">\\n");
        for (GraphicsObject obj : doc.getObjects()) {
            svg.append(renderObject(obj));
        }
        svg.append("  </g>\\n");
        svg.append("</svg>");

        return svg.toString();
    }

    private String renderObject(GraphicsObject obj) {
        if (obj instanceof Flash flash) {
            return String.format(
                "    <use href=\"#ap%d\" x=\"%f\" y=\"%f\" fill=\"%s\"/>\\n",
                flash.getAperture().getDCode(),
                flash.getX(), flash.getY(),
                flash.getPolarity() == Polarity.DARK ? colors.getDark() : colors.getClear()
            );
        } else if (obj instanceof Draw draw) {
            return String.format(
                "    <line x1=\"%f\" y1=\"%f\" x2=\"%f\" y2=\"%f\" " +
                "stroke=\"%s\" stroke-width=\"%f\" stroke-linecap=\"round\"/>\\n",
                draw.getStartX(), draw.getStartY(),
                draw.getEndX(), draw.getEndY(),
                draw.getPolarity() == Polarity.DARK ? colors.getDark() : colors.getClear(),
                ((CircleAperture) draw.getAperture()).getDiameter()
            );
        } else if (obj instanceof Arc arc) {
            return renderArc(arc);
        } else if (obj instanceof Region region) {
            return renderRegion(region);
        }
        return "";
    }

    private String renderArc(Arc arc) {
        // Calculate SVG arc parameters
        double radius = Math.sqrt(arc.getI() * arc.getI() + arc.getJ() * arc.getJ());
        boolean largeArc = isLargeArc(arc);
        int sweep = arc.isClockwise() ? 0 : 1;

        return String.format(
            "    <path d=\"M %f %f A %f %f 0 %d %d %f %f\" " +
            "stroke=\"%s\" stroke-width=\"%f\" stroke-linecap=\"round\" fill=\"none\"/>\\n",
            arc.getStartX(), arc.getStartY(),
            radius, radius, largeArc ? 1 : 0, sweep,
            arc.getEndX(), arc.getEndY(),
            arc.getPolarity() == Polarity.DARK ? colors.getDark() : colors.getClear(),
            ((CircleAperture) arc.getAperture()).getDiameter()
        );
    }

    private String renderRegion(Region region) {
        StringBuilder path = new StringBuilder();
        path.append("    <path d=\"");

        for (Contour contour : region.getContours()) {
            path.append(contour.toSVGPath()).append(" ");
        }

        return path.append("\" fill=\"")
            .append(region.getPolarity() == Polarity.DARK ? colors.getDark() : colors.getClear())
            .append("\" fill-rule=\"evenodd\"/>\\n")
            .toString();
    }
}
```

### 11.5 Excellon Parser Patterns

Based on tracespace's `_parse-drill.js`:

```java
public class ExcellonParser {
    // Format detection from comments
    private static final Pattern RE_ALTIUM_HINT =
        Pattern.compile(";FILE_FORMAT=(\\d):(\\d)");
    private static final Pattern RE_KICAD_HINT =
        Pattern.compile(";FORMAT\\{(.):(.)/\\s*(absolute|.+)?\\s*/\\s*(metric|inch)\\s*/.+(trailing|leading|decimal|keep)");
    private static final Pattern RE_PLATING_HINT =
        Pattern.compile(";TYPE=(PLATED|NON_PLATED)");

    // Command patterns
    private static final Pattern RE_TOOL_DEF =
        Pattern.compile("T0*(\\d+)[\\S]*C([\\d.]+)");
    private static final Pattern RE_TOOL_SET =
        Pattern.compile("T0*(\\d+)(?![\\S]*C)");
    private static final Pattern RE_COORD =
        Pattern.compile("((?:[XYIJA][+-]?[\\d.]+){1,4})(?:G85((?:[XY][+-]?[\\d.]+){1,2}))?");

    public DrillDocument parse(String content) {
        DrillDocument doc = new DrillDocument();

        for (String line : content.split("\\n")) {
            line = line.trim();

            if (line.startsWith(";")) {
                parseFormatHints(line, doc);
            } else if (line.matches("METRIC.*|M71")) {
                doc.setUnit(Unit.MM);
            } else if (line.matches("INCH.*|M72")) {
                doc.setUnit(Unit.INCH);
            } else if (RE_TOOL_DEF.matcher(line).find()) {
                parseToolDef(line, doc);
            } else if (RE_COORD.matcher(line).find()) {
                parseDrillCoord(line, doc);
            }
        }

        return doc;
    }

    private void parseDrillCoord(String line, DrillDocument doc) {
        Matcher m = RE_COORD.matcher(line);
        if (m.find()) {
            String coord1 = m.group(1);
            String coord2 = m.group(2);  // G85 slot end

            DrillHit hit = parseCoordinates(coord1, doc);

            if (coord2 != null) {
                // It's a slot (G85)
                DrillHit end = parseCoordinates(coord2, doc);
                doc.addOperation(new DrillSlot(
                    hit.getX(), hit.getY(),
                    end.getX(), end.getY(),
                    doc.getCurrentTool()
                ));
            } else {
                doc.addOperation(hit);
            }
        }
    }
}
```

---

## 12. Appendix A: Gerber Command Quick Reference

| Command | Description | Example |
|---------|-------------|---------|
| G04 | Comment | `G04 This is a comment*` |
| FS | Format specification | `%FSLAX26Y26*%` |
| MO | Unit mode | `%MOMM*%` or `%MOIN*%` |
| AD | Aperture definition | `%ADD10C,1.5*%` |
| AM | Aperture macro | `%AMTHERMAL*7,0,0,0.8,0.5,0.1,45*%` |
| Dnn | Select aperture | `D10*` |
| D01 | Plot (draw/arc) | `X1000Y2000D01*` |
| D02 | Move | `X0Y0D02*` |
| D03 | Flash | `X5000Y5000D03*` |
| G01 | Linear mode | `G01*` |
| G02 | Clockwise arc | `G02*` |
| G03 | Counter-clockwise arc | `G03*` |
| G36/G37 | Region start/end | `G36*...G37*` |
| G75 | Multi-quadrant arc mode | `G75*` |
| LP | Load polarity | `%LPD*%` or `%LPC*%` |
| LM | Load mirroring | `%LMN*%` or `%LMXY*%` |
| LR | Load rotation | `%LR45*%` |
| LS | Load scaling | `%LS1.5*%` |
| TF | File attribute | `%TF.FileFunction,Copper,L1,Top*%` |
| TA | Aperture attribute | `%TA.AperFunction,ViaPad*%` |
| TO | Object attribute | `%TO.N,NET1*%` |
| TD | Delete attribute | `%TD*%` |
| AB | Block aperture | `%ABD10*%...%AB*%` |
| SR | Step and repeat | `%SRX3Y2I5.0J4.0*%...%SR*%` |
| M02 | End of file | `M02*` |

---

## 13. Appendix B: Aperture Macro Primitives

| Code | Primitive | Parameters |
|------|-----------|------------|
| 1 | Circle | exposure, diameter, center_x, center_y [, rotation] |
| 4 | Outline | exposure, n_vertices, x0, y0, x1, y1, ..., xn, yn, rotation |
| 5 | Polygon | exposure, n_vertices, center_x, center_y, diameter, rotation |
| 6 | Moire (deprecated) | center_x, center_y, outer_dia, ring_thickness, gap, max_rings, cross_thickness, cross_length, rotation |
| 7 | Thermal | center_x, center_y, outer_dia, inner_dia, gap_thickness, rotation |
| 20 | Vector Line | exposure, width, start_x, start_y, end_x, end_y, rotation |
| 21 | Center Line | exposure, width, height, center_x, center_y, rotation |

---

*Document created: January 2026*
*Last updated: January 2026*
*Author: Claude Code*
