package com.deltaproto.deltagerber.model.gerber.aperture;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;

/**
 * Regular polygon aperture (template code 'P').
 */
public class PolygonAperture extends Aperture {

    private final double outerDiameter;
    private final int numVertices;
    private final double rotation;
    private final double holeDiameter;

    public PolygonAperture(int dCode, double outerDiameter, int numVertices) {
        this(dCode, outerDiameter, numVertices, 0, 0);
    }

    public PolygonAperture(int dCode, double outerDiameter, int numVertices, double rotation) {
        this(dCode, outerDiameter, numVertices, rotation, 0);
    }

    public PolygonAperture(int dCode, double outerDiameter, int numVertices,
                          double rotation, double holeDiameter) {
        super(dCode);
        this.outerDiameter = outerDiameter;
        this.numVertices = numVertices;
        this.rotation = rotation;
        this.holeDiameter = holeDiameter;
    }

    public double getOuterDiameter() {
        return outerDiameter;
    }

    public int getNumVertices() {
        return numVertices;
    }

    public double getRotation() {
        return rotation;
    }

    public double getHoleDiameter() {
        return holeDiameter;
    }

    public boolean hasHole() {
        return holeDiameter > 0;
    }

    @Override
    public String getTemplateCode() {
        return "P";
    }

    @Override
    public BoundingBox getBoundingBox() {
        double r = outerDiameter / 2;
        return new BoundingBox(-r, -r, r, r);
    }

    @Override
    public String toSvgDef(String id, SvgOptions options) {
        double r = outerDiameter / 2;
        double rotRad = Math.toRadians(rotation);
        String darkColor = options.getDarkColor();

        // Generate polygon points (exact for both modes - polygons are exact by definition)
        StringBuilder points = new StringBuilder();
        for (int i = 0; i < numVertices; i++) {
            double angle = rotRad + (2 * Math.PI * i / numVertices);
            double x = r * Math.cos(angle);
            double y = r * Math.sin(angle);
            if (i > 0) points.append(" ");
            points.append(String.format(java.util.Locale.US, "%.6f,%.6f", x, y));
        }

        if (options.isPolygonize()) {
            // Polygonized mode: use path for consistency
            String pathData = SvgPathUtils.polygonPath(0, 0, outerDiameter, numVertices, rotation);
            if (hasHole()) {
                double hr = holeDiameter / 2;
                pathData = pathData + " " + reverseCirclePath(0, 0, hr, options.getCircleSegments());
            }
            return String.format("<path id=\"%s\" d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
                id, pathData, darkColor);
        } else {
            // Exact mode
            if (hasHole()) {
                // Use path for true transparent hole (fill-rule evenodd)
                double hr = holeDiameter / 2;
                // Polygon as path + hole circle counter-clockwise
                StringBuilder pathData = new StringBuilder();
                for (int i = 0; i < numVertices; i++) {
                    double angle = rotRad + (2 * Math.PI * i / numVertices);
                    double x = r * Math.cos(angle);
                    double y = r * Math.sin(angle);
                    pathData.append(String.format(java.util.Locale.US,
                        "%s %.6f %.6f ", i == 0 ? "M" : "L", x, y));
                }
                pathData.append("Z ");
                // Add hole circle counter-clockwise
                pathData.append(String.format(java.util.Locale.US,
                    "M %.6f 0 A %.6f %.6f 0 1 0 %.6f 0 A %.6f %.6f 0 1 0 %.6f 0 Z",
                    hr, hr, hr, -hr, hr, hr, hr));
                return String.format("<path id=\"%s\" d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
                    id, pathData, darkColor);
            } else {
                return String.format(java.util.Locale.US,
                    "<polygon id=\"%s\" points=\"%s\" fill=\"%s\"/>",
                    id, points, darkColor);
            }
        }
    }

    /**
     * Generate a circle path in reverse (counter-clockwise) for hole cutouts.
     */
    private String reverseCirclePath(double cx, double cy, double radius, int segments) {
        StringBuilder path = new StringBuilder();
        for (int i = segments - 1; i >= 0; i--) {
            double angle = 2 * Math.PI * i / segments;
            double x = cx + radius * Math.cos(angle);
            double y = cy + radius * Math.sin(angle);
            if (i == segments - 1) {
                path.append(String.format(java.util.Locale.US, "M %.6f %.6f", x, y));
            } else {
                path.append(String.format(java.util.Locale.US, " L %.6f %.6f", x, y));
            }
        }
        path.append(" Z");
        return path.toString();
    }

    @Override
    public String toString() {
        return String.format("PolygonAperture[D%d, d=%.4f, n=%d, rot=%.1f]",
            getDCode(), outerDiameter, numVertices, rotation);
    }
}
