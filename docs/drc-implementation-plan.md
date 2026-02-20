# KiCad DRC Implementation Plan for delta-gerber

## Context

The delta-gerber project parses Gerber RS-274X and Excellon drill files and renders them as SVG. There is no existing DRC functionality. We want to add the ability to parse KiCad `.kicad_dru` design rule files and run manufacturing-oriented DRC checks on parsed Gerber data. This fills a real gap — no open-source tool currently does DRC on Gerber files using KiCad rules.

**Key constraint**: Gerber files are flat geometry with no netlist, so net-based rules (diff pairs, length matching, net clearance) are inherently unsupported. We focus on **manufacturing DRC** — minimum widths, clearances, hole sizes — which is exactly what matters for Gerber verification.

**Test data available**: PCBWay `.kicad_dru` rules at `testdata/kicad-design-rules/KiCAD_Custom_DRC_Rules_for_PCBWay/`

---

## Phase 1: Parser & Rule Model

**Goal**: Parse `.kicad_dru` files into a structured Java model.

### Step 1.1: S-Expression Parser

The `.kicad_dru` format is S-expressions with `#` comments and `"quoted strings"`.

**New files**:
- `src/main/java/nl/bytesoflife/deltagerber/drc/parser/SNode.java` — sealed interface: `SAtom(String value)` and `SList(List<SNode> children)`
- `src/main/java/nl/bytesoflife/deltagerber/drc/parser/SExpressionParser.java` — character-by-character parser producing `List<SNode>`
- `src/test/java/nl/bytesoflife/deltagerber/drc/parser/SExpressionParserTest.java`

### Step 1.2: DRC Rule Model

**New files under** `src/main/java/nl/bytesoflife/deltagerber/drc/model/`:

| Class | Contents |
|-------|----------|
| `DrcRuleSet` | `int version`, `List<DrcRule> rules` |
| `DrcRule` | `String name`, `Severity severity`, `LayerSelector layer`, `List<DrcConstraint> constraints`, `String conditionExpression` |
| `DrcConstraint` | `ConstraintType type`, `Double minMm`, `Double maxMm` |
| `ConstraintType` | Enum: `CLEARANCE, TRACK_WIDTH, HOLE_SIZE, HOLE_TO_HOLE, EDGE_CLEARANCE, ANNULAR_WIDTH, SILK_CLEARANCE, TEXT_HEIGHT, TEXT_THICKNESS, VIA_DIAMETER, HOLE_CLEARANCE, DISALLOW` with `fromKicadName(String)` |
| `Severity` | Enum: `ERROR, WARNING, IGNORE` |
| `LayerSelector` | Raw string + `matches(String kicadLayerName)` with wildcard support for `outer`, `inner`, `"?.Cu"` etc. |

### Step 1.3: KiCad DRU File Parser

**New file**: `src/main/java/nl/bytesoflife/deltagerber/drc/parser/KicadDruParser.java`

- Takes S-expression tree, builds `DrcRuleSet`
- Handles unit parsing: `0.127mm` → strip suffix, parse double. Handle both `mm` and `mil` (1 mil = 0.0254 mm)
- Stores condition as raw string (evaluated in Phase 2)

**Tests**: `src/test/java/nl/bytesoflife/deltagerber/drc/parser/KicadDruParserTest.java`
- Parse the PCBWay `.kicad_dru` file end-to-end
- Assert rule count, specific constraint values
- Test comments, multiple constraints per rule, rules without conditions

---

## Phase 2: Simple Checks (Single-Object)

**Goal**: DRC checks that examine individual objects against thresholds — no distance calculations needed.

### Step 2.1: Board Input & Layer Mapping

**New file**: `src/main/java/nl/bytesoflife/deltagerber/drc/DrcBoardInput.java`

- `Map<String, GerberDocument> layers` — keyed by KiCad layer name (`"F.Cu"`, `"B.Cu"`, `"F.Silkscreen"`, `"Edge.Cuts"`)
- `List<DrillDocument> drillFiles`
- Auto-mapping from `GerberDocument.getFileFunction()`:
  - `"Copper,L1,Top"` → `"F.Cu"`, `"Copper,L2,Bot"` → `"B.Cu"`
  - `"Legend,Top"` / `"Silkscreen,Top"` → `"F.Silkscreen"`
  - `"Profile"` → `"Edge.Cuts"`
- Builder: `addGerberLayer(String, GerberDocument)`, `addGerberLayerAuto(GerberDocument)`, `addDrill(DrillDocument)`
- Helpers: `isOuterLayer(String)`, `isInnerLayer(String)`

### Step 2.2: Violation & Report Model

**New files**:
- `src/main/java/nl/bytesoflife/deltagerber/drc/DrcViolation.java` — `rule`, `constraint`, `severity`, `description`, `measuredValueMm`, `requiredValueMm`, `x`, `y`, `layer`
- `src/main/java/nl/bytesoflife/deltagerber/drc/DrcReport.java` — `violations`, `skippedRules`, `getErrors()`, `getWarnings()`, `hasErrors()`, `toString()` summary

### Step 2.3: Condition Evaluator (Pattern-Matching)

**New file**: `src/main/java/nl/bytesoflife/deltagerber/drc/check/ConditionEvaluator.java`

Maps Gerber objects to KiCad types for condition evaluation:
- `Draw` → `'track'`
- `Flash` → `'pad'`
- `Region` → `'zone'`

Returns `APPLICABLE`, `NOT_APPLICABLE`, or `UNSUPPORTED` for conditions referencing:
- `A.Net`, `A.NetClass` → UNSUPPORTED (no netlist)
- `A.Type == 'via'` → UNSUPPORTED (can't distinguish from pad)
- `A.isPlated()` → UNSUPPORTED without metadata
- No condition → APPLICABLE to all

Rules with UNSUPPORTED conditions go to `skippedRules` in the report.

### Step 2.4: Check Implementations

**New interface**: `src/main/java/nl/bytesoflife/deltagerber/drc/check/DrcCheck.java`
```java
public interface DrcCheck {
    List<DrcViolation> check(DrcRule rule, DrcConstraint constraint, DrcBoardInput board);
    ConstraintType getSupportedType();
}
```

**New checks**:

| Check | File | Logic |
|-------|------|-------|
| `TrackWidthCheck` | `drc/check/TrackWidthCheck.java` | For Draw objects on copper layers: aperture diameter vs `min`. Uses `CircleAperture.getDiameter()` (already accessible via `Draw.getAperture()`) |
| `HoleSizeCheck` | `drc/check/HoleSizeCheck.java` | For DrillHit/DrillSlot: `tool.getDiameter()` vs `min`/`max` |
| `AnnularWidthCheck` | `drc/check/AnnularWidthCheck.java` | Correlate DrillHit position with Flash on copper layer. Annular width = `(pad_diameter - drill_diameter) / 2`. Use coordinate matching. |

### Step 2.5: DRC Runner

**New file**: `src/main/java/nl/bytesoflife/deltagerber/drc/DrcRunner.java`

Orchestrates: iterates rules → matches constraints to checks → evaluates conditions → runs checks → collects violations into `DrcReport`.

### Step 2.6: Tests

- `src/test/java/nl/bytesoflife/deltagerber/drc/check/TrackWidthCheckTest.java` — synthetic GerberDocument with known aperture sizes
- `src/test/java/nl/bytesoflife/deltagerber/drc/check/HoleSizeCheckTest.java` — synthetic DrillDocument with known tool diameters
- `src/test/java/nl/bytesoflife/deltagerber/drc/DrcRunnerIntegrationTest.java` — parse PCBWay rules + real Gerber test data, verify end-to-end

---

## Phase 3: Distance-Based Checks (JTS Geometry)

**Goal**: Clearance checks requiring minimum distance between shapes.

### Step 3.1: Add JTS Dependency

Add to `pom.xml`:
```xml
<dependency>
    <groupId>org.locationtech.jts</groupId>
    <artifactId>jts-core</artifactId>
    <version>1.20.0</version>
</dependency>
```

JTS provides `Geometry.distance()`, `LineString.buffer()` (creates track capsule shapes), and `STRtree` (R-tree spatial index).

### Step 3.2: Gerber-to-JTS Converter

**New file**: `src/main/java/nl/bytesoflife/deltagerber/drc/geometry/GerberGeometryConverter.java`

| Gerber Object | JTS Geometry |
|---------------|-------------|
| `Flash` + `CircleAperture` | `Point.buffer(radius)` → circular polygon |
| `Flash` + `RectangleAperture` | `createPolygon(4 corners)` |
| `Draw` + `CircleAperture` | `LineString.buffer(radius)` → capsule shape |
| `Arc` | Convert to line segments (reuse `SvgPathUtils` point logic), then `LineString.buffer(radius)` |
| `Region` | Convert contour points to JTS `Polygon` |
| `DrillHit` | `Point.buffer(radius)` |

**New file**: `src/main/java/nl/bytesoflife/deltagerber/drc/geometry/DrillGeometryConverter.java`

### Step 3.3: Spatial Index

**New file**: `src/main/java/nl/bytesoflife/deltagerber/drc/geometry/SpatialIndex.java`

Wraps JTS `STRtree`. For each layer, index all geometries. Query neighbors within `minClearance` distance, then compute exact `geom1.distance(geom2)`. Reduces O(n²) to ~O(n log n).

### Step 3.4: Distance-Based Checks

| Check | File | Logic |
|-------|------|-------|
| `ClearanceCheck` | `drc/check/ClearanceCheck.java` | Copper-to-copper min distance on same layer via spatial index |
| `HoleToHoleCheck` | `drc/check/HoleToHoleCheck.java` | Drill-to-drill spacing (simplified: `center_distance - r1 - r2`) |
| `EdgeClearanceCheck` | `drc/check/EdgeClearanceCheck.java` | Copper to `Edge.Cuts` layer distance |

### Step 3.5: Tests

Unit tests with synthetic geometries at known distances. Integration test with real PCB data.

---

## Phase 4: Future Enhancements (Out of Scope for Now)

- `SilkClearanceCheck` — silkscreen to pad distance
- Full condition expression parser (AST-based) for broader rule coverage
- Violation SVG overlay — render markers on the board visualization
- JSON/CSV report export
- NextPCB `.kicad_pro` JSON format parsing

---

## Existing Code to Reuse

| What | Where | Used For |
|------|-------|----------|
| `GerberDocument.getFileFunction()` | `model/gerber/GerberDocument.java` | Auto layer mapping |
| `GerberDocument.getObjects()` | same | Iterating all graphics objects |
| `Draw.getAperture()` → `CircleAperture.getDiameter()` | `model/gerber/operation/Draw.java` | Track width check |
| `DrillHit.getTool().getDiameter()` | `model/drill/DrillHit.java` | Hole size check |
| `BoundingBox` | `model/gerber/BoundingBox.java` | Quick overlap pre-filter |
| `SvgPathUtils` point generation logic | `renderer/svg/SvgPathUtils.java` | Converting arcs to line segments for JTS in Phase 3 |
| `Unit.toMm()` / unit info | `model/gerber/Unit.java` | Ensuring all comparisons in mm |

## Verification

- **Phase 1**: Parse `testdata/kicad-design-rules/KiCAD_Custom_DRC_Rules_for_PCBWay/KiCAD_Custom_DRC_Rules_for_PCBWay.kicad_dru`, assert correct rule count and constraint values
- **Phase 2**: Run DRC with PCBWay rules against test Gerber data in `testdata/`, verify violations are reported with correct locations and values
- **Phase 3**: Create synthetic test cases with objects at known distances, verify clearance violations are detected accurately
- **All phases**: `mvn test` passes
