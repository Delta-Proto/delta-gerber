package com.deltaproto.deltagerber.model.gerber.aperture;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

/**
 * Base class for all aperture types.
 */
public abstract class Aperture {

    private final int dCode;

    protected Aperture(int dCode) {
        this.dCode = dCode;
    }

    public int getDCode() {
        return dCode;
    }

    /**
     * Get the template code for this aperture type.
     * C=Circle, R=Rectangle, O=Obround, P=Polygon, or macro name.
     */
    public abstract String getTemplateCode();

    /**
     * Get the bounding box of this aperture centered at origin.
     */
    public abstract BoundingBox getBoundingBox();

    /**
     * Generate SVG definition for this aperture with default options (exact mode).
     * Returns an SVG element string that can be placed in a &lt;defs&gt; section.
     */
    public String toSvgDef(String id) {
        return toSvgDef(id, SvgOptions.exact());
    }

    /**
     * Generate SVG definition for this aperture with specified options.
     * @param id the SVG element id
     * @param options output options (exact or polygonized)
     * @return SVG element string for the defs section
     */
    public abstract String toSvgDef(String id, SvgOptions options);
}
