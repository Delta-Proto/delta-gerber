package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

/**
 * The area a Gerber object <em>inks</em>, as a Java2D {@link Shape} in millimetres, Y up.
 *
 * <p>This is for geometry work — silhouettes, boolean operations, point-in-shape tests — not for
 * drawing. Rendering goes through {@code GraphicsObject.toSvg}, which emits exact SVG elements;
 * here everything ends up a filled path, and a stroked draw or arc is materialised as the swept
 * band of its aperture.
 *
 * <p>Round apertures are exact. Everything else — rectangles, obrounds, polygons, macros — is
 * approximated by its bounding box, and a draw's width by its aperture's <em>smaller</em>
 * dimension. Both callers (the derived board silhouette, which outsets and unions what it gets,
 * and the drill-hole subtraction in the STEP export, where the apertures are circles anyway) are
 * insensitive to that; a caller that is not should not use this class.
 */
public final class GerberShapes {

    /** Curve flattening tolerance (mm) for arcs materialised as polylines. */
    private static final double FLATNESS_MM = 0.05;

    private GerberShapes() {}

    /** The filled footprint of one object, or {@code null} for one that inks nothing. */
    public static Shape of(GraphicsObject obj) {
        if (obj instanceof Region) return regionShape((Region) obj);
        if (obj instanceof Flash)  return flashShape((Flash) obj);
        if (obj instanceof Draw)   return drawShape((Draw) obj);
        if (obj instanceof Arc)    return arcShape((Arc) obj);
        return null;
    }

    private static Shape regionShape(Region region) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (Contour contour : region.getContours()) {
            double cx = contour.getStartX();
            double cy = contour.getStartY();
            path.moveTo(cx, cy);
            for (Contour.ContourSegment seg : contour.getSegments()) {
                if (seg.isArc()) {
                    appendArc(path, cx, cy, seg.getX(), seg.getY(),
                        seg.getCenterX(), seg.getCenterY(), seg.isClockwise());
                } else {
                    path.lineTo(seg.getX(), seg.getY());
                }
                cx = seg.getX();
                cy = seg.getY();
            }
            path.closePath();
        }
        return path;
    }

    private static Shape flashShape(Flash flash) {
        Aperture ap = flash.getAperture();
        if (ap instanceof CircleAperture) {
            double r = ((CircleAperture) ap).getDiameter() / 2.0;
            return new Ellipse2D.Double(flash.getX() - r, flash.getY() - r, 2 * r, 2 * r);
        }
        BoundingBox bb = flash.getBoundingBox();
        if (!bb.isValid()) return null;
        return new Rectangle2D.Double(bb.getMinX(), bb.getMinY(), bb.getWidth(), bb.getHeight());
    }

    private static Shape drawShape(Draw draw) {
        double w = apertureWidth(draw.getAperture());
        Line2D line = new Line2D.Double(draw.getStartX(), draw.getStartY(),
            draw.getEndX(), draw.getEndY());
        return new BasicStroke((float) Math.max(w, FLATNESS_MM),
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(line);
    }

    private static Shape arcShape(Arc arc) {
        double w = apertureWidth(arc.getAperture());
        Path2D.Double poly = new Path2D.Double();
        poly.moveTo(arc.getStartX(), arc.getStartY());
        appendArc(poly, arc.getStartX(), arc.getStartY(), arc.getEndX(), arc.getEndY(),
            arc.getCenterX(), arc.getCenterY(), arc.isClockwise());
        return new BasicStroke((float) Math.max(w, FLATNESS_MM),
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(poly);
    }

    private static double apertureWidth(Aperture ap) {
        if (ap == null) return FLATNESS_MM;
        BoundingBox bb = ap.getBoundingBox();
        if (!bb.isValid()) return FLATNESS_MM;
        return Math.min(bb.getWidth(), bb.getHeight());
    }

    /** Append an arc from (cx,cy) to (ex,ey) about (centerX,centerY) as line segments. */
    static void appendArc(Path2D path, double cx, double cy, double ex, double ey,
                          double centerX, double centerY, boolean clockwise) {
        double r = Math.hypot(cx - centerX, cy - centerY);
        double startAngle = Math.atan2(cy - centerY, cx - centerX);
        double endAngle = Math.atan2(ey - centerY, ex - centerX);
        double sweep;
        if (clockwise) {
            sweep = startAngle - endAngle;
            if (sweep <= 0) sweep += 2 * Math.PI;
            sweep = -sweep;
        } else {
            sweep = endAngle - startAngle;
            if (sweep <= 0) sweep += 2 * Math.PI;
        }
        int steps = Math.max(2, (int) Math.ceil(Math.abs(sweep) * r / FLATNESS_MM));
        for (int i = 1; i <= steps; i++) {
            double a = startAngle + sweep * i / steps;
            path.lineTo(centerX + r * Math.cos(a), centerY + r * Math.sin(a));
        }
    }
}
