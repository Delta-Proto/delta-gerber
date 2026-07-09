package com.deltaproto.deltagerber.classify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out what each file in a Gerber/drill set is, from its name and its header.
 *
 * <p>Evidence is weighed strongest-first, because the strong signals are declarations and the weak
 * ones are conventions:
 *
 * <ol>
 *   <li>the Gerber X2 {@code .FileFunction} attribute — the file says what it is;
 *   <li>Cadence Allegro's proprietary {@code FILE IDENTIFICATION RECORD} comment block;
 *   <li>an Excellon {@code ;TYPE=PLATED} / {@code ;TYPE=NON_PLATED} header comment;
 *   <li>filename patterns, ordered by the CAD tool detected from {@code .GenerationSoftware}.
 * </ol>
 *
 * <p>Only step 4 can be wrong for a well-formed file, and it is the only step that needs to know
 * about CAD tools at all. Steps 1–3 read only the first few kilobytes, so passing a truncated
 * header rather than a whole file is fine and cheap.
 *
 * <h2>Naming conventions</h2>
 * <pre>
 * Layer            Function     Side    Protel            KiCad            Eagle              Altium              Other
 * ---------------- ------------ ------- ----------------- ---------------- ------------------ ------------------- --------------------------------
 * Top copper       COPPER       TOP     .GTL              F.Cu / F_Cu      toplayer           Copper_Signal_Top   .cmp .top copper_top
 * Bottom copper    COPPER       BOTTOM  .GBL              B.Cu / B_Cu      bottomlayer        Copper_Signal_Bot   .sol .bot copper_bottom
 * Inner copper n   COPPER       INNER   .G&lt;n&gt;L / .G&lt;n&gt;    In&lt;n&gt;.Cu          internalplane&lt;n&gt;    Copper_Signal_&lt;n&gt;   .ly&lt;n&gt; .in&lt;n&gt;
 * Top soldermask   SOLDERMASK   TOP     .GTS              F.Mask           topsoldermask      Soldermask_Top      .stc .tsm .smt mask_top
 * Bot soldermask   SOLDERMASK   BOTTOM  .GBS              B.Mask           bottomsoldermask   Soldermask_Bot      .sts .bsm .smb mask_bottom
 * Top silkscreen   SILKSCREEN   TOP     .GTO              F.SilkS          topsilkscreen      Legend_Top          .plc .tsk .sst legend_top
 * Bot silkscreen   SILKSCREEN   BOTTOM  .GBO              B.SilkS          bottomsilkscreen   Legend_Bot          .pls .bsk .ssb legend_bottom
 * Top paste        PASTE        TOP     .GTP              F.Paste          tcream / toppaste  Paste_Top           .crc .tsp .spt
 * Bottom paste     PASTE        BOTTOM  .GBP              B.Paste          bcream             Paste_Bot           .crs .bsp .spb
 * Board outline    OUTLINE      NA      .GKO .GM .GM1     Edge.Cuts        boardoutline       Profile             .dim .mil .fab
 * Plated drill     DRILL_PLATED NA      -PTH.TXT/.DRL     -PTH.drl         drills_pth         PTH                 .cnc .tap .drd .exc
 * Non-plated drill DRILL_NONPL. NA      -NPTH.TXT/.DRL    -NPTH.drl        holes_npth         NPTH                .npt
 * Drill drawing    FAB_DRAWING  NA      .GD1 .GML                                             DrillMap            .gg1
 * </pre>
 */
public final class LayerClassifier {

    /** Enough to cover any header block we read; classification never needs the body. */
    public static final int HEADER_BYTES = 10240;

    private LayerClassifier() {
    }

    // ------------------------------------------------------------------------
    // Filename conventions, one table per CAD tool
    // ------------------------------------------------------------------------

    /** Matches the last run of digits in a filename — the inner-layer index. */
    private static final String TRAILING_NUMBER = "(?:\\d+)(?!.*\\d)";

    private static final List<LayerPattern> PROTEL = List.of(
        new LayerPattern("top copper", LayerFunction.COPPER, LayerSide.TOP, "\\.gtl$"),
        new LayerPattern("bottom copper", LayerFunction.COPPER, LayerSide.BOTTOM, "\\.gbl$"),
        new LayerPattern("inner copper %d", LayerFunction.COPPER, LayerSide.INNER, "\\.(g\\d+l?|g\\d+)$", TRAILING_NUMBER),
        new LayerPattern("top soldermask", LayerFunction.SOLDERMASK, LayerSide.TOP, "\\.gts$"),
        new LayerPattern("bottom soldermask", LayerFunction.SOLDERMASK, LayerSide.BOTTOM, "\\.gbs$"),
        new LayerPattern("top silkscreen", LayerFunction.SILKSCREEN, LayerSide.TOP, "\\.gto$"),
        new LayerPattern("bottom silkscreen", LayerFunction.SILKSCREEN, LayerSide.BOTTOM, "\\.gbo$"),
        new LayerPattern("top paste", LayerFunction.PASTE, LayerSide.TOP, "\\.gtp$"),
        new LayerPattern("bottom paste", LayerFunction.PASTE, LayerSide.BOTTOM, "\\.gbp$"),
        // .GKO is Protel's "Gerber KeepOut", but every fabricator — and Altium's own export —
        // uses it as the board profile, so that is what it means here.
        new LayerPattern("board outline", LayerFunction.OUTLINE, LayerSide.NA, "\\.(gko|gm1?|gm)$"),
        // Specific drill variants must come before the generic drill pattern.
        new LayerPattern("plated drill", LayerFunction.DRILL_PLATED, LayerSide.NA, "[-_](?:pth|plated)\\.(?:txt|drl|xln)$"),
        new LayerPattern("non-plated drill", LayerFunction.DRILL_NONPLATED, LayerSide.NA, "[-_](?:npth|non[-_]?plated)\\.(?:txt|drl|xln)$"),
        new LayerPattern("drill", LayerFunction.DRILL, LayerSide.NA, "\\.(txt|drl|xln)$"),
        new LayerPattern("drill drawing", LayerFunction.FAB_DRAWING, LayerSide.NA, "\\.(gd1|gml)$")
    );

    // Anchored to the end of the name: unanchored, "board-B_Cu.gbr.svg" is a bottom copper layer.
    private static final List<LayerPattern> KICAD = List.of(
        new LayerPattern("top copper", LayerFunction.COPPER, LayerSide.TOP, "F[_.]Cu\\.(gbr|gtl)$"),
        new LayerPattern("bottom copper", LayerFunction.COPPER, LayerSide.BOTTOM, "B[_.]Cu\\.gbr$"),
        new LayerPattern("inner copper %d", LayerFunction.COPPER, LayerSide.INNER, "In(ner)?\\d+[_.]Cu\\.(gbr|g\\d+)$", TRAILING_NUMBER),
        new LayerPattern("top soldermask", LayerFunction.SOLDERMASK, LayerSide.TOP, "F[_.]Mask\\.(gbr|gts)$"),
        new LayerPattern("bottom soldermask", LayerFunction.SOLDERMASK, LayerSide.BOTTOM, "B[_.]Mask\\.(gbr|gbs)$"),
        new LayerPattern("top silkscreen", LayerFunction.SILKSCREEN, LayerSide.TOP, "F[_.]SilkS(creen)?\\.(gbr|gto)$"),
        new LayerPattern("bottom silkscreen", LayerFunction.SILKSCREEN, LayerSide.BOTTOM, "B[_.]SilkS(creen)?\\.(gbr|gbo)$"),
        new LayerPattern("top paste", LayerFunction.PASTE, LayerSide.TOP, "F[_.]Paste\\.(gbr|gtp)$"),
        new LayerPattern("bottom paste", LayerFunction.PASTE, LayerSide.BOTTOM, "B[_.]Paste\\.(gbr|gbp)$"),
        new LayerPattern("board outline", LayerFunction.OUTLINE, LayerSide.NA, "Edge[_.]Cuts\\.(gbr|gm1?)$"),
        new LayerPattern("plated drill", LayerFunction.DRILL_PLATED, LayerSide.NA, "[-_](?:pth|plated)\\.(?:drl|txt|xln)$"),
        new LayerPattern("non-plated drill", LayerFunction.DRILL_NONPLATED, LayerSide.NA, "[-_](?:npth|non[-_]?plated)\\.(?:drl|txt|xln)$"),
        new LayerPattern("drill", LayerFunction.DRILL, LayerSide.NA, "\\.drl$")
    );

    private static final List<LayerPattern> EAGLE = List.of(
        new LayerPattern("top copper", LayerFunction.COPPER, LayerSide.TOP, "\\.toplayer\\.ger$"),
        new LayerPattern("bottom copper", LayerFunction.COPPER, LayerSide.BOTTOM, "\\.bottomlayer\\.ger$"),
        new LayerPattern("inner copper %d", LayerFunction.COPPER, LayerSide.INNER, "\\.internalplane\\d+\\.ger$", TRAILING_NUMBER),
        new LayerPattern("top soldermask", LayerFunction.SOLDERMASK, LayerSide.TOP, "\\.topsoldermask\\.ger$"),
        new LayerPattern("bottom soldermask", LayerFunction.SOLDERMASK, LayerSide.BOTTOM, "\\.bottomsoldermask\\.ger$"),
        new LayerPattern("top silkscreen", LayerFunction.SILKSCREEN, LayerSide.TOP, "\\.topsilkscreen\\.ger$"),
        new LayerPattern("bottom silkscreen", LayerFunction.SILKSCREEN, LayerSide.BOTTOM, "\\.bottomsilkscreen\\.ger$"),
        new LayerPattern("top paste", LayerFunction.PASTE, LayerSide.TOP, "\\.(tcream|toppaste)\\.ger$"),
        new LayerPattern("bottom paste", LayerFunction.PASTE, LayerSide.BOTTOM, "\\.(bcream|bottompaste)\\.ger$"),
        new LayerPattern("board outline", LayerFunction.OUTLINE, LayerSide.NA, "\\.(boardout|boardoutline|outline)\\.ger$"),
        new LayerPattern("drill", LayerFunction.DRILL, LayerSide.NA, "\\.(drills|drills_pth)\\.xln$")
    );

    private static final List<LayerPattern> ALTIUM = List.of(
        new LayerPattern("top copper", LayerFunction.COPPER, LayerSide.TOP, "_Copper_Signal_Top\\.gbr$"),
        new LayerPattern("bottom copper", LayerFunction.COPPER, LayerSide.BOTTOM, "_Copper_Signal_Bot\\.gbr$"),
        new LayerPattern("inner copper %d", LayerFunction.COPPER, LayerSide.INNER, "_Copper_Signal_\\d+\\.gbr$", TRAILING_NUMBER),
        new LayerPattern("top soldermask", LayerFunction.SOLDERMASK, LayerSide.TOP, "_Soldermask_Top\\.gbr$"),
        new LayerPattern("bottom soldermask", LayerFunction.SOLDERMASK, LayerSide.BOTTOM, "_Soldermask_Bot\\.gbr$"),
        new LayerPattern("top silkscreen", LayerFunction.SILKSCREEN, LayerSide.TOP, "_Legend_Top\\.gbr$"),
        new LayerPattern("bottom silkscreen", LayerFunction.SILKSCREEN, LayerSide.BOTTOM, "_Legend_Bot\\.gbr$"),
        new LayerPattern("top paste", LayerFunction.PASTE, LayerSide.TOP, "_Paste_Top\\.gbr$"),
        new LayerPattern("bottom paste", LayerFunction.PASTE, LayerSide.BOTTOM, "_Paste_Bot\\.gbr$"),
        new LayerPattern("board outline", LayerFunction.OUTLINE, LayerSide.NA, "_Profile\\.gbr$"),
        new LayerPattern("plated drill", LayerFunction.DRILL_PLATED, LayerSide.NA, "[-_](?:pth|plated)\\.(?:drl|txt|xln)$"),
        new LayerPattern("non-plated drill", LayerFunction.DRILL_NONPLATED, LayerSide.NA, "[-_](?:npth|non[-_]?plated)\\.(?:drl|txt|xln)$")
    );

    private static final List<LayerPattern> GENERIC = List.of(
        new LayerPattern("top copper", LayerFunction.COPPER, LayerSide.TOP, "(copper_top\\.(gbr|txt))|\\.((cmp|top)$)|(top copper\\.txt$)"),
        new LayerPattern("bottom copper", LayerFunction.COPPER, LayerSide.BOTTOM, "(copper_(bottom|bot)\\.(gbr|txt))|\\.((sol|bot)$)|(bottom copper\\.txt$)"),
        new LayerPattern("inner copper %d", LayerFunction.COPPER, LayerSide.INNER, "\\.(ly|in)\\d+$", TRAILING_NUMBER),
        new LayerPattern("top soldermask", LayerFunction.SOLDERMASK, LayerSide.TOP, "(mask_top\\.gbr)|(topmask)|(\\.((stc|tsm|smt)$))|(top solder resist\\.txt$)"),
        new LayerPattern("bottom soldermask", LayerFunction.SOLDERMASK, LayerSide.BOTTOM, "(mask_(bottom|bot)\\.gbr)|(bottommask)|(\\.((sts|bsm|smb)$))|(bottom solder resist\\.txt$)"),
        new LayerPattern("top silkscreen", LayerFunction.SILKSCREEN, LayerSide.TOP, "(legend_top\\.gbr)|(topsilk)|(\\.((plc|tsk|sst)$))|(top silk screen\\.txt$)"),
        new LayerPattern("bottom silkscreen", LayerFunction.SILKSCREEN, LayerSide.BOTTOM, "(legend_(bottom|bot)\\.gbr)|(bottomsilk)|(\\.((pls|bsk|ssb)$))|(bottom silk screen\\.txt$)"),
        new LayerPattern("top paste", LayerFunction.PASTE, LayerSide.TOP, "(paste_top\\.gbr)|(\\.((crc|tsp|spt)$))"),
        new LayerPattern("bottom paste", LayerFunction.PASTE, LayerSide.BOTTOM, "(paste_(bottom|bot)\\.gbr)|(\\.((crs|bsp|spb)$))"),
        new LayerPattern("board outline", LayerFunction.OUTLINE, LayerSide.NA, "(\\.outline\\.gbr$)|(\\.((dim|mil|fab)$))|(mechanical \\d+\\.txt$)"),
        new LayerPattern("drill", LayerFunction.DRILL, LayerSide.NA, "\\.(cnc|tap|drd|exc|npt)$"),
        new LayerPattern("drill drawing", LayerFunction.FAB_DRAWING, LayerSide.NA, "\\.(gg1)$")
    );

    /**
     * Last resort: a {@code .gbr} nothing else recognised is some kind of drawing.
     *
     * <p>Deliberately outside the tables. As an entry in {@link #GENERIC} it would swallow every
     * {@code .gbr} before the KiCad and Altium conventions were ever tried, so a header-less
     * {@code board-Edge_Cuts.gbr} would come back a drawing rather than the board profile.
     */
    private static final LayerPattern GERBER_DRAWING =
        new LayerPattern("gerber drawing", LayerFunction.FAB_DRAWING, LayerSide.NA, "\\.gbr$");

    /** The tool's own convention first, then the others, with generic as the last resort. */
    private static final Map<CadTool, List<List<LayerPattern>>> SEARCH_ORDER = Map.of(
        CadTool.ALTIUM, List.of(ALTIUM, PROTEL, KICAD, EAGLE, GENERIC),
        CadTool.KICAD, List.of(KICAD, PROTEL, ALTIUM, EAGLE, GENERIC),
        CadTool.EAGLE, List.of(EAGLE, PROTEL, KICAD, ALTIUM, GENERIC),
        CadTool.PROTEL, List.of(PROTEL, ALTIUM, KICAD, EAGLE, GENERIC),
        // Allegro declares itself in the FILE IDENTIFICATION RECORD, so its files never reach the
        // filename tables; if one does, it has no convention of its own to prefer.
        CadTool.ALLEGRO, List.of(GENERIC, PROTEL, KICAD, ALTIUM, EAGLE),
        CadTool.GENERIC, List.of(GENERIC, PROTEL, KICAD, ALTIUM, EAGLE)
    );

    /** The filename conventions tried first for {@code tool}. */
    public static List<LayerPattern> patternsFor(CadTool tool) {
        return SEARCH_ORDER.get(tool).get(0);
    }

    // ------------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------------

    /**
     * Classify a file. {@code fileContent} may be null or a truncated header (see
     * {@link #HEADER_BYTES}); without it only the filename is available and the answer is weaker.
     *
     * @return the classification, or {@code null} when nothing recognised the file
     */
    public static LayerClassification classify(String fileName, String fileContent) {
        if (fileContent != null && !fileContent.isEmpty()) {
            LayerClassification declared = parseFileFunctionAttribute(fileContent);
            if (declared == null) {
                declared = parseAllegroFileIdentificationRecord(fileContent);
            }
            if (declared == null) {
                declared = parseExcellonDrillType(fileContent);
            }
            if (declared != null) {
                return declared;
            }
        }

        List<List<LayerPattern>> order = SEARCH_ORDER.get(detectCadTool(fileContent, fileName));

        LayerClassification match = matchFileName(order, fileName, fileContent);
        if (match == null) {
            // Gerbers reach us with a second extension stuck on the end — "board.GTL.txt" out of
            // an email client, say — so try again on the stem.
            int dot = fileName.lastIndexOf('.');
            if (dot > 0 && !isSidecarExtension(fileName.substring(dot + 1))) {
                match = matchFileName(order, fileName.substring(0, dot), fileContent);
            }
        }
        if (match == null && GERBER_DRAWING.matches(fileName)) {
            match = GERBER_DRAWING.classify(fileName);
        }
        return match;
    }

    /**
     * Formats that are never artwork, and whose extension must therefore not be stripped when
     * retrying the filename tables. Sets ship rendered previews next to the layers they depict —
     * strip the extension off {@code board-B_Cu.gbr.svg} and the preview becomes a copper layer,
     * which inflates the layer count and, downstream, the quoted price.
     */
    private static final Set<String> SIDECAR_EXTENSIONS = Set.of(
        "svg", "png", "jpg", "jpeg", "gif", "bmp", "webp", "pdf", "zip", "rar", "7z", "gz", "html");

    private static boolean isSidecarExtension(String extension) {
        return SIDECAR_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * First pattern across the ordered tables that recognises {@code fileName}.
     *
     * <p>An outline match is dropped when the file holds no geometry. Sets routinely ship an
     * empty {@code .GKO} alongside a real profile drawn on a mechanical layer, and letting the
     * empty one win means the board has no outline and no size.
     */
    private static LayerClassification matchFileName(List<List<LayerPattern>> order, String fileName, String fileContent) {
        for (List<LayerPattern> table : order) {
            Optional<LayerPattern> hit = table.stream().filter(p -> p.matches(fileName)).findFirst();
            if (hit.isPresent()) {
                LayerClassification classification = hit.get().classify(fileName);
                if (classification.function() == LayerFunction.OUTLINE
                        && fileContent != null && !hasDrawCommands(fileContent)) {
                    return null;
                }
                return classification;
            }
        }
        return null;
    }

    /** Whether the Gerber holds any draw (D01) or flash (D03) command, i.e. any geometry at all. */
    private static boolean hasDrawCommands(String fileContent) {
        return DRAW_COMMAND.matcher(fileContent).find();
    }

    private static final Pattern DRAW_COMMAND = Pattern.compile("D0?[13]\\*", Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------------------
    // Step 4a: which CAD tool wrote this?
    // ------------------------------------------------------------------------

    /**
     * Identify the originating CAD tool, preferring the {@code .GenerationSoftware} declaration
     * over filename guesswork. Returns {@link CadTool#GENERIC} when nothing matches.
     */
    public static CadTool detectCadTool(String fileContent, String fileName) {
        if (fileContent != null && !fileContent.isEmpty()) {
            GenerationSoftware software = parseGenerationSoftware(fileContent);
            if (software != null) {
                String vendor = software.vendor().toLowerCase();
                String application = software.application().toLowerCase();
                if (vendor.contains("cadence") || application.contains("allegro")) {
                    return CadTool.ALLEGRO;
                } else if (vendor.contains("altium") || application.contains("altium")) {
                    return CadTool.ALTIUM;
                } else if (vendor.contains("kicad") || application.contains("kicad") || application.contains("pcbnew")) {
                    return CadTool.KICAD;
                } else if (vendor.contains("eagle") || application.contains("eagle")) {
                    return CadTool.EAGLE;
                } else if (vendor.contains("protel") || application.contains("protel")) {
                    return CadTool.PROTEL;
                }
            }
            if (fileContent.contains("begin FILE IDENTIFICATION RECORD")) {
                return CadTool.ALLEGRO;
            }
        }

        if (fileName.matches("(?i).*\\.art$")) {
            return CadTool.ALLEGRO;               // Allegro writes Gerber artwork as .art
        } else if (fileName.matches(".*[_-](F|B|In\\d+)[_.].*\\.(gbr|drl)$")) {
            return CadTool.KICAD;                 // F_Cu, B_Cu, In1_Cu
        } else if (fileName.matches(".*\\.(gtl|gbl|gts|gbs|gto|gbo)$")) {
            return CadTool.PROTEL;
        } else if (fileName.matches(".*\\.(toplayer|bottomlayer|internalplane\\d+)\\.ger$")) {
            return CadTool.EAGLE;
        } else if (fileName.matches(".*_(Copper_Signal|Soldermask|Legend|Paste|Profile).*\\.gbr$")) {
            return CadTool.ALTIUM;
        }
        return CadTool.GENERIC;
    }

    /**
     * Parse {@code .GenerationSoftware}, in either the standard form
     * {@code %TF.GenerationSoftware,<vendor>,<application>,<version>*%} or the X2-in-a-comment
     * form {@code G04 #@! TF.GenerationSoftware,...*} that Altium emits.
     *
     * @return the declaration, or null when absent
     */
    public static GenerationSoftware parseGenerationSoftware(String fileContent) {
        if (fileContent == null || fileContent.isEmpty()) {
            return null;
        }
        Matcher matcher = GENERATION_SOFTWARE.matcher(fileContent);
        if (matcher.find()) {
            return new GenerationSoftware(matcher.group(1).trim(), matcher.group(2).trim(), matcher.group(3).trim());
        }
        return null;
    }

    private static final Pattern GENERATION_SOFTWARE = Pattern.compile(
        "(?:%|G04\\s+#@!\\s+)TF\\.GenerationSoftware,([^,]+),([^,]+),([^*]+)\\*");

    // ------------------------------------------------------------------------
    // Step 1: the Gerber X2 .FileFunction attribute
    // ------------------------------------------------------------------------

    /** A {@code .FileFunction} name whose layer role is fixed, with the side in a known position. */
    private record FileFunctionMapping(String namePrefix, LayerFunction function, SidePosition sidePosition) {
        FileFunctionMapping(String namePrefix, LayerFunction function) {
            this(namePrefix, function, SidePosition.NONE);
        }
    }

    /** Where the {@code (Top|Bot)} parameter sits in a {@code .FileFunction} value list. */
    private enum SidePosition { NONE, SECOND, THIRD }

    private static final Map<String, FileFunctionMapping> FILE_FUNCTIONS = new LinkedHashMap<>();

    static {
        // Functions whose second parameter is (Top|Bot).
        FILE_FUNCTIONS.put("soldermask", new FileFunctionMapping("soldermask", LayerFunction.SOLDERMASK, SidePosition.SECOND));
        FILE_FUNCTIONS.put("legend", new FileFunctionMapping("silkscreen", LayerFunction.SILKSCREEN, SidePosition.SECOND));
        FILE_FUNCTIONS.put("paste", new FileFunctionMapping("solderpaste", LayerFunction.PASTE, SidePosition.SECOND));
        FILE_FUNCTIONS.put("glue", new FileFunctionMapping("glue", LayerFunction.UNKNOWN, SidePosition.SECOND));
        FILE_FUNCTIONS.put("carbonmask", new FileFunctionMapping("carbonmask", LayerFunction.UNKNOWN, SidePosition.SECOND));
        FILE_FUNCTIONS.put("goldmask", new FileFunctionMapping("goldmask", LayerFunction.UNKNOWN, SidePosition.SECOND));
        FILE_FUNCTIONS.put("heatsinkmask", new FileFunctionMapping("heatsinkmask", LayerFunction.UNKNOWN, SidePosition.SECOND));
        FILE_FUNCTIONS.put("peelablemask", new FileFunctionMapping("peelablemask", LayerFunction.UNKNOWN, SidePosition.SECOND));
        FILE_FUNCTIONS.put("silvermask", new FileFunctionMapping("silvermask", LayerFunction.UNKNOWN, SidePosition.SECOND));
        FILE_FUNCTIONS.put("tinmask", new FileFunctionMapping("tinmask", LayerFunction.UNKNOWN, SidePosition.SECOND));
        FILE_FUNCTIONS.put("depthrout", new FileFunctionMapping("depth rout", LayerFunction.ROUT, SidePosition.SECOND));
        FILE_FUNCTIONS.put("pads", new FileFunctionMapping("pads", LayerFunction.UNKNOWN, SidePosition.SECOND));
        FILE_FUNCTIONS.put("assemblydrawing", new FileFunctionMapping("assembly drawing", LayerFunction.FAB_DRAWING, SidePosition.SECOND));

        // Component,<layer>,(Top|Bot) puts the side third.
        FILE_FUNCTIONS.put("component", new FileFunctionMapping("component layer", LayerFunction.UNKNOWN, SidePosition.THIRD));

        // Sideless functions.
        FILE_FUNCTIONS.put("profile", new FileFunctionMapping("board outline", LayerFunction.OUTLINE));
        FILE_FUNCTIONS.put("viafill", new FileFunctionMapping("via fill", LayerFunction.UNKNOWN));
        FILE_FUNCTIONS.put("drillmap", new FileFunctionMapping("drill map", LayerFunction.FAB_DRAWING));
        FILE_FUNCTIONS.put("fabricationdrawing", new FileFunctionMapping("fabrication drawing", LayerFunction.FAB_DRAWING));
        FILE_FUNCTIONS.put("vcutmap", new FileFunctionMapping("v-cut map", LayerFunction.FAB_DRAWING));
        FILE_FUNCTIONS.put("arraydrawing", new FileFunctionMapping("array drawing", LayerFunction.FAB_DRAWING));
    }

    private static final Pattern FILE_FUNCTION = Pattern.compile(
        "(?:%|G04\\s+#@!\\s+)TF\\.FileFunction,([^*]+)\\*");

    /** The {@code <p>} of a {@code Copper,L<p>,…} function. */
    private static final Pattern COPPER_LAYER_INDEX = Pattern.compile("L(\\d+)");

    /**
     * Parse {@code .FileFunction} (Gerber X2 spec §5.6.3), in either the standard or the
     * X2-in-a-comment form. Returns null when the attribute is absent or unrecognised.
     */
    private static LayerClassification parseFileFunctionAttribute(String fileContent) {
        Matcher matcher = FILE_FUNCTION.matcher(fileContent);
        while (matcher.find()) {
            LayerClassification classification = fromFileFunction(matcher.group(1));
            if (classification != null) {
                return classification;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Set-level normalization
    // ------------------------------------------------------------------------

    /**
     * Renumber a set's inner copper layers so the topmost one is layer 1.
     *
     * <p>Generators disagree on where to start counting. Protel names its inner layers
     * {@code .G1 .G2 …} and Cadence Allegro names them {@code IN1 IN2 …}, both counting the inner
     * layers themselves. Gerber X2 writes {@code Copper,L2,Inr} — the <em>absolute</em> stack
     * position, where L1 is the top copper — so KiCad's {@code In1_Cu} arrives as 2. Left alone,
     * the same four-inner-layer board is numbered 1–4 by one tool and 2–5 by another.
     *
     * <p>Only a whole set can settle this: a single file cannot know whether its 2 means "second
     * inner layer" or "second layer of the stack". So every inner index is shifted by the same
     * amount, enough to bring the lowest to 1. Shifting rather than densely renumbering keeps a
     * gap a gap — inner layers 1 and 3 with no 2 stay 1 and 3, which is a missing file, not a
     * two-layer stack-up.
     *
     * <p>Idempotent. Null entries and layers that are not inner copper pass through untouched; the
     * inner ones are relabelled "inner copper &lt;n&gt;" so a KiCad layer does not read
     * "copper layer L2" while numbered 1.
     *
     * @param classifications a set's classifications, in any order; may contain nulls
     * @return a new list, same size and order, with inner copper renumbered from 1
     */
    public static List<LayerClassification> normalizeInnerCopperNumbers(List<LayerClassification> classifications) {
        if (classifications == null || classifications.isEmpty()) {
            return List.of();
        }
        int lowest = Integer.MAX_VALUE;
        for (LayerClassification classification : classifications) {
            if (isNumberedInnerCopper(classification)) {
                lowest = Math.min(lowest, classification.number());
            }
        }
        int shift = lowest == Integer.MAX_VALUE ? 0 : lowest - 1;

        List<LayerClassification> normalized = new ArrayList<>(classifications.size());
        for (LayerClassification classification : classifications) {
            if (!isNumberedInnerCopper(classification)) {
                normalized.add(classification);
                continue;
            }
            int number = classification.number() - shift;
            normalized.add(new LayerClassification(
                    "inner copper " + number, LayerFunction.COPPER, LayerSide.INNER, number));
        }
        return normalized;
    }

    private static boolean isNumberedInnerCopper(LayerClassification classification) {
        return classification != null && classification.isInnerCopper() && classification.number() != null;
    }

    /**
     * Classify from a bare {@code .FileFunction} value such as {@code "Copper,L1,Top"} or
     * {@code "Plated,1,4,PTH"} — the form found in a Gerber job file's {@code FilesAttributes},
     * without the surrounding attribute syntax.
     *
     * @return the classification, or null when the function is not one we know
     */
    public static LayerClassification fromFileFunction(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split(",");
        String function = parts[0].trim();

        // Copper,L<p>,(Top|Inr|Bot)[,<type>]
        if (function.equalsIgnoreCase("Copper") && parts.length >= 3) {
            String layerName = parts[1].trim();
            LayerSide side = sideOf(parts[2].trim());
            LayerClassification copper =
                    new LayerClassification("copper layer " + layerName, LayerFunction.COPPER, side);
            // Only inner copper carries a number; on outer copper the L<p> index is just the
            // stack-up position, and exposing it invites callers to render "TOP-1".
            if (side == LayerSide.INNER) {
                Matcher index = COPPER_LAYER_INDEX.matcher(layerName);
                if (index.find()) {
                    copper = copper.withNumber(Integer.parseInt(index.group(1)));
                }
            }
            return copper;
        }

        // Plated,i,j,(PTH|Blind|Buried)[,<label>]
        if (function.equalsIgnoreCase("Plated") && parts.length >= 4) {
            return isBlindOrBuried(parts[3].trim())
                    ? new LayerClassification("blind/buried drill", LayerFunction.DRILL_BLINDBURIED, LayerSide.NA)
                    : new LayerClassification("plated drill", LayerFunction.DRILL_PLATED, LayerSide.NA);
        }

        // NonPlated,i,j,(NPTH|Blind|Buried)[,<label>]
        if (function.equalsIgnoreCase("NonPlated") && parts.length >= 4) {
            return isBlindOrBuried(parts[3].trim())
                    ? new LayerClassification("blind/buried drill (non-plated)", LayerFunction.DRILL_BLINDBURIED, LayerSide.NA)
                    : new LayerClassification("non-plated drill", LayerFunction.DRILL_NONPLATED, LayerSide.NA);
        }

        // Vcut[,(Top|Bot)] — the score line a panel snaps along.
        if (function.equalsIgnoreCase("Vcut")) {
            LayerSide side = parts.length >= 2 ? sideOf(parts[1].trim()) : LayerSide.NA;
            return new LayerClassification("v-cut", LayerFunction.SCORE, side);
        }

        if (function.equalsIgnoreCase("other") && parts.length >= 2) {
            return new LayerClassification("other: " + parts[1].trim(), LayerFunction.UNKNOWN, LayerSide.NA);
        }

        if (function.equalsIgnoreCase("otherDrawing") && parts.length >= 2) {
            return new LayerClassification("other drawing: " + parts[1].trim(), LayerFunction.FAB_DRAWING, LayerSide.NA);
        }

        FileFunctionMapping mapping = FILE_FUNCTIONS.get(function.toLowerCase());
        if (mapping != null) {
            LayerSide side = LayerSide.NA;
            if (mapping.sidePosition() == SidePosition.SECOND && parts.length >= 2) {
                side = sideOf(parts[1].trim());
            } else if (mapping.sidePosition() == SidePosition.THIRD && parts.length >= 3) {
                side = sideOf(parts[2].trim());
            }
            String name = side == LayerSide.TOP || side == LayerSide.BOTTOM
                    ? (side == LayerSide.TOP ? "top " : "bottom ") + mapping.namePrefix()
                    : mapping.namePrefix();
            return new LayerClassification(name, mapping.function(), side);
        }
        return null;
    }

    private static boolean isBlindOrBuried(String drillType) {
        return drillType.equalsIgnoreCase("Blind") || drillType.equalsIgnoreCase("Buried");
    }

    /** Map a {@code .FileFunction} position token to a side. Anything unrecognised is {@link LayerSide#NA}. */
    private static LayerSide sideOf(String position) {
        if (position.equalsIgnoreCase("Top")) {
            return LayerSide.TOP;
        } else if (position.equalsIgnoreCase("Bot") || position.equalsIgnoreCase("Bottom")) {
            return LayerSide.BOTTOM;
        } else if (position.equalsIgnoreCase("Inr") || position.equalsIgnoreCase("Inner")) {
            return LayerSide.INNER;
        }
        return LayerSide.NA;
    }

    // ------------------------------------------------------------------------
    // Step 2: Cadence Allegro's FILE IDENTIFICATION RECORD
    // ------------------------------------------------------------------------

    /**
     * Parse Cadence Allegro's proprietary header block.
     *
     * <p>Allegro (and OrCAD PCB Editor) prefix RS-274X output with a G04 comment block that names
     * every Allegro CLASS/SUBCLASS drawn into the film. It is not part of any Gerber spec and
     * Cadence publishes no format for it, but it has been stable from 16.6 through 25.1:
     *
     * <pre>
     *   G04 ================== begin FILE IDENTIFICATION RECORD ==================*
     *   G04 Layout Name:  &lt;name&gt;.brd*
     *   G04 Film Name:    &lt;user-defined film name&gt;*
     *   G04 File Origin:  Cadence Allegro &lt;version&gt;*
     *   G04 Layer:  &lt;CLASS/SUBCLASS&gt;*        (once per layer in the film)
     *   G04 ================== end FILE IDENTIFICATION RECORD ====================*
     * </pre>
     *
     * <p>The subclass discriminates; the class varies. Film names (SSTP, SPBT, …) are user-defined
     * in the Artwork Control Form and so cannot be trusted. A copper film always carries an
     * {@code ETCH/<layer>} entry, which is why copper is checked first — a copper film also lists
     * {@code PIN/TOP} and {@code VIA CLASS/TOP}, and an outline film lists nothing else.
     *
     * @return the classification, or null when this is not an Allegro file
     */
    private static LayerClassification parseAllegroFileIdentificationRecord(String fileContent) {
        if (!fileContent.contains("begin FILE IDENTIFICATION RECORD")) {
            return null;
        }

        List<String> entries = new ArrayList<>();
        Matcher matcher = ALLEGRO_LAYER.matcher(fileContent);
        while (matcher.find()) {
            entries.add(matcher.group(1).trim().toUpperCase());
        }
        if (entries.isEmpty()) {
            return null;
        }

        // Copper: ETCH/TOP, ETCH/BOTTOM, ETCH/IN<n>.
        for (String entry : entries) {
            if (!entry.startsWith("ETCH/")) {
                continue;
            }
            String etchLayer = entry.substring("ETCH/".length()).trim();
            if (etchLayer.equals("TOP")) {
                return new LayerClassification("top copper", LayerFunction.COPPER, LayerSide.TOP);
            }
            if (etchLayer.equals("BOTTOM")) {
                return new LayerClassification("bottom copper", LayerFunction.COPPER, LayerSide.BOTTOM);
            }
            Matcher inner = ALLEGRO_INNER.matcher(etchLayer);
            if (inner.find()) {
                int number = Integer.parseInt(inner.group(1));
                return new LayerClassification("inner copper " + number, LayerFunction.COPPER, LayerSide.INNER, number);
            }
        }

        for (String entry : entries) {
            String subclass = subclassOf(entry);
            LayerClassification bySubclass = switch (subclass) {
                case "SILKSCREEN_TOP" -> new LayerClassification("top silkscreen", LayerFunction.SILKSCREEN, LayerSide.TOP);
                case "SILKSCREEN_BOTTOM" -> new LayerClassification("bottom silkscreen", LayerFunction.SILKSCREEN, LayerSide.BOTTOM);
                case "PASTEMASK_TOP" -> new LayerClassification("top solderpaste", LayerFunction.PASTE, LayerSide.TOP);
                case "PASTEMASK_BOTTOM" -> new LayerClassification("bottom solderpaste", LayerFunction.PASTE, LayerSide.BOTTOM);
                case "SOLDERMASK_TOP" -> new LayerClassification("top soldermask", LayerFunction.SOLDERMASK, LayerSide.TOP);
                case "SOLDERMASK_BOTTOM" -> new LayerClassification("bottom soldermask", LayerFunction.SOLDERMASK, LayerSide.BOTTOM);
                case "ASSEMBLY_TOP" -> new LayerClassification("top assembly drawing", LayerFunction.FAB_DRAWING, LayerSide.TOP);
                case "ASSEMBLY_BOTTOM" -> new LayerClassification("bottom assembly drawing", LayerFunction.FAB_DRAWING, LayerSide.BOTTOM);
                default -> null;
            };
            if (bySubclass != null) {
                return bySubclass;
            }
        }

        for (String entry : entries) {
            if (entry.startsWith("MANUFACTURING/NCLEGEND") || entry.startsWith("MANUFACTURING/NCDRILL")) {
                return new LayerClassification("drill map", LayerFunction.FAB_DRAWING, LayerSide.NA);
            }
        }

        // Board outline, but only when the film draws nothing else: a film that also carries
        // silkscreen or assembly geometry is a drawing that happens to include the outline.
        boolean hasOutline = false;
        boolean onlyBoardGeometry = true;
        for (String entry : entries) {
            if (entry.contains("DESIGN_OUTLINE") || entry.equals("BOARD GEOMETRY/OUTLINE")) {
                hasOutline = true;
            }
            if (!entry.startsWith("BOARD GEOMETRY/")) {
                onlyBoardGeometry = false;
            }
        }
        if (hasOutline && onlyBoardGeometry) {
            return new LayerClassification("board outline", LayerFunction.OUTLINE, LayerSide.NA);
        }
        return null;
    }

    private static final Pattern ALLEGRO_LAYER = Pattern.compile("G04 Layer:\\s+(.+?)\\*");
    private static final Pattern ALLEGRO_INNER = Pattern.compile("IN(\\d+)");

    /** The SUBCLASS half of an Allegro {@code CLASS/SUBCLASS} entry. */
    private static String subclassOf(String entry) {
        int slash = entry.lastIndexOf('/');
        return slash >= 0 && slash < entry.length() - 1 ? entry.substring(slash + 1).trim() : "";
    }

    // ------------------------------------------------------------------------
    // Step 3: the Excellon ;TYPE= header comment
    // ------------------------------------------------------------------------

    /**
     * Read plating from an Excellon M48 header comment.
     *
     * <p>Altium and other Protel-lineage tools omit {@code .FileFunction} from drill files but
     * write {@code ;TYPE=PLATED} or {@code ;TYPE=NON_PLATED}. That comment is authoritative
     * whatever the filename says, and it is what separates {@code Foo-Plated.TXT} from
     * {@code Foo-NonPlated.TXT} when neither name follows a convention we know.
     *
     * @return the classification, or null when this is not an Excellon file or has no type comment
     */
    private static LayerClassification parseExcellonDrillType(String fileContent) {
        String header = fileContent.substring(0, Math.min(fileContent.length(), 1024));
        if (!header.contains("M48")) {
            return null;
        }
        String scanned = fileContent.substring(0, Math.min(fileContent.length(), 4096));
        Matcher matcher = EXCELLON_TYPE.matcher(scanned);
        if (!matcher.find()) {
            return null;
        }
        String kind = matcher.group(1).replaceAll("[_\\- ]", "").toUpperCase();
        return kind.equals("NONPLATED")
                ? new LayerClassification("non-plated drill", LayerFunction.DRILL_NONPLATED, LayerSide.NA)
                : new LayerClassification("plated drill", LayerFunction.DRILL_PLATED, LayerSide.NA);
    }

    private static final Pattern EXCELLON_TYPE = Pattern.compile(
        "(?m)^\\s*;\\s*TYPE\\s*=\\s*(NON[_\\- ]?PLATED|PLATED)", Pattern.CASE_INSENSITIVE);
}
