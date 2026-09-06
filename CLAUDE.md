# CLAUDE.md

## Project

Delta Gerber — Java Gerber/NC drill file parser with SVG export.

## Build & Test

```bash
mvn clean test                # Run all tests
mvn test -Dtest=ClassName     # Run a specific test class
```

## Deploy

See [DEPLOY.md](DEPLOY.md) for release instructions. The GPG passphrase is in `.mvn-gpg-passphrase`.

```bash
mvn clean deploy -Prelease -Dgpg.passphrase=$(cat .mvn-gpg-passphrase)
```

## Test data

Customer board files must **not** be committed — this repo is public and published to Maven
Central. Put them under `excluded/` (gitignored) and have the test skip when they are absent
(see `NdcBoardSpecificationTest`), backed by a synthetic fixture that always runs.

## Key Conventions

- Coordinates and dimensions are normalized to millimetres at parse time by **all three** parsers:
  `ExcellonParser` (drill hits, slots, tool diameters), `GerberParser` (operation coordinates,
  aperture sizes), and `Ipc356Parser` (netlist test points, conductors, hole diameters — from the
  file's `CUST` 0.0001 inch or `SI` 0.001 mm grid). The file's native unit (inch/mm) is consumed
  during parsing and not retained.
- After parsing, `getUnit()` returns `Unit.MM` on `DrillDocument`, `GerberDocument`, and
  `Ipc356Document`, so all parsed geometry can be treated as mm with no conversion — drill holes,
  Gerber flashes, and netlist test points share one coordinate space (this is what lets
  `DrillGerberAlignment` correlate them directly).
- Two bounding boxes, and they mean different things. `getBoundingBox()` is the **inked** extent —
  the path grown by half the aperture. `getPathBoundingBox()` is the **centreline**. A board
  outline measures to the centreline, because that is where the router cuts: a 32 mm board drawn
  with a 0.05 mm aperture inks 32.05 mm but *is* 32 mm. (KiCad's `.gbrjob` reports the inked
  figure, so expect our size to be one aperture-width smaller than what a job file declares.)

## Drill/Gerber origin mismatch

Altium (and others) let the Gerber and NC-drill exports reference different origins, and nothing in
the drill file records the difference. `align.DrillGerberAlignment` recovers it exactly — every
plated hole is concentric with its copper pad, so the `(pad - hole)` vectors collapse to one
translation — and never approximately: no support, no fix.

**Misalignment is judged by hole-on-pad support, never by bounding boxes.** A displaced drill only
clears the board's bounding box when the shift exceeds the board's own size, so on a large board a
badly-placed drill still overlaps it and a box test sees nothing wrong (issue #5). Boxes are only a
cheap *trigger*: a correctly placed hole is always inside the board, so a drill whose bounds are
**contained** in the Gerber bounds is dismissed with no pad index and no correlation — which is what
keeps a healthy set free. Anything poking outside is then counted properly against the pads. The bar
to actually move a drill is raised for one that still overlaps the board (90% of holes, at least 8)
versus one hanging entirely clear of it (50%, at least 3), and a shift must always seat strictly more
holes than leaving the drill alone would — so an NPTH-only file, which matches no pad at any offset,
is never moved.

A drill *set* goes through `analyzeAll`/`alignedAll`, not one file at a time. EDA tools split the
drill program — Altium writes round holes and slots separately — and a slot-only file carries no hole
centres to correlate, so it can never recover its own offset. All files in one export share an
origin, so the best-supported sibling's offset is handed to the ones that came up empty, provided the
shift lands them on the board. `Result.isInherited()` says an offset arrived that way.

## Board finish colors

`MultiLayerSVGRenderer.renderRealistic` (and every PNG path built on it) colors the board from
two palettes: `SoldermaskColor` and `SilkscreenColor`. Both are held **per side**, so
`setSoldermaskColor(Side.BOTTOM, BLACK)` recolors only the bottom; the no-`Side` overloads set both.

`NONE` in either palette means *the board was ordered without that finish* — the mask sheet or the
legend group is simply not emitted, rather than drawn in a substitute color. Consequently
`getMaskColor()`/`getColor()` return `null` for `NONE`, which is what the renderer keys off.

Silkscreen is tri-state (unset / a color / none) so the two setters **commute**: unset takes the
color the mask pairs with (white on everything but a white mask, which pairs black), and once a
caller names a `SilkscreenColor` a later `setSoldermaskColor` re-pairs nothing. Don't collapse this
back to "mask setter also assigns silk" — call order would then decide the legend color.

## Board outline, and the STEP export

`MultiLayerSVGRenderer.resolveBoardOutline(layers)` is the **one** place the board edge is decided,
and it returns a `renderer.svg.BoardOutline` — the path *plus* the fill rule it must be read under,
because the two sources do not mean the same thing by a loop. A profile layer is chained by
`extractOutlinePath` and its extra loops are genuine cut-outs, which only subtract under
**even-odd**; a set with no profile gets `OutlineDeriver`'s copper silhouette, whose one loop per
disjoint board piece must be unioned — **nonzero**. Both the realistic view's clip path and
`renderer.step.StepExporter` go through it, so what renders is what gets extruded. Don't re-derive
an outline in a new caller; add a consumer of `BoardOutline`.

`StepExporter` extrudes that outline into an ISO 10303-21 (AP214) B-rep: bottom face at z=0, top at
the thickness, one planar wall per polygon edge, X/Y left in the Gerber frame's millimetres so the
solid lines up with everything else the library reports. Everything the set drills (`DrillHoles`,
mirroring what the realistic view's `mech-mask` punches) is subtracted from the outline **before**
the loops are nested — one `Area.subtract` for the whole drill program. It is deliberately *not*
clipped to the board first: a mouse bite is a hole that straddles the routed edge, and subtracting
it unclipped is what scallops the edge. Loop nesting is resolved by **parity** —
a loop enclosed by an even number of others is material and becomes its own `MANIFOLD_SOLID_BREP`,
an odd one is a cut-out in the innermost loop enclosing it — which is right for even-odd cut-outs
and harmless for a derived silhouette, whose loops never nest. Enclosure is decided by a *vote* of
the inner loop's vertices, not one representative point: board geometry is grid-aligned and loops
share coordinates constantly, so any single test point lands on the other loop's boundary often
enough to matter, and a crossing test answers arbitrarily there.

Java2D's `Area` is free to hand one connected region back as several subpaths meeting along a
seam, and with a few hundred holes subtracted it does — the board arrives sliced into horizontal
bands, which extrude into separate solids that merely touch. `weldSeams` undoes it: both sides of
a seam traverse the same points (after `splitAtSharedVertices` gives them the same vertices), so
every directed edge with an exact opposite twin is cancelled and the survivors are re-chained. On
a genuine boundary nothing cancels, so it is a no-op where there was no seam.

The two flat faces are named `top`/`bottom` and carry the words as well: `StrokeFont` (a
single-stroke plotter font, hand-built so output stays identical on every host — `java.awt.Font`
would depend on the machine's fontconfig and hand back empty glyphs on a stripped container) draws
them into a `GEOMETRIC_CURVE_SET` in its own `GEOMETRICALLY_BOUNDED_WIREFRAME_SHAPE_REPRESENTATION`,
linked by a `SHAPE_REPRESENTATION_RELATIONSHIP`. Annotation, never material: the solid is
byte-identical with the labels off, and the underside's word is mirrored so it reads from below.

Orientation is carried entirely by winding — material loops counter-clockwise seen from +Z,
cut-outs clockwise — which is what lets one wall construction serve both and makes the shell close
(every edge used exactly twice, once in each direction; `StepExporterTest` asserts it). Thickness is
the one number no Gerber file carries: it defaults to `DEFAULT_THICKNESS_MM` (1.6 mm) and every
entry point takes it — the library setter, `GerberViewerServer.exportStep`, the
`POST /api/gerber/step?thickness=` query parameter, and the viewer's thickness box. A set that
ships a `.gbrjob` or IPC-2581 stack-up states a real one in `BoardSpecification.getBoardThicknessMm()`.

## Board analysis

`classify.LayerClassifier` decides what each file in a set is, from strongest evidence to weakest:
X2 `.FileFunction` → Allegro `FILE IDENTIFICATION RECORD` → Excellon `;TYPE=` → filename patterns
ordered by the CAD tool detected from `.GenerationSoftware`. It never mutates its pattern tables.

`LayerClassification.number()` is the stack-up index, and it exists **iff** the layer is inner
copper — the record's constructor drops it otherwise, so no caller has to remember. Outer copper
gets none: its side already says everything, and the `L<p>` an X2 file gives it is just the absolute
stack position. `renderer.svg.LayerType.of(function, side)` is the one place a classification
becomes something a renderer can draw, and `getFunction()`/`getSide()` invert it.

Inner-layer numbering is a **set-level** question. Protel (`.G1`) and Allegro (`IN1`) count inner
layers from 1; Gerber X2 (`Copper,L2,Inr`) states the absolute stack position, so KiCad's `In1_Cu`
arrives as 2. `LayerClassifier.normalizeInnerCopperNumbers(List)` shifts a whole set so its lowest
inner layer is 1 — shifting, not densely renumbering, so a gap stays a gap (a missing file, not a
shorter stack-up). `PcbAnalyzer` applies it to the classifications it derived; a classification
supplied on a `PcbFile` is final and is never renumbered.

`spec.PcbAnalyzer` classifies, measures and reduces a whole set to a `spec.BoardSpecification`
(size, copper layer count, mask/silkscreen/stencil sides, min track in µm, min drill in mm).
It never renders. `BoardSpecification.from(List<AnalyzedLayer>)` re-derives the same answer from
persisted measurements, without the files.

`dfm.ViaInPadDetector` answers one DFM question: is any drilled hole inside a surface-mount pad
(*via in pad*), and if so does that actually force a filled-and-capped via process (IPC-4761
Type VII), which drives cost.
The pad geometry comes from the **solder-paste** layer — paste marks exactly the SMD lands and a
through-hole pad gets none — so a hole whose centre falls in a paste opening is the via in pad; this
catches thermal vias under a QFN/BGA pad and ignores an ordinary plated through-hole. Holes must be
in the Gerber frame first (`detectAligned` runs `DrillGerberAlignment` for a drill on a foreign
origin). `PcbAnalyzer` collects the paste pads, drill holes and copper flashes as it parses (heavy
copper is still released; only its flash centres survive), correlates them once, and surfaces the
verdict on `BoardSpecification.hasViaInPad()` / `getViaInPadCount()` / `getViaInPadSide()`. That
getter is `null` — *not determined* — when the set has no paste or no drill, or was rebuilt from
persisted `AnalyzedLayer` measurements (via-in-pad is a two-layer relationship, so
`BoardSpecification.from(layers)` cannot re-derive it; pass a stored result to
`from(layers, viaInPad)`). Paste is parsed for this even at `SPECIFICATION` depth when a drill is
present, because the verdict *is* part of the specification.

Finding a hole in a pad and *needing to plug it* are two different answers, and the detector keeps
them apart. The hits are grouped by the pad they sit in — one `ViaInPadGroup` per paste opening,
carrying that opening's true area in mm² (the aperture as flashed, or the painted region — not its
bounding box), the hole diameter and how many holes landed there. `ViaInPadPolicy` then reads the
group: **several vias in one pad** is a via field, which only ever appears under a heat spreader, and
**a pad far larger than its holes** carries paste to spare, so what wicks down the barrel does not
starve the joint. Either makes the pad thermal and needs no cap; anything else — the lone via in an
0402 or SOIC land — does. So `hasViaInPad()` is the geometric fact and
`requiresFilledAndCappedVias()` is the process verdict, and a board of QFN thermal vias answers
`TRUE` to the first and `FALSE` to the second. Quote off the second. The default thresholds (2 vias;
25× the hole area with a 2.0 mm² floor) put the cut between a QFN heat pad and a discrete land —
fabricators disagree about exactly where it sits, so every judging method takes a `ViaInPadPolicy`
overload.

`spec.BoardStack` is the board's physical build-up **and** its finished thickness — one value,
because the two come from the same file and a total taken from a different file than the layers is a
bug. Its entries are `spec.StackEntry` rows (dense ordinal, `StackFunction`, name, thickness in
picometres, material, `estimated`), surfaced as `BoardSpecification.getStack()`, with the total on
`getBoardThicknessPm()`/`Mm()`. Four ways to build one: `from(GerberJobDocument)` (a `.gbrjob`'s
`MaterialStackup` plus `GeneralSpecs.BoardThickness`), `from(Ipc2581StackupDocument)`, `of(entries,
thicknessPm)` for persisted rows or a format we do not parse (ODB++ — pass its matrix rows and
`.board_thickness`), and `estimate(layers)` from the classified layers, which is what most sets get.
An estimated stack has the right layers in the right order (legend, paste, mask, copper top → inner
by number → bottom, then out again), no dielectrics and no thickness anywhere.

**Either half can be missing and both cases are real**, so they resolve separately: a file that
states a thickness but no stack-up (EAGLE, ODB++) still answers `getBoardThicknessPm()` while the
layers are estimated; a KiCad 6/7 stack-up states layers with no thicknesses and the total still
comes from `GeneralSpecs`. Where nothing declares a total, it is the sum of the entries that state
one. `PcbAnalyzer` picks up a `.gbrjob` or an IPC-2581 file in the set automatically and prefers
whichever states more.

`parser.Ipc2581StackupParser` reads **only** the stack-up of an IPC-2581 file (`.cvg`/`.xml`) —
`CadHeader/@units`, the `Spec` material names, the `Layer` functions and the `Stackup` — as a StAX
stream that stops at `</Stackup>`, which sits in the first ~2% of the document. These files run to
158 MB and the stack-up is a few kB. It is the only format we read that states a thickness per layer
*and* for the board; an IPC-2581 stack-up group also lists documentation layers (assembly, courtyard,
drill guide) with `thickness="0"`, and `Ipc2581StackupDocument.Function.isPhysical()` is what keeps
them out of the stack.

Thickness is `Long` picometres, never a `double` of mm: 1 mil = 25 400 000 pm and 1 µin = 25 400 pm
exactly, so nominal values in either unit system are integers and a stack of them sums exactly (real
KiCad boards add back up to their declared 1.6 mm). Job files quote mm; the conversion happens once,
in `BoardStack`.

`StackFunction` is a **separate vocabulary** from `classify.LayerFunction` on purpose — the latter is
the role a *file* plays, and a dielectric is not a file. `LayerFunction.isPhysical()` (copper, mask,
silkscreen, paste) is the bridge: does this file occupy a z-position. There is **no CORE or PREPREG**
and the split is never inferred: the job file format has one `Dielectric` type and no field that
separates them, and across a corpus of 29 real `.gbrjob` files not one names either. Which layers a
fabricator builds from core and which from prepreg is the fabricator's call, and it is not in the
files. `OTHER` exists for a type we do not model (a flex coverlay, say) — the entry keeps its place,
name and thickness, so the stack still adds up to the board.

IPC-2581 *does* have `DIELCORE`/`DIELPREG` — and cannot be trusted on it: the Altium exports in the
corpus label every dielectric `DIELCORE`, including the ones whose `Spec` material is `PP-001`. The
raw value stays on `Ipc2581StackupDocument.StackupLayer.rawFunction()` and the material name is
carried through, so a caller who wants to make that call still can.

`spec/JobFileCorpusTest` pins what those 29 real files actually contain: exactly five `Type` values
(Copper, Dielectric, SolderMask, Legend, SolderPaste) and four stack shapes; copper entries always
equal `LayerNumber` with one fewer dielectric; stated thicknesses sum to the declared
`BoardThickness` exactly, on every board. KiCad writes a stack-up from v6 but thicknesses only from
v8; **EAGLE/Fusion writes no stack-up at all** and puts the general specs under `Overall` (with
`Name.ProjectId` the other way round), which `GerberJobParser` reads as a fallback — without it
those files are not even recognised as job files.

Pass `AnalysisDepth.SPECIFICATION` when you only want the specification: layers whose geometry
cannot change it (silkscreen above all) are then classified but never parsed. Parsing a Gerber
costs memory proportional to what it draws — a 27 MB silkscreen needs ~1 GB of heap to build and
contributes nothing once the set has an outline. `FULL` (the default) measures every layer, and is
what you want if you are going to keep the per-layer bounds to align rendered SVGs.
