package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.dfm.ViaInPadGroup;
import com.deltaproto.deltagerber.dfm.ViaInPadPolicy;
import com.deltaproto.deltagerber.dfm.ViaInPadResult;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What a set of Gerber and drill files says the bare board is: its size, its stack-up, the
 * processes it needs, and the two tolerances that drive fabrication cost — narrowest track and
 * smallest drill.
 *
 * <p>Every field is what the <em>files</em> claim, never what anyone ordered. A field is null when
 * the set does not answer that question, so a caller can tell "the files say no soldermask" (
 * {@link BoardSide#NONE}) from "the files do not say" (null).
 */
public final class BoardSpecification {

    /**
     * Layers that bound the board when no outline file is present. A drill file is excluded
     * deliberately: holes sit inside the board, never at its edge, so drills would understate the
     * size. Fabrication drawings are excluded because their dimension lines and title blocks sit
     * outside the board and would overstate it.
     */
    private static final Set<LayerFunction> SIZE_LAYERS = EnumSet.of(
            LayerFunction.COPPER,
            LayerFunction.SILKSCREEN,
            LayerFunction.SOLDERMASK,
            LayerFunction.PASTE,
            LayerFunction.OUTLINE);

    private final Double sizeXMm;
    private final Double sizeYMm;
    private final BoundingBox bounds;
    private final Integer copperLayerCount;
    private final BoardSide solderMaskSide;
    private final BoardSide silkscreenSide;
    private final BoardSide stencilSide;
    private final Double minTrackWidthUm;
    private final Double minDrillDiameterMm;
    private final boolean hasDrill;
    private final boolean hasCopper;
    private final boolean hasOutline;
    private final ViaInPadResult viaInPad;
    private final BoardStack stack;
    private final List<AnalyzedLayer> layers;

    private BoardSpecification(Double sizeXMm, Double sizeYMm, BoundingBox bounds, Integer copperLayerCount,
                               BoardSide solderMaskSide, BoardSide silkscreenSide, BoardSide stencilSide,
                               Double minTrackWidthUm, Double minDrillDiameterMm,
                               boolean hasDrill, boolean hasCopper, boolean hasOutline,
                               ViaInPadResult viaInPad, BoardStack stack,
                               List<AnalyzedLayer> layers) {
        this.sizeXMm = sizeXMm;
        this.sizeYMm = sizeYMm;
        this.bounds = bounds;
        this.copperLayerCount = copperLayerCount;
        this.solderMaskSide = solderMaskSide;
        this.silkscreenSide = silkscreenSide;
        this.stencilSide = stencilSide;
        this.minTrackWidthUm = minTrackWidthUm;
        this.minDrillDiameterMm = minDrillDiameterMm;
        this.hasDrill = hasDrill;
        this.hasCopper = hasCopper;
        this.hasOutline = hasOutline;
        this.viaInPad = viaInPad;
        this.stack = stack;
        this.layers = layers;
    }

    /**
     * Derive the board specification from already-measured layers.
     *
     * <p>Separate from {@link PcbAnalyzer#analyze} so a caller that persisted its measurements can
     * re-derive the specification without the files. Via-in-pad is a geometric relationship between
     * two layers, not a per-layer measurement, so it cannot be re-derived here — it is left
     * {@linkplain #hasViaInPad() unknown}; use {@link #from(List, ViaInPadResult)} to supply it.
     * The stack is {@linkplain #getStack() estimated} from the layers for the same reason: only a
     * job file or an IPC-2581 file states the real one, so pass a stored one to
     * {@link #from(List, ViaInPadResult, BoardStack)}.
     */
    public static BoardSpecification from(List<AnalyzedLayer> layers) {
        return from(layers, null, null);
    }

    /**
     * As {@link #from(List)}, but with a {@link ViaInPadResult} the caller detected separately (
     * {@link PcbAnalyzer} runs it during analysis; a caller re-deriving from persisted data can pass
     * a stored result). A {@code null} result leaves via-in-pad {@linkplain #hasViaInPad() unknown}.
     */
    public static BoardSpecification from(List<AnalyzedLayer> layers, ViaInPadResult viaInPad) {
        return from(layers, viaInPad, null);
    }

    /**
     * As {@link #from(List, ViaInPadResult)}, with the physical stack the caller already has —
     * {@link BoardStack#from(com.deltaproto.deltagerber.model.gerber.GerberJobDocument) read} from
     * a job file, {@link BoardStack#from(com.deltaproto.deltagerber.model.ipc2581.Ipc2581StackupDocument)
     * from an IPC-2581 file}, or {@link BoardStack#of stored} and being re-derived.
     *
     * <p>The two halves of a stack are resolved separately, because a file may state one without
     * the other. Layers are {@linkplain BoardStack#estimate estimated} from {@code layers} when the
     * stack has none — what every set without a job file gets. A stated
     * {@linkplain BoardStack#getBoardThicknessPm() board thickness} is kept either way, so a file
     * that gives a total and no stack-up still yields the total.
     */
    public static BoardSpecification from(List<AnalyzedLayer> layers, ViaInPadResult viaInPad,
                                          BoardStack stack) {
        List<AnalyzedLayer> safe = layers == null ? List.of() : List.copyOf(layers);
        BoardStack given = stack == null ? BoardStack.empty() : stack;
        BoardStack resolvedStack = given.getEntries().isEmpty()
                ? BoardStack.of(BoardStack.estimate(safe).getEntries(), given.getBoardThicknessPm())
                : given;
        BoundingBox bounds = boardBounds(safe);
        boolean empty = safe.isEmpty();

        return new BoardSpecification(
                bounds == null ? null : bounds.getWidth(),
                bounds == null ? null : bounds.getHeight(),
                bounds,
                copperLayerCount(safe),
                empty ? null : sideOf(safe, LayerFunction.SOLDERMASK, false),
                empty ? null : sideOf(safe, LayerFunction.SILKSCREEN, false),
                empty ? null : sideOf(safe, LayerFunction.PASTE, true),
                min(safe, AnalyzedLayer::getMinTrackWidthUm),
                min(safe, AnalyzedLayer::getMinDrillDiameterMm),
                safe.stream().anyMatch(l -> l.getFunction().isDrill()),
                safe.stream().anyMatch(l -> l.getFunction().isCopper()),
                safe.stream().anyMatch(l -> l.getFunction() == LayerFunction.OUTLINE),
                viaInPad, resolvedStack, safe);
    }

    /**
     * The board rectangle: the outline layer when the set has one, otherwise the extent of the
     * artwork that must sit on copper.
     *
     * <p>An outline that parsed but drew nothing measures zero and is ignored, so a set that ships
     * an empty {@code .GKO} still gets a size.
     */
    private static BoundingBox boardBounds(List<AnalyzedLayer> layers) {
        BoundingBox outline = union(layers, l -> l.getFunction() == LayerFunction.OUTLINE);
        if (outline != null && outline.getWidth() > 0 && outline.getHeight() > 0) {
            return outline;
        }
        return union(layers, l -> SIZE_LAYERS.contains(l.getFunction()));
    }

    private static BoundingBox union(List<AnalyzedLayer> layers, java.util.function.Predicate<AnalyzedLayer> include) {
        BoundingBox union = new BoundingBox();
        for (AnalyzedLayer layer : layers) {
            if (include.test(layer)) {
                union.include(layer.getBounds());
            }
        }
        return union.isValid() ? union : null;
    }

    private static Integer copperLayerCount(List<AnalyzedLayer> layers) {
        long count = layers.stream().filter(l -> l.getFunction().isCopper()).count();
        return count > 0 ? (int) count : null;
    }

    /**
     * Which sides carry {@code function}. When {@code requireGeometry}, a layer only counts if it
     * actually draws something — the rule for solder paste, where an empty paste layer means no
     * stencil is needed for that side.
     */
    private static BoardSide sideOf(List<AnalyzedLayer> layers, LayerFunction function, boolean requireGeometry) {
        boolean top = false;
        boolean bottom = false;
        for (AnalyzedLayer layer : layers) {
            if (layer.getFunction() != function) {
                continue;
            }
            if (requireGeometry && !Boolean.TRUE.equals(layer.getHasGeometry())) {
                continue;
            }
            top |= layer.getSide() == LayerSide.TOP;
            bottom |= layer.getSide() == LayerSide.BOTTOM;
        }
        return BoardSide.of(top, bottom);
    }

    private static Double min(List<AnalyzedLayer> layers, java.util.function.Function<AnalyzedLayer, Double> value) {
        return layers.stream().map(value).filter(java.util.Objects::nonNull).min(Double::compare).orElse(null);
    }

    /** Board width in millimetres, or null when the set has no measurable geometry. */
    public Double getSizeXMm() {
        return sizeXMm;
    }

    /** Board height in millimetres, or null when the set has no measurable geometry. */
    public Double getSizeYMm() {
        return sizeYMm;
    }

    /**
     * The board rectangle in millimetres, in the files' own coordinate space — the origin is
     * wherever the CAD tool put it, so this is not necessarily anchored at (0,0). Null when the
     * set has no measurable geometry.
     */
    public BoundingBox getBounds() {
        return bounds;
    }

    /** Number of copper layers found, or null when there are none. */
    public Integer getCopperLayerCount() {
        return copperLayerCount;
    }

    /** Sides carrying soldermask; null when the set has no files at all. */
    public BoardSide getSolderMaskSide() {
        return solderMaskSide;
    }

    /** Sides carrying silkscreen; null when the set has no files at all. */
    public BoardSide getSilkscreenSide() {
        return silkscreenSide;
    }

    /** Sides needing an SMD stencil — paste layers that actually carry pads. Null when no files. */
    public BoardSide getStencilSide() {
        return stencilSide;
    }

    /** Narrowest track across all copper layers, in micrometres; null when no track was measured. */
    public Double getMinTrackWidthUm() {
        return minTrackWidthUm;
    }

    /** Smallest drill across all drill layers, in millimetres; null when no drill was measured. */
    public Double getMinDrillDiameterMm() {
        return minDrillDiameterMm;
    }

    /** True when the set contains an NC drill file. */
    public boolean hasDrill() {
        return hasDrill;
    }

    /** True when the set contains at least one copper layer. */
    public boolean hasCopper() {
        return hasCopper;
    }

    /** True when the set contains a board outline layer. */
    public boolean hasOutline() {
        return hasOutline;
    }

    /**
     * Whether the board has any via in pad — a drilled hole inside a surface-mount pad. {@code null}
     * when it could not be determined: the set has no paste layer, no drill, or was re-derived from
     * persisted layer measurements without re-running detection. See {@link #getViaInPad()} for the
     * detail.
     *
     * <p>This is the geometric fact, not the process verdict: a via field under a QFN heat pad
     * answers {@code TRUE} here and still needs no via fill. Quote off
     * {@link #requiresFilledAndCappedVias()}.
     */
    public Boolean hasViaInPad() {
        return viaInPad == null ? null : viaInPad.hasViaInPad();
    }

    /** How many vias in pad were found; 0 when there are none or it was not determined. */
    public int getViaInPadCount() {
        return viaInPad == null ? 0 : viaInPad.getCount();
    }

    /**
     * Which side(s) carry a via in pad, or {@link BoardSide#NONE} when there are none. {@code null}
     * when via-in-pad was not determined (see {@link #hasViaInPad()}).
     */
    public BoardSide getViaInPadSide() {
        return viaInPad == null ? null : BoardSide.of(viaInPad.isOnTop(), viaInPad.isOnBottom());
    }

    /**
     * Whether any via in pad actually forces a filled-and-capped via process (IPC-4761 Type VII),
     * which raises fabrication cost and lead time. {@code null} when via-in-pad was not determined
     * (see {@link #hasViaInPad()}).
     *
     * <p>It is {@code FALSE} while {@link #hasViaInPad()} is {@code TRUE} whenever every offending
     * pad is judged thermal — a via field, or a land large enough relative to its holes that the
     * paste it carries survives what drains away. {@link ViaInPadGroup} holds the per-pad evidence
     * and {@link ViaInPadPolicy} the thresholds; use
     * {@link #requiresFilledAndCappedVias(ViaInPadPolicy)} to apply your own.
     */
    public Boolean requiresFilledAndCappedVias() {
        return requiresFilledAndCappedVias(ViaInPadPolicy.DEFAULT);
    }

    /** As {@link #requiresFilledAndCappedVias()}, judged by {@code policy}. */
    public Boolean requiresFilledAndCappedVias(ViaInPadPolicy policy) {
        return viaInPad == null ? null : viaInPad.requiresFilledAndCapped(policy);
    }

    /**
     * The vias in pad grouped by the pad they sit in, each with its area, via count and hole
     * diameter — empty when there are none or via-in-pad was not determined.
     */
    public List<ViaInPadGroup> getViaInPadGroups() {
        return viaInPad == null ? List.of() : viaInPad.getGroups();
    }

    /**
     * The full via-in-pad detection result (every offending hole, and the pads they sit in), or
     * {@code null} when detection was not run for this specification.
     */
    public ViaInPadResult getViaInPad() {
        return viaInPad;
    }

    /**
     * The board's physical build-up, from the top of the board down: copper, the dielectrics
     * between it, and the mask, legend and paste on the outside. Empty when the set has nothing
     * that occupies a z-position at all.
     *
     * <p>Read from the {@code MaterialStackup} of a Gerber job file when the set ships one — the
     * only place a Gerber set states its materials and their thicknesses. Otherwise every entry is
     * {@linkplain StackEntry#isEstimated() estimated}: the layers the set does have, in the right
     * order, with no dielectrics and no thicknesses. See {@link BoardStack}.
     */
    public List<StackEntry> getStack() {
        return stack.getEntries();
    }

    /** The stack and the board's thickness together, as one value. */
    public BoardStack getBoardStack() {
        return stack;
    }

    /**
     * The finished board's thickness in picometres, or {@code null} when nothing states one —
     * which is the common case, since no Gerber file carries a thickness.
     *
     * <p>The figure a Gerber job file, an IPC-2581 file or a stored stack declares; failing that,
     * the sum of the stack's own layers. Note that this is answered even when the stack itself is
     * {@linkplain #isStackEstimated() estimated}: an EAGLE job file states the board's thickness
     * and no stack-up at all, and an ODB++ archive's {@code .board_thickness} passed through
     * {@link BoardStack#of} behaves the same way.
     */
    public Long getBoardThicknessPm() {
        return stack.getBoardThicknessPm();
    }

    /** {@link #getBoardThicknessPm()} in millimetres, or null when nothing states one. */
    public Double getBoardThicknessMm() {
        return stack.getBoardThicknessMm();
    }

    /**
     * Whether {@link #getStack()} was synthesised from the artwork rather than read from a job
     * file's stack-up. {@code null} when the stack is empty, and so neither.
     */
    public Boolean isStackEstimated() {
        return stack.getEntries().isEmpty() ? null : stack.isEstimated();
    }

    /** Every analysed file, in the order given. */
    public List<AnalyzedLayer> getLayers() {
        return layers;
    }

    @Override
    public String toString() {
        return String.format("BoardSpecification[%s x %s mm, %s copper layers, %s mm thick, "
                        + "minTrack=%sum, minDrill=%smm]",
                sizeXMm, sizeYMm, copperLayerCount, getBoardThicknessMm(), minTrackWidthUm,
                minDrillDiameterMm);
    }
}
