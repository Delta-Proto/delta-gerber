package nl.bytesoflife.gerber.model.gerber.aperture.macro;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.renderer.svg.SvgOptions;
import java.util.Locale;
import java.util.Map;

/**
 * Center line primitive (code 21).
 * A rectangle defined by its center point and dimensions.
 * Parameters: exposure, width, height, center X, center Y, rotation
 */
public class CenterLinePrimitive implements MacroPrimitive {

    private final MacroExpression exposure;
    private final MacroExpression width;
    private final MacroExpression height;
    private final MacroExpression centerX;
    private final MacroExpression centerY;
    private final MacroExpression rotation;

    public CenterLinePrimitive(String[] params) {
        this.exposure = new MacroExpression(params[0]);
        this.width = new MacroExpression(params[1]);
        this.height = new MacroExpression(params[2]);
        this.centerX = new MacroExpression(params[3]);
        this.centerY = new MacroExpression(params[4]);
        this.rotation = params.length > 5 ? new MacroExpression(params[5]) : new MacroExpression("0");
    }

    @Override
    public String toSvg(Map<Integer, Double> variables, SvgOptions options) {
        double exp = exposure.evaluate(variables);
        double w = width.evaluate(variables);
        double h = height.evaluate(variables);
        double cx = centerX.evaluate(variables);
        double cy = centerY.evaluate(variables);
        double rot = rotation.evaluate(variables);

        double hw = w / 2;
        double hh = h / 2;

        // Four corners relative to center
        double[] cornersX = {cx - hw, cx + hw, cx + hw, cx - hw};
        double[] cornersY = {cy - hh, cy - hh, cy + hh, cy + hh};

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
            return String.format("<path d=\"%s\" fill=\"%s\"/>", pathData, fill);
        } else {
            // Exact mode: use native SVG polygon element
            StringBuilder points = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (i > 0) points.append(" ");
                points.append(String.format(Locale.US, "%.6f,%.6f", cornersX[i], cornersY[i]));
            }
            return String.format("<polygon points=\"%s\" fill=\"%s\"/>", points, fill);
        }
    }

    @Override
    public BoundingBox getBoundingBox(Map<Integer, Double> variables) {
        double w = width.evaluate(variables);
        double h = height.evaluate(variables);
        double cx = centerX.evaluate(variables);
        double cy = centerY.evaluate(variables);
        double rot = rotation.evaluate(variables);

        double hw = w / 2;
        double hh = h / 2;

        double[] cornersX = {cx - hw, cx + hw, cx + hw, cx - hw};
        double[] cornersY = {cy - hh, cy - hh, cy + hh, cy + hh};

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
