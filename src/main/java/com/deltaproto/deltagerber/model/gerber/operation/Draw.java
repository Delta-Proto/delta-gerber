package com.deltaproto.deltagerber.model.gerber.operation;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;

import java.util.Locale;

/**
 * Draw operation (D01 with linear interpolation) - draws a line with aperture.
 */
public class Draw extends GraphicsObject {

    private final double startX;
    private final double startY;
    private final double endX;
    private final double endY;
    private final Aperture aperture;

    // Extra stroke-width multiplier accumulated from LS scaling when this draw is part of a
    // transformed (scaled) block-aperture flash. 1.0 for ordinary draws.
    private double strokeScale = 1.0;

    public Draw(double startX, double startY, double endX, double endY, Aperture aperture) {
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.aperture = aperture;
    }

    public double getStartX() {
        return startX;
    }

    public double getStartY() {
        return startY;
    }

    public double getEndX() {
        return endX;
    }

    public double getEndY() {
        return endY;
    }

    public Aperture getAperture() {
        return aperture;
    }

    /** Factor the {@code %LS%} scale in force applied to the aperture's size; 1 when none. */
    public double getStrokeScale() {
        return strokeScale;
    }

    @Override
    public BoundingBox getBoundingBox() {
        BoundingBox bounds = new BoundingBox();
        bounds.includePoint(startX, startY);
        bounds.includePoint(endX, endY);
        // Expand by the aperture's extent on each axis: a rectangle swept along a line stays
        // axis-aligned, so it reaches width/2 sideways and height/2 up and down, never the
        // larger of the two in every direction.
        BoundingBox apBounds = aperture.getBoundingBox();
        if (apBounds.isValid()) {
            double hw = apBounds.getWidth() / 2, hh = apBounds.getHeight() / 2;
            bounds.includePoint(bounds.getMinX() - hw, bounds.getMinY() - hh);
            bounds.includePoint(bounds.getMaxX() + hw, bounds.getMaxY() + hh);
        }
        return bounds;
    }

    @Override
    public BoundingBox getPathBoundingBox() {
        BoundingBox bounds = new BoundingBox();
        bounds.includePoint(startX, startY);
        bounds.includePoint(endX, endY);
        return bounds;
    }

    @Override
    public String toSvg(SvgOptions options) {
        String color = polarity == Polarity.DARK ? options.getDarkColor() : options.getClearColor();
        if (!(aperture instanceof CircleAperture)) {
            // A rectangle swept along a line inks the convex hull of the rectangle at both ends
            // (spec §4.9.1): flat ends, square corners. A round stroke of its larger side, which
            // this used to be, pokes a half-disc past each end — ST draws every solder-bridge
            // chevron on the NUCLEO boards this way, and the caps showed up as bare laminate
            // beside the pads. Any other aperture is swept as its bounding box, which can only
            // overstate.
            BoundingBox ap = aperture.getBoundingBox();
            if (!ap.isValid()) {
                return "";
            }
            String pathData = SvgPathUtils.sweptRectanglePath(startX, startY, endX, endY,
                ap.getWidth() * strokeScale, ap.getHeight() * strokeScale);
            return String.format("<path d=\"%s\" fill=\"%s\"/>", pathData, color);
        }
        double strokeWidth = ((CircleAperture) aperture).getDiameter() * strokeScale;

        if (options.isPolygonize()) {
            // Polygonized mode: path-based stroked line (rectangle with round caps)
            String pathData = SvgPathUtils.strokedLinePath(startX, startY, endX, endY, strokeWidth);
            return String.format("<path d=\"%s\" fill=\"%s\"/>", pathData, color);
        } else {
            // Exact mode: use native SVG line with stroke
            return String.format(Locale.US,
                "<line x1=\"%.6f\" y1=\"%.6f\" x2=\"%.6f\" y2=\"%.6f\" " +
                "stroke=\"%s\" stroke-width=\"%.6f\" stroke-linecap=\"round\"/>",
                startX, startY, endX, endY, color, strokeWidth);
        }
    }

    @Override
    public GraphicsObject translate(double offsetX, double offsetY) {
        Draw translated = new Draw(
            startX + offsetX, startY + offsetY,
            endX + offsetX, endY + offsetY,
            aperture);
        translated.strokeScale = this.strokeScale;
        copyMetaTo(translated);
        return translated;
    }

    @Override
    public GraphicsObject transform(GraphicsTransform t) {
        Draw out = new Draw(
            t.applyX(startX, startY), t.applyY(startX, startY),
            t.applyX(endX, endY), t.applyY(endX, endY),
            aperture);
        out.strokeScale = this.strokeScale * t.scale();
        copyMetaTo(out);
        return out;
    }

    @Override
    public String toString() {
        return String.format("Draw[%.4f,%.4f -> %.4f,%.4f D%d]",
            startX, startY, endX, endY, aperture.getDCode());
    }
}
