package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.align.DrillGerberAlignment;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.DrillOperation;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.ObroundAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.PolygonAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.RectangleAperture;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects <em>vias in pad</em>: drilled holes that fall inside a surface-mount component pad.
 *
 * <p>The pad geometry is read from the <strong>solder-paste</strong> layer, which is the honest
 * marker of an SMD land — paste is stencilled onto exactly the pads a component reflows onto, and
 * a through-hole pad gets none. So the rule is simply: a hole whose centre lies inside a paste
 * opening (a flash of an aperture, or a painted region) is a via in pad. This naturally includes
 * the thermal vias under a QFN/BGA pad — the classic case — and excludes an ordinary plated
 * through-hole, which has no paste around it.
 *
 * <p>Finding the holes is only half the answer. The result groups them by the pad they sit in
 * ({@link ViaInPadGroup}), with that pad's area, so a via field under a QFN heat pad can be told
 * apart from a lone via in an 0402 land: the first carries paste to spare and needs no capping, the
 * second wicks the joint dry and forces a filled-and-capped via process (IPC-4761 Type VII).
 * {@link ViaInPadResult#hasViaInPad()} is the geometric fact;
 * {@link ViaInPadResult#requiresFilledAndCapped()} is the flag the calculator keys off.
 *
 * <p>The holes must be in the same coordinate frame as the paste. Both parsers normalise to mm, so
 * they already are unless the drill was exported on a different origin (some Altium flows) — use
 * {@link #detectAligned} to correct that first, or pass drills that a caller already aligned with
 * {@link DrillGerberAlignment}.
 */
public final class ViaInPadDetector {

    private ViaInPadDetector() {
    }

    /**
     * Detect vias in pad from the paste layers (split by side) and drill files.
     *
     * <p>The drills must already share the paste's coordinate origin; see {@link #detectAligned}
     * when that is not guaranteed. A {@code null} collection is treated as empty.
     *
     * @param topPaste    top-side solder-paste layers (usually one)
     * @param bottomPaste bottom-side solder-paste layers (usually one)
     * @param drills      NC drill documents whose hits are candidate vias
     * @return the vias in pad found — {@link ViaInPadResult#empty()} when there are no paste pads
     *         or no holes to test
     */
    public static ViaInPadResult detect(Collection<GerberDocument> topPaste,
                                        Collection<GerberDocument> bottomPaste,
                                        Collection<DrillDocument> drills) {
        List<Pad> pads = new ArrayList<>();
        collectPads(topPaste, true, false, pads);
        collectPads(bottomPaste, false, true, pads);
        if (pads.isEmpty() || drills == null || drills.isEmpty()) {
            return ViaInPadResult.empty();
        }

        PadIndex index = new PadIndex(pads);
        List<ViaInPad> hits = new ArrayList<>();
        for (DrillDocument drill : drills) {
            if (drill == null) {
                continue;
            }
            for (DrillOperation op : drill.getOperations()) {
                if (!(op instanceof DrillHit hit)) {
                    continue;   // slots are routed, not drilled vias
                }
                boolean top = false;
                boolean bottom = false;
                List<Pad> in = null;
                for (Pad pad : index.candidates(hit.getX(), hit.getY())) {
                    if (pad.contains(hit.getX(), hit.getY())) {
                        top |= pad.top;
                        bottom |= pad.bottom;
                        (in == null ? in = new ArrayList<>() : in).add(pad);
                    }
                }
                if (in != null) {
                    // One ViaInPad per hole, shared by every pad it lands in, so a hole in a
                    // top and a bottom pad is one hit in two groups rather than two hits.
                    ViaInPad via = new ViaInPad(hit.getX(), hit.getY(),
                            hit.getTool().getDiameter(), top, bottom);
                    hits.add(via);
                    for (Pad pad : in) {
                        pad.vias.add(via);
                    }
                }
            }
        }
        return new ViaInPadResult(hits, groups(pads));
    }

    /** The pads that caught at least one hole, each measured, in the order the paste declared them. */
    private static List<ViaInPadGroup> groups(List<Pad> pads) {
        List<ViaInPadGroup> groups = new ArrayList<>();
        for (Pad pad : pads) {
            if (!pad.vias.isEmpty()) {
                groups.add(new ViaInPadGroup(pad.area(), pad.centerX(), pad.centerY(), pad.shape(),
                        pad.top, pad.bottom, pad.vias));
            }
        }
        return groups;
    }

    /**
     * As {@link #detect}, but first aligns the drills into the Gerber frame using the copper pads —
     * for the case where they were exported on a different origin than the Gerbers. The drills are
     * resolved as a set, so a file carrying no hole centres of its own (a slot-only drill file) takes
     * the offset its siblings recovered. When the drills already sit on the board this is a no-op, so
     * it is safe to call unconditionally.
     *
     * @param copperLayers copper layers whose flashes anchor the drill/Gerber alignment; may be empty
     */
    public static ViaInPadResult detectAligned(Collection<GerberDocument> topPaste,
                                               Collection<GerberDocument> bottomPaste,
                                               Collection<GerberDocument> copperLayers,
                                               Collection<DrillDocument> drills) {
        if (drills == null || drills.isEmpty()) {
            return ViaInPadResult.empty();
        }
        BoundingBox bounds = new BoundingBox();
        List<double[]> padCenters = new ArrayList<>();
        if (copperLayers != null) {
            for (GerberDocument copper : copperLayers) {
                if (copper == null) {
                    continue;
                }
                BoundingBox b = copper.getBoundingBox();
                if (b != null && b.isValid()) {
                    bounds.include(b);
                }
                padCenters.addAll(DrillGerberAlignment.flashCenters(copper));
            }
        }
        return detect(topPaste, bottomPaste,
            DrillGerberAlignment.alignedAll(new ArrayList<>(drills), bounds, padCenters));
    }

    // ------------------------------------------------------------------------
    // Pad collection
    // ------------------------------------------------------------------------

    private static void collectPads(Collection<GerberDocument> pasteLayers,
                                    boolean top, boolean bottom, List<Pad> out) {
        if (pasteLayers == null) {
            return;
        }
        for (GerberDocument paste : pasteLayers) {
            if (paste == null) {
                continue;
            }
            for (GraphicsObject object : paste.getObjects()) {
                // Only additive artwork is a pad; a clear flash cuts a hole in the paste, and a
                // stroked draw/arc is not a land. Flashes and regions are what paste is made of.
                if (object.getPolarity() != Polarity.DARK) {
                    continue;
                }
                if (object instanceof Flash flash) {
                    out.add(new Pad(flash, top, bottom));
                } else if (object instanceof Region region && !region.getContours().isEmpty()) {
                    out.add(new Pad(region, top, bottom));
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Spatial index
    // ------------------------------------------------------------------------

    /**
     * A uniform grid over the pads so each hole is tested only against the pads near it, keeping the
     * cost linear in holes rather than holes × pads on dense boards (a BGA is thousands of each).
     */
    private static final class PadIndex {
        private final double cell;
        private final Map<Long, List<Pad>> grid = new HashMap<>();

        PadIndex(List<Pad> pads) {
            this.cell = cellSize(pads);
            for (Pad pad : pads) {
                int minCx = (int) Math.floor(pad.minX / cell);
                int maxCx = (int) Math.floor(pad.maxX / cell);
                int minCy = (int) Math.floor(pad.minY / cell);
                int maxCy = (int) Math.floor(pad.maxY / cell);
                for (int cx = minCx; cx <= maxCx; cx++) {
                    for (int cy = minCy; cy <= maxCy; cy++) {
                        grid.computeIfAbsent(key(cx, cy), k -> new ArrayList<>()).add(pad);
                    }
                }
            }
        }

        List<Pad> candidates(double x, double y) {
            List<Pad> pads = grid.get(key((int) Math.floor(x / cell), (int) Math.floor(y / cell)));
            return pads == null ? List.of() : pads;
        }

        /** Cell a little larger than a typical pad, so a pad spans only a handful of cells. */
        private static double cellSize(List<Pad> pads) {
            double sum = 0;
            for (Pad pad : pads) {
                sum += Math.max(pad.maxX - pad.minX, pad.maxY - pad.minY);
            }
            double avg = sum / pads.size();
            return Math.min(Math.max(avg, 0.5), 5.0);
        }

        private static long key(int cx, int cy) {
            return ((long) cx << 32) ^ (cy & 0xffffffffL);
        }
    }

    // ------------------------------------------------------------------------
    // Pad geometry
    // ------------------------------------------------------------------------

    /**
     * One paste opening, as a point-in-shape test plus a rotation-invariant bounding box for the
     * grid. A flash is its aperture placed (and possibly rotated/scaled/mirrored) at a point; a
     * region is a filled polygon.
     */
    private static final class Pad {
        private final Flash flash;      // exactly one of flash / region is set
        private final Region region;
        private final boolean top;
        private final boolean bottom;
        private final double minX;
        private final double minY;
        private final double maxX;
        private final double maxY;
        private final List<ViaInPad> vias = new ArrayList<>();

        Pad(Flash flash, boolean top, boolean bottom) {
            this.flash = flash;
            this.region = null;
            this.top = top;
            this.bottom = bottom;
            // Circumscribe the aperture with a circle so the box holds the pad at any rotation.
            BoundingBox ab = flash.getAperture().getBoundingBox();
            double halfDiag = Math.hypot(
                    Math.max(Math.abs(ab.getMinX()), Math.abs(ab.getMaxX())),
                    Math.max(Math.abs(ab.getMinY()), Math.abs(ab.getMaxY())))
                    * Math.max(flash.getScale(), 0);
            this.minX = flash.getX() - halfDiag;
            this.maxX = flash.getX() + halfDiag;
            this.minY = flash.getY() - halfDiag;
            this.maxY = flash.getY() + halfDiag;
        }

        Pad(Region region, boolean top, boolean bottom) {
            this.flash = null;
            this.region = region;
            this.top = top;
            this.bottom = bottom;
            BoundingBox b = region.getBoundingBox();
            this.minX = b.getMinX();
            this.maxX = b.getMaxX();
            this.minY = b.getMinY();
            this.maxY = b.getMaxY();
        }

        boolean contains(double x, double y) {
            if (x < minX || x > maxX || y < minY || y > maxY) {
                return false;
            }
            return flash != null ? flashContains(flash, x, y) : regionContains(region, x, y);
        }

        /**
         * The opening's own area in mm² — the aperture's shape as flashed, or the painted polygon.
         * Not the bounding box: on a rotated obround or a round pad the box overstates the paste by
         * a third or more, and the area is what decides whether the pad is thermal.
         */
        double area() {
            if (flash == null) {
                return regionArea(region);
            }
            double scale = flash.getScale();
            return apertureArea(flash.getAperture()) * (scale > 0 ? scale * scale : 1);
        }

        double centerX() {
            return flash != null ? flash.getX() : (minX + maxX) / 2;
        }

        double centerY() {
            return flash != null ? flash.getY() : (minY + maxY) / 2;
        }

        String shape() {
            return flash != null ? flash.getAperture().getTemplateCode() : "region";
        }
    }

    /** Area of an aperture in its own frame, in mm². */
    private static double apertureArea(Aperture aperture) {
        if (aperture instanceof CircleAperture circle) {
            double r = circle.getRadius();
            return Math.PI * r * r;
        }
        if (aperture instanceof RectangleAperture rect) {
            return rect.getWidth() * rect.getHeight();
        }
        if (aperture instanceof ObroundAperture ob) {
            // A stadium: the full rectangle less what the four rounded corners cut away.
            double r = Math.min(ob.getWidth(), ob.getHeight()) / 2;
            return ob.getWidth() * ob.getHeight() - (4 - Math.PI) * r * r;
        }
        if (aperture instanceof PolygonAperture poly) {
            int n = poly.getNumVertices();
            if (n < 3) {
                return 0;
            }
            double r = poly.getOuterDiameter() / 2;
            return 0.5 * n * r * r * Math.sin(2 * Math.PI / n);
        }
        // Macro / block apertures: fall back to the bounds, as the containment test does. This
        // overstates a concave macro pad, which can only ever call it thermal too readily.
        BoundingBox b = aperture.getBoundingBox();
        return Math.max(b.getMaxX() - b.getMinX(), 0) * Math.max(b.getMaxY() - b.getMinY(), 0);
    }

    /**
     * Area of a painted region in mm², by the shoelace formula over its flattened contours. The
     * signed areas are summed before taking the magnitude, so a contour wound against the outline —
     * a cut-out — subtracts, matching the even-odd rule {@link #regionContains} tests with.
     */
    private static double regionArea(Region region) {
        double sum = 0;
        for (Contour contour : region.getContours()) {
            double[][] poly = flatten(contour);
            sum += signedArea(poly[0], poly[1]);
        }
        return Math.abs(sum);
    }

    private static double signedArea(double[] xs, double[] ys) {
        double sum = 0;
        int n = xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            sum += (xs[j] + xs[i]) * (ys[j] - ys[i]);
        }
        return sum / 2;
    }

    /** Whether {@code (x, y)} lies inside a flashed aperture, honouring the flash's transform. */
    private static boolean flashContains(Flash flash, double x, double y) {
        // Map the world point into the aperture's local frame by inverting the flash transform,
        // whose SVG order is translate → mirror → rotate → scale.
        double lx = x - flash.getX();
        double ly = y - flash.getY();
        if (flash.isMirrorX()) {
            lx = -lx;
        }
        if (flash.isMirrorY()) {
            ly = -ly;
        }
        if (flash.getRotation() != 0) {
            double rad = -Math.toRadians(flash.getRotation());
            double c = Math.cos(rad);
            double s = Math.sin(rad);
            double rx = lx * c - ly * s;
            ly = lx * s + ly * c;
            lx = rx;
        }
        double scale = flash.getScale();
        if (scale != 0 && scale != 1.0) {
            lx /= scale;
            ly /= scale;
        }
        return apertureContains(flash.getAperture(), lx, ly);
    }

    /** Whether {@code (lx, ly)}, in the aperture's own centred frame, is inside the aperture. */
    private static boolean apertureContains(Aperture aperture, double lx, double ly) {
        if (aperture instanceof CircleAperture circle) {
            double r = circle.getRadius();
            return lx * lx + ly * ly <= r * r;
        }
        if (aperture instanceof RectangleAperture rect) {
            return Math.abs(lx) <= rect.getWidth() / 2 && Math.abs(ly) <= rect.getHeight() / 2;
        }
        if (aperture instanceof ObroundAperture ob) {
            double hw = ob.getWidth() / 2;
            double hh = ob.getHeight() / 2;
            double r = Math.min(hw, hh);
            double dx = Math.max(Math.abs(lx) - (hw - r), 0);
            double dy = Math.max(Math.abs(ly) - (hh - r), 0);
            return dx * dx + dy * dy <= r * r;
        }
        if (aperture instanceof PolygonAperture poly) {
            return inRegularPolygon(poly, lx, ly);
        }
        // Macro / block apertures: no exact test here, so fall back to the aperture's own bounds.
        // This can over-count a concave macro pad, but never misses a hole that is truly inside.
        BoundingBox b = aperture.getBoundingBox();
        return lx >= b.getMinX() && lx <= b.getMaxX() && ly >= b.getMinY() && ly <= b.getMaxY();
    }

    private static boolean inRegularPolygon(PolygonAperture poly, double px, double py) {
        int n = poly.getNumVertices();
        if (n < 3) {
            return false;
        }
        double r = poly.getOuterDiameter() / 2;
        double rot = Math.toRadians(poly.getRotation());
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            double a = rot + 2 * Math.PI * i / n;
            xs[i] = r * Math.cos(a);
            ys[i] = r * Math.sin(a);
        }
        return pointInPolygon(xs, ys, px, py);
    }

    /** Whether {@code (px, py)} is inside a region, under the even-odd rule its contours imply. */
    private static boolean regionContains(Region region, double px, double py) {
        boolean inside = false;
        for (Contour contour : region.getContours()) {
            double[][] poly = flatten(contour);
            if (crossingsOdd(poly[0], poly[1], px, py)) {
                inside = !inside;   // even-odd across contours gives holes for free
            }
        }
        return inside;
    }

    /** Flatten a contour into a closed polygon, sampling arcs into short chords. */
    private static double[][] flatten(Contour contour) {
        List<double[]> pts = new ArrayList<>();
        double cx = contour.getStartX();
        double cy = contour.getStartY();
        pts.add(new double[]{cx, cy});
        for (Contour.ContourSegment seg : contour.getSegments()) {
            if (seg.isArc()) {
                double sr = Math.hypot(cx - seg.getCenterX(), cy - seg.getCenterY());
                double startAngle = Math.atan2(cy - seg.getCenterY(), cx - seg.getCenterX());
                double endAngle = Math.atan2(seg.getY() - seg.getCenterY(), seg.getX() - seg.getCenterX());
                double sweep;
                if (seg.isClockwise()) {
                    sweep = startAngle - endAngle;
                    if (sweep <= 0) sweep += 2 * Math.PI;
                    sweep = -sweep;
                } else {
                    sweep = endAngle - startAngle;
                    if (sweep <= 0) sweep += 2 * Math.PI;
                }
                int steps = Math.max(6, (int) Math.ceil(Math.abs(sweep) / (Math.PI / 12)));
                for (int i = 1; i <= steps; i++) {
                    double a = startAngle + sweep * i / steps;
                    pts.add(new double[]{seg.getCenterX() + sr * Math.cos(a),
                            seg.getCenterY() + sr * Math.sin(a)});
                }
            } else {
                pts.add(new double[]{seg.getX(), seg.getY()});
            }
            cx = seg.getX();
            cy = seg.getY();
        }
        double[] xs = new double[pts.size()];
        double[] ys = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i)[0];
            ys[i] = pts.get(i)[1];
        }
        return new double[][]{xs, ys};
    }

    private static boolean crossingsOdd(double[] xs, double[] ys, double px, double py) {
        boolean odd = false;
        int n = xs.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            if ((ys[i] > py) != (ys[j] > py)) {
                double xInt = xs[i] + (py - ys[i]) / (ys[j] - ys[i]) * (xs[j] - xs[i]);
                if (px < xInt) {
                    odd = !odd;
                }
            }
        }
        return odd;
    }

    private static boolean pointInPolygon(double[] xs, double[] ys, double px, double py) {
        return crossingsOdd(xs, ys, px, py);
    }
}
