package com.deltaproto.deltagerber.model.netlist;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Net conductor-segment data ({@code 378} / {@code 078} continuation).
 *
 * <p>A conductor belongs to one net and one layer and is drawn with a fixed aperture: round when
 * only an X size is given (the X value is the diameter), rectangular when both X and Y are given.
 * Its geometry is a list of <em>chains</em> — each chain is a connected poly-line. The IPC-356
 * coordinate stream separates points with a space (continue the current chain) or an asterisk
 * (start a new chain); a missing X or Y repeats the previous (modal) value. All sizes and
 * coordinates are in millimetres.
 */
public class Conductor {

    private final String netName;
    private final String rawNetName;
    private final int layer;
    private final double apertureWidthMm;
    private final double apertureHeightMm; // 0 for a round aperture
    private final boolean round;

    private final List<List<NetPoint>> chains = new ArrayList<>();

    public Conductor(String netName, String rawNetName, int layer,
                     double apertureWidthMm, double apertureHeightMm, boolean round) {
        this.netName = netName;
        this.rawNetName = rawNetName;
        this.layer = layer;
        this.apertureWidthMm = apertureWidthMm;
        this.apertureHeightMm = apertureHeightMm;
        this.round = round;
    }

    /** Begin a new chain (asterisk delimiter, or the first point) at this location. */
    public void startChain(double xMm, double yMm) {
        List<NetPoint> chain = new ArrayList<>();
        chain.add(new NetPoint(xMm, yMm));
        chains.add(chain);
    }

    /** Extend the current chain (space delimiter) to this location. */
    public void addPoint(double xMm, double yMm) {
        if (chains.isEmpty()) {
            startChain(xMm, yMm);
        } else {
            chains.get(chains.size() - 1).add(new NetPoint(xMm, yMm));
        }
    }

    public String getNetName() { return netName; }
    public String getRawNetName() { return rawNetName; }
    public int getLayer() { return layer; }
    public double getApertureWidthMm() { return apertureWidthMm; }
    /** Aperture height in mm, or {@code 0} for a round aperture (see {@link #isRound()}). */
    public double getApertureHeightMm() { return apertureHeightMm; }
    public boolean isRound() { return round; }

    /** The conductor's chains; each inner list is one connected poly-line. */
    public List<List<NetPoint>> getChains() {
        return Collections.unmodifiableList(chains);
    }

    public BoundingBox getBoundingBox() {
        BoundingBox bb = new BoundingBox();
        for (List<NetPoint> chain : chains) {
            for (NetPoint p : chain) bb.includePoint(p.x(), p.y());
        }
        return bb;
    }

    @Override
    public String toString() {
        int points = chains.stream().mapToInt(List::size).sum();
        return String.format("Conductor[%s L%02d, %d chain(s), %d point(s)]",
            netName, layer, chains.size(), points);
    }
}
