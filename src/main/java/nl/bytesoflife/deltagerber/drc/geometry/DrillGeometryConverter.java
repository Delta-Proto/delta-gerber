package nl.bytesoflife.deltagerber.drc.geometry;

import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.drill.DrillHit;
import nl.bytesoflife.deltagerber.model.drill.DrillOperation;
import nl.bytesoflife.deltagerber.model.drill.DrillSlot;
import org.locationtech.jts.geom.*;

import java.util.ArrayList;
import java.util.List;

public class DrillGeometryConverter {

    private static final int CIRCLE_SEGMENTS = 8;
    private final GeometryFactory factory = new GeometryFactory();

    public List<Geometry> convert(DrillDocument doc) {
        double unitFactor = doc.getUnit().toMm(1.0);
        List<Geometry> geometries = new ArrayList<>();

        for (DrillOperation op : doc.getOperations()) {
            Geometry geom = convertOperation(op, unitFactor);
            if (geom != null && !geom.isEmpty()) {
                geometries.add(geom);
            }
        }

        return geometries;
    }

    private Geometry convertOperation(DrillOperation op, double uf) {
        double radius = op.getTool().getDiameter() * uf / 2;

        if (op instanceof DrillHit hit) {
            Point point = factory.createPoint(new Coordinate(hit.getX() * uf, hit.getY() * uf));
            return point.buffer(radius, CIRCLE_SEGMENTS);
        } else if (op instanceof DrillSlot slot) {
            LineString line = factory.createLineString(new Coordinate[]{
                    new Coordinate(slot.getStartX() * uf, slot.getStartY() * uf),
                    new Coordinate(slot.getEndX() * uf, slot.getEndY() * uf)
            });
            return line.buffer(radius, CIRCLE_SEGMENTS);
        }

        return null;
    }
}
