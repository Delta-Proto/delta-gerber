package com.deltaproto.deltagerber.model.gerber.operation;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Region (G36/G37) - filled area defined by contours.
 */
public class Region extends GraphicsObject {

    private final List<Contour> contours = new ArrayList<>();
    private Map<String, List<String>> apertureAttributes = Map.of();

    public Region() {
    }

    /**
     * The aperture attributes in force when the region was drawn ({@code .AperFunction} above all).
     * Gerber attaches them to a region the way it does to an aperture, since a region has none.
     */
    public Map<String, List<String>> getApertureAttributes() {
        return apertureAttributes;
    }

    public void setApertureAttributes(Map<String, List<String>> attrs) {
        this.apertureAttributes = attrs == null || attrs.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
    }

    public void addContour(Contour contour) {
        contours.add(contour);
    }

    public List<Contour> getContours() {
        return contours;
    }

    @Override
    public BoundingBox getBoundingBox() {
        BoundingBox bounds = new BoundingBox();
        for (Contour contour : contours) {
            bounds.include(contour.getBoundingBox());
        }
        return bounds;
    }

    @Override
    public BoundingBox getPathBoundingBox() {
        BoundingBox bounds = new BoundingBox();
        for (Contour contour : contours) {
            bounds.include(contour.getPathBoundingBox());
        }
        return bounds;
    }

    @Override
    public String toSvg(SvgOptions options) {
        if (contours.isEmpty()) {
            return "";
        }

        StringBuilder path = new StringBuilder();
        for (Contour contour : contours) {
            path.append(contour.toSvgPath(options));
            path.append(" ");
        }

        String color = polarity == Polarity.DARK ? options.getDarkColor() : options.getClearColor();
        return String.format(
            "<path d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
            path.toString().trim(), color);
    }

    @Override
    public GraphicsObject translate(double offsetX, double offsetY) {
        Region translated = new Region();
        copyMetaTo(translated);
        translated.apertureAttributes = this.apertureAttributes;
        for (Contour contour : contours) {
            translated.addContour(contour.translate(offsetX, offsetY));
        }
        return translated;
    }

    @Override
    public GraphicsObject transform(GraphicsTransform t) {
        Region out = new Region();
        copyMetaTo(out);
        out.apertureAttributes = this.apertureAttributes;
        for (Contour contour : contours) {
            out.addContour(contour.transform(t));
        }
        return out;
    }

    @Override
    public String toString() {
        return String.format("Region[%d contours]", contours.size());
    }
}
