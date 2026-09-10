package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;

import java.util.List;

/**
 * One copper layer handed to a check that has to know <em>where</em> in the stack-up it sits —
 * today {@link AnnularRingDetector}, which reports a ring per layer because a via's ring differs
 * from layer to layer and the per-layer breakdown is what a designer fixes.
 *
 * <p>A {@link GerberDocument} on its own cannot say that: it is one file's artwork, with no notion
 * of top, bottom or inner-3. That is the classification's job, so this pairs the two.
 *
 * <p>The artwork can also be given as a plain list of {@link GraphicsObject}s rather than a whole
 * document. That is for a caller which parsed the layer for something else and kept only the
 * objects that can be a pad — {@code spec.PcbAnalyzer} does exactly that, so a copper layer's
 * traces and pours are released while its flashes live on to answer this question.
 */
public final class CopperLayer {

    private final String name;
    private final LayerSide side;
    private final Integer number;
    private final List<GraphicsObject> objects;

    private CopperLayer(String name, LayerSide side, Integer number, List<GraphicsObject> objects) {
        this.name = name;
        this.side = side == null ? LayerSide.UNKNOWN : side;
        this.number = number;
        this.objects = objects == null ? List.of() : objects;
    }

    /** A copper layer from a parsed document. */
    public static CopperLayer of(String name, LayerSide side, Integer number, GerberDocument document) {
        return new CopperLayer(name, side, number,
                document == null ? List.of() : document.getObjects());
    }

    /** A copper layer from a parsed document, taking its side and stack-up index from a classification. */
    public static CopperLayer of(String name, LayerClassification classification, GerberDocument document) {
        return of(name,
                classification == null ? LayerSide.UNKNOWN : classification.side(),
                classification == null ? null : classification.number(),
                document);
    }

    /** The top copper layer. */
    public static CopperLayer top(String name, GerberDocument document) {
        return of(name, LayerSide.TOP, null, document);
    }

    /** The bottom copper layer. */
    public static CopperLayer bottom(String name, GerberDocument document) {
        return of(name, LayerSide.BOTTOM, null, document);
    }

    /** An inner copper layer at the given stack-up index (1 is the topmost inner layer). */
    public static CopperLayer inner(String name, int number, GerberDocument document) {
        return of(name, LayerSide.INNER, number, document);
    }

    /**
     * A copper layer given as artwork rather than as a document — for a caller that kept only the
     * objects which can be a pad. The list is not copied and must not be mutated afterwards.
     */
    public static CopperLayer of(String name, LayerSide side, Integer number,
                                 List<GraphicsObject> objects) {
        return new CopperLayer(name, side, number, objects);
    }

    /** The file this layer came from, or whatever label the caller chose; may be null. */
    public String getName() {
        return name;
    }

    /** Top, bottom or inner — {@link LayerSide#UNKNOWN} when the caller did not say. */
    public LayerSide getSide() {
        return side;
    }

    /** Stack-up index for an inner layer, null for outer copper — see {@code LayerClassification}. */
    public Integer getNumber() {
        return number;
    }

    /** The artwork on this layer. */
    public List<GraphicsObject> getObjects() {
        return objects;
    }

    @Override
    public String toString() {
        return "CopperLayer[" + name + " " + side + (number == null ? "" : " " + number)
                + ", " + objects.size() + " objects]";
    }
}
