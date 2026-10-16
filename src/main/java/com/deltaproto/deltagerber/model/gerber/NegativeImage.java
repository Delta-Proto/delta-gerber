package com.deltaproto.deltagerber.model.gerber;

import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroTemplate;
import com.deltaproto.deltagerber.model.gerber.attribute.FileAttribute;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;

import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.regex.Pattern;

/**
 * A negative image, read as the copper it stands for.
 *
 * <p>A negative copper file draws its <em>clearances</em>: Altium's plane layers ({@code .GP1},
 * {@code .GP2}) declare {@code .FilePolarity,Negative} and contain the pull-back along the board
 * edge, the antipads and the thermal reliefs — and the copper is everything they leave untouched.
 * Rendered or measured as drawn, such a layer shows a few rings on an empty board and reports its
 * pull-back stroke as its narrowest track.
 *
 * <p>{@link #toPositive} rewrites the file as the painter's model already knows how to say it: a
 * dark region covering the board, then every original object with its polarity flipped, so the
 * dark clearances become clears cut out of the copper and anything the file itself cleared is
 * copper again. The rest of the pipeline — polarity masks, {@code CopperGeometry}, the DFM checks
 * — needs no notion of negativity.
 *
 * <p>The board's extent has to come from outside the file: nothing in a negative image says where
 * the copper stops. Callers pass the resolved board outline; without one the image's own bounding
 * box serves, which is right whenever the file draws its pull-back along the whole edge (Altium
 * always does).
 *
 * <p>The {@code .FilePolarity} attribute is deliberately metadata about the <em>file</em> (Gerber
 * spec §5.6.3): a solder-mask file is conventionally negative too, because what it draws is the
 * openings. So which layers this applies to is the caller's decision, by layer function — copper,
 * and only copper.
 */
public final class NegativeImage {

    /** Flatness, in mm, at which an outline's arcs become region contour segments. */
    public static final double FLATNESS_MM = 0.005;

    private NegativeImage() {
    }

    /**
     * Whether the file declares a negative image: {@code .FilePolarity,Negative} (X2, also in
     * Altium's {@code G04 #@!} comment form) or the deprecated {@code %IPNEG*%}. True of a document
     * {@link #toPositive} built as well — the file it came from is still a negative.
     */
    public static boolean declaresNegative(GerberDocument document) {
        if (document == null) {
            return false;
        }
        return "Negative".equalsIgnoreCase(document.getFilePolarity())
                || document.getImagePolarity() == ImagePolarity.NEGATIVE;
    }

    /**
     * Whether a file's text declares a negative image, read from its header without parsing it —
     * for a caller that has to decide, before any file is parsed, which ones to parse last
     * (a plane's extent is the board, and the board is best judged once the copper is known).
     * Matches {@code %TF.FilePolarity,Negative*%}, Altium's {@code G04 #@! TF.FilePolarity,Negative*}
     * and {@code %IPNEG*%}.
     */
    public static boolean headerDeclaresNegative(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String head = content.length() > HEADER_CHARS ? content.substring(0, HEADER_CHARS) : content;
        return NEGATIVE_HEADER.matcher(head).find();
    }

    private static final int HEADER_CHARS = 10_240;

    private static final Pattern NEGATIVE_HEADER = Pattern.compile(
        "(?:%|G04\\s+#@!\\s+)TF\\.FilePolarity,\\s*Negative\\s*\\*|%IPNEG\\*%", Pattern.CASE_INSENSITIVE);

    /**
     * Whether this document still <em>is</em> the negative: it declares one and has not been
     * rewritten by {@link #toPositive}. This is the test a consumer applies before converting.
     */
    public static boolean isNegative(GerberDocument document) {
        return declaresNegative(document) && !document.isInvertedImage();
    }

    /**
     * The copper a negative image stands for, within {@code extentMm}: a dark region over the
     * extent, then the file's objects with their polarity inverted.
     *
     * @param negative the negative image; left untouched
     * @param extentMm where the copper would be if the file cleared nothing — the board outline,
     *                 in the Gerber frame's millimetres; any winding, holes included
     * @return a new document, positive, marked {@link GerberDocument#isInvertedImage()}
     */
    public static GerberDocument toPositive(GerberDocument negative, Shape extentMm) {
        GerberDocument positive = new GerberDocument();
        positive.setFileName(negative.getFileName());
        positive.setCoordinateFormat(negative.getCoordinateFormat());
        positive.setUnit(negative.getUnit());
        positive.setSourceUnit(negative.getSourceUnit());
        positive.setImagePolarity(ImagePolarity.POSITIVE);
        positive.setInvertedImage(true);
        for (FileAttribute attribute : negative.getFileAttributes().values()) {
            positive.addFileAttribute(attribute);
        }
        for (MacroTemplate template : negative.getMacroTemplates().values()) {
            positive.addMacroTemplate(template);
        }
        for (Aperture aperture : negative.getApertures().values()) {
            positive.addAperture(aperture);
        }
        for (String warning : negative.getWarnings()) {
            positive.addWarning(warning);
        }
        for (ComponentPlacement component : negative.getComponents()) {
            positive.addComponent(component);
        }

        Region field = extentRegion(extentMm);
        if (field != null) {
            positive.addObject(field);
        }
        for (GraphicsObject object : negative.getObjects()) {
            GraphicsObject copy = object.translate(0, 0);
            copy.setPolarity(object.getPolarity().inverse());
            positive.addObject(copy);
        }
        return positive;
    }

    /**
     * As {@link #toPositive(GerberDocument, Shape)} with the image's own bounding box as the extent
     * — for a set with no outline to resolve.
     */
    public static GerberDocument toPositive(GerberDocument negative) {
        BoundingBox bounds = negative.getBoundingBox();
        if (bounds == null || !bounds.isValid()) {
            return toPositive(negative, new Rectangle2D.Double());
        }
        return toPositive(negative, new Rectangle2D.Double(
                bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight()));
    }

    /**
     * The extent as one dark region, a contour per loop. Going through {@link Area} normalises any
     * winding to non-overlapping loops, under which even-odd (what a region renders with) and
     * nonzero agree, and {@code CopperGeometry} re-derives holes from nesting anyway.
     */
    private static Region extentRegion(Shape extentMm) {
        if (extentMm == null) {
            return null;
        }
        Area area = extentMm instanceof Area a ? a : new Area(extentMm);
        if (area.isEmpty()) {
            return null;
        }
        Region region = new Region();
        region.setPolarity(Polarity.DARK);
        Contour contour = null;
        double[] c = new double[6];
        for (PathIterator it = area.getPathIterator(null, FLATNESS_MM); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    if (contour != null) {
                        region.addContour(contour);
                    }
                    contour = new Contour(c[0], c[1]);
                }
                case PathIterator.SEG_LINETO -> {
                    if (contour != null) {
                        contour.addLineTo(c[0], c[1]);
                    }
                }
                case PathIterator.SEG_CLOSE -> {
                    if (contour != null) {
                        region.addContour(contour);
                        contour = null;
                    }
                }
                default -> { }
            }
        }
        if (contour != null) {
            region.addContour(contour);
        }
        return region.getContours().isEmpty() ? null : region;
    }
}
