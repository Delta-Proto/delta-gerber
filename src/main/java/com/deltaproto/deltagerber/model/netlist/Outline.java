package com.deltaproto.deltagerber.model.netlist;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Board / panel / fabrication outline data ({@code 389} / {@code 089} continuation).
 *
 * <p>Follows the conductor coordinate format (modal X/Y, space-or-asterisk delimited chains) but is
 * not tied to a net. The outline type is the name in columns 4-17 — one of {@code BOARD_EDGE},
 * {@code PANEL_EDGE}, {@code SCORE_LINE}, {@code OTHER_FAB}, or a vendor-specific name. Any leading
 * round drawing-size aperture is recorded as {@link #getDrawingWidthMm()} (display only — the
 * centre line is the edge). Coordinates are in millimetres.
 */
public class Outline {

    private final String outlineType;
    private final double drawingWidthMm; // 0 when no drawing size was given

    private final List<List<NetPoint>> chains = new ArrayList<>();

    public Outline(String outlineType, double drawingWidthMm) {
        this.outlineType = outlineType;
        this.drawingWidthMm = drawingWidthMm;
    }

    public void startChain(double xMm, double yMm) {
        List<NetPoint> chain = new ArrayList<>();
        chain.add(new NetPoint(xMm, yMm));
        chains.add(chain);
    }

    public void addPoint(double xMm, double yMm) {
        if (chains.isEmpty()) {
            startChain(xMm, yMm);
        } else {
            chains.get(chains.size() - 1).add(new NetPoint(xMm, yMm));
        }
    }

    public String getOutlineType() { return outlineType; }
    /** Round drawing-size aperture in mm (display only), or {@code 0} if none was given. */
    public double getDrawingWidthMm() { return drawingWidthMm; }

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
        return String.format("Outline[%s, %d chain(s), %d point(s)]", outlineType, chains.size(), points);
    }
}
