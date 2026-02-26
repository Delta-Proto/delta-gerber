package com.deltaproto.deltagerber.model.gerber.aperture.macro;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Outline primitive (code 4).
 * An arbitrary polygon defined by a list of vertices.
 * Parameters: exposure, n (vertex count), x0, y0, x1, y1, ..., xn, yn, rotation
 * Note: The first and last vertex must be the same to close the polygon.
 */
public class OutlinePrimitive implements MacroPrimitive {

    private final MacroExpression exposure;
    private final int vertexCount;
    private final List<MacroExpression> verticesX;
    private final List<MacroExpression> verticesY;
    private final MacroExpression rotation;

    public OutlinePrimitive(String[] params) {
        this.exposure = new MacroExpression(params[0]);
        this.vertexCount = (int) new MacroExpression(params[1]).evaluate();

        this.verticesX = new ArrayList<>();
        this.verticesY = new ArrayList<>();

        int numPoints = vertexCount + 1;
        for (int i = 0; i < numPoints; i++) {
            int idx = 2 + i * 2;
            if (idx < params.length) {
                verticesX.add(new MacroExpression(params[idx]));
            }
            if (idx + 1 < params.length) {
                verticesY.add(new MacroExpression(params[idx + 1]));
            }
        }

        int rotIdx = 2 + numPoints * 2;
        this.rotation = rotIdx < params.length ? new MacroExpression(params[rotIdx]) : new MacroExpression("0");
    }

    @Override
    public String toSvg(Map<Integer, Double> variables, SvgOptions options, double unitFactor) {
        double exp = exposure.evaluate(variables);
        double rot = rotation.evaluate(variables);

        List<double[]> points = new ArrayList<>();
        int numPoints = Math.min(verticesX.size(), verticesY.size());
        for (int i = 0; i < numPoints; i++) {
            double x = verticesX.get(i).evaluate(variables) * unitFactor;
            double y = verticesY.get(i).evaluate(variables) * unitFactor;
            points.add(new double[]{x, y});
        }

        if (rot != 0) {
            double radians = Math.toRadians(rot);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            for (double[] pt : points) {
                double newX = pt[0] * cos - pt[1] * sin;
                double newY = pt[0] * sin + pt[1] * cos;
                pt[0] = newX;
                pt[1] = newY;
            }
        }

        String fill = exp >= 1 ? options.getDarkColor() : options.getClearColor();

        if (options.isPolygonize()) {
            String pathData = SvgPathUtils.outlinePath(points, true);
            return String.format(java.util.Locale.US, "<path d=\"%s\" fill=\"%s\"/>", pathData, fill);
        } else {
            StringBuilder pointsStr = new StringBuilder();
            for (int i = 0; i < points.size() - 1; i++) {
                if (i > 0) pointsStr.append(" ");
                pointsStr.append(String.format(java.util.Locale.US, "%.6f,%.6f", points.get(i)[0], points.get(i)[1]));
            }
            return String.format(java.util.Locale.US, "<polygon points=\"%s\" fill=\"%s\"/>", pointsStr, fill);
        }
    }

    @Override
    public BoundingBox getBoundingBox(Map<Integer, Double> variables, double unitFactor) {
        double rot = rotation.evaluate(variables);
        BoundingBox bbox = new BoundingBox();

        int numPoints = Math.min(verticesX.size(), verticesY.size());
        for (int i = 0; i < numPoints; i++) {
            double x = verticesX.get(i).evaluate(variables) * unitFactor;
            double y = verticesY.get(i).evaluate(variables) * unitFactor;

            if (rot != 0) {
                double radians = Math.toRadians(rot);
                double cos = Math.cos(radians);
                double sin = Math.sin(radians);
                double newX = x * cos - y * sin;
                double newY = x * sin + y * cos;
                x = newX;
                y = newY;
            }

            bbox.extend(x, y);
        }

        return bbox;
    }

    @Override
    public boolean isExposed(Map<Integer, Double> variables) {
        return exposure.evaluate(variables) >= 1;
    }
}
