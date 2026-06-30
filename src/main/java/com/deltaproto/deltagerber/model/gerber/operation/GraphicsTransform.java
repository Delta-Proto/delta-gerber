package com.deltaproto.deltagerber.model.gerber.operation;

/**
 * An affine placement transform applied to a graphics object: translate, mirror, rotate and
 * uniform scale. The linear part is composed in the order {@code mirror · rotate · scale},
 * matching the order {@link Flash#toSvg} applies the aperture object-transforms, so expanding a
 * block aperture produces geometry identical to the equivalent single flash.
 *
 * <p>A point {@code (x, y)} maps to
 * {@code (m00·x + m01·y + tx, m10·x + m11·y + ty)}.
 *
 * <p>This is the shared geometric primitive behind block-aperture flashing today and the planned
 * file-level offset / rotate / mirror / merge operations.
 */
public final class GraphicsTransform {

    private final double tx, ty;
    private final double rotationDeg;
    private final double scale;
    private final boolean mirrorX, mirrorY;

    private final double m00, m01, m10, m11;

    public GraphicsTransform(double tx, double ty, double rotationDeg, double scale,
                             boolean mirrorX, boolean mirrorY) {
        this.tx = tx;
        this.ty = ty;
        this.rotationDeg = rotationDeg;
        this.scale = scale;
        this.mirrorX = mirrorX;
        this.mirrorY = mirrorY;

        double mx = mirrorX ? -1.0 : 1.0;
        double my = mirrorY ? -1.0 : 1.0;
        double rad = Math.toRadians(rotationDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        // M = Mirror · R(rot) · Scale
        this.m00 = mx * scale * cos;
        this.m01 = -mx * scale * sin;
        this.m10 = my * scale * sin;
        this.m11 = my * scale * cos;
    }

    /** A pure translation (used for block flashes with no object transformation). */
    public static GraphicsTransform translation(double tx, double ty) {
        return new GraphicsTransform(tx, ty, 0, 1.0, false, false);
    }

    public double applyX(double x, double y) {
        return m00 * x + m01 * y + tx;
    }

    public double applyY(double x, double y) {
        return m10 * x + m11 * y + ty;
    }

    /** Uniform distance scale factor — also used to scale stroke widths of drawn lines/arcs. */
    public double scale() {
        return scale;
    }

    public double rotationDeg() {
        return rotationDeg;
    }

    public boolean mirrorX() {
        return mirrorX;
    }

    public boolean mirrorY() {
        return mirrorY;
    }

    /**
     * True when the transform reverses orientation (an odd number of mirrors). Arc sweep direction
     * (clockwise/counter-clockwise) flips exactly when this is true; uniform scale and rotation
     * preserve orientation.
     */
    public boolean flipsOrientation() {
        return mirrorX ^ mirrorY;
    }

    /** True when this transform leaves geometry unchanged (identity). */
    public boolean isIdentity() {
        return tx == 0 && ty == 0 && rotationDeg == 0 && scale == 1.0 && !mirrorX && !mirrorY;
    }
}
