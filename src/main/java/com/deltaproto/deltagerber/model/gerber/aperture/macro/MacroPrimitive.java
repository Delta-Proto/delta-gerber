package com.deltaproto.deltagerber.model.gerber.aperture.macro;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import java.util.Map;

/**
 * Base interface for all macro primitive types.
 * Each primitive can be rendered to SVG and contributes to the bounding box.
 *
 * All dimensional values (coordinates, diameters, widths, heights) produced by
 * the primitive are multiplied by the unitFactor to normalize to mm.
 * Non-dimensional values (exposure, vertex count, rotation in degrees) are not scaled.
 */
public interface MacroPrimitive {

    /**
     * Render this primitive to SVG with default options and no unit conversion.
     */
    default String toSvg(Map<Integer, Double> variables) {
        return toSvg(variables, SvgOptions.exact(), 1.0);
    }

    /**
     * Render this primitive to SVG with specified options and no unit conversion.
     */
    default String toSvg(Map<Integer, Double> variables, SvgOptions options) {
        return toSvg(variables, options, 1.0);
    }

    /**
     * Render this primitive to SVG with specified options and unit conversion.
     * @param variables The variable values from aperture instantiation
     * @param options SVG output options (exact or polygonized)
     * @param unitFactor Factor to multiply dimensional values by (e.g. 25.4 for inch-to-mm)
     * @return SVG path commands or shape elements
     */
    String toSvg(Map<Integer, Double> variables, SvgOptions options, double unitFactor);

    /**
     * Get the bounding box with no unit conversion.
     */
    default BoundingBox getBoundingBox(Map<Integer, Double> variables) {
        return getBoundingBox(variables, 1.0);
    }

    /**
     * Get the bounding box of this primitive with unit conversion.
     * @param variables The variable values from aperture instantiation
     * @param unitFactor Factor to multiply dimensional values by (e.g. 25.4 for inch-to-mm)
     * @return The bounding box in mm
     */
    BoundingBox getBoundingBox(Map<Integer, Double> variables, double unitFactor);

    /**
     * Get the exposure of this primitive (1=on/dark, 0=off/clear).
     */
    boolean isExposed(Map<Integer, Double> variables);
}
