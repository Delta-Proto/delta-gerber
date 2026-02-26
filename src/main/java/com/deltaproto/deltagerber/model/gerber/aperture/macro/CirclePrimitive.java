package com.deltaproto.deltagerber.model.gerber.aperture.macro;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;
import java.util.Locale;
import java.util.Map;

/**
 * Circle primitive (code 1).
 * Parameters: exposure, diameter, center X, center Y [, rotation]
 */
public class CirclePrimitive implements MacroPrimitive {

    private final MacroExpression exposure;
    private final MacroExpression diameter;
    private final MacroExpression centerX;
    private final MacroExpression centerY;
    private final MacroExpression rotation;

    public CirclePrimitive(String[] params) {
        this.exposure = new MacroExpression(params[0]);
        this.diameter = new MacroExpression(params[1]);
        this.centerX = params.length > 2 ? new MacroExpression(params[2]) : new MacroExpression("0");
        this.centerY = params.length > 3 ? new MacroExpression(params[3]) : new MacroExpression("0");
        this.rotation = params.length > 4 ? new MacroExpression(params[4]) : new MacroExpression("0");
    }

    @Override
    public String toSvg(Map<Integer, Double> variables, SvgOptions options, double unitFactor) {
        double exp = exposure.evaluate(variables);
        double d = diameter.evaluate(variables) * unitFactor;
        double cx = centerX.evaluate(variables) * unitFactor;
        double cy = centerY.evaluate(variables) * unitFactor;
        double rot = rotation.evaluate(variables);

        // Apply rotation if specified
        if (rot != 0) {
            double radians = Math.toRadians(rot);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double newX = cx * cos - cy * sin;
            double newY = cx * sin + cy * cos;
            cx = newX;
            cy = newY;
        }

        double r = d / 2;
        String fill = exp >= 1 ? options.getDarkColor() : options.getClearColor();

        if (options.isPolygonize()) {
            String pathData = SvgPathUtils.circlePath(cx, cy, r, options.getCircleSegments());
            return String.format("<path d=\"%s\" fill=\"%s\"/>", pathData, fill);
        } else {
            return String.format(Locale.US, "<circle cx=\"%.6f\" cy=\"%.6f\" r=\"%.6f\" fill=\"%s\"/>", cx, cy, r, fill);
        }
    }

    @Override
    public BoundingBox getBoundingBox(Map<Integer, Double> variables, double unitFactor) {
        double d = diameter.evaluate(variables) * unitFactor;
        double cx = centerX.evaluate(variables) * unitFactor;
        double cy = centerY.evaluate(variables) * unitFactor;
        double rot = rotation.evaluate(variables);

        if (rot != 0) {
            double radians = Math.toRadians(rot);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double newX = cx * cos - cy * sin;
            double newY = cx * sin + cy * cos;
            cx = newX;
            cy = newY;
        }

        double r = d / 2;
        return new BoundingBox(cx - r, cy - r, cx + r, cy + r);
    }

    @Override
    public boolean isExposed(Map<Integer, Double> variables) {
        return exposure.evaluate(variables) >= 1;
    }
}
