package nl.bytesoflife.gerber.model.gerber.aperture.macro;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.renderer.svg.SvgOptions;
import java.util.Map;

/**
 * Base interface for all macro primitive types.
 * Each primitive can be rendered to SVG and contributes to the bounding box.
 */
public interface MacroPrimitive {

    /**
     * Render this primitive to SVG with default options (exact mode).
     * @param variables The variable values from aperture instantiation
     * @return SVG path commands or shape elements
     */
    default String toSvg(Map<Integer, Double> variables) {
        return toSvg(variables, SvgOptions.exact());
    }

    /**
     * Render this primitive to SVG with specified options.
     * @param variables The variable values from aperture instantiation
     * @param options SVG output options (exact or polygonized)
     * @return SVG path commands or shape elements
     */
    String toSvg(Map<Integer, Double> variables, SvgOptions options);

    /**
     * Get the bounding box of this primitive.
     * @param variables The variable values from aperture instantiation
     * @return The bounding box
     */
    BoundingBox getBoundingBox(Map<Integer, Double> variables);

    /**
     * Get the exposure of this primitive (1=on/dark, 0=off/clear).
     * @param variables The variable values from aperture instantiation
     * @return true if exposure is on (dark)
     */
    boolean isExposed(Map<Integer, Double> variables);
}
