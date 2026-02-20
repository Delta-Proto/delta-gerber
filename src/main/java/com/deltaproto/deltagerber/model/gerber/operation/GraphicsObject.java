package com.deltaproto.deltagerber.model.gerber.operation;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

/**
 * Base class for graphics objects produced by Gerber operations.
 */
public abstract class GraphicsObject {

    protected Polarity polarity = Polarity.DARK;

    public Polarity getPolarity() {
        return polarity;
    }

    public void setPolarity(Polarity polarity) {
        this.polarity = polarity;
    }

    /**
     * Get the bounding box of this graphics object.
     */
    public abstract BoundingBox getBoundingBox();

    /**
     * Generate SVG representation with default (exact) options.
     */
    public String toSvg() {
        return toSvg(SvgOptions.exact());
    }

    /**
     * Generate SVG representation with specified options.
     */
    public abstract String toSvg(SvgOptions options);

    /**
     * Create a translated copy of this object.
     */
    public abstract GraphicsObject translate(double offsetX, double offsetY);
}
