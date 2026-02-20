package com.deltaproto.deltagerber.model.gerber.aperture.macro;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import java.util.Locale;
import java.util.Map;

/**
 * Vector line primitive (code 20).
 * A rectangle defined by its endpoints and width.
 * Parameters: exposure, width, start X, start Y, end X, end Y, rotation
 */
public class VectorLinePrimitive implements MacroPrimitive {

    private final MacroExpression exposure;
    private final MacroExpression width;
    private final MacroExpression startX;
    private final MacroExpression startY;
    private final MacroExpression endX;
    private final MacroExpression endY;
    private final MacroExpression rotation;

    public VectorLinePrimitive(String[] params) {
        this.exposure = new MacroExpression(params[0]);
        this.width = new MacroExpression(params[1]);
        this.startX = new MacroExpression(params[2]);
        this.startY = new MacroExpression(params[3]);
        this.endX = new MacroExpression(params[4]);
        this.endY = new MacroExpression(params[5]);
        this.rotation = params.length > 6 ? new MacroExpression(params[6]) : new MacroExpression("0");
    }

    @Override
    public String toSvg(Map<Integer, Double> variables, SvgOptions options) {
        double exp = exposure.evaluate(variables);
        double w = width.evaluate(variables);
        double sx = startX.evaluate(variables);
        double sy = startY.evaluate(variables);
        double ex = endX.evaluate(variables);
        double ey = endY.evaluate(variables);
        double rot = rotation.evaluate(variables);

        // Calculate the line direction and perpendicular
        double dx = ex - sx;
        double dy = ey - sy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len == 0) {
            return "";
        }

        // Perpendicular unit vector
        double px = -dy / len;
        double py = dx / len;

        // Half width
        double hw = w / 2;

        // Four corners of the rectangle
        double[] cornersX = {
            sx + px * hw, ex + px * hw, ex - px * hw, sx - px * hw
        };
        double[] cornersY = {
            sy + py * hw, ey + py * hw, ey - py * hw, sy - py * hw
        };

        // Apply rotation around origin
        if (rot != 0) {
            double radians = Math.toRadians(rot);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            for (int i = 0; i < 4; i++) {
                double newX = cornersX[i] * cos - cornersY[i] * sin;
                double newY = cornersX[i] * sin + cornersY[i] * cos;
                cornersX[i] = newX;
                cornersY[i] = newY;
            }
        }

        String fill = exp >= 1 ? options.getDarkColor() : options.getClearColor();

        if (options.isPolygonize()) {
            // Polygonized mode: use path (same as exact for rectangles)
            String pathData = String.format(Locale.US,
                "M %.6f %.6f L %.6f %.6f L %.6f %.6f L %.6f %.6f Z",
                cornersX[0], cornersY[0], cornersX[1], cornersY[1],
                cornersX[2], cornersY[2], cornersX[3], cornersY[3]);
            return String.format(Locale.US, "<path d=\"%s\" fill=\"%s\"/>", pathData, fill);
        } else {
            // Exact mode: use native SVG polygon element
            StringBuilder points = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) points.append(" ");
                points.append(String.format(Locale.US, "%.6f,%.6f", cornersX[i], cornersY[i]));
            }
            return String.format(Locale.US, "<polygon points=\"%s\" fill=\"%s\"/>", points, fill);
        }
    }

    @Override
    public BoundingBox getBoundingBox(Map<Integer, Double> variables) {
        double w = width.evaluate(variables);
        double sx = startX.evaluate(variables);
        double sy = startY.evaluate(variables);
        double ex = endX.evaluate(variables);
        double ey = endY.evaluate(variables);
        double rot = rotation.evaluate(variables);

        double dx = ex - sx;
        double dy = ey - sy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len == 0) {
            return new BoundingBox();
        }

        double px = -dy / len;
        double py = dx / len;
        double hw = w / 2;

        double[] cornersX = {
            sx + px * hw, ex + px * hw, ex - px * hw, sx - px * hw
        };
        double[] cornersY = {
            sy + py * hw, ey + py * hw, ey - py * hw, sy - py * hw
        };

        if (rot != 0) {
            double radians = Math.toRadians(rot);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            for (int i = 0; i < 4; i++) {
                double newX = cornersX[i] * cos - cornersY[i] * sin;
                double newY = cornersX[i] * sin + cornersY[i] * cos;
                cornersX[i] = newX;
                cornersY[i] = newY;
            }
        }

        BoundingBox bbox = new BoundingBox();
        for (int i = 0; i < 4; i++) {
            bbox.extend(cornersX[i], cornersY[i]);
        }
        return bbox;
    }

    @Override
    public boolean isExposed(Map<Integer, Double> variables) {
        return exposure.evaluate(variables) >= 1;
    }
}
