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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * Above this total object count, the exact {@link Area}-based union (roughly
     * quadratic in edge count; OOMs a multi-GB heap on dense boards) is replaced by
     * the raster silhouette in {@link #deriveOutlineSvgPathRaster}.
     */
    private static final int MAX_EXACT_OBJECTS = 5_000;

    /** Target raster size (longest side, px) for the raster silhouette. */
    private static final int RASTER_TARGET_PX = 1200;

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
        int totalObjects = 0;
        for (GerberDocument doc : docs) {
            if (doc != null) totalObjects += doc.getObjects().size();
        }
        if (totalObjects > MAX_EXACT_OBJECTS) {
            return deriveOutlineSvgPathRaster(docs, closeMm, outsetMm);
        }

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

    // --- raster silhouette (dense-board fallback) --------------------------------------

    /**
     * Same contract as {@link #deriveOutlineSvgPath}, computed on a bitmap instead of
     * with exact geometry: rasterize every object (Java2D scanline fill — O(objects)),
     * morphologically close/outset via an exact Euclidean distance transform
     * (O(pixels)), fill interior holes, trace each remaining component's boundary and
     * simplify it. Cost and memory are bounded by the raster size regardless of how
     * many pads the board has; the outline is accurate to ~1 raster pixel
     * (board dimension / {@value #RASTER_TARGET_PX}).
     */
    static String deriveOutlineSvgPathRaster(List<GerberDocument> docs, double closeMm, double outsetMm) {
        // Extent: union of doc bounds, padded so close/outset never touch the border.
        BoundingBox bb = new BoundingBox();
        for (GerberDocument doc : docs) {
            if (doc == null) continue;
            BoundingBox d = doc.getBoundingBox();
            if (d.isValid()) bb.extend(d);
        }
        if (!bb.isValid()) return "";
        double pad = closeMm + outsetMm + 1.0;
        double minX = bb.getMinX() - pad;
        double minY = bb.getMinY() - pad;
        double wMm = bb.getWidth() + 2 * pad;
        double hMm = bb.getHeight() + 2 * pad;

        double pxPerMm = RASTER_TARGET_PX / Math.max(wMm, hMm);
        // Keep the close radius resolvable: at least ~2 px, but never balloon the raster.
        if (closeMm > 0) pxPerMm = Math.max(pxPerMm, 2.0 / closeMm);
        int w = (int) Math.ceil(wMm * pxPerMm);
        int h = (int) Math.ceil(hMm * pxPerMm);
        long maxPixels = 4_000_000L;
        if ((long) w * h > maxPixels) {
            double s = Math.sqrt((double) maxPixels / ((double) w * h));
            pxPerMm *= s;
            w = Math.max(8, (int) Math.ceil(wMm * pxPerMm));
            h = Math.max(8, (int) Math.ceil(hMm * pxPerMm));
        }

        // 1. Rasterize all objects, filled, into a binary mask.
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Color.WHITE);
            g.scale(pxPerMm, pxPerMm);
            g.translate(-minX, -minY);
            for (GerberDocument doc : docs) {
                if (doc == null) continue;
                for (GraphicsObject obj : doc.getObjects()) {
                    Shape s = toShape(obj);
                    if (s != null) g.fill(s);
                }
            }
        } finally {
            g.dispose();
        }
        boolean[] mask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                mask[y * w + x] = (img.getRaster().getSample(x, y, 0) != 0);
            }
        }

        // 2. Morphology via distance transform: close (dilate+erode), then outset.
        double closePx = closeMm * pxPerMm;
        double outsetPx = outsetMm * pxPerMm;
        if (closePx > 0.5) {
            mask = dilatePx(mask, w, h, closePx);
            mask = erodePx(mask, w, h, closePx);
        }
        if (outsetPx > 0.5) {
            mask = dilatePx(mask, w, h, outsetPx);
        }

        // 3. Fill interior holes: anything not reachable from the border background.
        fillHoles(mask, w, h);

        // 4. Trace boundary per connected component, drop specks, simplify, emit mm path.
        double minPiecePx = MIN_PIECE_AREA_MM2 * pxPerMm * pxPerMm;
        double epsPx = 1.5; // Douglas-Peucker tolerance in raster px (~1 px accuracy anyway)
        int[] componentOf = labelComponents(mask, w, h);
        int componentCount = 0;
        for (int v : componentOf) componentCount = Math.max(componentCount, v);
        long[] componentSize = new long[componentCount + 1];
        for (int v : componentOf) if (v > 0) componentSize[v]++;

        StringBuilder sb = new StringBuilder();
        boolean[] traced = new boolean[componentCount + 1];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int comp = componentOf[y * w + x];
                if (comp == 0 || traced[comp]) continue;
                traced[comp] = true;
                if (componentSize[comp] < minPiecePx) continue;
                List<int[]> ring = traceBoundary(componentOf, comp, w, h, x, y);
                List<int[]> simplified = simplify(ring, epsPx);
                if (simplified.size() < 3) continue;
                if (sb.length() > 0) sb.append(' ');
                appendRingAsMm(sb, simplified, minX, minY, pxPerMm);
            }
        }
        return sb.toString();
    }

    /** Exact squared Euclidean distance to the nearest {@code true} cell (Felzenszwalb, separable). */
    private static double[] distanceSq(boolean[] set, int w, int h) {
        final double INF = 1e18;
        double[] d = new double[w * h];
        for (int i = 0; i < d.length; i++) d[i] = set[i] ? 0 : INF;
        // columns
        double[] f = new double[Math.max(w, h)];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) f[y] = d[y * w + x];
            double[] r = dt1d(f, h);
            for (int y = 0; y < h; y++) d[y * w + x] = r[y];
        }
        // rows
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) f[x] = d[y * w + x];
            double[] r = dt1d(f, w);
            for (int x = 0; x < w; x++) d[y * w + x] = r[x];
        }
        return d;
    }

    /** 1-D squared distance transform (lower envelope of parabolas). */
    private static double[] dt1d(double[] f, int n) {
        double[] d = new double[n];
        int[] v = new int[n];
        double[] z = new double[n + 1];
        int k = 0;
        v[0] = 0;
        z[0] = -1e18;
        z[1] = 1e18;
        for (int q = 1; q < n; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = 1e18;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) k++;
            double dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
        return d;
    }

    private static boolean[] dilatePx(boolean[] mask, int w, int h, double r) {
        double[] d = distanceSq(mask, w, h);
        boolean[] out = new boolean[w * h];
        double r2 = r * r;
        for (int i = 0; i < out.length; i++) out[i] = d[i] <= r2;
        return out;
    }

    private static boolean[] erodePx(boolean[] mask, int w, int h, double r) {
        boolean[] inverse = new boolean[w * h];
        for (int i = 0; i < mask.length; i++) inverse[i] = !mask[i];
        double[] d = distanceSq(inverse, w, h);
        boolean[] out = new boolean[w * h];
        double r2 = r * r;
        for (int i = 0; i < out.length; i++) out[i] = d[i] > r2;
        return out;
    }

    /** Flood-fill background from the border; unreached background cells are holes — fill them. */
    private static void fillHoles(boolean[] mask, int w, int h) {
        boolean[] reached = new boolean[w * h];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            for (int y : new int[]{0, h - 1}) {
                int i = y * w + x;
                if (!mask[i] && !reached[i]) { reached[i] = true; queue.add(i); }
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x : new int[]{0, w - 1}) {
                int i = y * w + x;
                if (!mask[i] && !reached[i]) { reached[i] = true; queue.add(i); }
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w, y = i / w;
            if (x > 0)     visitBg(mask, reached, queue, i - 1);
            if (x < w - 1) visitBg(mask, reached, queue, i + 1);
            if (y > 0)     visitBg(mask, reached, queue, i - w);
            if (y < h - 1) visitBg(mask, reached, queue, i + w);
        }
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i] && !reached[i]) mask[i] = true;
        }
    }

    private static void visitBg(boolean[] mask, boolean[] reached, ArrayDeque<Integer> queue, int i) {
        if (!mask[i] && !reached[i]) { reached[i] = true; queue.add(i); }
    }

    /** 4-connected component labels, 1-based; 0 = background. */
    private static int[] labelComponents(boolean[] mask, int w, int h) {
        int[] label = new int[w * h];
        int next = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || label[start] != 0) continue;
            next++;
            label[start] = next;
            queue.add(start);
            while (!queue.isEmpty()) {
                int i = queue.poll();
                int x = i % w, y = i / w;
                if (x > 0)     visitFg(mask, label, queue, i - 1, next);
                if (x < w - 1) visitFg(mask, label, queue, i + 1, next);
                if (y > 0)     visitFg(mask, label, queue, i - w, next);
                if (y < h - 1) visitFg(mask, label, queue, i + w, next);
            }
        }
        return label;
    }

    private static void visitFg(boolean[] mask, int[] label, ArrayDeque<Integer> queue, int i, int comp) {
        if (mask[i] && label[i] == 0) { label[i] = comp; queue.add(i); }
    }

    /**
     * Trace the outer boundary of one component as a closed loop of pixel-corner
     * coordinates, walking pixel edges with the foreground kept on the right
     * (square tracing). Starts at the top-left corner of the component's
     * topmost-leftmost pixel (supplied by scan order). Cannot get stuck: every
     * iteration either advances along a boundary edge or rotates in place, and the
     * walk terminates when the start (corner, direction) state recurs.
     */
    private static List<int[]> traceBoundary(int[] componentOf, int comp, int w, int h,
                                             int startX, int startY) {
        // Directions: 0=R(+x), 1=D(+y), 2=L(-x), 3=U(-y). For the edge leaving corner
        // (cx,cy) in direction d, the flanking pixels (by top-left-corner indexing):
        //   left-of-travel and right-of-travel, in image coordinates (y down).
        final int[][] leftPx  = {{0, -1}, {0, 0}, {-1, 0}, {-1, -1}};
        final int[][] rightPx = {{0, 0}, {-1, 0}, {-1, -1}, {0, -1}};
        final int[] dx = {1, 0, -1, 0};
        final int[] dy = {0, 1, 0, -1};

        int cx = startX, cy = startY, dir = 0; // top-left corner of start pixel, heading right
        List<int[]> ring = new ArrayList<>();
        ring.add(new int[]{cx, cy});
        long guard = 8L * (w + 1) * (h + 1);
        while (guard-- > 0) {
            boolean la = isComp(componentOf, comp, w, h, cx + leftPx[dir][0], cy + leftPx[dir][1]);
            boolean ra = isComp(componentOf, comp, w, h, cx + rightPx[dir][0], cy + rightPx[dir][1]);
            if (!la && ra) {
                cx += dx[dir];
                cy += dy[dir];
                if (cx == startX && cy == startY) break;
                ring.add(new int[]{cx, cy});
            } else if (!ra) {
                dir = (dir + 1) % 4;  // nothing on the right — turn right toward the body
            } else {
                dir = (dir + 3) % 4;  // wall on the left — turn left
            }
        }
        return ring;
    }

    private static boolean isComp(int[] componentOf, int comp, int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && componentOf[y * w + x] == comp;
    }

    /** Iterative Douglas-Peucker simplification on a closed pixel ring. */
    private static List<int[]> simplify(List<int[]> ring, double eps) {
        int n = ring.size();
        if (n < 5) return ring;
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n / 2] = true; // two anchors for a closed ring
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, n / 2});
        stack.push(new int[]{n / 2, n}); // wraps to 0
        while (!stack.isEmpty()) {
            int[] seg = stack.pop();
            int a = seg[0], b = seg[1];
            int[] pa = ring.get(a), pb = ring.get(b % n);
            double maxDist = -1;
            int maxIdx = -1;
            for (int i = a + 1; i < b; i++) {
                double dist = pointSegDist(ring.get(i), pa, pb);
                if (dist > maxDist) { maxDist = dist; maxIdx = i; }
            }
            if (maxDist > eps && maxIdx > 0) {
                keep[maxIdx] = true;
                stack.push(new int[]{a, maxIdx});
                stack.push(new int[]{maxIdx, b});
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) out.add(ring.get(i));
        }
        return out;
    }

    private static double pointSegDist(int[] p, int[] a, int[] b) {
        double vx = b[0] - a[0], vy = b[1] - a[1];
        double wx = p[0] - a[0], wy = p[1] - a[1];
        double len2 = vx * vx + vy * vy;
        double t = len2 > 0 ? Math.max(0, Math.min(1, (wx * vx + wy * vy) / len2)) : 0;
        double ex = wx - t * vx, ey = wy - t * vy;
        return Math.hypot(ex, ey);
    }

    private static void appendRingAsMm(StringBuilder sb, List<int[]> ring,
                                       double minX, double minY, double pxPerMm) {
        for (int i = 0; i < ring.size(); i++) {
            int[] p = ring.get(i);
            // ring vertices are pixel CORNERS: pixel (0,0) spans corners (0,0)..(1,1)
            double mx = minX + p[0] / pxPerMm;
            double my = minY + p[1] / pxPerMm;
            sb.append(String.format(Locale.US, i == 0 ? "M %.4f %.4f" : " L %.4f %.4f", mx, my));
        }
        sb.append(" Z");
    }
}
