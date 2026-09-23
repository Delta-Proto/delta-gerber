package com.deltaproto.deltagerber.dfm.geometry;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The board's edge as measurable geometry: the centreline of every draw and arc an outline layer
 * makes, and the contour of every region it fills, as zero-radius {@link Capsule}s. The router
 * follows the centreline of the profile, so that — not the ink of the aperture drawing it — is where
 * the board ends. Arcs are flattened at {@link CopperGeometry#FLATNESS_MM}.
 *
 * <p>Every closed path counts, cut-outs and slots included: copper next to an internal cut-out is as
 * close to an edge as copper next to the outside.
 */
public final class BoardProfile {

    private final List<Capsule> segments;
    private final BoundingBox bounds;

    private BoardProfile(List<Capsule> segments) {
        this.segments = Collections.unmodifiableList(segments);
        BoundingBox b = new BoundingBox();
        for (Capsule c : segments) {
            b.include(c.bounds());
        }
        this.bounds = b;
    }

    /** The profile drawn by these outline layers; empty when they draw nothing. */
    public static BoardProfile of(List<GerberDocument> outlines) {
        List<Capsule> out = new ArrayList<>();
        for (GerberDocument doc : outlines) {
            for (GraphicsObject obj : doc.getObjects()) {
                if (obj instanceof Draw d) {
                    out.add(new Capsule(d.getStartX(), d.getStartY(), d.getEndX(), d.getEndY(), 0));
                } else if (obj instanceof Arc a) {
                    List<double[]> pts = new ArrayList<>();
                    pts.add(new double[]{a.getStartX(), a.getStartY()});
                    CopperGeometry.appendArc(pts, a.getStartX(), a.getStartY(), a.getEndX(), a.getEndY(),
                            a.getCenterX(), a.getCenterY(), a.isClockwise());
                    chain(pts, false, out);
                } else if (obj instanceof Region r) {
                    for (Contour contour : r.getContours()) {
                        List<double[]> pts = new ArrayList<>();
                        double cx = contour.getStartX(), cy = contour.getStartY();
                        pts.add(new double[]{cx, cy});
                        for (Contour.ContourSegment seg : contour.getSegments()) {
                            if (seg.isArc()) {
                                CopperGeometry.appendArc(pts, cx, cy, seg.getX(), seg.getY(),
                                        seg.getCenterX(), seg.getCenterY(), seg.isClockwise());
                            } else {
                                pts.add(new double[]{seg.getX(), seg.getY()});
                            }
                            cx = seg.getX();
                            cy = seg.getY();
                        }
                        chain(pts, true, out);
                    }
                }
            }
        }
        return new BoardProfile(out);
    }

    private static void chain(List<double[]> pts, boolean close, List<Capsule> out) {
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            out.add(new Capsule(a[0], a[1], b[0], b[1], 0));
        }
        if (close && pts.size() > 2) {
            double[] a = pts.get(pts.size() - 1), b = pts.get(0);
            if (a[0] != b[0] || a[1] != b[1]) {
                out.add(new Capsule(a[0], a[1], b[0], b[1], 0));
            }
        }
    }

    /** The edge as zero-radius segments along the router's path. */
    public List<Capsule> segments() {
        return segments;
    }

    /** The extent of the profile; invalid when it is empty. */
    public BoundingBox bounds() {
        return bounds;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }
}
