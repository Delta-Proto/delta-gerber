package com.deltaproto.deltagerber.model.gerber.aperture.macro;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;
import java.util.Map;

/**
 * Moire primitive (code 6).
 * A target with concentric rings and crosshairs.
 * Parameters: center X, center Y, outer ring diameter, ring thickness,
 *             gap between rings, max rings, crosshair thickness, crosshair length, rotation
 */
public class MoirePrimitive implements MacroPrimitive {

    private final MacroExpression centerX;
    private final MacroExpression centerY;
    private final MacroExpression outerDiameter;
    private final MacroExpression ringThickness;
    private final MacroExpression ringGap;
    private final MacroExpression maxRings;
    private final MacroExpression crosshairThickness;
    private final MacroExpression crosshairLength;
    private final MacroExpression rotation;

    public MoirePrimitive(String[] params) {
        this.centerX = new MacroExpression(params[0]);
        this.centerY = new MacroExpression(params[1]);
        this.outerDiameter = new MacroExpression(params[2]);
        this.ringThickness = new MacroExpression(params[3]);
        this.ringGap = new MacroExpression(params[4]);
        this.maxRings = new MacroExpression(params[5]);
        this.crosshairThickness = new MacroExpression(params[6]);
        this.crosshairLength = new MacroExpression(params[7]);
        this.rotation = params.length > 8 ? new MacroExpression(params[8]) : new MacroExpression("0");
    }

    @Override
    public String toSvg(Map<Integer, Double> variables, SvgOptions options) {
        double cx = centerX.evaluate(variables);
        double cy = centerY.evaluate(variables);
        double od = outerDiameter.evaluate(variables);
        double thick = ringThickness.evaluate(variables);
        double gap = ringGap.evaluate(variables);
        int rings = (int) maxRings.evaluate(variables);
        double crossThick = crosshairThickness.evaluate(variables);
        double crossLen = crosshairLength.evaluate(variables);
        double rot = rotation.evaluate(variables);

        if (options.isPolygonize()) {
            // Polygonized mode: use path approximations
            StringBuilder pathData = new StringBuilder();
            double outerRadius = od / 2;
            double pitch = thick + gap;

            for (int i = 0; i < rings && outerRadius > 0; i++) {
                double innerRadius = Math.max(0, outerRadius - thick);

                if (innerRadius > 0) {
                    String ringPath = SvgPathUtils.annulusPath(cx, cy, outerRadius, innerRadius, options.getCircleSegments());
                    if (pathData.length() > 0) pathData.append(" ");
                    pathData.append(ringPath);
                } else {
                    String circlePath = SvgPathUtils.circlePath(cx, cy, outerRadius, options.getCircleSegments());
                    if (pathData.length() > 0) pathData.append(" ");
                    pathData.append(circlePath);
                }

                outerRadius = outerRadius - pitch;
            }

            if (crossThick > 0 && crossLen > 0) {
                String hBarPath = SvgPathUtils.rectanglePath(cx, cy, crossLen, crossThick);
                if (pathData.length() > 0) pathData.append(" ");
                pathData.append(hBarPath);

                String vBarPath = SvgPathUtils.rectanglePath(cx, cy, crossThick, crossLen);
                if (pathData.length() > 0) pathData.append(" ");
                pathData.append(vBarPath);
            }

            StringBuilder svg = new StringBuilder();
            if (rot != 0) {
                svg.append(String.format(java.util.Locale.US, "<g transform=\"rotate(%.6f %.6f %.6f)\">", rot, cx, cy));
                svg.append(String.format(java.util.Locale.US, "<path d=\"%s\" fill=\"black\" fill-rule=\"evenodd\"/>", pathData));
                svg.append("</g>");
            } else {
                svg.append(String.format(java.util.Locale.US, "<path d=\"%s\" fill=\"black\" fill-rule=\"evenodd\"/>", pathData));
            }
            return svg.toString();
        } else {
            // Exact mode: use native SVG circles and rectangles
            StringBuilder svg = new StringBuilder();

            if (rot != 0) {
                svg.append(String.format(java.util.Locale.US, "<g transform=\"rotate(%.6f %.6f %.6f)\">", rot, cx, cy));
            }

            double outerRadius = od / 2;
            double pitch = thick + gap;

            // Draw concentric rings
            for (int i = 0; i < rings && outerRadius > 0; i++) {
                double innerRadius = Math.max(0, outerRadius - thick);

                if (innerRadius > 0) {
                    // Annulus: outer circle black, inner circle white
                    svg.append(String.format(java.util.Locale.US,
                        "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"%.6f\" fill=\"black\"/>", cx, cy, outerRadius));
                    svg.append(String.format(java.util.Locale.US,
                        "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"%.6f\" fill=\"white\"/>", cx, cy, innerRadius));
                } else {
                    // Solid circle for center
                    svg.append(String.format(java.util.Locale.US,
                        "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"%.6f\" fill=\"black\"/>", cx, cy, outerRadius));
                }

                outerRadius = outerRadius - pitch;
            }

            // Draw crosshairs as rectangles
            if (crossThick > 0 && crossLen > 0) {
                double hw = crossLen / 2;
                double hh = crossThick / 2;
                // Horizontal bar
                svg.append(String.format(java.util.Locale.US,
                    "<rect x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"black\"/>",
                    cx - hw, cy - hh, crossLen, crossThick));
                // Vertical bar
                svg.append(String.format(java.util.Locale.US,
                    "<rect x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"black\"/>",
                    cx - hh, cy - hw, crossThick, crossLen));
            }

            if (rot != 0) {
                svg.append("</g>");
            }

            return svg.toString();
        }
    }

    @Override
    public BoundingBox getBoundingBox(Map<Integer, Double> variables) {
        double cx = centerX.evaluate(variables);
        double cy = centerY.evaluate(variables);
        double od = outerDiameter.evaluate(variables);
        double crossLen = crosshairLength.evaluate(variables);

        // The bounding box is the max of outer diameter and crosshair length
        double maxExtent = Math.max(od, crossLen);
        double r = maxExtent / 2;
        return new BoundingBox(cx - r, cy - r, cx + r, cy + r);
    }

    @Override
    public boolean isExposed(Map<Integer, Double> variables) {
        return true; // Moire is always exposed
    }
}
