package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
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
    private final List<AnalyzedLayer> layers;

    private BoardSpecification(Double sizeXMm, Double sizeYMm, BoundingBox bounds, Integer copperLayerCount,
                               BoardSide solderMaskSide, BoardSide silkscreenSide, BoardSide stencilSide,
                               Double minTrackWidthUm, Double minDrillDiameterMm,
                               boolean hasDrill, boolean hasCopper, boolean hasOutline,
                               ViaInPadResult viaInPad, List<AnalyzedLayer> layers) {
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
        this.layers = layers;
    }

    /**
     * Derive the board specification from already-measured layers.
     *
     * <p>Separate from {@link PcbAnalyzer#analyze} so a caller that persisted its measurements can
     * re-derive the specification without the files. Via-in-pad is a geometric relationship between
     * two layers, not a per-layer measurement, so it cannot be re-derived here — it is left
     * {@linkplain #hasViaInPad() unknown}; use {@link #from(List, ViaInPadResult)} to supply it.
     */
    public static BoardSpecification from(List<AnalyzedLayer> layers) {
        return from(layers, null);
    }

    /**
     * As {@link #from(List)}, but with a {@link ViaInPadResult} the caller detected separately (
     * {@link PcbAnalyzer} runs it during analysis; a caller re-deriving from persisted data can pass
     * a stored result). A {@code null} result leaves via-in-pad {@linkplain #hasViaInPad() unknown}.
     */
    public static BoardSpecification from(List<AnalyzedLayer> layers, ViaInPadResult viaInPad) {
        List<AnalyzedLayer> safe = layers == null ? List.of() : List.copyOf(layers);
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
                viaInPad, safe);
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
     * Whether the board has any via in pad — a drilled hole inside a surface-mount pad, which
     * forces a filled-and-capped via process (IPC-4761 Type VII). {@code null} when it could not be
     * determined: the set has no paste layer, no drill, or was re-derived from persisted layer
     * measurements without re-running detection. See {@link #getViaInPad()} for the detail.
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
     * The full via-in-pad detection result (every offending hole), or {@code null} when detection
     * was not run for this specification.
     */
    public ViaInPadResult getViaInPad() {
        return viaInPad;
    }

    /** Every analysed file, in the order given. */
    public List<AnalyzedLayer> getLayers() {
        return layers;
    }

    @Override
    public String toString() {
        return String.format("BoardSpecification[%s x %s mm, %s copper layers, minTrack=%sum, minDrill=%smm]",
                sizeXMm, sizeYMm, copperLayerCount, minTrackWidthUm, minDrillDiameterMm);
    }
}
