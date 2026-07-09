package com.deltaproto.deltagerber.model.gerber.operation;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

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
    public BoundingBox getPathBoundingBox() {
        return new BoundingBox(x, y, x, y);
    }

    @Override
    public String toSvg(SvgOptions options) {
        // Flash uses <use> elements referencing aperture definitions.
        // Fill color is set on the <use> element based on polarity, so it
        // inherits into the aperture def shapes (which have no explicit fill).
        String color = polarity == Polarity.DARK ? options.getDarkColor() : options.getClearColor();

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

        // Get aperture ID prefix from options (allows multi-layer SVGs with unique IDs)
        String prefix = options.getApertureIdPrefix();

        // If we have transforms other than position, use transform attribute
        if (rotation != 0 || scale != 1.0 || mirrorX || mirrorY) {
            return String.format(Locale.US,
                "<use href=\"#%s%d\" fill=\"%s\" transform=\"%s\"/>",
                prefix, aperture.getDCode(), color, transform.toString());
        } else {
            // Simple case: just position
            return String.format(Locale.US,
                "<use href=\"#%s%d\" x=\"%.6f\" y=\"%.6f\" fill=\"%s\"/>",
                prefix, aperture.getDCode(), x, y, color);
        }
    }

    @Override
    public GraphicsObject translate(double offsetX, double offsetY) {
        Flash translated = new Flash(x + offsetX, y + offsetY, aperture, rotation, scale, mirrorX, mirrorY);
        copyMetaTo(translated);
        return translated;
    }

    @Override
    public GraphicsObject transform(GraphicsTransform t) {
        double nx = t.applyX(x, y);
        double ny = t.applyY(x, y);
        // Compose the placement transform with this flash's own aperture transform. For a flash
        // inside a block with no LM/LR/LS of its own (the common case) this is exact; a nested
        // already-transformed flash composes naively.
        Flash out = new Flash(nx, ny, aperture,
            rotation + t.rotationDeg(),
            scale * t.scale(),
            mirrorX ^ t.mirrorX(),
            mirrorY ^ t.mirrorY());
        copyMetaTo(out);
        return out;
    }

    @Override
    public String toString() {
        return String.format("Flash[%.4f,%.4f D%d]", x, y, aperture.getDCode());
    }
}
