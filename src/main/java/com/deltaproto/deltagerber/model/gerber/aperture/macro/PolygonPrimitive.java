package com.deltaproto.deltagerber.model.gerber.aperture.macro;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;
import java.util.Map;

/**
 * Regular polygon primitive (code 5).
 * A regular polygon with a specified number of vertices.
 * Parameters: exposure, n (vertices), center X, center Y, diameter, rotation
 */
public class PolygonPrimitive implements MacroPrimitive {

    private final MacroExpression exposure;
    private final MacroExpression vertexCount;
    private final MacroExpression centerX;
    private final MacroExpression centerY;
    private final MacroExpression diameter;
    private final MacroExpression rotation;

    public PolygonPrimitive(String[] params) {
        this.exposure = new MacroExpression(params[0]);
        this.vertexCount = new MacroExpression(params[1]);
        this.centerX = new MacroExpression(params[2]);
        this.centerY = new MacroExpression(params[3]);
        this.diameter = new MacroExpression(params[4]);
        this.rotation = params.length > 5 ? new MacroExpression(params[5]) : new MacroExpression("0");
    }

    @Override
    public String toSvg(Map<Integer, Double> variables, SvgOptions options) {
        double exp = exposure.evaluate(variables);
        int n = (int) vertexCount.evaluate(variables);
        double cx = centerX.evaluate(variables);
        double cy = centerY.evaluate(variables);
        double d = diameter.evaluate(variables);
        double rot = rotation.evaluate(variables);

        String fill = exp >= 1 ? options.getDarkColor() : options.getClearColor();

        if (options.isPolygonize()) {
            // Polygonized mode: use path
            String pathData = SvgPathUtils.polygonPath(cx, cy, d, n, rot);
            return String.format(java.util.Locale.US, "<path d=\"%s\" fill=\"%s\"/>", pathData, fill);
        } else {
            // Exact mode: use native SVG polygon element (already geometrically exact)
            double r = d / 2;
            double rotRad = Math.toRadians(rot);
            StringBuilder points = new StringBuilder();
            for (int i = 0; i < n; i++) {
                double angle = rotRad + (2 * Math.PI * i / n);
                double x = cx + r * Math.cos(angle);
                double y = cy + r * Math.sin(angle);
                if (i > 0) points.append(" ");
                points.append(String.format(java.util.Locale.US, "%.6f,%.6f", x, y));
            }
            return String.format(java.util.Locale.US, "<polygon points=\"%s\" fill=\"%s\"/>", points, fill);
        }
    }

    @Override
    public BoundingBox getBoundingBox(Map<Integer, Double> variables) {
        int n = (int) vertexCount.evaluate(variables);
        double cx = centerX.evaluate(variables);
        double cy = centerY.evaluate(variables);
        double d = diameter.evaluate(variables);
        double rot = rotation.evaluate(variables);

        double r = d / 2;
        double angleStep = 2 * Math.PI / n;
        double startAngle = Math.toRadians(rot);

        BoundingBox bbox = new BoundingBox();
        for (int i = 0; i < n; i++) {
            double angle = startAngle + i * angleStep;
            double x = cx + r * Math.cos(angle);
            double y = cy + r * Math.sin(angle);
            bbox.extend(x, y);
        }

        return bbox;
    }

    @Override
    public boolean isExposed(Map<Integer, Double> variables) {
        return exposure.evaluate(variables) >= 1;
    }
}
