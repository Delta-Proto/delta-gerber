package nl.bytesoflife.gerber.model.gerber.operation;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.model.gerber.Polarity;
import nl.bytesoflife.gerber.model.gerber.aperture.Aperture;
import nl.bytesoflife.gerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.gerber.renderer.svg.SvgOptions;
import nl.bytesoflife.gerber.renderer.svg.SvgPathUtils;

import java.util.Locale;

/**
 * Arc operation (D01 with circular interpolation).
 */
public class Arc extends GraphicsObject {

    private final double startX;
    private final double startY;
    private final double endX;
    private final double endY;
    private final double centerX;
    private final double centerY;
    private final boolean clockwise;
    private final Aperture aperture;

    public Arc(double startX, double startY, double endX, double endY,
               double centerX, double centerY, boolean clockwise, Aperture aperture) {
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.centerX = centerX;
        this.centerY = centerY;
        this.clockwise = clockwise;
        this.aperture = aperture;
    }

    public double getStartX() { return startX; }
    public double getStartY() { return startY; }
    public double getEndX() { return endX; }
    public double getEndY() { return endY; }
    public double getCenterX() { return centerX; }
    public double getCenterY() { return centerY; }
    public boolean isClockwise() { return clockwise; }
    public Aperture getAperture() { return aperture; }

    public double getRadius() {
        double dx = startX - centerX;
        double dy = startY - centerY;
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public BoundingBox getBoundingBox() {
        BoundingBox bounds = new BoundingBox();
        double r = getRadius();
        // Approximate: use center +/- radius
        bounds.includePoint(centerX - r, centerY - r);
        bounds.includePoint(centerX + r, centerY + r);
        // Expand by aperture size
        if (aperture != null) {
            BoundingBox apBounds = aperture.getBoundingBox();
            double margin = Math.max(apBounds.getWidth(), apBounds.getHeight()) / 2;
            bounds.expand(margin);
        }
        return bounds;
    }

    @Override
    public String toSvg(SvgOptions options) {
        String color = polarity == Polarity.DARK ? options.getDarkColor() : options.getClearColor();
        double strokeWidth = 0;
        if (aperture instanceof CircleAperture) {
            strokeWidth = ((CircleAperture) aperture).getDiameter();
        }

        if (options.isPolygonize()) {
            // Polygonized mode: path-based stroked arc (filled polygon approximation)
            String pathData = SvgPathUtils.strokedArcPath(
                startX, startY, endX, endY, centerX, centerY, clockwise, strokeWidth);
            return String.format("<path d=\"%s\" fill=\"%s\"/>", pathData, color);
        } else {
            // Exact mode: use native SVG path with arc commands
            double radius = getRadius();

            // Check for full circle (start == end)
            double dx = endX - startX;
            double dy = endY - startY;
            boolean isFullCircle = Math.sqrt(dx * dx + dy * dy) < 0.0001;

            if (isFullCircle) {
                // Full circle: SVG arc can't represent this in one arc, use two half-arcs
                // Calculate the opposite point on the circle
                double oppositeX = 2 * centerX - startX;
                double oppositeY = 2 * centerY - startY;

                // sweepFlag: 0=CCW, 1=CW in SVG coordinates
                // With Y-flip, visual direction inverts, so:
                // Gerber CW needs SVG CCW (0), Gerber CCW needs SVG CW (1)
                int sweepFlag;
                if (options.isFlipY()) {
                    sweepFlag = clockwise ? 0 : 1;
                } else {
                    sweepFlag = clockwise ? 1 : 0;
                }

                // Two half-arcs
                String pathData = String.format(Locale.US,
                    "M %.6f %.6f A %.6f %.6f 0 0 %d %.6f %.6f A %.6f %.6f 0 0 %d %.6f %.6f",
                    startX, startY,
                    radius, radius, sweepFlag, oppositeX, oppositeY,
                    radius, radius, sweepFlag, startX, startY);

                return String.format(Locale.US,
                    "<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.6f\" stroke-linecap=\"round\"/>",
                    pathData, color, strokeWidth);
            }

            // Calculate sweep angle to determine large-arc-flag
            double startAngle = Math.atan2(startY - centerY, startX - centerX);
            double endAngle = Math.atan2(endY - centerY, endX - centerX);
            double sweep;
            if (clockwise) {
                sweep = startAngle - endAngle;
                if (sweep <= 0) sweep += 2 * Math.PI;
            } else {
                sweep = endAngle - startAngle;
                if (sweep <= 0) sweep += 2 * Math.PI;
            }
            int largeArcFlag = sweep > Math.PI ? 1 : 0;
            // sweepFlag: 0=CCW, 1=CW in SVG coordinates
            // With Y-flip transform, visual direction is inverted:
            // - SVG CCW (sweep=0) appears CW after flip
            // - SVG CW (sweep=1) appears CCW after flip
            // So for flipY: Gerber CW needs SVG CCW (0), Gerber CCW needs SVG CW (1)
            int sweepFlag;
            if (options.isFlipY()) {
                sweepFlag = clockwise ? 0 : 1;
            } else {
                sweepFlag = clockwise ? 1 : 0;
            }

            // SVG path with arc: M start, A radius radius x-axis-rotation large-arc-flag sweep-flag end
            String pathData = String.format(Locale.US,
                "M %.6f %.6f A %.6f %.6f 0 %d %d %.6f %.6f",
                startX, startY, radius, radius, largeArcFlag, sweepFlag, endX, endY);

            return String.format(Locale.US,
                "<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.6f\" stroke-linecap=\"round\"/>",
                pathData, color, strokeWidth);
        }
    }

    @Override
    public GraphicsObject translate(double offsetX, double offsetY) {
        Arc translated = new Arc(
            startX + offsetX, startY + offsetY,
            endX + offsetX, endY + offsetY,
            centerX + offsetX, centerY + offsetY,
            clockwise, aperture);
        translated.setPolarity(this.polarity);
        return translated;
    }

    @Override
    public String toString() {
        return String.format("Arc[%.4f,%.4f -> %.4f,%.4f, center=%.4f,%.4f, %s]",
            startX, startY, endX, endY, centerX, centerY,
            clockwise ? "CW" : "CCW");
    }
}
