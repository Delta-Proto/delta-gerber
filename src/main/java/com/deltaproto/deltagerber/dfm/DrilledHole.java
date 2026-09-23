package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * One drilled hole, in the Gerber frame: where, how wide, and whether the drill file says it is
 * plated ({@code null} when it does not say). Slots are not holes here — a routed slot has no single
 * centre to measure from.
 *
 * @param xMm        centre x in millimetres
 * @param yMm        centre y
 * @param diameterMm the drilled diameter
 * @param plated     the drill file's statement, or null
 */
public record DrilledHole(double xMm, double yMm, double diameterMm, Boolean plated) {

    /** Every drilled hole of these drill programs, which must already be in the Gerber frame. */
    public static List<DrilledHole> of(Collection<DrillDocument> drills) {
        List<DrilledHole> out = new ArrayList<>();
        if (drills == null) {
            return out;
        }
        for (DrillDocument drill : drills) {
            for (var op : drill.getOperations()) {
                if (op instanceof DrillHit hit && hit.getTool().getDiameter() > 0) {
                    out.add(new DrilledHole(hit.getX(), hit.getY(), hit.getTool().getDiameter(),
                            hit.getTool().getPlated()));
                }
            }
        }
        return out;
    }
}
