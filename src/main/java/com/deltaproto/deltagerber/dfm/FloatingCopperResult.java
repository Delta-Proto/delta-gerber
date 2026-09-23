package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.dfm.geometry.CopperNets;

import java.util.Collections;
import java.util.List;

/**
 * The floating copper of one layer: every piece that no pad, plated hole or solder-mask opening
 * reaches, largest first.
 *
 * <p>The answer depends on what the check was told. On its own a layer knows its pads; the holes
 * and the mask openings come from other files. {@link #isHoleAware()} says whether they were given —
 * without them a plane joined to its nets only by vias with no pad of their own reads as floating.
 */
@Beta("validated against HQDFM on two boards; see issue #11")
public final class FloatingCopperResult {

    private final String fileName;
    private final List<FloatingCopper> pieces;
    private final boolean holeAware;
    private final CopperNets nets;
    private final boolean[] floatingNet;

    FloatingCopperResult(String fileName, List<FloatingCopper> pieces, boolean holeAware,
                         CopperNets nets, boolean[] floatingNet) {
        this.fileName = fileName;
        this.pieces = Collections.unmodifiableList(pieces);
        this.holeAware = holeAware;
        this.nets = nets;
        this.floatingNet = floatingNet;
    }

    public String getFileName() {
        return fileName;
    }

    /** Every floating piece, largest first. */
    public List<FloatingCopper> getPieces() {
        return pieces;
    }

    public int getCount() {
        return pieces.size();
    }

    /** Whether drilled holes were given as anchors — see the class documentation. */
    public boolean isHoleAware() {
        return holeAware;
    }

    /** Whether a feature of the layer this was measured on belongs to a floating piece. */
    boolean isFloatingFeature(int feature) {
        int net = nets.netOf(feature);
        return net >= 0 && floatingNet[net];
    }

    /** Whether this result was measured on exactly this geometry — feature indices are its. */
    boolean isOf(CopperGeometry geometry) {
        return nets.geometry() == geometry;
    }

    @Override
    public String toString() {
        return "FloatingCopperResult[" + fileName + ": " + pieces.size() + " piece(s)]";
    }
}
