# Delta Gerber

A Java library for parsing Gerber RS-274X and Excellon NC drill files with SVG and PNG rendering, realistic PCB visualization, and an interactive web viewer.

**[Try the online viewer at deltaproto.com](https://deltaproto.com/opensource-gerber-viewer)** — no install needed, runs in your browser.

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
    <version>1.1.6</version>
</dependency>
```

## Features

### Gerber Parsing
- Full RS-274X support (.gbr, .ger, .gtl, .gbl, .gts, .gbs, .gto, .gbo, .gtp, .gbp, .gko, .gm, etc.)
- All standard apertures: circle (C), rectangle (R), obround (O), polygon (P)
- Aperture macros with primitives (circle, vector line incl. legacy code 2, center line, outline, polygon, moiré, thermal) with variable expressions
- Region fills (G36/G37) with multiple contours
- Arc interpolation (G02/G03) with single and multi-quadrant modes
- Layer polarity (LPD/LPC) with true SVG mask-based transparency
- Step and Repeat (%SR%) for panelized boards
- Block apertures (%AB%) with full flash expansion, nesting, transforms and LPC polarity toggling
- Aperture transforms: rotation (LR), scaling (LS), mirroring (LM)
- **Full Gerber X3 / X2 attribute compliance** — every standard attribute (TF file, TA aperture,
  TO object, TD delete) is parsed into a typed, queryable model verified against the Ucamco
  **2024.05** specification:
  - **File**: `.FileFunction`, `.FilePolarity`, `.Part`, `.GenerationSoftware`, `.CreationDate`,
    `.ProjectId`, `.MD5`, `.SameCoordinates`
  - **Aperture**: the complete `.AperFunction` set (32 values, typed enum), `.DrillTolerance`
    (mm-normalized), `.FlashText`
  - **Object / X3 assembly**: nets (`.N`), pins (`.P`), component refdes (`.C`) and the full
    `.Cxxx` component characteristics — value, mfr, MPN, mount, rotation, package, library, and
    height (`.CHgt`, mm-normalized) — plus pick-and-place centroid extraction
- Image polarity (%IP%): negative-image inversion rendering; image offset (%OF%) recognition

### Excellon Drill Parsing
- Standard Excellon NC drill format (.drl, .txt, .xln, .drd)
- Tool definitions with diameter
- Drill hits and routed slots (G85, M15/M16/M17 routing mode)
- Plated (PTH) and non-plated (NPTH) hole distinction
- Absolute and incremental coordinate modes (G90/G91)
- Metric and inch units with automatic format detection

### IPC-D-356A Netlist Parsing
- Bare-board electrical-test netlist reader (`.ipc`, `.ipc356`) for connectivity / test-point data
- Test points: `317` through-hole, `327` SMD, `367` non-plated tooling, `307` blind/buried via —
  with net, ref-des/pin (incl. `VIA` and mid-net `M`), hole + plating, access side, location,
  feature size and rotation, soldermask
- Conductor segments (`378`/`078`) with modal coordinates and space/asterisk-delimited chains,
  net adjacency (`379`/`079`), and board/panel outlines (`389`/`089`)
- Continuation records: `017`/`027` hole, `099` test-point location, `088` soldermask clearance
- Long-net-name aliases (`P NNAME`), resolved into full net names — tolerates the non-standard
  Allegro alias-as-comment quirk
- Coordinates and sizes normalized to **mm** at parse time (from `CUST` 0.0001 inch or `SI`
  0.001 mm), sharing one coordinate space with Gerber/drill geometry
- Non-fatal parse warnings (missing `P UNITS`, unknown op codes, truncated records) instead of
  exceptions

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
- Derived board outline when no GKO/outline layer is present — the board edge is
  reconstructed from the copper-union silhouette so the realistic view still works

### PNG / Raster Output
- Per-layer PNG export for single Gerber or drill layers (`SVGRenderer.renderPng`,
  `DrillSVGRenderer.renderPng`) — ideal for feeding individual layers to vision models
- Realistic top/bottom PNGs (`renderRealisticSidePng`), dimension-clamped to keep
  memory bounded on large boards
- Scale-aware export (`renderRealisticSidePngWithScale` → `PngWithScale`) carrying
  px↔mm geometry, embedded directly in the PNG via `pHYs` + `tEXt` chunks
- Board overview PNG (`renderBoardOverviewPng`) — composites the realistic board over
  an all-layers underlay so off-board annotations (drill charts, stackup tables, fab
  notes) stay visible; scales to dense multi-layer boards via a raster silhouette path

### Board Analysis & DFM
- `PcbAnalyzer` reduces a whole folder of Gerber + drill files to one `BoardSpecification`:
  board size, copper layer count, soldermask / silkscreen / stencil sides, minimum track width
  (µm) and minimum drill diameter (mm) — classifying every file first, then measuring only what
  can change the answer
- **Via-in-pad detection** — determines whether any drilled hole lands inside a surface-mount pad
  (read from the solder-paste layer), which forces a filled-and-capped via process
  (**IPC-4761 Type VII**) and raises fabrication cost and lead time. Catches thermal vias under a
  QFN/BGA pad; ignores ordinary plated through-holes. Surfaced as
  `spec.hasViaInPad()` / `getViaInPadCount()` / `getViaInPadSide()`, or standalone via
  `dfm.ViaInPadDetector`
- Drill/Gerber origin auto-alignment (`DrillGerberAlignment`) recovers an exact offset when the NC
  drill was exported on a different origin than the copper (e.g. some Altium flows), so holes and
  pads share one coordinate space before any analysis

### Parse Diagnostics
- Both Gerber and drill documents expose a de-duplicated list of parse warnings
- Malformed, truncated, or hostile files degrade gracefully with a recorded warning
  instead of crashing or producing a blank render

### Web Viewer
- Interactive pan/zoom with mouse wheel and drag
- Three visualization modes: All Layers, Board Top, Board Bottom
- PNG Top/Bottom export of the realistic view
- Warnings tab listing per-file parse warnings (disabled when there are none)
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
java -jar delta-gerber-1.1.6-jar-with-dependencies.jar
```

Open http://localhost:938 and drop a Gerber ZIP file onto the viewer, or click **"Try Example"** to load the bundled Arduino Uno board.

> Requires Java 17+. No other dependencies needed.

### Build from Source

```bash
mvn clean package
java -jar target/delta-gerber-1.1.6-jar-with-dependencies.jar
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

### Component Placement (Pick-and-Place / Centroid)

KiCad and other EDA tools can export component placement data as Gerber X2 files with `%TF.FileFunction,Component,...*%`. The parser extracts the centroid of each component and makes it available as a `List<ComponentPlacement>`.

```java
GerberParser parser = new GerberParser();
GerberDocument doc = parser.parse(pnpFileContent);

if (doc.isComponentFile()) {
    String side = doc.getComponentSide(); // "Top" or "Bottom"
    List<ComponentPlacement> components = doc.getComponents();

    for (ComponentPlacement c : components) {
        System.out.printf("%s\t%s\t%s\t%.4f\t%.4f\t%.1f\t%s%n",
            c.getRefdes(),     // e.g. "R1"
            c.getValue(),      // e.g. "10k"
            c.getFootprint(),  // e.g. "R_0402_1005Metric"
            c.getX(),          // centroid X in mm
            c.getY(),          // centroid Y in mm
            c.getRotation(),   // degrees
            c.getSide());      // "Top" or "Bottom"
    }
}
```

**Export to CSV:**

```java
StringBuilder csv = new StringBuilder("Designator,Value,Footprint,MountType,X_mm,Y_mm,Rotation_deg,Side\n");
for (ComponentPlacement c : doc.getComponents()) {
    csv.append(String.format(Locale.US, "\"%s\",\"%s\",\"%s\",\"%s\",%.4f,%.4f,%.2f,\"%s\"%n",
        c.getRefdes(), c.getValue(), c.getFootprint(), c.getMountType(),
        c.getX(), c.getY(), c.getRotation(), c.getSide()));
}
Files.writeString(Path.of("centroid.csv"), csv.toString());
```

If you have separate top and bottom PnP files, parse each one independently and combine the lists:

```java
GerberParser parser = new GerberParser();
List<ComponentPlacement> all = new ArrayList<>();
all.addAll(parser.parse(pnpTopContent).getComponents());
all.addAll(parser.parse(pnpBottomContent).getComponents());
```

Each coordinate is in **millimetres**, normalised at parse time regardless of the source file unit. The `mountType` field is `"SMD"` or `"TH"` as declared in `%TO.CMnt*%`.

### Board Specification & Via-in-Pad Detection

Give `PcbAnalyzer` the whole file set and it returns a `BoardSpecification` describing the bare
board — size, layer count, processes, tolerances — and, when the set has both a solder-paste layer
and a drill, whether the board has any **via in pad**.

```java
import com.deltaproto.deltagerber.spec.PcbAnalyzer;
import com.deltaproto.deltagerber.spec.PcbFile;
import com.deltaproto.deltagerber.spec.BoardSpecification;

// One PcbFile per file in the customer's Gerber/drill set. The analyzer classifies each by
// name + content, so you do not have to tell it which file is which.
List<PcbFile> files = List.of(
    PcbFile.of("board-Edge_Cuts.gbr", outlineBytes),
    PcbFile.of("board-F_Cu.gbr",      topCopperBytes),
    PcbFile.of("board-F_Paste.gbr",   topPasteBytes),   // the paste layer is what defines SMD pads
    PcbFile.of("board-PTH.drl",       drillBytes));

BoardSpecification spec = new PcbAnalyzer().analyze(files);

Boolean viaInPad = spec.hasViaInPad();   // TRUE / FALSE, or null when it can't be judged
int     count    = spec.getViaInPadCount();
```

`hasViaInPad()` is a **nullable** `Boolean` on purpose:

| Value   | Meaning                                                                          |
|---------|----------------------------------------------------------------------------------|
| `TRUE`  | At least one hole sits inside an SMD pad → the board needs IPC-4761 Type VII      |
| `FALSE` | Paste and drill were both present and no hole falls in a pad                      |
| `null`  | Not determined — the set had no paste layer, or no drill, so the question is open |

`PcbFile.of` accepts a `String` or a `byte[]`; bytes are decoded as ISO-8859-1 (both formats are
ASCII), so you can hand it raw upload bytes directly. Customer files should never be committed to a
public repo — keep them out of version control.

#### Integrating into a calculator / quoting flow

```java
BoardSpecification spec = new PcbAnalyzer().analyze(files);

// Flag the extra process only on a positive result; treat null (unknown) as "no data",
// not as a pass — the same way you would treat a missing paste layer.
if (Boolean.TRUE.equals(spec.hasViaInPad())) {
    quote.requireProcess(Process.IPC_4761_TYPE_VII);   // filled & capped vias
    quote.addNote(spec.getViaInPadCount() + " via(s) in pad on " + spec.getViaInPadSide());
}
```

For the full list of offending holes (each with its location and drilled diameter), read the
detection result off the spec:

```java
import com.deltaproto.deltagerber.dfm.ViaInPad;
import com.deltaproto.deltagerber.dfm.ViaInPadResult;

ViaInPadResult vip = spec.getViaInPad();   // null when detection did not run
if (vip != null) {
    for (ViaInPad v : vip.getViaInPads()) {
        System.out.printf("via in pad at (%.3f, %.3f) mm, ⌀%.3f mm%n",
            v.getX(), v.getY(), v.getHoleDiameterMm());
    }
}
```

If you have already parsed the paste and drill documents for another purpose (rendering, say),
call the detector directly instead of re-analysing — pass the paste layers split by side, and use
`detectAligned(...)` when the drill might be on a different origin than the copper:

```java
import com.deltaproto.deltagerber.dfm.ViaInPadDetector;

ViaInPadResult vip = ViaInPadDetector.detect(
    List.of(topPasteDoc),      // top-side paste GerberDocuments
    List.of(bottomPasteDoc),   // bottom-side paste GerberDocuments
    List.of(drillDoc));        // Excellon DrillDocuments (already in the Gerber coordinate frame)

boolean needsViaFill = vip.hasViaInPad();
```

> Cost note: at `AnalysisDepth.SPECIFICATION` the analyzer skips parsing layers that can't change
> the spec (a large silkscreen, for instance) but still parses the small paste layer when a drill
> is present, so via-in-pad is reported at both depths.

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

#### Soldermask Color

The realistic view (and the PNG paths built on it) defaults to a realistic dark
green soldermask. Pick one of the standard fab colors with `setSoldermaskColor`.
Each color carries its paired silkscreen color — white on every color except
**white** soldermask, which uses black silkscreen:

```java
MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer()
    .setSoldermaskColor(SoldermaskColor.RED);   // green (default), purple, red, yellow, blue, white, black

String realisticSvg = renderer.renderRealistic(layers);
```

The `SoldermaskColor` palette matches the colors common fabricators (e.g. JLCPCB)
offer. `SoldermaskColor.GREEN` keeps a deliberately darker mask shade (`#004200`)
rather than the brighter advertised swatch green — at the soldermask's
semi-transparent opacity over copper/FR4 it blends to a realistic board green.
For a color outside the palette, pass explicit mask + silkscreen hex fills:

```java
renderer.setSoldermaskColor("#102a4c", "#ffffff");   // custom navy mask, white silk
```

### PNG Export

All renderers can rasterize straight to PNG through the shared Batik pipeline.

```java
// A single layer to PNG (e.g. a fab drawing or drill legend for a vision model)
byte[] layerPng = new SVGRenderer().renderPng(copperDoc, 1024);
byte[] drillPng = new DrillSVGRenderer().renderPng(drillDoc, 1024);

// Realistic top/bottom views to PNG (dimension-clamped for bounded memory)
MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
byte[] topPng = renderer.renderRealisticSidePng(layers, MultiLayerSVGRenderer.Side.TOP, 1024);

// One image with the realistic board plus all off-board annotations
// (drill charts, stackup tables, fab notes) composited around it
byte[] overviewPng = renderer.renderBoardOverviewPng(layers, MultiLayerSVGRenderer.Side.TOP, 1024);

Files.write(Path.of("board-top.png"), topPng);
```

When you need to map pixels back to real-world coordinates, `renderRealisticSidePngWithScale`
returns a `PngWithScale` carrying the px↔mm scale, the mm rectangle the image covers, and the
datum origin — the same geometry is also embedded in the PNG via `pHYs` + `tEXt` chunks.

```java
MultiLayerSVGRenderer.PngWithScale r =
    renderer.renderRealisticSidePngWithScale(layers, MultiLayerSVGRenderer.Side.TOP, 1024, 0, false);
double pxPerMm = r.pxPerMm;   // e.g. overlay a 10 mm grid behind the board
Files.write(Path.of("board-top.png"), r.png);
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
