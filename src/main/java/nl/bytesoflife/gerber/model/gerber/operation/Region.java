package nl.bytesoflife.gerber.model.gerber.operation;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.model.gerber.Polarity;
import nl.bytesoflife.gerber.renderer.svg.SvgOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * Region (G36/G37) - filled area defined by contours.
 */
public class Region extends GraphicsObject {

    private final List<Contour> contours = new ArrayList<>();

    public Region() {
    }

    public void addContour(Contour contour) {
        contours.add(contour);
    }

    public List<Contour> getContours() {
        return contours;
    }

    @Override
    public BoundingBox getBoundingBox() {
        BoundingBox bounds = new BoundingBox();
        for (Contour contour : contours) {
            bounds.include(contour.getBoundingBox());
        }
        return bounds;
    }

    @Override
    public String toSvg(SvgOptions options) {
        if (contours.isEmpty()) {
            return "";
        }

        StringBuilder path = new StringBuilder();
        for (Contour contour : contours) {
            path.append(contour.toSvgPath(options));
            path.append(" ");
        }

        String color = polarity == Polarity.DARK ? options.getDarkColor() : options.getClearColor();
        return String.format(
            "<path d=\"%s\" fill=\"%s\" fill-rule=\"evenodd\"/>",
            path.toString().trim(), color);
    }

    @Override
    public GraphicsObject translate(double offsetX, double offsetY) {
        Region translated = new Region();
        translated.setPolarity(this.polarity);
        for (Contour contour : contours) {
            translated.addContour(contour.translate(offsetX, offsetY));
        }
        return translated;
    }

    @Override
    public String toString() {
        return String.format("Region[%d contours]", contours.size());
    }
}
