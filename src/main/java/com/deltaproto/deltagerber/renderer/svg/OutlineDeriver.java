package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Derives an approximate board-outline path from the filled geometry of "meaningful"
 * layers (copper, soldermask) when a set ships without a dedicated profile/outline file.
 * <p>
 * Copper pours, traces, and pads collectively trace the board: the <em>outer silhouette
 * of their union</em> is the board edge, minus the small clearance copper keeps from the
 * routed edge. The emitted path is in raw Gerber coordinates — the same convention as
 * {@code MultiLayerSVGRenderer.extractOutlinePath} — so it drops straight into the
 * realistic renderer's clip path.
 * <p>
 * The silhouette is computed on a <b>raster</b>, not with exact geometry:
 * <ol>
 *   <li>scan-convert every object into a binary grid;
 *   <li>morphologically close it (bridging the clearance seams between separately-poured
 *       zones) and outset it (recovering the copper-to-edge clearance), both via an exact
 *       squared Euclidean distance transform;
 *   <li>flood-fill the exterior, so interior copper-free pockets fill in — we want the
 *       solid board, not the pockets;
 *   <li>trace the boundary of what remains and simplify it.
 * </ol>
 * Every step is linear in the number of pixels and <em>independent of how many vertices the
 * copper has</em>. That is the whole point: the same job done with exact {@link java.awt.geom.Area}
 * booleans is roughly quadratic in edge count, because the morphological steps stroke a
 * many-piece area and union the massively self-intersecting band back in. It needed minutes
 * for a small board's copper and exhausted a multi-GB heap on a large one.
 * <p>
 * This is an approximation: it follows the true board shape (rounded corners, tabs) but is
 * polygonal, slightly inset, and cannot recover genuine internal cut-outs (no copper marks
 * them). It is a fallback for sets with no outline file, not a replacement for one.
 * <p>
 * Because step 3 leaves the mask free of holes, every traced loop is the outer boundary of a
 * disjoint piece and no loop nests inside another. All loops therefore carry the same winding
 * and the path means the same thing under either fill rule — though the renderer still declares
 * {@code nonzero}, which is what a union of same-wound pieces requires.
 */
final class OutlineDeriver {

    /** Curve flattening tolerance (mm); also the polyline simplification tolerance. */
    private static final double FLATNESS_MM = 0.05;

    /** Discard silhouette pieces smaller than this (mm²) — isolated pads, specks. */
    private static final double MIN_PIECE_AREA_MM2 = 1.0;

    /**
     * Raster resolution. This is tied to the <em>physical</em> close radius, not to a pixel
     * budget: the close must span several pixels or it silently becomes a no-op, and a panel's
     * routing channels then get bridged shut into one solid rectangle. At 0.1 mm/px a 0.6 mm
     * close is 6 px and a 0.2 mm outset is 2 px.
     */
    private static final double MM_PER_PIXEL = 0.10;

    /** Ceiling on raster area, so an enormous panel costs resolution rather than memory. */
    private static final long MAX_PIXELS = 12_000_000L;

    /** Blank margin around the board, so a dilation never clips against the raster border. */
    private static final double RASTER_MARGIN_MM = 1.0;

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
        BoundingBox board = new BoundingBox();
        for (GerberDocument doc : docs) {
            if (doc == null) continue;
            BoundingBox b = doc.getBoundingBox();
            if (b.isValid()) board.extend(b);
        }
        if (!board.isValid()) return "";

        double pad = closeMm + outsetMm + RASTER_MARGIN_MM;
        double minX = board.getMinX() - pad;
        double minY = board.getMinY() - pad;
        double widthMm = board.getWidth() + 2 * pad;
        double heightMm = board.getHeight() + 2 * pad;

        double pxPerMm = 1.0 / MM_PER_PIXEL;
        double pixels = (widthMm * pxPerMm) * (heightMm * pxPerMm);
        if (pixels > MAX_PIXELS) pxPerMm *= Math.sqrt(MAX_PIXELS / pixels);
        int w = Math.max(1, (int) Math.ceil(widthMm * pxPerMm));
        int h = Math.max(1, (int) Math.ceil(heightMm * pxPerMm));

        boolean[] mask = scanConvert(docs, w, h, pxPerMm, minX, minY + heightMm);
        if (mask == null) return "";

        if (closeMm > 0) {
            mask = dilate(mask, w, h, closeMm * pxPerMm);
            mask = erode(mask, w, h, closeMm * pxPerMm);
        }
        if (outsetMm > 0) {
            mask = dilate(mask, w, h, outsetMm * pxPerMm);
        }
        fillEnclosedBackground(mask, w, h);

        return traceSilhouette(mask, w, h, pxPerMm, minX, minY + heightMm);
    }

    // --- scan conversion -------------------------------------------------------------

    /**
     * Paint every object into a binary grid. Antialiasing is on and <em>any</em> coverage counts
     * as ink: a hairline trace narrower than a pixel must not vanish and disconnect the union.
     * That over-inks by up to one pixel, which is well inside the outset that follows.
     *
     * @return the mask, or {@code null} when nothing was drawn
     */
    private static boolean[] scanConvert(List<GerberDocument> docs, int w, int h,
                                         double pxPerMm, double minX, double maxY) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        // Gerber is y-up, the raster is y-down.
        g.setTransform(new AffineTransform(pxPerMm, 0, 0, -pxPerMm, -minX * pxPerMm, maxY * pxPerMm));
        boolean any = false;
        for (GerberDocument doc : docs) {
            if (doc == null) continue;
            for (GraphicsObject obj : doc.getObjects()) {
                Shape s = GerberShapes.of(obj);
                if (s == null) continue;
                g.fill(s);
                any = true;
            }
        }
        g.dispose();
        if (!any) return null;

        byte[] pixels = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        boolean[] mask = new boolean[w * h];
        boolean inked = false;
        for (int i = 0; i < mask.length; i++) {
            mask[i] = pixels[i] != 0;
            inked |= mask[i];
        }
        return inked ? mask : null;
    }

    // --- morphology ------------------------------------------------------------------

    private static boolean[] dilate(boolean[] mask, int w, int h, double radiusPx) {
        float[] d = squaredDistanceTo(mask, w, h, radiusPx);
        float limit = (float) (radiusPx * radiusPx);
        boolean[] out = new boolean[mask.length];
        for (int i = 0; i < out.length; i++) out[i] = d[i] <= limit;
        return out;
    }

    private static boolean[] erode(boolean[] mask, int w, int h, double radiusPx) {
        boolean[] background = new boolean[mask.length];
        for (int i = 0; i < background.length; i++) background[i] = !mask[i];
        float[] d = squaredDistanceTo(background, w, h, radiusPx);
        float limit = (float) (radiusPx * radiusPx);
        boolean[] out = new boolean[mask.length];
        for (int i = 0; i < out.length; i++) out[i] = d[i] > limit;
        return out;
    }

    /**
     * Exact squared Euclidean distance from every pixel to the nearest {@code seed}, by the
     * linear-time algorithm of Felzenszwalb &amp; Huttenlocher: a lower envelope of parabolas,
     * swept once down the columns and once across the rows.
     * <p>
     * Distances are clamped to just past {@code radiusPx}². Callers only ever compare against
     * that radius, and clamping can only raise a result that was already beyond it — so the
     * comparison is unaffected, while every stored value stays small enough to be exact in a
     * {@code float}.
     */
    private static float[] squaredDistanceTo(boolean[] seed, int w, int h, double radiusPx) {
        double cap = radiusPx * radiusPx + 1;
        float[] d = new float[w * h];
        for (int i = 0; i < d.length; i++) d[i] = seed[i] ? 0 : (float) cap;

        int n = Math.max(w, h);
        double[] f = new double[n];
        double[] out = new double[n];
        double[] boundary = new double[n + 1];
        int[] vertex = new int[n];

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) f[y] = d[y * w + x];
            lowerEnvelope(f, out, h, vertex, boundary);
            for (int y = 0; y < h; y++) d[y * w + x] = (float) Math.min(out[y], cap);
        }
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) f[x] = d[row + x];
            lowerEnvelope(f, out, w, vertex, boundary);
            for (int x = 0; x < w; x++) d[row + x] = (float) Math.min(out[x], cap);
        }
        return d;
    }

    /** One-dimensional squared distance transform of the sampled function {@code f}. */
    private static void lowerEnvelope(double[] f, double[] out, int n, int[] vertex, double[] boundary) {
        int k = 0;
        vertex[0] = 0;
        boundary[0] = Double.NEGATIVE_INFINITY;
        boundary[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            double s;
            while (true) {
                int v = vertex[k];
                s = ((f[q] + (double) q * q) - (f[v] + (double) v * v)) / (2.0 * q - 2.0 * v);
                if (s > boundary[k]) break;
                k--;
            }
            k++;
            vertex[k] = q;
            boundary[k] = s;
            boundary[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (boundary[k + 1] < q) k++;
            double dx = q - vertex[k];
            out[q] = dx * dx + f[vertex[k]];
        }
    }

    /**
     * Turn every background pixel not reachable from the raster border into foreground: the
     * copper-free pockets inside the board are not outline cut-outs, and the board is solid.
     */
    private static void fillEnclosedBackground(boolean[] mask, int w, int h) {
        boolean[] outside = new boolean[mask.length];
        int[] stack = new int[1024];
        int top = 0;
        for (int x = 0; x < w; x++) {
            top = visit(stack, top, outside, mask, x);
            stack = grow(stack, top);
            top = visit(stack, top, outside, mask, (h - 1) * w + x);
            stack = grow(stack, top);
        }
        for (int y = 0; y < h; y++) {
            top = visit(stack, top, outside, mask, y * w);
            stack = grow(stack, top);
            top = visit(stack, top, outside, mask, y * w + w - 1);
            stack = grow(stack, top);
        }
        while (top > 0) {
            int i = stack[--top];
            int x = i % w, y = i / w;
            stack = grow(stack, top + 4);
            if (x > 0)     top = visit(stack, top, outside, mask, i - 1);
            if (x < w - 1) top = visit(stack, top, outside, mask, i + 1);
            if (y > 0)     top = visit(stack, top, outside, mask, i - w);
            if (y < h - 1) top = visit(stack, top, outside, mask, i + w);
        }
        for (int i = 0; i < mask.length; i++) if (!outside[i]) mask[i] = true;
    }

    private static int visit(int[] stack, int top, boolean[] outside, boolean[] mask, int i) {
        if (outside[i] || mask[i]) return top;
        outside[i] = true;
        stack[top] = i;
        return top + 1;
    }

    private static int[] grow(int[] stack, int needed) {
        if (needed < stack.length) return stack;
        int[] bigger = new int[Math.max(needed + 1, stack.length * 2)];
        System.arraycopy(stack, 0, bigger, 0, stack.length);
        return bigger;
    }

    // --- boundary tracing ------------------------------------------------------------

    /**
     * Chain the "cracks" between foreground and background pixels into closed loops, drop the
     * specks, simplify, and emit one SVG subpath per surviving piece.
     * <p>
     * Each foreground pixel contributes a directed edge along every side it shares with
     * background, wound so that foreground lies to its left. Those edges meet end to end, so
     * following them from any start returns to it. Where two pieces meet at a single corner the
     * node has two outgoing edges and the choice between them is arbitrary — it decides whether
     * the pieces come out as two loops or one figure-eight, but not which pixels the path
     * encloses, which is all the caller fills.
     */
    private static String traceSilhouette(boolean[] mask, int w, int h,
                                          double pxPerMm, double minX, double maxY) {
        // Nodes are pixel corners, indexed (w+1) wide. Insertion order is the pixel scan
        // order, which keeps the emitted path stable across runs.
        Map<Integer, Integer> edges = new LinkedHashMap<>();
        Map<Integer, Integer> extraEdges = new LinkedHashMap<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!mask[y * w + x]) continue;
                boolean above = y > 0     && mask[(y - 1) * w + x];
                boolean below = y < h - 1 && mask[(y + 1) * w + x];
                boolean left  = x > 0     && mask[y * w + x - 1];
                boolean right = x < w - 1 && mask[y * w + x + 1];
                if (!above) addEdge(edges, extraEdges, node(x, y, w),         node(x + 1, y, w));
                if (!right) addEdge(edges, extraEdges, node(x + 1, y, w),     node(x + 1, y + 1, w));
                if (!below) addEdge(edges, extraEdges, node(x + 1, y + 1, w), node(x, y + 1, w));
                if (!left)  addEdge(edges, extraEdges, node(x, y + 1, w),     node(x, y, w));
            }
        }

        double minPieceAreaPx = MIN_PIECE_AREA_MM2 * pxPerMm * pxPerMm;
        double tolerancePx = FLATNESS_MM * pxPerMm;
        StringBuilder sb = new StringBuilder();
        while (!edges.isEmpty()) {
            int start = edges.keySet().iterator().next();
            List<double[]> loop = new ArrayList<>();
            int current = start;
            while (true) {
                Integer next = takeEdge(edges, extraEdges, current);
                if (next == null) break;
                loop.add(new double[]{current % (w + 1), current / (w + 1)});
                current = next;
                if (current == start) break;
            }
            if (loop.size() < 4 || Math.abs(signedArea(loop)) < minPieceAreaPx) continue;

            List<double[]> simplified = simplify(loop, tolerancePx);
            if (simplified.size() < 3) continue;
            if (sb.length() > 0) sb.append(' ');
            for (int i = 0; i < simplified.size(); i++) {
                double[] p = simplified.get(i);
                sb.append(String.format(Locale.US, "%s %.6f %.6f",
                    i == 0 ? "M" : " L", minX + p[0] / pxPerMm, maxY - p[1] / pxPerMm));
            }
            sb.append(" Z");
        }
        return sb.toString();
    }

    private static int node(int x, int y, int w) {
        return y * (w + 1) + x;
    }

    /** A node carries at most two outgoing boundary edges; the second lands in {@code extra}. */
    private static void addEdge(Map<Integer, Integer> edges, Map<Integer, Integer> extra,
                                int from, int to) {
        if (edges.putIfAbsent(from, to) != null) extra.put(from, to);
    }

    private static Integer takeEdge(Map<Integer, Integer> edges, Map<Integer, Integer> extra, int from) {
        Integer to = edges.remove(from);
        if (to != null) {
            Integer second = extra.remove(from);
            if (second != null) edges.put(from, second);
            return to;
        }
        return extra.remove(from);
    }

    /** Douglas-Peucker, iterative — a traced loop can carry tens of thousands of points. */
    private static List<double[]> simplify(List<double[]> pts, double tolerance) {
        int n = pts.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        Deque<int[]> pending = new ArrayDeque<>();
        pending.push(new int[]{0, n - 1});
        while (!pending.isEmpty()) {
            int[] span = pending.pop();
            int a = span[0], b = span[1];
            if (b <= a + 1) continue;
            double[] pa = pts.get(a), pb = pts.get(b);
            double dx = pb[0] - pa[0], dy = pb[1] - pa[1];
            double length = Math.hypot(dx, dy);
            int worst = -1;
            double worstDistance = tolerance;
            for (int i = a + 1; i < b; i++) {
                double[] p = pts.get(i);
                double distance = length < 1e-12
                    ? Math.hypot(p[0] - pa[0], p[1] - pa[1])
                    : Math.abs(dy * (p[0] - pa[0]) - dx * (p[1] - pa[1])) / length;
                if (distance > worstDistance) {
                    worstDistance = distance;
                    worst = i;
                }
            }
            if (worst < 0) continue;
            keep[worst] = true;
            pending.push(new int[]{a, worst});
            pending.push(new int[]{worst, b});
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(pts.get(i));
        return out;
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
