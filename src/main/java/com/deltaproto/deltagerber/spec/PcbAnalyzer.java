package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.classify.LayerClassifier;
import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.Tool;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;

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
        BoundingBox outline = outlineBounds(files, classifications);
        boolean usableOutline = outline != null && outline.getWidth() > 0 && outline.getHeight() > 0;

        List<AnalyzedLayer> layers = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            layers.add(measure(files.get(i), classifications.get(i), outline, usableOutline, depth));
        }
        return BoardSpecification.from(layers);
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
    private static boolean mustParse(LayerFunction function, boolean usableOutline, AnalysisDepth depth) {
        if (depth == AnalysisDepth.FULL) {
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
     * Union of the profile centrelines of every outline layer in the set.
     *
     * <p>Outline layers are parsed here and released; they are parsed a second time when measured.
     * A profile is a handful of draws, so the duplicated work is not worth avoiding — whereas
     * keeping every document alive to avoid it would defeat the point of the whole exercise.
     */
    private static BoundingBox outlineBounds(List<PcbFile> files, List<LayerClassification> classifications) {
        BoundingBox union = new BoundingBox();
        for (int i = 0; i < files.size(); i++) {
            LayerClassification classification = classifications.get(i);
            String content = files.get(i).getContent();
            if (classification == null || classification.function() != LayerFunction.OUTLINE
                    || content == null || content.isBlank()) {
                continue;
            }
            try {
                union.include(new GerberParser().parse(content).calculatePathBoundingBox());
            } catch (RuntimeException e) {
                log.warn("Could not parse outline {}: {}", files.get(i).getFileName(), e.toString());
            }
        }
        return union.isValid() ? union : null;
    }

    // ------------------------------------------------------------------------
    // Measure
    // ------------------------------------------------------------------------

    /** Parse one file, take its measurements, and let the document go before the next one. */
    private AnalyzedLayer measure(PcbFile file, LayerClassification classification,
                                  BoundingBox outline, boolean usableOutline, AnalysisDepth depth) {
        String content = file.getContent();
        if (content == null || content.isBlank()) {
            return AnalyzedLayer.builder(file.getFileName()).classification(classification).build();
        }

        LayerFunction function = classification == null ? LayerFunction.UNKNOWN : classification.function();
        if (!mustParse(function, usableOutline, depth)) {
            // Nothing this layer draws can change the specification. Its extent is unmeasured;
            // whether it draws at all still is, because an empty paste layer needs no stencil.
            return AnalyzedLayer.builder(file.getFileName())
                    .classification(classification)
                    .hasGeometry(hasGeometry(content))
                    .build();
        }

        try {
            return isExcellon(content, function)
                    ? measure(file.getFileName(), new ExcellonParser().parse(content), classification)
                    : measure(file.getFileName(), new GerberParser().parse(content), classification, outline);
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
        LayerFunction function = classification == null ? LayerFunction.UNKNOWN : classification.function();
        AnalyzedLayer.Builder layer = AnalyzedLayer.builder(fileName)
                .classification(classification)
                .warnings(document.getWarnings())
                // An outline measures to its centreline; every other layer to the ink it lays down.
                .bounds(function == LayerFunction.OUTLINE
                        ? document.calculatePathBoundingBox()
                        : document.getBoundingBox())
                .hasGeometry(!document.getObjects().isEmpty());
        if (function.isCopper()) {
            layer.minTrackWidthUm(minTrackWidthUm(document, outlineMm));
        }
        if (function.isDrill()) {
            layer.minDrillDiameterMm(minDrillDiameterMm(document));      // Gerber X2 drill file
        }
        return layer.build();
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
}
