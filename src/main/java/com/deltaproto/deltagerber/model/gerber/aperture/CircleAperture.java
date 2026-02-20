package com.deltaproto.deltagerber.model.gerber.aperture;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;

/**
 * Circle aperture (template code 'C').
 */
public class CircleAperture extends Aperture {

    private final double diameter;
    private final double holeDiameter;

    public CircleAperture(int dCode, double diameter) {
        this(dCode, diameter, 0);
    }

    public CircleAperture(int dCode, double diameter, double holeDiameter) {
        super(dCode);
        this.diameter = diameter;
        this.holeDiameter = holeDiameter;
    }

    public double getDiameter() {
        return diameter;
    }

    public double getRadius() {
        return diameter / 2;
    }

    public double getHoleDiameter() {
        return holeDiameter;
    }

    public boolean hasHole() {
        return holeDiameter > 0;
    }

    @Override
    public String getTemplateCode() {
        return "C";
    }

    @Override
    public BoundingBox getBoundingBox() {
        double r = diameter / 2;
        return new BoundingBox(-r, -r, r, r);
    }

    @Override
    public String toSvgDef(String id, SvgOptions options) {
        double r = diameter / 2;
        String darkColor = options.getDarkColor();

        if (options.isPolygonize()) {
            // Polygonized mode: approximate circles as paths
            String pathData;
            if (hasHole()) {
                double hr = holeDiameter / 2;
                pathData = SvgPathUtils.annulusPath(0, 0, r, hr, options.getCircleSegments());
            } else {
                pathData = SvgPathUtils.circlePath(0, 0, r, options.getCircleSegments());
            }
            return String.format("<path id=\"%s\" d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
                id, pathData, darkColor);
        } else {
            // Exact mode: use native SVG elements
            if (hasHole()) {
                // Use path with arc commands for true transparent hole (fill-rule evenodd)
                double hr = holeDiameter / 2;
                // Outer circle clockwise, inner circle counter-clockwise
                String pathData = String.format(java.util.Locale.US,
                    "M %.6f 0 A %.6f %.6f 0 1 1 %.6f 0 A %.6f %.6f 0 1 1 %.6f 0 Z " +  // Outer CW
                    "M %.6f 0 A %.6f %.6f 0 1 0 %.6f 0 A %.6f %.6f 0 1 0 %.6f 0 Z",    // Inner CCW
                    r, r, r, -r, r, r, r,
                    hr, hr, hr, -hr, hr, hr, hr);
                return String.format("<path id=\"%s\" d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
                    id, pathData, darkColor);
            } else {
                return String.format(java.util.Locale.US,
                    "<circle id=\"%s\" cx=\"0\" cy=\"0\" r=\"%.6f\" fill=\"%s\"/>",
                    id, r, darkColor);
            }
        }
    }

    @Override
    public String toString() {
        if (hasHole()) {
            return String.format("CircleAperture[D%d, d=%.4f, hole=%.4f]",
                getDCode(), diameter, holeDiameter);
        }
        return String.format("CircleAperture[D%d, d=%.4f]", getDCode(), diameter);
    }
}
