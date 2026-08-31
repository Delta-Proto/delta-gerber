package com.deltaproto.deltagerber.renderer.step;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.DrillOperation;
import com.deltaproto.deltagerber.model.drill.DrillSlot;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.renderer.svg.GerberShapes;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * Everything a layer set drills, as one area in millimetres — what the STEP export subtracts
 * from the board.
 *
 * <p>The realistic view punches the same geometry out of the rendered board with its
 * {@code mech-mask}, and this mirrors what goes into that mask: an Excellon file's hits and
 * slots, and the objects of a Gerber X2 drill layer, whichever form the set ships. A drill hit
 * is a circle of the tool's diameter; a slot is the same circle swept along the routed path.
 *
 * <p>The area is <em>not</em> clipped to the board. A hole that straddles the routed edge —
 * a mouse-bite perforation on a break-off tab — must take its bite out of the outline, and
 * subtracting the unclipped area is what does that.
 */
final class DrillHoles {

    private DrillHoles() {}

    /**
     * The union of every hole in the set, or {@code null} when it drills nothing.
     *
     * <p>Layers are read by {@linkplain MultiLayerSVGRenderer.Layer#getLayerType() type}, so a
     * caller that has classified a set — or a user who corrected a layer's type in the viewer —
     * decides what counts as a drill.
     */
    static Area of(List<MultiLayerSVGRenderer.Layer> layers) {
        if (layers == null || layers.isEmpty()) return null;
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        boolean any = false;
        for (MultiLayerSVGRenderer.Layer layer : layers) {
            if (layer == null || !layer.getLayerType().isDrill()) continue;
            if (layer.isDrill() && layer.getDrillDoc() != null) {
                any |= addExcellon(path, layer.getDrillDoc());
            } else if (layer.isGerber() && layer.getGerberDoc() != null) {
                any |= addGerberDrill(path, layer.getGerberDoc());
            }
        }
        return any ? new Area(path) : null;
    }

    private static boolean addExcellon(Path2D path, DrillDocument doc) {
        boolean any = false;
        for (DrillOperation op : doc.getOperations()) {
            double diameter = op.getTool() != null ? op.getTool().getDiameter() : 0;
            if (diameter <= 0) continue;
            if (op instanceof DrillHit) {
                DrillHit hit = (DrillHit) op;
                double r = diameter / 2.0;
                path.append(new Ellipse2D.Double(hit.getX() - r, hit.getY() - r, 2 * r, 2 * r), false);
                any = true;
            } else if (op instanceof DrillSlot) {
                DrillSlot slot = (DrillSlot) op;
                Shape swept = new BasicStroke((float) diameter, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND).createStrokedShape(new Line2D.Double(
                        slot.getStartX(), slot.getStartY(), slot.getEndX(), slot.getEndY()));
                path.append(swept, false);
                any = true;
            }
        }
        return any;
    }

    /** A Gerber X2 drill layer (KiCad's {@code *-PTH-drl.gbr} and friends): holes are flashes. */
    private static boolean addGerberDrill(Path2D path, com.deltaproto.deltagerber.model.gerber.GerberDocument doc) {
        boolean any = false;
        for (GraphicsObject obj : doc.getObjects()) {
            Shape s = GerberShapes.of(obj);
            if (s == null) continue;
            path.append(s, false);
            any = true;
        }
        return any;
    }
}
