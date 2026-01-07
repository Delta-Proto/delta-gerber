package nl.bytesoflife.gerber.model.gerber.operation;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.model.gerber.aperture.Aperture;
import nl.bytesoflife.gerber.renderer.svg.SvgOptions;

import java.util.Locale;

/**
 * Flash operation (D03) - places aperture at a point.
 */
public class Flash extends GraphicsObject {

    private final double x;
    private final double y;
    private final Aperture aperture;

    // Aperture transforms (from LR, LS, LM commands)
    private double rotation = 0;       // Rotation in degrees
    private double scale = 1.0;        // Scale factor
    private boolean mirrorX = false;   // Mirror X axis
    private boolean mirrorY = false;   // Mirror Y axis

    public Flash(double x, double y, Aperture aperture) {
        this.x = x;
        this.y = y;
        this.aperture = aperture;
    }

    public Flash(double x, double y, Aperture aperture, double rotation, double scale, boolean mirrorX, boolean mirrorY) {
        this.x = x;
        this.y = y;
        this.aperture = aperture;
        this.rotation = rotation;
        this.scale = scale;
        this.mirrorX = mirrorX;
        this.mirrorY = mirrorY;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public Aperture getAperture() {
        return aperture;
    }

    @Override
    public BoundingBox getBoundingBox() {
        BoundingBox apBounds = aperture.getBoundingBox();
        return new BoundingBox(
            x + apBounds.getMinX(),
            y + apBounds.getMinY(),
            x + apBounds.getMaxX(),
            y + apBounds.getMaxY()
        );
    }

    @Override
    public String toSvg(SvgOptions options) {
        // Flash uses <use> elements referencing aperture definitions
        // The aperture defs already have fill colors set directly, no CSS class needed

        // Build transform string for rotation, scaling, mirroring
        StringBuilder transform = new StringBuilder();

        // Translate to position first
        transform.append(String.format(Locale.US, "translate(%.6f,%.6f)", x, y));

        // Apply mirroring (before rotation)
        if (mirrorX || mirrorY) {
            double scaleX = mirrorX ? -1 : 1;
            double scaleY = mirrorY ? -1 : 1;
            transform.append(String.format(Locale.US, " scale(%.1f,%.1f)", scaleX, scaleY));
        }

        // Apply rotation
        if (rotation != 0) {
            transform.append(String.format(Locale.US, " rotate(%.6f)", rotation));
        }

        // Apply scaling
        if (scale != 1.0) {
            transform.append(String.format(Locale.US, " scale(%.6f)", scale));
        }

        // If we have transforms other than position, use transform attribute
        if (rotation != 0 || scale != 1.0 || mirrorX || mirrorY) {
            return String.format(Locale.US,
                "<use href=\"#ap%d\" transform=\"%s\"/>",
                aperture.getDCode(), transform.toString());
        } else {
            // Simple case: just position
            return String.format(Locale.US,
                "<use href=\"#ap%d\" x=\"%.6f\" y=\"%.6f\"/>",
                aperture.getDCode(), x, y);
        }
    }

    @Override
    public GraphicsObject translate(double offsetX, double offsetY) {
        Flash translated = new Flash(x + offsetX, y + offsetY, aperture, rotation, scale, mirrorX, mirrorY);
        translated.setPolarity(this.polarity);
        return translated;
    }

    @Override
    public String toString() {
        return String.format("Flash[%.4f,%.4f D%d]", x, y, aperture.getDCode());
    }
}
