# Delta Gerber

A Java library for parsing Gerber RS-274X and Excellon NC drill files with SVG rendering, realistic PCB visualization, and an interactive web viewer.

![All Layers View](screenshot-depr-layers.png)

## Realistic PCB Rendering

Generate photorealistic top and bottom views of your PCB with proper layer stacking — FR4 substrate, copper with HASL/ENIG finish, semi-transparent soldermask, silkscreen, and drill holes with true SVG transparency.

| Board Top | Board Bottom |
|-----------|--------------|
| ![Arduino Uno Top](screenshot-arduino-realistic-top.png) | ![Arduino Uno Bottom](screenshot-arduino-realistic-bottom.png) |

| All Layers | Realistic Top |
|------------|---------------|
| ![Arduino Layers](screenshot-arduino-layers.png) | ![DeltaProto Board Top](screenshot-depr-realistic-top.png) |

## Maven Dependency

```xml
<dependency>
    <groupId>com.deltaproto</groupId>
    <artifactId>delta-gerber</artifactId>
    <version>1.1.0</version>
</dependency>
```

## Features

### Gerber Parsing
- Full RS-274X support (.gbr, .ger, .gtl, .gbl, .gts, .gbs, .gto, .gbo, .gtp, .gbp, .gko, .gm, etc.)
- All standard apertures: circle (C), rectangle (R), obround (O), polygon (P)
- Aperture macros with primitives (circle, vector line, center line, outline, polygon, thermal)
- Region fills (G36/G37) with multiple contours
- Arc interpolation (G02/G03) with single and multi-quadrant modes
- Layer polarity (LPD/LPC) with true SVG mask-based transparency
- Step and Repeat (%SR%) for panelized boards
- Aperture transforms: rotation (LR), scaling (LS), mirroring (LM)
- Image polarity (%IP%) and offset (%OF%) recognition

### Excellon Drill Parsing
- Standard Excellon NC drill format (.drl, .txt, .xln, .drd)
- Tool definitions with diameter
- Drill hits and routed slots (G85, M15/M16/M17 routing mode)
- Plated (PTH) and non-plated (NPTH) hole distinction
- Absolute and incremental coordinate modes (G90/G91)
- Metric and inch units with automatic format detection

### CAD Tool Compatibility
- **Altium Designer** — Gerber X2 attributes, mechanical layer outlines (.GM, .GM1), format detection
- **Cadence Allegro** — Non-standard drill format with holesize comments, M00 tool separators, repeat codes (R02X...)
- **EAGLE** — Non-standard OC8/OCn octagon aperture type, combined FS+MO command blocks
- **KiCad** — Standard Gerber X2 output with file function attributes
- **Legacy RS-274X** — Deprecated G-codes (G54, G70/G71), deprecated extended commands (%IN, %LN, %AS, %MI, %SF, %IR%)
- **UTF-8 BOM** handling for files exported from Windows tools

### SVG Rendering
- High-fidelity SVG output with native SVG elements (circles, arcs, paths)
- Polygonized mode for geometry processing
- Multi-layer composite rendering with configurable colors and opacity
- Realistic PCB rendering with physically accurate layer stacking:
  - FR4 substrate, copper (silver under mask / gold at exposed pads)
  - Semi-transparent soldermask with inverted mask for pad openings
  - Silkscreen nested inside soldermask (only visible where mask is present)
  - Drill holes punching through all layers as true SVG transparency

### Web Viewer
- Interactive pan/zoom with mouse wheel and drag
- Three visualization modes: All Layers, Board Top, Board Bottom
- Layer type auto-detection from filename and content analysis
- Layer type dropdowns for manual override
- Select all/none checkbox with tri-state indicator
- Top/Bottom quick-filter buttons
- Hover-to-solo: preview individual layers by hovering
- Center-truncated filenames with instant tooltips
- Browser-side ZIP extraction and file persistence (IndexedDB)
- Recent project history with re-open support
- Stateless server architecture (browser owns the data)

## Quick Start — Download and Run

Download the standalone JAR from the [latest release](https://github.com/Delta-Proto/delta-gerber/releases/latest) and run:

```bash
java -jar delta-gerber-1.1.0-jar-with-dependencies.jar
```

Open http://localhost:938 and drop a Gerber ZIP file onto the viewer, or click **"Try Example"** to load the bundled Arduino Uno board.

> Requires Java 17+. No other dependencies needed.

### Build from Source

```bash
mvn clean package
java -jar target/delta-gerber-1.1.0-jar-with-dependencies.jar
```

## Usage as Library

```java
// Parse a Gerber file
GerberParser parser = new GerberParser();
GerberDocument doc = parser.parse(gerberContent);

// Render a single layer to SVG
SVGRenderer renderer = new SVGRenderer();
String svg = renderer.render(doc);

// Parse an Excellon drill file
ExcellonParser drillParser = new ExcellonParser();
DrillDocument drillDoc = drillParser.parse(excellonContent);
```

### Multi-Layer Rendering

```java
MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
layers.add(new MultiLayerSVGRenderer.Layer("top-copper", copperDoc)
    .setColor("#e94560").setOpacity(0.85));
layers.add(new MultiLayerSVGRenderer.Layer("drill", drillDoc)
    .setColor("#00ffff"));

String svg = renderer.render(layers);
```

### Realistic PCB Rendering

```java
MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();

layers.add(new MultiLayerSVGRenderer.Layer("outline", outlineDoc)
    .setLayerType(LayerType.OUTLINE));
layers.add(new MultiLayerSVGRenderer.Layer("copper", copperDoc)
    .setLayerType(LayerType.COPPER_TOP));
layers.add(new MultiLayerSVGRenderer.Layer("mask", soldermaskDoc)
    .setLayerType(LayerType.SOLDERMASK_TOP));
layers.add(new MultiLayerSVGRenderer.Layer("silk", silkscreenDoc)
    .setLayerType(LayerType.SILKSCREEN_TOP));
layers.add(new MultiLayerSVGRenderer.Layer("drill", drillDoc)
    .setLayerType(LayerType.DRILL));

String realisticSvg = renderer.renderRealistic(layers);
```

## Aperture Visual Test

The library includes a comprehensive visual test catalog with 127 test cases covering all aperture types, macros, regions, polarity, transforms, and legacy format support.

[View Aperture Visual Test](https://htmlpreview.github.io/?https://github.com/Delta-Proto/delta-gerber/blob/main/generated/aperture-visual-test.html)

## Project Structure

- `src/main/java/com/deltaproto/deltagerber/parser` — Gerber and Excellon parsers
- `src/main/java/com/deltaproto/deltagerber/lexer` — Tokenizer for Gerber files
- `src/main/java/com/deltaproto/deltagerber/model` — Data model for Gerber/drill documents
- `src/main/java/com/deltaproto/deltagerber/renderer/svg` — SVG rendering engine
- `src/main/java/com/deltaproto/deltagerber/web` — Web viewer server
- `src/main/resources/web` — Web viewer HTML/CSS/JS
- `testdata` — Sample Gerber projects for testing

## License

MIT
