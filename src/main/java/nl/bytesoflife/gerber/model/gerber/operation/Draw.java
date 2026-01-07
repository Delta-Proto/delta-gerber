package nl.bytesoflife.gerber.model.gerber.operation;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.model.gerber.Polarity;
import nl.bytesoflife.gerber.model.gerber.aperture.Aperture;
import nl.bytesoflife.gerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.gerber.model.gerber.aperture.RectangleAperture;
import nl.bytesoflife.gerber.renderer.svg.SvgOptions;
import nl.bytesoflife.gerber.renderer.svg.SvgPathUtils;

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

    @Override
    public BoundingBox getBoundingBox() {
        BoundingBox bounds = new BoundingBox();
        bounds.includePoint(startX, startY);
        bounds.includePoint(endX, endY);
        // Expand by aperture size
        BoundingBox apBounds = aperture.getBoundingBox();
        double margin = Math.max(apBounds.getWidth(), apBounds.getHeight()) / 2;
        bounds.expand(margin);
        return bounds;
    }

    @Override
    public String toSvg(SvgOptions options) {
        String color = polarity == Polarity.DARK ? options.getDarkColor() : options.getClearColor();
        double strokeWidth = 0;
        if (aperture instanceof CircleAperture) {
            strokeWidth = ((CircleAperture) aperture).getDiameter();
        } else if (aperture instanceof RectangleAperture) {
            // Use max dimension as stroke width (approximation for non-rotated lines)
            RectangleAperture rect = (RectangleAperture) aperture;
            strokeWidth = Math.max(rect.getWidth(), rect.getHeight());
        }

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
        translated.setPolarity(this.polarity);
        return translated;
    }

    @Override
    public String toString() {
        return String.format("Draw[%.4f,%.4f -> %.4f,%.4f D%d]",
            startX, startY, endX, endY, aperture.getDCode());
    }
}
