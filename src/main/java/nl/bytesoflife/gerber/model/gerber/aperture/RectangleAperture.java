package nl.bytesoflife.gerber.model.gerber.aperture;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.renderer.svg.SvgOptions;
import nl.bytesoflife.gerber.renderer.svg.SvgPathUtils;

/**
 * Rectangle aperture (template code 'R').
 */
public class RectangleAperture extends Aperture {

    private final double width;
    private final double height;
    private final double holeDiameter;

    public RectangleAperture(int dCode, double width, double height) {
        this(dCode, width, height, 0);
    }

    public RectangleAperture(int dCode, double width, double height, double holeDiameter) {
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
        return "R";
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
        String darkColor = options.getDarkColor();

        if (options.isPolygonize()) {
            // Polygonized mode: use path approximations
            String pathData;
            if (hasHole()) {
                pathData = SvgPathUtils.rectangleWithHolePath(0, 0, width, height, holeDiameter);
            } else {
                pathData = SvgPathUtils.rectanglePath(0, 0, width, height);
            }
            return String.format("<path id=\"%s\" d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
                id, pathData, darkColor);
        } else {
            // Exact mode: use native SVG elements
            if (hasHole()) {
                // Use path for true transparent hole (fill-rule evenodd)
                double hr = holeDiameter / 2;
                // Rectangle clockwise, hole circle counter-clockwise
                String pathData = String.format(java.util.Locale.US,
                    "M %.6f %.6f L %.6f %.6f L %.6f %.6f L %.6f %.6f Z " +  // Rect CW
                    "M %.6f 0 A %.6f %.6f 0 1 0 %.6f 0 A %.6f %.6f 0 1 0 %.6f 0 Z",  // Circle CCW
                    -hw, -hh, hw, -hh, hw, hh, -hw, hh,
                    hr, hr, hr, -hr, hr, hr, hr);
                return String.format("<path id=\"%s\" d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
                    id, pathData, darkColor);
            } else {
                return String.format(
                    "<rect id=\"%s\" x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"%s\"/>",
                    id, -hw, -hh, width, height, darkColor);
            }
        }
    }

    @Override
    public String toString() {
        return String.format("RectangleAperture[D%d, %.4fx%.4f]",
            getDCode(), width, height);
    }
}
