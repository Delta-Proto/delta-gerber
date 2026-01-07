package nl.bytesoflife.gerber.model.gerber.operation;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.renderer.svg.SvgOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A contour is a closed path of segments (lines and arcs).
 * Used within regions.
 */
public class Contour {

    private final List<ContourSegment> segments = new ArrayList<>();
    private double startX;
    private double startY;

    public Contour(double startX, double startY) {
        this.startX = startX;
        this.startY = startY;
    }

    public void addLineTo(double x, double y) {
        segments.add(new ContourSegment(x, y, false, 0, 0, false));
    }

    public void addArcTo(double x, double y, double centerX, double centerY, boolean clockwise) {
        segments.add(new ContourSegment(x, y, true, centerX, centerY, clockwise));
    }

    public List<ContourSegment> getSegments() {
        return segments;
    }

    public double getStartX() {
        return startX;
    }

    public double getStartY() {
        return startY;
    }

    public BoundingBox getBoundingBox() {
        BoundingBox bounds = new BoundingBox();
        bounds.includePoint(startX, startY);
        for (ContourSegment seg : segments) {
            bounds.includePoint(seg.getX(), seg.getY());
            if (seg.isArc()) {
                // Include arc center +/- radius for approximation
                double dx = seg.getX() - seg.getCenterX();
                double dy = seg.getY() - seg.getCenterY();
                double r = Math.sqrt(dx * dx + dy * dy);
                bounds.includePoint(seg.getCenterX() - r, seg.getCenterY() - r);
                bounds.includePoint(seg.getCenterX() + r, seg.getCenterY() + r);
            }
        }
        return bounds;
    }

    /**
     * Generate SVG path with default (exact) options.
     */
    public String toSvgPath() {
        return toSvgPath(SvgOptions.exact());
    }

    /**
     * Generate SVG path with specified options.
     */
    public String toSvgPath(SvgOptions options) {
        StringBuilder path = new StringBuilder();
        path.append(String.format(Locale.US, "M %.6f %.6f", startX, startY));

        double currentX = startX;
        double currentY = startY;

        for (ContourSegment seg : segments) {
            if (seg.isArc()) {
                double dx = currentX - seg.getCenterX();
                double dy = currentY - seg.getCenterY();
                double r = Math.sqrt(dx * dx + dy * dy);

                double startAngle = Math.atan2(dy, dx);
                double endDx = seg.getX() - seg.getCenterX();
                double endDy = seg.getY() - seg.getCenterY();
                double endAngle = Math.atan2(endDy, endDx);

                // Calculate sweep angle
                double sweep;
                if (seg.isClockwise()) {
                    sweep = startAngle - endAngle;
                    if (sweep <= 0) sweep += 2 * Math.PI;
                } else {
                    sweep = endAngle - startAngle;
                    if (sweep <= 0) sweep += 2 * Math.PI;
                }

                if (options.isPolygonize()) {
                    // Polygonized mode: approximate arc with line segments
                    int arcSegments = Math.max(8, (int) (sweep * r * 10));
                    for (int i = 1; i <= arcSegments; i++) {
                        double t = (double) i / arcSegments;
                        double angle;
                        if (seg.isClockwise()) {
                            angle = startAngle - sweep * t;
                        } else {
                            angle = startAngle + sweep * t;
                        }
                        double x = seg.getCenterX() + r * Math.cos(angle);
                        double y = seg.getCenterY() + r * Math.sin(angle);
                        path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
                    }
                } else {
                    // Exact mode: use SVG arc command
                    int largeArcFlag = sweep > Math.PI ? 1 : 0;
                    // sweepFlag: 0=CCW, 1=CW in SVG coordinates
                    // With Y-flip, visual direction inverts, so:
                    // Gerber CW needs SVG CCW (0), Gerber CCW needs SVG CW (1)
                    int sweepFlag;
                    if (options.isFlipY()) {
                        sweepFlag = seg.isClockwise() ? 0 : 1;
                    } else {
                        sweepFlag = seg.isClockwise() ? 1 : 0;
                    }
                    path.append(String.format(Locale.US, " A %.6f %.6f 0 %d %d %.6f %.6f",
                        r, r, largeArcFlag, sweepFlag, seg.getX(), seg.getY()));
                }
            } else {
                path.append(String.format(Locale.US, " L %.6f %.6f", seg.getX(), seg.getY()));
            }
            currentX = seg.getX();
            currentY = seg.getY();
        }

        path.append(" Z");
        return path.toString();
    }

    public Contour translate(double offsetX, double offsetY) {
        Contour translated = new Contour(startX + offsetX, startY + offsetY);
        for (ContourSegment seg : segments) {
            if (seg.isArc()) {
                translated.addArcTo(
                    seg.getX() + offsetX, seg.getY() + offsetY,
                    seg.getCenterX() + offsetX, seg.getCenterY() + offsetY,
                    seg.isClockwise());
            } else {
                translated.addLineTo(seg.getX() + offsetX, seg.getY() + offsetY);
            }
        }
        return translated;
    }

    /**
     * A segment within a contour.
     */
    public static class ContourSegment {
        private final double x;
        private final double y;
        private final boolean arc;
        private final double centerX;
        private final double centerY;
        private final boolean clockwise;

        public ContourSegment(double x, double y, boolean arc,
                             double centerX, double centerY, boolean clockwise) {
            this.x = x;
            this.y = y;
            this.arc = arc;
            this.centerX = centerX;
            this.centerY = centerY;
            this.clockwise = clockwise;
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public boolean isArc() { return arc; }
        public double getCenterX() { return centerX; }
        public double getCenterY() { return centerY; }
        public boolean isClockwise() { return clockwise; }
    }
}
