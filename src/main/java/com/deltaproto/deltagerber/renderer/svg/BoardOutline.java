package com.deltaproto.deltagerber.renderer.svg;

import org.apache.batik.parser.AWTPathProducer;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;

/**
 * The board edge, however it was arrived at.
 *
 * <p>A set either ships a profile layer or it does not, and the two answers are not
 * interchangeable: a real profile carries genuine internal cut-outs, while a silhouette derived
 * from the copper has none at all (see {@link OutlineDeriver}) and is only ever the outer edge of
 * each disjoint board piece. Consumers care about the difference — a cut-out is a hole to punch,
 * a derived edge is an approximation — so which one this is travels with the path rather than
 * being guessed from it.
 *
 * <p>What does <em>not</em> vary is how the loops are read. A profile's cut-outs used to arrive as
 * extra loops to be subtracted by an even-odd fill rule, which is right only while they nest;
 * loops that merely overlap cancel under it, and one feature drawn as two overlapping rectangles
 * — routine in Altium exports — came out with its overlap filled back in. Nesting is now resolved
 * before the path is built ({@link OutlineResolver}), so what arrives here is already material:
 * outer loops and their holes, wound so that <b>nonzero</b> is the whole story, whichever source
 * it came from.
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
     * The fill rule the path's loops must be interpreted under. Always {@code nonzero}: both
     * sources hand over already-resolved material, wound so that outer loops fill and holes
     * clear. It stays a method, rather than becoming a constant at every call site, because it
     * is a property of the path and reads as one.
     */
    public String getFillRule() {
        return "nonzero";
    }

    /** True when no board edge was resolved and there is nothing to clip or extrude. */
    public boolean isEmpty() {
        return svgPath.isBlank();
    }

    /**
     * The outline as a Java2D shape in the Gerber frame's millimetres, under {@link #getFillRule()},
     * or null when unresolved. Batik parses the path — it is on the classpath for rasterising, and
     * its arc handling is the one the browser applies to the realistic view — so the shape means
     * exactly what the renderer means by the path.
     */
    public Shape toShape() {
        if (isEmpty()) {
            return null;
        }
        try {
            return AWTPathProducer.createShape(new StringReader(svgPath),
                "evenodd".equals(getFillRule()) ? PathIterator.WIND_EVEN_ODD : PathIterator.WIND_NON_ZERO);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read board outline path", e);
        }
    }
}
