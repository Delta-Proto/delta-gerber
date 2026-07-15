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
(*via in pad*), which forces a filled-and-capped via process (IPC-4761 Type VII) and drives cost.
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

Pass `AnalysisDepth.SPECIFICATION` when you only want the specification: layers whose geometry
cannot change it (silkscreen above all) are then classified but never parsed. Parsing a Gerber
costs memory proportional to what it draws — a 27 MB silkscreen needs ~1 GB of heap to build and
contributes nothing once the set has an outline. `FULL` (the default) measures every layer, and is
what you want if you are going to keep the per-layer bounds to align rendered SVGs.
