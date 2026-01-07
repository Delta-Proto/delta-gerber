package nl.bytesoflife.gerber.renderer.svg;

/**
 * Configuration options for SVG output generation.
 */
public class SvgOptions {

    private boolean polygonize = false;
    private int circleSegments = 32;
    private String darkColor = "#000000";
    private String clearColor = "#ffffff";
    private boolean flipY = true;  // Whether Y-axis is flipped (Gerber Y-up to SVG Y-down)

    public SvgOptions() {
    }

    /**
     * If true, all shapes are converted to polygon path approximations.
     * This is useful for geometry processing (distance calculations, boolean operations).
     * If false (default), native SVG elements are used for precision.
     */
    public boolean isPolygonize() {
        return polygonize;
    }

    public SvgOptions setPolygonize(boolean polygonize) {
        this.polygonize = polygonize;
        return this;
    }

    /**
     * Number of segments used to approximate circles when polygonize is enabled.
     * Higher values = more precision but larger file size.
     * Default: 32 (max error ~0.5% of radius)
     */
    public int getCircleSegments() {
        return circleSegments;
    }

    public SvgOptions setCircleSegments(int circleSegments) {
        this.circleSegments = circleSegments;
        return this;
    }

    /**
     * The fill color for dark (positive) polarity.
     */
    public String getDarkColor() {
        return darkColor;
    }

    public SvgOptions setDarkColor(String darkColor) {
        this.darkColor = darkColor;
        return this;
    }

    /**
     * The fill color for clear (negative) polarity / holes.
     */
    public String getClearColor() {
        return clearColor;
    }

    public SvgOptions setClearColor(String clearColor) {
        this.clearColor = clearColor;
        return this;
    }

    /**
     * Whether the Y-axis is flipped (Gerber Y-up to SVG Y-down).
     * This affects arc sweep direction calculations.
     */
    public boolean isFlipY() {
        return flipY;
    }

    public SvgOptions setFlipY(boolean flipY) {
        this.flipY = flipY;
        return this;
    }

    /**
     * Default options: exact native SVG elements.
     */
    public static SvgOptions exact() {
        return new SvgOptions().setPolygonize(false);
    }

    /**
     * Polygonized options: all shapes as path approximations.
     */
    public static SvgOptions polygonized() {
        return new SvgOptions().setPolygonize(true);
    }

    /**
     * Polygonized with custom segment count.
     */
    public static SvgOptions polygonized(int segments) {
        return new SvgOptions().setPolygonize(true).setCircleSegments(segments);
    }
}
