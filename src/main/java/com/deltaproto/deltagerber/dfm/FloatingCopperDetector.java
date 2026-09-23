package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry.Feature;
import com.deltaproto.deltagerber.dfm.geometry.CopperNets;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Floating copper: pieces of a copper layer that nothing connects to.
 *
 * <p>A piece is one net of {@link CopperNets} — copper that touches, as this layer draws it. It is
 * <em>anchored</em> when any of these holds, and floating otherwise:
 * <ul>
 *   <li>it contains a <b>pad</b>: a flash, or any object carrying a {@code .P} pin attribute (KiCad
 *       writes custom-shape pads as regions, and tags them so);
 *   <li>an <b>anchor point</b> lands on its surviving copper. The caller passes the centres of the
 *       plated holes — a via with no pad on this layer still joins the plane it passes through — and
 *       of the solder-mask openings on this side, because a pad the tool painted with strokes
 *       (EAGLE does this for any pad it rotates off the grid) has no flash, and is only a pad
 *       because the mask opens over it.
 * </ul>
 *
 * <p>Every rule can only make a piece anchored, so a reported piece has none of them — but with
 * no anchor points at all, a plane tied to its net only by padless vias is reported. That is what
 * {@link FloatingCopperResult#isHoleAware()} records.
 *
 * <p>Validated against HQDFM on the Arduino Uno (nine pieces of bottom copper lettering at
 * (22.96, 28.99), the same count and position HQDFM gives) and DEPR (a pour fragment at
 * (30.42, 69.41)).
 */
@Beta("validated against HQDFM on two boards; see issue #11")
public final class FloatingCopperDetector {

    private FloatingCopperDetector() {}

    /**
     * @param anchorPoints points {x, y} in mm, in the Gerber frame, that connect whatever copper
     *                     survives under them — plated hole centres and solder-mask openings; may
     *                     be null or empty
     * @param holeAware    whether the anchor points include the drilled holes
     */
    public static FloatingCopperResult detect(CopperNets nets, String fileName,
                                              Collection<double[]> anchorPoints, boolean holeAware) {
        CopperGeometry g = nets.geometry();
        int n = nets.netCount();
        boolean[] anchored = new boolean[n];
        int[] count = new int[n];
        BoundingBox[] box = new BoundingBox[n];
        String[] first = new String[n];
        for (Feature f : g.features()) {
            if (f.clear) {
                continue;
            }
            int net = nets.netOf(f.index);
            count[net]++;
            if (box[net] == null) {
                box[net] = new BoundingBox();
                first[net] = f.describe();
            }
            box[net].include(f.bounds);
            if (f.kind == CopperGeometry.Kind.PAD || f.source.getPinNumber() != null) {
                anchored[net] = true;
            }
        }
        if (anchorPoints != null) {
            for (double[] p : anchorPoints) {
                int at = g.copperAt(p[0], p[1]);
                if (at >= 0) {
                    anchored[nets.netOf(at)] = true;
                }
            }
        }

        boolean[] floating = new boolean[n];
        List<FloatingCopper> pieces = new ArrayList<>();
        for (int net = 0; net < n; net++) {
            if (anchored[net] || box[net] == null) {
                continue;
            }
            floating[net] = true;
            BoundingBox b = box[net];
            pieces.add(new FloatingCopper(b.getCenterX(), b.getCenterY(), b.getWidth(), b.getHeight(),
                    count[net], nets.nameOf(net), first[net]));
        }
        pieces.sort(Comparator.comparingDouble((FloatingCopper p) -> p.widthMm() * p.heightMm()).reversed());
        return new FloatingCopperResult(fileName, pieces, holeAware, nets, floating);
    }
}
