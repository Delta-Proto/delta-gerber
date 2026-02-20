package com.deltaproto.deltagerber.model.gerber.aperture.macro;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;
import java.util.Map;

/**
 * Thermal primitive (code 7).
 * A ring with four gaps, typically used for thermal relief pads.
 * Parameters: center X, center Y, outer diameter, inner diameter, gap width, rotation
 * Note: Thermal is always exposed (dark).
 */
public class ThermalPrimitive implements MacroPrimitive {

    private final MacroExpression centerX;
    private final MacroExpression centerY;
    private final MacroExpression outerDiameter;
    private final MacroExpression innerDiameter;
    private final MacroExpression gapWidth;
    private final MacroExpression rotation;

    public ThermalPrimitive(String[] params) {
        this.centerX = new MacroExpression(params[0]);
        this.centerY = new MacroExpression(params[1]);
        this.outerDiameter = new MacroExpression(params[2]);
        this.innerDiameter = new MacroExpression(params[3]);
        this.gapWidth = new MacroExpression(params[4]);
        this.rotation = params.length > 5 ? new MacroExpression(params[5]) : new MacroExpression("0");
    }

    @Override
    public String toSvg(Map<Integer, Double> variables, SvgOptions options) {
        double cx = centerX.evaluate(variables);
        double cy = centerY.evaluate(variables);
        double od = outerDiameter.evaluate(variables);
        double id = innerDiameter.evaluate(variables);
        double gap = gapWidth.evaluate(variables);
        double rot = rotation.evaluate(variables);

        if (options.isPolygonize()) {
            // Polygonized mode: use path approximation
            String pathData = SvgPathUtils.thermalPath(cx, cy, od, id, gap, rot, options.getCircleSegments());
            return String.format(java.util.Locale.US, "<path d=\"%s\" fill=\"%s\"/>", pathData, options.getDarkColor());
        } else {
            // Exact mode: use SVG clip-path for precise thermal relief
            // Thermals are complex - use clip-path with circles and rectangles
            double or = od / 2;
            double ir = id / 2;
            double hw = gap / 2;
            double rotRad = Math.toRadians(rot);

            StringBuilder svg = new StringBuilder();
            String clipId = String.format(java.util.Locale.US, "thermal-clip-%.0f-%.0f", cx * 1000, cy * 1000);

            svg.append(String.format("<defs><clipPath id=\"%s\">", clipId));
            // Outer circle minus gap rectangles
            svg.append(String.format(java.util.Locale.US,
                "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"%.6f\"/>", cx, cy, or));
            svg.append("</clipPath></defs>");

            svg.append(String.format("<g clip-path=\"url(#%s)\">", clipId));

            // Draw annulus (outer - inner)
            svg.append(String.format(java.util.Locale.US,
                "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"%.6f\" fill=\"%s\"/>", cx, cy, or, options.getDarkColor()));
            svg.append(String.format(java.util.Locale.US,
                "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"%.6f\" fill=\"%s\"/>", cx, cy, ir, options.getClearColor()));

            // Draw gap rectangles (cut out four gaps)
            for (int i = 0; i < 4; i++) {
                double angle = rotRad + (Math.PI / 2) * i;
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);

                // Rectangle along the angle direction
                double rectCx = cx;
                double rectCy = cy;
                double rectW = od;
                double rectH = gap;

                // Rotated rectangle as polygon
                double[] cornersX = new double[4];
                double[] cornersY = new double[4];
                double rhw = rectW / 2;
                double rhh = rectH / 2;

                // Corners before rotation
                double[][] corners = {{-rhw, -rhh}, {rhw, -rhh}, {rhw, rhh}, {-rhw, rhh}};
                for (int j = 0; j < 4; j++) {
                    cornersX[j] = cx + corners[j][0] * cos - corners[j][1] * sin;
                    cornersY[j] = cy + corners[j][0] * sin + corners[j][1] * cos;
                }

                svg.append(String.format(java.util.Locale.US,
                    "<polygon points=\"%.6f,%.6f %.6f,%.6f %.6f,%.6f %.6f,%.6f\" fill=\"%s\"/>",
                    cornersX[0], cornersY[0], cornersX[1], cornersY[1],
                    cornersX[2], cornersY[2], cornersX[3], cornersY[3], options.getClearColor()));
            }

            svg.append("</g>");
            return svg.toString();
        }
    }

    @Override
    public BoundingBox getBoundingBox(Map<Integer, Double> variables) {
        double cx = centerX.evaluate(variables);
        double cy = centerY.evaluate(variables);
        double od = outerDiameter.evaluate(variables);

        double r = od / 2;
        return new BoundingBox(cx - r, cy - r, cx + r, cy + r);
    }

    @Override
    public boolean isExposed(Map<Integer, Double> variables) {
        return true; // Thermal is always exposed
    }
}
