package com.deltaproto.deltagerber.model.gerber.aperture;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;

/**
 * Obround (stadium/discorectangle) aperture (template code 'O').
 */
public class ObroundAperture extends Aperture {

    private final double width;
    private final double height;
    private final double holeDiameter;

    public ObroundAperture(int dCode, double width, double height) {
        this(dCode, width, height, 0);
    }

    public ObroundAperture(int dCode, double width, double height, double holeDiameter) {
        super(dCode);
        this.width = width;
        this.height = height;
        this.holeDiameter = holeDiameter;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public double getHoleDiameter() {
        return holeDiameter;
    }

    public boolean hasHole() {
        return holeDiameter > 0;
    }

    @Override
    public String getTemplateCode() {
        return "O";
    }

    @Override
    public BoundingBox getBoundingBox() {
        double hw = width / 2;
        double hh = height / 2;
        return new BoundingBox(-hw, -hh, hw, hh);
    }

    @Override
    public String toSvgDef(String id, SvgOptions options) {
        double hw = width / 2;
        double hh = height / 2;
        double r = Math.min(hw, hh);
        String darkColor = options.getDarkColor();

        if (options.isPolygonize()) {
            // Polygonized mode: approximate arcs with line segments
            String pathData = SvgPathUtils.obroundPath(0, 0, width, height);
            if (hasHole()) {
                pathData = pathData + " " + reverseCirclePath(0, 0, holeDiameter / 2, options.getCircleSegments());
            }
            return String.format("<path id=\"%s\" d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
                id, pathData, darkColor);
        } else {
            // Exact mode: use SVG arc commands
            StringBuilder path = new StringBuilder();
            if (width > height) {
                // Horizontal obround
                double flatWidth = width - height;
                path.append(String.format(java.util.Locale.US, "M %.6f %.6f ", -flatWidth/2, -hh));
                path.append(String.format(java.util.Locale.US, "L %.6f %.6f ", flatWidth/2, -hh));
                path.append(String.format(java.util.Locale.US, "A %.6f %.6f 0 0 1 %.6f %.6f ", r, r, flatWidth/2, hh));
                path.append(String.format(java.util.Locale.US, "L %.6f %.6f ", -flatWidth/2, hh));
                path.append(String.format(java.util.Locale.US, "A %.6f %.6f 0 0 1 %.6f %.6f ", r, r, -flatWidth/2, -hh));
                path.append("Z");
            } else {
                // Vertical obround
                double flatHeight = height - width;
                path.append(String.format(java.util.Locale.US, "M %.6f %.6f ", -hw, -flatHeight/2));
                path.append(String.format(java.util.Locale.US, "A %.6f %.6f 0 0 1 %.6f %.6f ", r, r, hw, -flatHeight/2));
                path.append(String.format(java.util.Locale.US, "L %.6f %.6f ", hw, flatHeight/2));
                path.append(String.format(java.util.Locale.US, "A %.6f %.6f 0 0 1 %.6f %.6f ", r, r, -hw, flatHeight/2));
                path.append(String.format(java.util.Locale.US, "L %.6f %.6f ", -hw, -flatHeight/2));
                path.append("Z");
            }

            if (hasHole()) {
                // Add hole circle counter-clockwise for true transparent hole
                double hr = holeDiameter / 2;
                path.append(String.format(java.util.Locale.US,
                    " M %.6f 0 A %.6f %.6f 0 1 0 %.6f 0 A %.6f %.6f 0 1 0 %.6f 0 Z",
                    hr, hr, hr, -hr, hr, hr, hr));
                return String.format("<path id=\"%s\" d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
                    id, path, darkColor);
            } else {
                return String.format("<path id=\"%s\" d=\"%s\" fill=\"%s\"/>", id, path, darkColor);
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
        return String.format("ObroundAperture[D%d, %.4fx%.4f]",
            getDCode(), width, height);
    }
}
