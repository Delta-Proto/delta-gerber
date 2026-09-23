package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.align.DrillGerberAlignment;
import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.classify.LayerClassifier;
import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.dfm.AnnularRingDetector;
import com.deltaproto.deltagerber.dfm.AnnularRingResult;
import com.deltaproto.deltagerber.dfm.ClearanceDetector;
import com.deltaproto.deltagerber.dfm.ConductorWidthDetector;
import com.deltaproto.deltagerber.dfm.CopperLayer;
import com.deltaproto.deltagerber.dfm.DrillClearanceDetector;
import com.deltaproto.deltagerber.dfm.DrilledHole;
import com.deltaproto.deltagerber.dfm.HoleSpacingDetector;
import com.deltaproto.deltagerber.dfm.HoleSpacingResult;
import com.deltaproto.deltagerber.dfm.EdgeClearanceDetector;
import com.deltaproto.deltagerber.dfm.FloatingCopperDetector;
import com.deltaproto.deltagerber.dfm.FloatingCopperResult;
import com.deltaproto.deltagerber.dfm.geometry.BoardProfile;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.dfm.geometry.CopperNets;
import com.deltaproto.deltagerber.dfm.ViaInPadDetector;
import com.deltaproto.deltagerber.dfm.ViaInPadResult;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.Tool;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.Unit;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.GerberJobDocument;
import com.deltaproto.deltagerber.model.ipc2581.Ipc2581StackupDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberJobParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.parser.Ipc2581StackupParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a folder of Gerber and drill files into a {@link BoardSpecification}: what board they
 * describe, and what it costs to make.
 *
 * <p>Files are classified first, then measured, then reduced. Classification has to come first
 * because the measurements depend on it — a track width is only meaningful on copper, and the
 * board outline decides which copper features are tracks at all.
 *
 * <pre>{@code
 * BoardSpecification spec = new PcbAnalyzer().analyze(List.of(
 *         PcbFile.of("board.GTL", topCopper),
 *         PcbFile.of("board.GKO", outline),
 *         PcbFile.of("board-Plated.TXT", drill)));
 * spec.getSizeXMm();            // 32.0
 * spec.getMinTrackWidthUm();    // 100.0
 * spec.getMinDrillDiameterMm(); // 0.15
 * }</pre>
 */
public class PcbAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PcbAnalyzer.class);

    /**
     * Copper layers routinely carry a thin trace along the board edge that is not a track. Shrink
     * the outline by this much (mm) so that trace — whose endpoints sit exactly on the profile —
     * falls outside the board and stops dragging the minimum down.
     */
    private static final double OUTLINE_SHRINK_MM = 0.01;

    /**
     * The outline filter is only trusted when at least this fraction of a layer's draws land
     * inside it. Below that the outline evidently does not belong to this file — a different
     * project, a panelisation frame, a mirrored export — and a minimum drawn from a sliver of the
     * data would be worse than no filter at all. Real boards clear 95%.
     */
    private static final double MIN_INSIDE_FRACTION = 0.25;

    private boolean floatingInClearance;

    /**
     * Whether floating copper counts in the clearance and drill-to-copper checks. Off by default:
     * the letters of copper lettering are separate pieces a stroke's width apart, and every gap
     * between two of them would read as a clearance between nets — HQDFM leaves them out too. The
     * pieces themselves are always reported on {@link AnalyzedLayer#getFloatingCopper()}.
     *
     * @return this analyzer
     */
    public PcbAnalyzer floatingCopperInClearance(boolean include) {
        this.floatingInClearance = include;
        return this;
    }

    /**
     * Classify and measure every file, then reduce them to one board specification.
     *
     * <p>A file that fails to parse still contributes its classification; its measurements are
     * null and its parse errors land in {@link AnalyzedLayer#getWarnings()}.
     */
    public BoardSpecification analyze(List<PcbFile> files) {
        return analyze(files, AnalysisDepth.FULL);
    }

    /**
     * Classify and measure a set to the given {@link AnalysisDepth}, then reduce it to one board
     * specification. Nothing here renders; no SVG is ever built.
     *
     * <p>Files are parsed one at a time and released, so peak memory is set by the largest single
     * file rather than by the set. At {@link AnalysisDepth#SPECIFICATION} the files that cannot
     * affect the answer are never parsed at all.
     */
    public BoardSpecification analyze(List<PcbFile> files, AnalysisDepth depth) {
        if (files == null || files.isEmpty()) {
            return BoardSpecification.from(List.of());
        }

        // Classification reads headers only — cheap enough to do for the whole set up front.
        List<LayerClassification> classifications = new ArrayList<>(files.size());
        for (PcbFile file : files) {
            classifications.add(classify(file));
        }
        normalizeDerivedInnerCopper(files, classifications);

        // The outline governs the rest: it is the board size, it decides which copper features are
        // tracks, and it decides whether any other layer's extent matters. So it is resolved
        // across the whole set before any single layer is measured.
        List<GerberDocument> outlineDocs = outlineDocuments(files, classifications);
        BoundingBox outline = outlineBounds(outlineDocs);
        boolean usableOutline = outline != null && outline.getWidth() > 0 && outline.getHeight() > 0;
        BoardProfile profile = usableOutline ? BoardProfile.of(spanningOutlines(outlineDocs, outline)) : null;

        // Both DFM checks are relationships between two layers rather than measurements of one, so
        // what they need is collected across the whole set as it is parsed — the paste's pads, the
        // copper's pads and the drill's holes — and correlated once at the end. It is only worth
        // parsing the paste for this when the set actually has a drill to test against.
        boolean setHasDrill = classifications.stream()
                .anyMatch(c -> c != null && c.function().isDrill());
        DfmCollector dfm = new DfmCollector(profile, setHasDrill, floatingInClearance);

        // Drills and solder masks are measured before copper: what connects a piece of copper to
        // anything — a plated hole, a mask opening over a painted pad — is in them, and floating
        // copper is decided while the copper layer is still in memory. The layers keep file order.
        AnalyzedLayer[] layers = new AnalyzedLayer[files.size()];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < files.size(); i++) {
                LayerClassification c = classifications.get(i);
                boolean early = c != null && (c.function().isDrill() || c.function() == LayerFunction.SOLDERMASK);
                if (early == (pass == 0)) {
                    layers[i] = measure(files.get(i), c, outline, usableOutline, depth, setHasDrill, dfm);
                }
            }
        }
        dfm.correlate();
        return BoardSpecification.from(List.of(layers), dfm.viaInPad(), stackOf(files), dfm.annularRing(),
                dfm.holeSpacing());
    }

    /**
     * As {@link #analyze(List)}, for documents the caller has already parsed and classified — every
     * check runs exactly as it does from files: the DFM correlations (via in pad, annular ring, hole
     * spacing), floating copper, drill-to-copper and copper-to-edge clearance. Drills may already be
     * aligned into the Gerber frame; aligning them again is a no-op.
     *
     * <p>The documents are not modified, except that a drill tool with no stated plating takes it
     * from its file's classification, as it does in {@link #analyze(List)}. No stack-up is read, so
     * the stack is {@linkplain BoardStack#estimate estimated} from the layers.
     */
    public BoardSpecification analyzeParsed(List<ParsedLayer> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return BoardSpecification.from(List.of());
        }
        List<GerberDocument> outlineDocs = new ArrayList<>();
        boolean setHasDrill = false;
        for (ParsedLayer p : parsed) {
            LayerClassification c = p.classification();
            if (p.gerber() != null && c != null && c.function() == LayerFunction.OUTLINE) {
                outlineDocs.add(p.gerber());
            }
            setHasDrill |= p.drill() != null || c != null && c.function().isDrill();
        }
        BoundingBox outline = outlineBounds(outlineDocs);
        boolean usableOutline = outline != null && outline.getWidth() > 0 && outline.getHeight() > 0;
        BoardProfile profile = usableOutline ? BoardProfile.of(spanningOutlines(outlineDocs, outline)) : null;
        DfmCollector dfm = new DfmCollector(profile, setHasDrill, floatingInClearance);

        AnalyzedLayer[] layers = new AnalyzedLayer[parsed.size()];
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < parsed.size(); i++) {
                ParsedLayer p = parsed.get(i);
                LayerClassification c = p.classification();
                boolean early = p.drill() != null
                        || c != null && (c.function().isDrill() || c.function() == LayerFunction.SOLDERMASK);
                if (early == (pass == 0)) {
                    layers[i] = measureParsed(p, usableOutline ? outline : null, dfm);
                }
            }
        }
        dfm.correlate();
        return BoardSpecification.from(List.of(layers), dfm.viaInPad(), null, dfm.annularRing(),
                dfm.holeSpacing());
    }

    private static AnalyzedLayer measureParsed(ParsedLayer p, BoundingBox outline, DfmCollector dfm) {
        LayerClassification c = p.classification();
        LayerFunction function = c == null ? LayerFunction.UNKNOWN : c.function();
        try {
            if (p.drill() != null) {
                dfm.addDrill(p.drill(), function);
                return measure(p.fileName(), p.drill(), c);
            }
            dfm.addGerber(p.fileName(), c, function, p.gerber());
            return measure(p.fileName(), p.gerber(), c, outline,
                    function.isCopper() ? dfm.copperContext(c, p.gerber()) : null);
        } catch (RuntimeException e) {
            log.warn("Could not measure {}: {}", p.fileName(), e.toString());
            return AnalyzedLayer.builder(p.fileName()).classification(c).warnings(List.of(e.toString())).build();
        }
    }

    /**
     * The physical stack-up the set itself states, or {@link BoardStack#empty()} when nothing in it
     * does — which is the common case, and leaves the specification to
     * {@linkplain BoardStack#estimate estimate} the layers from the artwork.
     *
     * <p>Two files can carry one, and an IPC-2581 file is the better of the two when a set has
     * both: it states a thickness per layer where a Gerber job file often states none. Neither is
     * looked for anywhere else in the set — every other file is artwork or a drill program, and
     * none of them says anything about materials.
     */
    private static BoardStack stackOf(List<PcbFile> files) {
        BoardStack best = BoardStack.empty();
        for (PcbFile file : files) {
            BoardStack stack = stackOf(file);
            if (!stack.getEntries().isEmpty() && stack.getBoardThicknessPm() != null) {
                return stack;
            }
            if (best.isEmpty() || (best.getEntries().isEmpty() && !stack.getEntries().isEmpty())) {
                best = stack.isEmpty() ? best : stack;
            }
        }
        return best;
    }

    private static BoardStack stackOf(PcbFile file) {
        String name = file.getFileName();
        String content = file.getContent();
        if (name == null || content == null || content.isBlank()) {
            return BoardStack.empty();
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".gbrjob")) {
            GerberJobDocument job = new GerberJobParser().parse(content);
            return BoardStack.from(job);
        }
        if ((lower.endsWith(".cvg") || lower.endsWith(".xml"))
                && Ipc2581StackupParser.looksLikeIpc2581(content)) {
            Ipc2581StackupDocument stackup = new Ipc2581StackupParser().parse(content);
            return BoardStack.from(stackup);
        }
        return BoardStack.empty();
    }

    /**
     * Renumber the inner copper layers this analyzer classified itself, so the topmost is layer 1
     * whether the generator counted from 1 or from 2. See
     * {@link LayerClassifier#normalizeInnerCopperNumbers}.
     *
     * <p>A classification supplied on the {@link PcbFile} is left alone: it is already final —
     * stored on an earlier analysis, or chosen by a person — and renumbering it would overwrite
     * the very answer the caller passed in to be honoured.
     */
    private static void normalizeDerivedInnerCopper(List<PcbFile> files, List<LayerClassification> classifications) {
        List<LayerClassification> derived = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            derived.add(files.get(i).getClassification() == null ? classifications.get(i) : null);
        }
        derived = LayerClassifier.normalizeInnerCopperNumbers(derived);
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getClassification() == null) {
                classifications.set(i, derived.get(i));
            }
        }
    }

    /**
     * Whether this layer's geometry can change the board specification.
     *
     * <p>Copper carries the track width, drills the hole diameter, the outline the board size.
     * Everything else — silkscreen above all, which is routinely the largest file in the set —
     * only ever contributes its extent, and only when there is no outline to take the size from.
     */
    private static boolean mustParse(LayerFunction function, boolean usableOutline, AnalysisDepth depth,
                                     boolean setHasDrill) {
        if (depth == AnalysisDepth.FULL) {
            return true;
        }
        // Paste has to be parsed even at SPECIFICATION depth so via-in-pad can be checked against
        // the drill, and the mask so floating copper can tell a painted pad from lettering — but
        // only when the set has a drill, and both layers are small regardless.
        if ((function == LayerFunction.PASTE || function == LayerFunction.SOLDERMASK) && setHasDrill) {
            return true;
        }
        return function == LayerFunction.OUTLINE || function.isCopper() || function.isDrill()
                || !usableOutline;
    }

    /**
     * Classify a single file without measuring it. Cheap: reads the header, never parses. A
     * classification carried on the file itself is taken as final — see
     * {@link PcbFile#of(String, String, LayerClassification)}.
     */
    public LayerClassification classify(PcbFile file) {
        return file.getClassification() != null
                ? file.getClassification()
                : LayerClassifier.classify(file.getFileName(), file.getContent());
    }

    /**
     * Whether a Gerber file draws anything, as opposed to being a header with no geometry.
     *
     * <p>Scans for draw ({@code D01}) and flash ({@code D03}) commands rather than parsing, so it
     * costs one linear pass and no memory regardless of how much the file draws. Returns false for
     * content that is absent.
     */
    public static boolean hasGeometry(String gerberContent) {
        return gerberContent != null && DRAW_COMMAND.matcher(gerberContent).find();
    }

    private static final Pattern DRAW_COMMAND = Pattern.compile("D0?[13]\\*", Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------------------
    // Parse
    // ------------------------------------------------------------------------

    /**
     * Whether to read this file as Excellon rather than Gerber. The two formats are told apart by
     * what they declare, not by their extension: a KiCad "drill" file may well be Gerber X2, and
     * an Excellon file may be named {@code .txt}.
     */
    private static boolean isExcellon(String content, LayerFunction function) {
        String header = content.substring(0, Math.min(content.length(), 4096));
        if (header.contains("%FS") || header.contains("%MO") || header.contains("%AD")) {
            return false;                       // Gerber format/units/aperture directives.
        }
        return header.contains("M48") || header.contains("FMAT,") || function.isDrill();
    }

    /**
     * Every outline layer of the set, parsed.
     *
     * <p>They are parsed a second time when measured. A profile is a handful of draws, so keeping
     * them for the edge-clearance check and parsing them twice both cost next to nothing.
     */
    private static List<GerberDocument> outlineDocuments(List<PcbFile> files, List<LayerClassification> classifications) {
        List<GerberDocument> docs = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            LayerClassification classification = classifications.get(i);
            String content = files.get(i).getContent();
            if (classification == null || classification.function() != LayerFunction.OUTLINE
                    || content == null || content.isBlank()) {
                continue;
            }
            try {
                docs.add(new GerberParser().parse(content));
            } catch (RuntimeException e) {
                log.warn("Could not parse outline {}: {}", files.get(i).getFileName(), e.toString());
            }
        }
        return docs;
    }

    /**
     * The outline layers that draw the board's edge, as opposed to a mechanical layer the
     * classifier also called an outline: a layer counts when it spans at least
     * {@link #SPANNING_FRACTION} of the set's outline in both directions. The DEPR set's
     * {@code .GM1} is a dimension and some lettering inside the board, and read as edge it put copper
     * "at the edge" in the middle of a thermal pad. A cut-out drawn in the edge's own file is kept.
     */
    private static List<GerberDocument> spanningOutlines(List<GerberDocument> outlines, BoundingBox union) {
        List<GerberDocument> out = new ArrayList<>();
        for (GerberDocument doc : outlines) {
            BoundingBox b = doc.calculatePathBoundingBox();
            if (b != null && b.isValid() && b.getWidth() >= SPANNING_FRACTION * union.getWidth()
                    && b.getHeight() >= SPANNING_FRACTION * union.getHeight()) {
                out.add(doc);
            }
        }
        return out;
    }

    private static final double SPANNING_FRACTION = 0.9;

    /** Union of the profile centrelines of the outline layers. */
    private static BoundingBox outlineBounds(List<GerberDocument> outlines) {
        BoundingBox union = new BoundingBox();
        for (GerberDocument doc : outlines) {
            union.include(doc.calculatePathBoundingBox());
        }
        return union.isValid() ? union : null;
    }

    // ------------------------------------------------------------------------
    // Measure
    // ------------------------------------------------------------------------

    /** Parse one file, take its measurements, and let the document go before the next one. */
    private AnalyzedLayer measure(PcbFile file, LayerClassification classification,
                                  BoundingBox outline, boolean usableOutline, AnalysisDepth depth,
                                  boolean setHasDrill, DfmCollector dfm) {
        String content = file.getContent();
        if (content == null || content.isBlank()) {
            return AnalyzedLayer.builder(file.getFileName()).classification(classification).build();
        }

        LayerFunction function = classification == null ? LayerFunction.UNKNOWN : classification.function();
        if (!mustParse(function, usableOutline, depth, setHasDrill)) {
            // Nothing this layer draws can change the specification. Its extent is unmeasured;
            // whether it draws at all still is, because an empty paste layer needs no stencil.
            return AnalyzedLayer.builder(file.getFileName())
                    .classification(classification)
                    .hasGeometry(hasGeometry(content))
                    .build();
        }

        try {
            // Parse once, then both measure the layer and feed the DFM correlations, so the
            // paste/copper/drill documents are not parsed a second time for the checks.
            if (isExcellon(content, function)) {
                DrillDocument doc = new ExcellonParser().parse(content);
                doc.setFileName(file.getFileName());
                dfm.addDrill(doc, function);
                return measure(file.getFileName(), doc, classification);
            }
            GerberDocument doc = new GerberParser().parse(content);
            dfm.addGerber(file.getFileName(), classification, function, doc);
            return measure(file.getFileName(), doc, classification, outline,
                    function.isCopper() ? dfm.copperContext(classification, doc) : null);
        } catch (RuntimeException e) {
            log.warn("Could not parse {}: {}", file.getFileName(), e.toString());
            return AnalyzedLayer.builder(file.getFileName())
                    .classification(classification)
                    .warnings(List.of(e.toString()))
                    .build();
        }
    }

    /**
     * Measure a Gerber document that has already been parsed — for callers that parsed it for
     * some other purpose (rendering, say) and should not pay to parse it twice.
     *
     * @param classification what this layer is; decides which measurements even apply
     * @param outlineMm      board profile bounds in mm, or null — see {@link #minTrackWidthUm}
     */
    public static AnalyzedLayer measure(String fileName, GerberDocument document,
                                        LayerClassification classification, BoundingBox outlineMm) {
        return measure(fileName, document, classification, outlineMm, null);
    }

    /**
     * What a copper layer's checks need from the rest of the set: the board's edge, and the points
     * that connect copper — plated hole centres aligned into this layer's frame, and this side's
     * mask openings. Null fields mean "not in the set", and switch the check that needs them off.
     */
    record CopperContext(BoardProfile profile, List<double[]> anchors, List<DrilledHole> holes,
                         boolean holeAware, boolean floatingInClearance) {}

    private static AnalyzedLayer measure(String fileName, GerberDocument document,
                                         LayerClassification classification, BoundingBox outlineMm,
                                         CopperContext context) {
        LayerFunction function = classification == null ? LayerFunction.UNKNOWN : classification.function();
        AnalyzedLayer.Builder layer = AnalyzedLayer.builder(fileName)
                .classification(classification)
                .warnings(document.getWarnings())
                // An outline measures to its centreline; every other layer to the ink it lays down.
                .bounds(function == LayerFunction.OUTLINE
                        ? document.calculatePathBoundingBox()
                        : document.getBoundingBox())
                .hasGeometry(!document.getObjects().isEmpty())
                .formatSpec(document.getFormatSpec());
        if (function.isCopper()) {
            layer.minTrackWidthUm(minTrackWidthUm(document, outlineMm));
            measureCopperGeometry(layer, fileName, document, outlineMm, context);
        }
        if (function.isDrill()) {
            layer.minDrillDiameterMm(minDrillDiameterMm(document));      // Gerber X2 drill file
        }
        return layer.build();
    }

    /**
     * The figures that come from the copper's geometry rather than its aperture table: the tightest
     * gap between nets ({@link ClearanceDetector}), the narrowest copper
     * ({@link ConductorWidthDetector}), the copper nothing connects to
     * ({@link FloatingCopperDetector}, left out of the width) and the copper nearest the board's edge
     * ({@link EdgeClearanceDetector}). All run on one {@link CopperGeometry} built while the document
     * is still in memory, and cost well under a second on the largest board in the corpus. A failure
     * inside them is logged and leaves the figures unmeasured; it never fails the analysis.
     *
     * <p>Floating copper only runs when the set has a drill: without the holes, a plane joined to
     * its net by padless vias would read as floating and vanish from the width.
     */
    private static void measureCopperGeometry(AnalyzedLayer.Builder layer, String fileName,
                                              GerberDocument document, BoundingBox outlineMm,
                                              CopperContext context) {
        try {
            CopperGeometry geometry = CopperGeometry.of(document, strokeFilter(document, outlineMm));
            CopperNets nets = CopperNets.of(geometry);
            FloatingCopperResult floating = context != null && context.holeAware()
                    ? FloatingCopperDetector.detect(nets, fileName, context.anchors(), true)
                    : null;
            layer.floatingCopper(floating);
            FloatingCopperResult leftOut = context != null && context.floatingInClearance() ? null : floating;
            layer.clearance(ClearanceDetector.detect(nets, fileName, ClearanceDetector.DEFAULT_CUTOFF_MM, leftOut));
            layer.conductorWidth(ConductorWidthDetector.detect(geometry, fileName,
                    ConductorWidthDetector.DEFAULT_CUTOFF_MM, floating));
            if (context != null && context.profile() != null) {
                layer.edgeClearance(EdgeClearanceDetector.detect(geometry, context.profile(), fileName,
                        EdgeClearanceDetector.DEFAULT_CUTOFF_MM));
            }
            if (context != null && context.holeAware()) {
                layer.drillClearance(DrillClearanceDetector.detect(nets, fileName, context.holes(),
                        DrillClearanceDetector.DEFAULT_CUTOFF_MM, leftOut));
            }
        } catch (RuntimeException e) {
            log.warn("copper geometry of {} not measured: {}", fileName, e.toString());
        }
    }

    /**
     * The box a stroke must lie inside to count as the board's copper — the outline shrunk by
     * {@link #OUTLINE_SHRINK_MM} — or null when there is no outline or it evidently is not this
     * layer's (fewer than {@link #MIN_INSIDE_FRACTION} of the strokes inside it). The same rule
     * {@link #minTrackWidthUm} applies, so the geometric figures skip the same edge trace.
     */
    static BoundingBox strokeFilter(GerberDocument document, BoundingBox outlineMm) {
        if (outlineMm == null) {
            return null;
        }
        int total = 0;
        int inside = 0;
        for (GraphicsObject object : document.getObjects()) {
            double startX, startY, endX, endY;
            if (object instanceof Draw draw) {
                startX = draw.getStartX();
                startY = draw.getStartY();
                endX = draw.getEndX();
                endY = draw.getEndY();
            } else if (object instanceof Arc arc) {
                startX = arc.getStartX();
                startY = arc.getStartY();
                endX = arc.getEndX();
                endY = arc.getEndY();
            } else {
                continue;
            }
            total++;
            if (isInside(startX, startY, outlineMm) && isInside(endX, endY, outlineMm)) {
                inside++;
            }
        }
        if (total > 0 && inside < total * MIN_INSIDE_FRACTION) {
            return null;
        }
        return new BoundingBox(outlineMm.getMinX() + OUTLINE_SHRINK_MM, outlineMm.getMinY() + OUTLINE_SHRINK_MM,
                outlineMm.getMaxX() - OUTLINE_SHRINK_MM, outlineMm.getMaxY() - OUTLINE_SHRINK_MM);
    }

    /** Measure an Excellon document that has already been parsed. */
    public static AnalyzedLayer measure(String fileName, DrillDocument document,
                                        LayerClassification classification) {
        return AnalyzedLayer.builder(fileName)
                .classification(classification)
                .warnings(document.getWarnings())
                .bounds(document.getBoundingBox())
                .hasGeometry(!document.getOperations().isEmpty())
                .minDrillDiameterMm(minDrillDiameterMm(document))
                // A file that declared no format and drilled no hole states nothing: it is a tool
                // report or a status log that the .txt extension made look like a drill, and its
                // "format" would be the parser's defaults, disagreeing with the real drill file.
                .formatSpec(document.isFormatDeclared() || !document.getOperations().isEmpty()
                        ? document.getFormatSpec()
                        : null)
                .build();
    }

    /**
     * Narrowest circular aperture actually used to draw a track, in micrometres.
     *
     * <p>Only stroked draws and arcs count. A flash is a pad and a region is a pour; neither is a
     * track, and both routinely use apertures far smaller than any track on the layer.
     *
     * <p>When {@code outlineMm} is given, a draw only counts if both its endpoints sit strictly
     * inside the board (see {@link #OUTLINE_SHRINK_MM}). This is what excludes the board-edge
     * trace that most tools stamp onto every copper layer. The filter is a bounding box, so on a
     * strongly non-rectangular board an edge trace can still slip through near the corners of the
     * box; it degrades to the unfiltered answer rather than to a wrong one.
     *
     * @param outlineMm board profile bounds in mm, or null to skip the filter
     * @return the minimum in µm, or null when the layer draws no circular-aperture track
     */
    public static Double minTrackWidthUm(GerberDocument document, BoundingBox outlineMm) {
        Map<Integer, Double> drawnApertures = new LinkedHashMap<>();
        Map<Integer, Integer> insideCounts = new HashMap<>();
        int totalDraws = 0;
        int insideDraws = 0;

        for (GraphicsObject object : document.getObjects()) {
            double startX;
            double startY;
            double endX;
            double endY;
            Aperture aperture;
            if (object instanceof Draw draw) {
                startX = draw.getStartX();
                startY = draw.getStartY();
                endX = draw.getEndX();
                endY = draw.getEndY();
                aperture = draw.getAperture();
            } else if (object instanceof Arc arc) {
                startX = arc.getStartX();
                startY = arc.getStartY();
                endX = arc.getEndX();
                endY = arc.getEndY();
                aperture = arc.getAperture();
            } else {
                continue;
            }
            if (!(aperture instanceof CircleAperture circle)) {
                continue;
            }

            totalDraws++;
            drawnApertures.put(circle.getDCode(), circle.getDiameter());
            if (outlineMm != null && isInside(startX, startY, outlineMm) && isInside(endX, endY, outlineMm)) {
                insideCounts.merge(circle.getDCode(), 1, Integer::sum);
                insideDraws++;
            }
        }

        if (drawnApertures.isEmpty()) {
            return null;
        }

        if (outlineMm != null && !insideCounts.isEmpty() && insideDraws >= totalDraws * MIN_INSIDE_FRACTION) {
            Double min = drawnApertures.entrySet().stream()
                    .filter(e -> insideCounts.containsKey(e.getKey()))
                    .map(Map.Entry::getValue)
                    .min(Double::compare)
                    .orElse(null);
            if (min != null) {
                return min * 1000.0;
            }
        } else if (outlineMm != null && totalDraws > 0) {
            log.warn("min-track: outline filter rejected for {} — {}/{} draws inside the outline; "
                    + "falling back to the smallest drawn aperture overall",
                    document.getFileName(), insideDraws, totalDraws);
        }

        return drawnApertures.values().stream().min(Double::compare).orElseThrow() * 1000.0;
    }

    private static boolean isInside(double x, double y, BoundingBox outline) {
        return x > outline.getMinX() + OUTLINE_SHRINK_MM
            && x < outline.getMaxX() - OUTLINE_SHRINK_MM
            && y > outline.getMinY() + OUTLINE_SHRINK_MM
            && y < outline.getMaxY() - OUTLINE_SHRINK_MM;
    }

    /** Smallest tool in an Excellon drill file, in millimetres; null when it defines no tools. */
    public static Double minDrillDiameterMm(DrillDocument document) {
        return document.getTools().values().stream()
                .map(Tool::getDiameter)
                .min(Double::compare)
                .orElse(null);
    }

    /**
     * Smallest hole in a Gerber X2 drill file, in millimetres. Such files (KiCad writes them)
     * express each drill as a circular aperture rather than an Excellon tool.
     *
     * @return the minimum, or null when the file defines no circular apertures
     */
    public static Double minDrillDiameterMm(GerberDocument document) {
        return document.getApertures().values().stream()
                .filter(CircleAperture.class::isInstance)
                .map(a -> ((CircleAperture) a).getDiameter())
                .min(Double::compare)
                .orElse(null);
    }

    // ------------------------------------------------------------------------
    // DFM correlations
    // ------------------------------------------------------------------------

    /**
     * Gathers what the two-layer DFM checks need while the set is parsed once — the paste pads for
     * via-in-pad, the copper pads for the annular ring, and the drill holes both are measured
     * against — and correlates them when every file has been read.
     *
     * <p>The heavy documents are released as each layer is measured. What is kept from copper is
     * only what can be a pad: every flash, and a stroke no longer than it is wide (several tools
     * draw an oblong through-hole pad as a swept aperture, and nothing else that short is copper a
     * hole sits in). Traces and pours — the bulk of a copper layer, and all of its memory — are
     * dropped, so the checks do not defeat the one-file-at-a-time memory model.
     */
    private static final class DfmCollector {

        /**
         * How many times its own width a stroke may be and still be a pad. A pad drawn as a swept
         * aperture is a stub — a 3:1 oval pad sweeps twice its width — while a trace runs for tens
         * of times its width, so this separates the two without having to know which tool wrote the
         * file.
         */
        private static final double MAX_PAD_STROKE_RATIO = 2.0;

        private final List<GerberDocument> topPaste = new ArrayList<>();
        private final List<GerberDocument> bottomPaste = new ArrayList<>();
        private final List<DrillDocument> drills = new ArrayList<>();
        private final List<CopperLayer> copperPads = new ArrayList<>();
        private final List<double[]> copperFlashCenters = new ArrayList<>();
        private final BoundingBox copperBounds = new BoundingBox();
        private final List<double[]> topMaskOpenings = new ArrayList<>();
        private final List<double[]> bottomMaskOpenings = new ArrayList<>();
        private final BoardProfile profile;
        private final boolean setHasDrill;
        private final boolean floatingInClearance;
        private ViaInPadResult viaInPad;
        private AnnularRingResult annularRing;
        private HoleSpacingResult holeSpacing;

        DfmCollector(BoardProfile profile, boolean setHasDrill, boolean floatingInClearance) {
            this.profile = profile;
            this.setHasDrill = setHasDrill;
            this.floatingInClearance = floatingInClearance;
        }

        /**
         * What one copper layer's checks need from the rest of the set, which {@code analyze} has
         * measured first: the plated holes, aligned onto this layer — alignment is per layer here
         * because the set-wide alignment runs only once every copper layer has been read — and the
         * mask openings on this layer's side.
         */
        CopperContext copperContext(LayerClassification classification, GerberDocument doc) {
            List<double[]> anchors = new ArrayList<>();
            List<DrilledHole> holes = List.of();
            if (!drills.isEmpty()) {
                holes = DrilledHole.of(DrillGerberAlignment.alignedAll(drills, doc.getBoundingBox(),
                        DrillGerberAlignment.flashCenters(doc)));
                for (DrilledHole hole : holes) {
                    if (!Boolean.FALSE.equals(hole.plated())) {
                        anchors.add(new double[]{hole.xMm(), hole.yMm()});
                    }
                }
            }
            LayerSide side = classification == null ? LayerSide.UNKNOWN : classification.side();
            if (side == LayerSide.TOP) {
                anchors.addAll(topMaskOpenings);
            } else if (side == LayerSide.BOTTOM) {
                anchors.addAll(bottomMaskOpenings);
            }
            return new CopperContext(profile, anchors, holes, setHasDrill && !drills.isEmpty(),
                    floatingInClearance);
        }

        /**
         * A point inside every opening a solder-mask layer makes: a flash's centre and the midpoint
         * of each stroke — a painted opening is strokes, and each of them lies inside the pad it
         * exposes. Regions are left out: a region's centre need not be inside it.
         */
        private static void maskOpenings(GerberDocument doc, List<double[]> out) {
            for (GraphicsObject obj : doc.getObjects()) {
                if (obj.getPolarity() != Polarity.DARK) {
                    continue;
                }
                if (obj instanceof Flash flash) {
                    out.add(new double[]{flash.getX(), flash.getY()});
                } else if (obj instanceof Draw draw) {
                    out.add(new double[]{(draw.getStartX() + draw.getEndX()) / 2, (draw.getStartY() + draw.getEndY()) / 2});
                }
            }
        }

        void addDrill(DrillDocument doc, LayerFunction function) {
            // What the file is called says something about plating, and what it contains says more:
            // Excellon states it per tool in a ;TYPE= comment, and one Altium file holds both kinds.
            // So the classification only fills in the tools that stated nothing themselves.
            Boolean stated = function == LayerFunction.DRILL_PLATED ? Boolean.TRUE
                    : function == LayerFunction.DRILL_NONPLATED ? Boolean.FALSE : null;
            if (stated != null) {
                for (Tool tool : doc.getTools().values()) {
                    if (tool.getPlated() == null) {
                        tool.setPlated(stated);
                    }
                }
            }
            drills.add(doc);
        }

        void addGerber(String fileName, LayerClassification classification,
                       LayerFunction function, GerberDocument doc) {
            LayerSide side = classification == null ? LayerSide.NA : classification.side();
            if (function.isDrill()) {
                // KiCad and others can write the drill program as Gerber X2 instead of Excellon.
                // It is still a drill program, and both checks want its holes.
                addDrill(gerberDrill(fileName, doc), function);
                return;
            }
            if (function == LayerFunction.PASTE) {
                // A sideless paste layer (rare) is assumed top; its pads still count either way.
                (side == LayerSide.BOTTOM ? bottomPaste : topPaste).add(doc);
            } else if (function == LayerFunction.SOLDERMASK) {
                maskOpenings(doc, side == LayerSide.BOTTOM ? bottomMaskOpenings : topMaskOpenings);
            } else if (function.isCopper()) {
                List<GraphicsObject> pads = new ArrayList<>();
                for (GraphicsObject obj : doc.getObjects()) {
                    if (obj instanceof Flash flash) {
                        pads.add(obj);
                        copperFlashCenters.add(new double[]{flash.getX(), flash.getY()});
                    } else if (isPadStroke(obj)) {
                        pads.add(obj);
                    }
                }
                copperPads.add(CopperLayer.of(fileName, side,
                        classification == null ? null : classification.number(), pads));
                BoundingBox b = doc.getBoundingBox();
                if (b != null && b.isValid()) {
                    copperBounds.include(b);
                }
            }
        }

        /**
         * A Gerber X2 drill layer ({@code *-PTH-drl.gbr}) read as a drill program: each hole is a
         * flashed circle whose aperture diameter is the tool that drills it. Anything else the
         * layer draws is a slot, which neither check measures.
         */
        private static DrillDocument gerberDrill(String fileName, GerberDocument doc) {
            DrillDocument out = new DrillDocument();
            out.setFileName(fileName);
            out.setUnit(Unit.MM);
            Map<Double, Tool> tools = new LinkedHashMap<>();
            for (GraphicsObject obj : doc.getObjects()) {
                if (!(obj instanceof Flash flash)
                        || !(flash.getAperture() instanceof CircleAperture circle)
                        || circle.getDiameter() <= 0) {
                    continue;
                }
                Tool tool = tools.get(circle.getDiameter());
                if (tool == null) {
                    tool = new Tool(tools.size() + 1, circle.getDiameter());
                    tools.put(circle.getDiameter(), tool);
                    out.addTool(tool);
                }
                out.addOperation(new DrillHit(tool, flash.getX(), flash.getY()));
            }
            return out;
        }

        /** A stroke short enough to be a pad rather than a trace — see {@link #MAX_PAD_STROKE_RATIO}. */
        private static boolean isPadStroke(GraphicsObject obj) {
            double length;
            Aperture aperture;
            if (obj instanceof Draw draw) {
                length = Math.hypot(draw.getEndX() - draw.getStartX(), draw.getEndY() - draw.getStartY());
                aperture = draw.getAperture();
            } else if (obj instanceof Arc arc) {
                length = arc.getBoundingBox().getWidth() + arc.getBoundingBox().getHeight();
                aperture = arc.getAperture();
            } else {
                return false;
            }
            if (aperture == null) {
                return false;
            }
            BoundingBox ab = aperture.getBoundingBox();
            double width = ab.isValid() ? Math.min(ab.getWidth(), ab.getHeight()) : 0;
            return width > 0 && length <= MAX_PAD_STROKE_RATIO * width;
        }

        /**
         * Correlate the collected pads and holes, once, after the last file has been read. The
         * drills are aligned into the Gerber frame here and both checks share that one answer —
         * resolved as a set, so a drill file with no hole centres of its own (Altium writes slots
         * separately) takes the offset its siblings recovered.
         */
        void correlate() {
            if (drills.isEmpty()) {
                return;     // neither check can say anything without holes
            }
            List<DrillDocument> aligned =
                DrillGerberAlignment.alignedAll(drills, copperBounds, copperFlashCenters);
            if (!topPaste.isEmpty() || !bottomPaste.isEmpty()) {
                viaInPad = ViaInPadDetector.detect(topPaste, bottomPaste, aligned);
            }
            if (!copperPads.isEmpty()) {
                annularRing = AnnularRingDetector.detect(copperPads, aligned);
            }
            holeSpacing = HoleSpacingDetector.detect(aligned, HoleSpacingDetector.DEFAULT_CUTOFF_MM);
        }

        /** Hole-to-hole spacing, or {@code null} when the set has no drill. */
        HoleSpacingResult holeSpacing() {
            return holeSpacing;
        }

        /** Via in pad, or {@code null} — "not determined" — when the set has no paste or no drill. */
        ViaInPadResult viaInPad() {
            return viaInPad;
        }

        /** Annular rings, or {@code null} — "not determined" — when the set has no copper or no drill. */
        AnnularRingResult annularRing() {
            return annularRing;
        }
    }
}
