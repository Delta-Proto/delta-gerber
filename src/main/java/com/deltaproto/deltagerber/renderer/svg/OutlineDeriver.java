package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
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
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Derives an approximate board-outline path from the filled geometry of "meaningful"
 * layers (copper, soldermask) when a set ships without a dedicated profile/outline file.
 * <p>
 * Copper pours, traces, and pads collectively trace the board: the <em>outer silhouette
 * of their union</em> is the board edge, minus the small clearance copper keeps from the
 * routed edge. Every object is converted to a filled {@link java.awt.geom.Shape}, all are
 * unioned into one {@link Area}, internal holes are filled (we want the solid board, not
 * the copper-free pockets inside it), and the result is optionally outset by an estimated
 * clearance to recover the true edge. The emitted path is in raw Gerber coordinates — the
 * same convention as {@code MultiLayerSVGRenderer.extractOutlinePath} — so it drops
 * straight into the realistic renderer's clip path.
 * <p>
 * This is an approximation: it follows the true board shape (rounded corners, tabs) but is
 * polygonal, slightly inset, and cannot recover genuine internal cut-outs (no copper marks
 * them). It is a fallback for sets with no outline file, not a replacement for one.
 */
final class OutlineDeriver {

    /** Curve flattening tolerance (mm) when converting shapes to polygons. */
    private static final double FLATNESS_MM = 0.05;

    /** Discard union pieces smaller than this (mm²) — isolated pads, specks. */
    private static final double MIN_PIECE_AREA_MM2 = 1.0;

    private OutlineDeriver() {}

    /**
     * @param docs    source layer documents (copper / soldermask) to union
     * @param closeMm morphologically close the union by this radius — bridges clearance
     *                gaps between adjacent pours/traces (up to ~2×) so the board comes out
     *                as one piece, without growing the outer edge (0 to disable)
     * @param outsetMm grow the silhouette outward by this much to compensate for the
     *                copper-to-edge clearance (0 to disable)
     * @return an SVG path string (raw coordinates) of the board silhouette, or "" if the
     *         documents carry no usable geometry
     */
    static String deriveOutlineSvgPath(List<GerberDocument> docs, double closeMm, double outsetMm) {
        Path2D.Double filled = new Path2D.Double(Path2D.WIND_NON_ZERO);
        boolean any = false;
        for (GerberDocument doc : docs) {
            if (doc == null) continue;
            for (GraphicsObject obj : doc.getObjects()) {
                Shape s = toShape(obj);
                if (s == null) continue;
                // Append each contour wound positively so the single nonzero Area below is
                // the union of everything, with all interior holes filled.
                appendAsPositiveContours(filled, s);
                any = true;
            }
        }
        if (!any) return "";

        Area area = new Area(filled);
        if (area.isEmpty()) return "";

        // Close: dilate then erode by the same radius. Bridges the small copper-free
        // seams between separately-poured zones so the board is one silhouette, while
        // leaving the outer edge essentially where it was.
        if (closeMm > 0) {
            dilate(area, closeMm);
            erode(area, closeMm);
        }
        // Outset: recover the clearance copper keeps from the routed edge.
        if (outsetMm > 0) {
            dilate(area, outsetMm);
        }

        return toOuterSilhouettePath(area);
    }

    private static void dilate(Area area, double r) {
        Shape band = new BasicStroke((float) (2 * r),
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(area);
        area.add(new Area(band));
    }

    private static void erode(Area area, double r) {
        Shape band = new BasicStroke((float) (2 * r),
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(area);
        area.subtract(new Area(band));
    }

    // --- object → filled shape -------------------------------------------------------

    private static Shape toShape(GraphicsObject obj) {
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
        // Rectangles, obrounds, polygons, macros: the aperture bounding box is a close
        // enough footprint for a silhouette that gets outset and unioned anyway.
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
    private static void appendArc(Path2D path, double cx, double cy, double ex, double ey,
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

    // --- union assembly & silhouette extraction --------------------------------------

    /** Flatten {@code s} into closed contours, each wound counter-clockwise, into {@code out}. */
    private static void appendAsPositiveContours(Path2D out, Shape s) {
        PathIterator it = s.getPathIterator(null, FLATNESS_MM);
        double[] c = new double[6];
        List<double[]> pts = new ArrayList<>();
        while (!it.isDone()) {
            int type = it.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    flushContour(out, pts);
                    pts.clear();
                    pts.add(new double[]{c[0], c[1]});
                    break;
                case PathIterator.SEG_LINETO:
                    pts.add(new double[]{c[0], c[1]});
                    break;
                case PathIterator.SEG_CLOSE:
                    flushContour(out, pts);
                    pts.clear();
                    break;
                default:
                    break;
            }
            it.next();
        }
        flushContour(out, pts);
    }

    private static void flushContour(Path2D out, List<double[]> pts) {
        if (pts.size() < 3) return;
        boolean ccw = signedArea(pts) >= 0;
        if (ccw) {
            out.moveTo(pts.get(0)[0], pts.get(0)[1]);
            for (int i = 1; i < pts.size(); i++) out.lineTo(pts.get(i)[0], pts.get(i)[1]);
        } else {
            out.moveTo(pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[1]);
            for (int i = pts.size() - 2; i >= 0; i--) out.lineTo(pts.get(i)[0], pts.get(i)[1]);
        }
        out.closePath();
    }

    /** Emit the outer (board) contours of the union, dropping interior holes and specks. */
    private static String toOuterSilhouettePath(Area area) {
        List<List<double[]>> contours = new ArrayList<>();
        List<Double> areas = new ArrayList<>();
        PathIterator it = area.getPathIterator(null, FLATNESS_MM);
        double[] c = new double[6];
        List<double[]> cur = new ArrayList<>();
        while (!it.isDone()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                if (!cur.isEmpty()) { contours.add(cur); areas.add(signedArea(cur)); }
                cur = new ArrayList<>();
                cur.add(new double[]{c[0], c[1]});
            } else if (type == PathIterator.SEG_LINETO) {
                cur.add(new double[]{c[0], c[1]});
            } else if (type == PathIterator.SEG_CLOSE) {
                if (!cur.isEmpty()) { contours.add(cur); areas.add(signedArea(cur)); }
                cur = new ArrayList<>();
            }
            it.next();
        }
        if (!cur.isEmpty()) { contours.add(cur); areas.add(signedArea(cur)); }
        if (contours.isEmpty()) return "";

        // Outer (board) contours and interior holes carry opposite winding. Keep the
        // sign of the largest piece (the board) and drop the holes so the silhouette is
        // solid; copper-free pockets inside the board are not real outline cut-outs.
        double maxAbs = 0;
        double outerSign = 1;
        for (double a : areas) {
            if (Math.abs(a) > maxAbs) { maxAbs = Math.abs(a); outerSign = Math.signum(a); }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contours.size(); i++) {
            double a = areas.get(i);
            if (Math.signum(a) != outerSign || Math.abs(a) < MIN_PIECE_AREA_MM2) continue;
            List<double[]> pts = contours.get(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "M %.6f %.6f", pts.get(0)[0], pts.get(0)[1]));
            for (int j = 1; j < pts.size(); j++) {
                sb.append(String.format(Locale.US, " L %.6f %.6f", pts.get(j)[0], pts.get(j)[1]));
            }
            sb.append(" Z");
        }
        return sb.toString();
    }

    private static double signedArea(List<double[]> pts) {
        double sum = 0;
        for (int i = 0, n = pts.size(); i < n; i++) {
            double[] p = pts.get(i);
            double[] q = pts.get((i + 1) % n);
            sum += p[0] * q[1] - q[0] * p[1];
        }
        return sum / 2.0;
    }
}
