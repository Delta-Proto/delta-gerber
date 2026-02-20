package com.deltaproto.deltagerber.renderer.svg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Utility class for generating SVG path data from geometric shapes.
 * Outputs path-based polygon approximations similar to professional Gerber viewers.
 */
public class SvgPathUtils {

    private static final int CIRCLE_SEGMENTS = 32;  // Number of segments for circle approximation

    private SvgPathUtils() {
        // Utility class
    }

    /**
     * Generate a circle path approximated as a polygon.
     *
     * @param cx center X
     * @param cy center Y
     * @param radius radius
     * @return SVG path data string
     */
    public static String circlePath(double cx, double cy, double radius) {
        return circlePath(cx, cy, radius, CIRCLE_SEGMENTS);
    }

    /**
     * Generate a circle path approximated as a polygon with specified segments.
     */
    public static String circlePath(double cx, double cy, double radius, int segments) {
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            double x = cx + radius * Math.cos(angle);
            double y = cy + radius * Math.sin(angle);
            if (i == 0) {
                path.append(String.format(Locale.US, "M %.6f %.6f", x, y));
            } else {
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }
        }
        path.append(" Z");
        return path.toString();
    }

    /**
     * Generate a circle with a hole (annulus) as a path.
     * Uses the even-odd fill rule where the inner circle creates the hole.
     */
    public static String annulusPath(double cx, double cy, double outerRadius, double innerRadius) {
        return annulusPath(cx, cy, outerRadius, innerRadius, CIRCLE_SEGMENTS);
    }

    /**
     * Generate an annulus with specified segments.
     */
    public static String annulusPath(double cx, double cy, double outerRadius, double innerRadius, int segments) {
        StringBuilder path = new StringBuilder();

        // Outer circle (clockwise)
        for (int i = 0; i < segments; i++) {
            double angle = 2 * Math.PI * i / segments;
            double x = cx + outerRadius * Math.cos(angle);
            double y = cy + outerRadius * Math.sin(angle);
            if (i == 0) {
                path.append(String.format(Locale.US, "M %.6f %.6f", x, y));
            } else {
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }
        }
        path.append(" Z");

        // Inner circle (counter-clockwise for hole)
        for (int i = segments - 1; i >= 0; i--) {
            double angle = 2 * Math.PI * i / segments;
            double x = cx + innerRadius * Math.cos(angle);
            double y = cy + innerRadius * Math.sin(angle);
            if (i == segments - 1) {
                path.append(String.format(Locale.US, " M %.6f %.6f", x, y));
            } else {
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }
        }
        path.append(" Z");

        return path.toString();
    }

    /**
     * Generate a rectangle path.
     */
    public static String rectanglePath(double cx, double cy, double width, double height) {
        double hw = width / 2;
        double hh = height / 2;
        return String.format(Locale.US,
            "M %.6f %.6f L %.6f %.6f L %.6f %.6f L %.6f %.6f Z",
            cx - hw, cy - hh,  // bottom-left
            cx + hw, cy - hh,  // bottom-right
            cx + hw, cy + hh,  // top-right
            cx - hw, cy + hh); // top-left
    }

    /**
     * Generate a rectangle with a round hole.
     */
    public static String rectangleWithHolePath(double cx, double cy, double width, double height, double holeDiameter) {
        StringBuilder path = new StringBuilder();

        // Rectangle
        double hw = width / 2;
        double hh = height / 2;
        path.append(String.format(Locale.US,
            "M %.6f %.6f L %.6f %.6f L %.6f %.6f L %.6f %.6f Z",
            cx - hw, cy - hh,
            cx + hw, cy - hh,
            cx + hw, cy + hh,
            cx - hw, cy + hh));

        // Inner hole (counter-clockwise)
        double hr = holeDiameter / 2;
        for (int i = CIRCLE_SEGMENTS - 1; i >= 0; i--) {
            double angle = 2 * Math.PI * i / CIRCLE_SEGMENTS;
            double x = cx + hr * Math.cos(angle);
            double y = cy + hr * Math.sin(angle);
            if (i == CIRCLE_SEGMENTS - 1) {
                path.append(String.format(Locale.US, " M %.6f %.6f", x, y));
            } else {
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }
        }
        path.append(" Z");

        return path.toString();
    }

    /**
     * Generate an obround (stadium/pill shape) path.
     */
    public static String obroundPath(double cx, double cy, double width, double height) {
        int semiSegments = CIRCLE_SEGMENTS / 2;

        double hw = width / 2;
        double hh = height / 2;

        StringBuilder path = new StringBuilder();

        if (width >= height) {
            // Horizontal obround: semicircles on left and right
            double radius = height / 2;
            double flatLength = width - height;
            double leftCenter = cx - flatLength / 2;
            double rightCenter = cx + flatLength / 2;

            // Start at bottom of right semicircle
            path.append(String.format(Locale.US, "M %.6f %.6f", rightCenter, cy - radius));

            // Right semicircle (bottom to top, clockwise)
            for (int i = 1; i <= semiSegments; i++) {
                double angle = -Math.PI / 2 + Math.PI * i / semiSegments;
                double x = rightCenter + radius * Math.cos(angle);
                double y = cy + radius * Math.sin(angle);
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }

            // Top flat edge
            path.append(String.format(Locale.US, " L %.6f %.6f", leftCenter, cy + radius));

            // Left semicircle (top to bottom, clockwise)
            for (int i = 1; i <= semiSegments; i++) {
                double angle = Math.PI / 2 + Math.PI * i / semiSegments;
                double x = leftCenter + radius * Math.cos(angle);
                double y = cy + radius * Math.sin(angle);
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }

            // Bottom flat edge (implicit closure)
            path.append(" Z");
        } else {
            // Vertical obround: semicircles on top and bottom
            double radius = width / 2;
            double flatLength = height - width;
            double bottomCenter = cy - flatLength / 2;
            double topCenter = cy + flatLength / 2;

            // Start at right of bottom semicircle
            path.append(String.format(Locale.US, "M %.6f %.6f", cx + radius, bottomCenter));

            // Right edge
            path.append(String.format(Locale.US, " L %.6f %.6f", cx + radius, topCenter));

            // Top semicircle (right to left)
            for (int i = 1; i <= semiSegments; i++) {
                double angle = 0 + Math.PI * i / semiSegments;
                double x = cx + radius * Math.cos(angle);
                double y = topCenter + radius * Math.sin(angle);
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }

            // Left edge
            path.append(String.format(Locale.US, " L %.6f %.6f", cx - radius, bottomCenter));

            // Bottom semicircle (left to right)
            for (int i = 1; i <= semiSegments; i++) {
                double angle = Math.PI + Math.PI * i / semiSegments;
                double x = cx + radius * Math.cos(angle);
                double y = bottomCenter + radius * Math.sin(angle);
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }

            path.append(" Z");
        }

        return path.toString();
    }

    /**
     * Generate a regular polygon path.
     *
     * @param cx center X
     * @param cy center Y
     * @param outerDiameter diameter of circumscribed circle
     * @param vertices number of vertices
     * @param rotationDegrees rotation angle in degrees
     */
    public static String polygonPath(double cx, double cy, double outerDiameter,
                                     int vertices, double rotationDegrees) {
        double radius = outerDiameter / 2;
        double rotationRad = Math.toRadians(rotationDegrees);

        StringBuilder path = new StringBuilder();
        for (int i = 0; i < vertices; i++) {
            double angle = rotationRad + 2 * Math.PI * i / vertices;
            double x = cx + radius * Math.cos(angle);
            double y = cy + radius * Math.sin(angle);
            if (i == 0) {
                path.append(String.format(Locale.US, "M %.6f %.6f", x, y));
            } else {
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }
        }
        path.append(" Z");

        return path.toString();
    }

    /**
     * Generate an outline (arbitrary polygon) path from a list of points.
     *
     * @param points list of [x, y] coordinate pairs
     * @param close whether to close the path
     */
    public static String outlinePath(List<double[]> points, boolean close) {
        if (points == null || points.isEmpty()) {
            return "";
        }

        StringBuilder path = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            double[] pt = points.get(i);
            if (i == 0) {
                path.append(String.format(Locale.US, "M %.6f %.6f", pt[0], pt[1]));
            } else {
                path.append(String.format(Locale.US, " L %.6f %.6f", pt[0], pt[1]));
            }
        }
        if (close) {
            path.append(" Z");
        }

        return path.toString();
    }

    /**
     * Generate an arc path approximated as line segments.
     *
     * @param startX start X
     * @param startY start Y
     * @param endX end X
     * @param endY end Y
     * @param centerX arc center X
     * @param centerY arc center Y
     * @param clockwise true for clockwise, false for counter-clockwise
     */
    public static String arcPath(double startX, double startY,
                                 double endX, double endY,
                                 double centerX, double centerY,
                                 boolean clockwise) {
        double startRadius = Math.sqrt(Math.pow(startX - centerX, 2) + Math.pow(startY - centerY, 2));
        double endRadius = Math.sqrt(Math.pow(endX - centerX, 2) + Math.pow(endY - centerY, 2));
        double radius = (startRadius + endRadius) / 2;  // Average for imprecise arcs

        double startAngle = Math.atan2(startY - centerY, startX - centerX);
        double endAngle = Math.atan2(endY - centerY, endX - centerX);

        // Calculate sweep angle
        double sweep;
        if (clockwise) {
            sweep = startAngle - endAngle;
            if (sweep <= 0) sweep += 2 * Math.PI;
        } else {
            sweep = endAngle - startAngle;
            if (sweep <= 0) sweep += 2 * Math.PI;
        }

        // Number of segments based on arc length
        int segments = Math.max(8, (int) (sweep * radius * 10));

        StringBuilder path = new StringBuilder();
        path.append(String.format(Locale.US, "M %.6f %.6f", startX, startY));

        for (int i = 1; i <= segments; i++) {
            double t = (double) i / segments;
            double angle;
            if (clockwise) {
                angle = startAngle - sweep * t;
            } else {
                angle = startAngle + sweep * t;
            }
            double x = centerX + radius * Math.cos(angle);
            double y = centerY + radius * Math.sin(angle);
            path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
        }

        return path.toString();
    }

    /**
     * Generate a line path (just a simple line between two points).
     */
    public static String linePath(double x1, double y1, double x2, double y2) {
        return String.format(Locale.US, "M %.6f %.6f L %.6f %.6f", x1, y1, x2, y2);
    }

    /**
     * Generate a stroked line as a rectangle path (with round caps approximated).
     */
    public static String strokedLinePath(double x1, double y1, double x2, double y2, double strokeWidth) {
        double hw = strokeWidth / 2;
        double dx = x2 - x1;
        double dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);

        if (len == 0) {
            // Degenerate line - return a circle
            return circlePath((x1 + x2) / 2, (y1 + y2) / 2, hw);
        }

        // Unit perpendicular vector
        double px = -dy / len * hw;
        double py = dx / len * hw;

        // Four corners of the rectangle
        double ax = x1 + px, ay = y1 + py;
        double bx = x2 + px, by = y2 + py;
        double cx = x2 - px, cy = y2 - py;
        double dx2 = x1 - px, dy2 = y1 - py;

        // Add rounded end caps (semicircles)
        StringBuilder path = new StringBuilder();

        // Start cap (semicircle at x1,y1)
        int semiSegments = CIRCLE_SEGMENTS / 2;
        double startAngle = Math.atan2(py, px);

        path.append(String.format(Locale.US, "M %.6f %.6f", ax, ay));

        // Line to end of first edge
        path.append(String.format(Locale.US, " L %.6f %.6f", bx, by));

        // End cap semicircle
        for (int i = 1; i <= semiSegments; i++) {
            double angle = startAngle - Math.PI * i / semiSegments;
            double x = x2 + hw * Math.cos(angle);
            double y = y2 + hw * Math.sin(angle);
            path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
        }

        // Line to start of last edge
        path.append(String.format(Locale.US, " L %.6f %.6f", dx2, dy2));

        // Start cap semicircle
        for (int i = 1; i <= semiSegments; i++) {
            double angle = startAngle + Math.PI + Math.PI * i / semiSegments;
            double x = x1 + hw * Math.cos(angle);
            double y = y1 + hw * Math.sin(angle);
            path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
        }

        path.append(" Z");

        return path.toString();
    }

    /**
     * Generate a stroked arc as a filled path (polygon approximation).
     */
    public static String strokedArcPath(double startX, double startY,
                                        double endX, double endY,
                                        double centerX, double centerY,
                                        boolean clockwise, double strokeWidth) {
        double hw = strokeWidth / 2;

        double startRadius = Math.sqrt(Math.pow(startX - centerX, 2) + Math.pow(startY - centerY, 2));
        double endRadius = Math.sqrt(Math.pow(endX - centerX, 2) + Math.pow(endY - centerY, 2));
        double radius = (startRadius + endRadius) / 2;

        double outerR = radius + hw;
        double innerR = radius - hw;

        double startAngle = Math.atan2(startY - centerY, startX - centerX);
        double endAngle = Math.atan2(endY - centerY, endX - centerX);

        // Calculate sweep angle
        double sweep;
        if (clockwise) {
            sweep = startAngle - endAngle;
            if (sweep <= 0) sweep += 2 * Math.PI;
        } else {
            sweep = endAngle - startAngle;
            if (sweep <= 0) sweep += 2 * Math.PI;
        }

        // Number of segments based on arc length
        int segments = Math.max(8, (int) (sweep * radius * 10));

        StringBuilder path = new StringBuilder();

        // Outer arc
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            double angle;
            if (clockwise) {
                angle = startAngle - sweep * t;
            } else {
                angle = startAngle + sweep * t;
            }
            double x = centerX + outerR * Math.cos(angle);
            double y = centerY + outerR * Math.sin(angle);
            if (i == 0) {
                path.append(String.format(Locale.US, "M %.6f %.6f", x, y));
            } else {
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }
        }

        // End cap semicircle
        int semiSegments = CIRCLE_SEGMENTS / 2;
        double endCapAngle = clockwise ? startAngle - sweep : startAngle + sweep;
        for (int i = 1; i <= semiSegments; i++) {
            double angle;
            if (clockwise) {
                angle = endCapAngle + Math.PI * i / semiSegments;
            } else {
                angle = endCapAngle - Math.PI * i / semiSegments;
            }
            double x = endX + hw * Math.cos(angle);
            double y = endY + hw * Math.sin(angle);
            path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
        }

        // Inner arc (reverse direction)
        for (int i = segments; i >= 0; i--) {
            double t = (double) i / segments;
            double angle;
            if (clockwise) {
                angle = startAngle - sweep * t;
            } else {
                angle = startAngle + sweep * t;
            }
            double x = centerX + innerR * Math.cos(angle);
            double y = centerY + innerR * Math.sin(angle);
            path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
        }

        // Start cap semicircle
        for (int i = 1; i < semiSegments; i++) {
            double angle;
            if (clockwise) {
                angle = startAngle - Math.PI * i / semiSegments;
            } else {
                angle = startAngle + Math.PI * i / semiSegments;
            }
            double x = startX + hw * Math.cos(angle);
            double y = startY + hw * Math.sin(angle);
            path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
        }

        path.append(" Z");
        return path.toString();
    }

    /**
     * Generate a thermal relief pattern as a path.
     */
    public static String thermalPath(double cx, double cy, double outerDiameter,
                                     double innerDiameter, double gapWidth, double rotationDegrees) {
        return thermalPath(cx, cy, outerDiameter, innerDiameter, gapWidth, rotationDegrees, CIRCLE_SEGMENTS);
    }

    /**
     * Generate a thermal relief pattern as a path with specified segments.
     */
    public static String thermalPath(double cx, double cy, double outerDiameter,
                                     double innerDiameter, double gapWidth, double rotationDegrees,
                                     int totalSegments) {
        double outerR = outerDiameter / 2;
        double innerR = innerDiameter / 2;
        double rotationRad = Math.toRadians(rotationDegrees);

        // Calculate gap angle
        double gapAngle = 2 * Math.asin(gapWidth / (2 * innerR));

        StringBuilder path = new StringBuilder();
        int segments = totalSegments / 4;  // Segments per quarter

        // Four arcs, each separated by gaps
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            double baseAngle = rotationRad + quadrant * Math.PI / 2 + gapAngle / 2;
            double arcAngle = Math.PI / 2 - gapAngle;

            // Outer arc
            for (int i = 0; i <= segments; i++) {
                double angle = baseAngle + arcAngle * i / segments;
                double x = cx + outerR * Math.cos(angle);
                double y = cy + outerR * Math.sin(angle);
                if (i == 0 && quadrant == 0) {
                    path.append(String.format(Locale.US, "M %.6f %.6f", x, y));
                } else if (i == 0) {
                    path.append(String.format(Locale.US, " M %.6f %.6f", x, y));
                } else {
                    path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
                }
            }

            // Line to inner arc
            double endAngle = baseAngle + arcAngle;
            path.append(String.format(Locale.US, " L %.6f %.6f",
                cx + innerR * Math.cos(endAngle), cy + innerR * Math.sin(endAngle)));

            // Inner arc (reverse direction)
            for (int i = segments - 1; i >= 0; i--) {
                double angle = baseAngle + arcAngle * i / segments;
                double x = cx + innerR * Math.cos(angle);
                double y = cy + innerR * Math.sin(angle);
                path.append(String.format(Locale.US, " L %.6f %.6f", x, y));
            }

            path.append(" Z");
        }

        return path.toString();
    }
}
