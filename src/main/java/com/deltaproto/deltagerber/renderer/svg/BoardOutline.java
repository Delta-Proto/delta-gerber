package com.deltaproto.deltagerber.renderer.svg;

/**
 * The board edge, however it was arrived at.
 *
 * <p>A set either ships a profile layer or it does not, and the two answers are not
 * interchangeable — they differ in what a closed loop <em>means</em>. A real profile carries
 * genuine internal cut-outs, emitted as extra loops that only subtract under the
 * <b>even-odd</b> rule; a silhouette derived from the copper has no cut-outs at all (see
 * {@link OutlineDeriver}) and its one loop per disjoint board piece must be unioned, which is
 * the <b>nonzero</b> rule. Every consumer of the outline needs both halves, so they travel
 * together rather than being re-derived from a bare path string.
 *
 * <p>The path is in raw Gerber coordinates: millimetres, Y up, unflipped. The realistic
 * renderer drops it straight into a clip path (its viewport group carries the Y flip);
 * {@link com.deltaproto.deltagerber.renderer.step.StepExporter} extrudes it into a solid.
 *
 * @see MultiLayerSVGRenderer#resolveBoardOutline(java.util.List)
 */
public final class BoardOutline {

    private static final BoardOutline NONE = new BoardOutline("", false);

    private final String svgPath;
    private final boolean fromProfileLayer;

    BoardOutline(String svgPath, boolean fromProfileLayer) {
        this.svgPath = svgPath == null ? "" : svgPath;
        this.fromProfileLayer = fromProfileLayer;
    }

    /** No board edge could be resolved — the set has neither a profile layer nor copper. */
    public static BoardOutline none() {
        return NONE;
    }

    /** The board edge as an SVG path, in millimetres with Y up. Empty when unresolved. */
    public String getSvgPath() {
        return svgPath;
    }

    /** True when the path came from a dedicated profile/outline layer rather than the copper. */
    public boolean isFromProfileLayer() {
        return fromProfileLayer;
    }

    /** True when the path was derived from the copper silhouette because the set ships no profile. */
    public boolean isDerived() {
        return !fromProfileLayer && !isEmpty();
    }

    /**
     * The fill rule the path's loops must be interpreted under: {@code evenodd} for a profile
     * layer, whose inner loops are cut-outs, {@code nonzero} for a derived silhouette, whose
     * loops are separate board pieces to union.
     */
    public String getFillRule() {
        return fromProfileLayer ? "evenodd" : "nonzero";
    }

    /** True when no board edge was resolved and there is nothing to clip or extrude. */
    public boolean isEmpty() {
        return svgPath.isBlank();
    }
}
