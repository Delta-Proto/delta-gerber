package nl.bytesoflife.deltagerber.drc.geometry;

import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.deltagerber.model.gerber.aperture.RectangleAperture;
import nl.bytesoflife.deltagerber.model.gerber.operation.*;
import org.locationtech.jts.geom.*;

import java.util.ArrayList;
import java.util.List;

public class GerberGeometryConverter {

    private static final int ARC_SEGMENTS = 32;
    private final GeometryFactory factory = new GeometryFactory();

    public List<Geometry> convert(GerberDocument doc, double unitFactor) {
        List<Geometry> geometries = new ArrayList<>();

        for (GraphicsObject obj : doc.getObjects()) {
            Geometry geom = convertObject(obj, unitFactor);
            if (geom != null && !geom.isEmpty()) {
                geometries.add(geom);
            }
        }

        return geometries;
    }

    public Geometry convertObject(GraphicsObject obj, double unitFactor) {
        if (obj instanceof Flash flash) {
            return convertFlash(flash, unitFactor);
        } else if (obj instanceof Draw draw) {
            return convertDraw(draw, unitFactor);
        } else if (obj instanceof Arc arc) {
            return convertArc(arc, unitFactor);
        } else if (obj instanceof Region region) {
            return convertRegion(region, unitFactor);
        }
        return null;
    }

    private Geometry convertFlash(Flash flash, double uf) {
        double x = flash.getX() * uf;
        double y = flash.getY() * uf;

        if (flash.getAperture() instanceof CircleAperture circle) {
            double radius = circle.getRadius() * uf;
            return factory.createPoint(new Coordinate(x, y)).buffer(radius, ARC_SEGMENTS / 4);
        } else if (flash.getAperture() instanceof RectangleAperture rect) {
            double hw = rect.getWidth() * uf / 2;
            double hh = rect.getHeight() * uf / 2;
            return factory.createPolygon(new Coordinate[]{
                    new Coordinate(x - hw, y - hh),
                    new Coordinate(x + hw, y - hh),
                    new Coordinate(x + hw, y + hh),
                    new Coordinate(x - hw, y + hh),
                    new Coordinate(x - hw, y - hh)
            });
        }
        // Fallback: treat as point with bounding box
        var bb = flash.getAperture().getBoundingBox();
        double margin = Math.max(bb.getWidth(), bb.getHeight()) * uf / 2;
        return factory.createPoint(new Coordinate(x, y)).buffer(margin, ARC_SEGMENTS / 4);
    }

    private Geometry convertDraw(Draw draw, double uf) {
        double radius = 0;
        if (draw.getAperture() instanceof CircleAperture circle) {
            radius = circle.getRadius() * uf;
        } else if (draw.getAperture() instanceof RectangleAperture rect) {
            radius = Math.max(rect.getWidth(), rect.getHeight()) * uf / 2;
        }

        LineString line = factory.createLineString(new Coordinate[]{
                new Coordinate(draw.getStartX() * uf, draw.getStartY() * uf),
                new Coordinate(draw.getEndX() * uf, draw.getEndY() * uf)
        });

        return radius > 0 ? line.buffer(radius, ARC_SEGMENTS / 4) : line;
    }

    private Geometry convertArc(Arc arc, double uf) {
        double radius = 0;
        if (arc.getAperture() instanceof CircleAperture circle) {
            radius = circle.getRadius() * uf;
        }

        // Convert arc to line segments
        List<Coordinate> coords = arcToCoordinates(
                arc.getStartX() * uf, arc.getStartY() * uf,
                arc.getEndX() * uf, arc.getEndY() * uf,
                arc.getCenterX() * uf, arc.getCenterY() * uf,
                arc.isClockwise());

        if (coords.size() < 2) return null;
        LineString line = factory.createLineString(coords.toArray(new Coordinate[0]));
        return radius > 0 ? line.buffer(radius, ARC_SEGMENTS / 4) : line;
    }

    private Geometry convertRegion(Region region, double uf) {
        if (region.getContours().isEmpty()) return null;

        List<Polygon> polygons = new ArrayList<>();
        for (Contour contour : region.getContours()) {
            List<Coordinate> coords = contourToCoordinates(contour, uf);
            if (coords.size() < 4) continue; // Need at least 3 + closing point
            polygons.add(factory.createPolygon(coords.toArray(new Coordinate[0])));
        }

        if (polygons.isEmpty()) return null;
        if (polygons.size() == 1) return polygons.get(0);
        return factory.createMultiPolygon(polygons.toArray(new Polygon[0]));
    }

    private List<Coordinate> contourToCoordinates(Contour contour, double uf) {
        List<Coordinate> coords = new ArrayList<>();
        coords.add(new Coordinate(contour.getStartX() * uf, contour.getStartY() * uf));

        double currentX = contour.getStartX() * uf;
        double currentY = contour.getStartY() * uf;

        for (Contour.ContourSegment seg : contour.getSegments()) {
            if (seg.isArc()) {
                List<Coordinate> arcCoords = arcToCoordinates(
                        currentX, currentY,
                        seg.getX() * uf, seg.getY() * uf,
                        seg.getCenterX() * uf, seg.getCenterY() * uf,
                        seg.isClockwise());
                // Skip the first point (duplicate of current position)
                for (int i = 1; i < arcCoords.size(); i++) {
                    coords.add(arcCoords.get(i));
                }
            } else {
                coords.add(new Coordinate(seg.getX() * uf, seg.getY() * uf));
            }
            currentX = seg.getX() * uf;
            currentY = seg.getY() * uf;
        }

        // Close the ring
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (first.x != last.x || first.y != last.y) {
            coords.add(new Coordinate(first.x, first.y));
        }

        return coords;
    }

    List<Coordinate> arcToCoordinates(double startX, double startY,
                                              double endX, double endY,
                                              double centerX, double centerY,
                                              boolean clockwise) {
        List<Coordinate> coords = new ArrayList<>();

        double startAngle = Math.atan2(startY - centerY, startX - centerX);
        double endAngle = Math.atan2(endY - centerY, endX - centerX);
        double r = Math.sqrt((startX - centerX) * (startX - centerX) +
                             (startY - centerY) * (startY - centerY));

        double sweep;
        if (clockwise) {
            sweep = startAngle - endAngle;
            if (sweep <= 0) sweep += 2 * Math.PI;
        } else {
            sweep = endAngle - startAngle;
            if (sweep <= 0) sweep += 2 * Math.PI;
        }

        // Check for full circle
        double dist = Math.sqrt((endX - startX) * (endX - startX) + (endY - startY) * (endY - startY));
        if (dist < 0.0001) {
            sweep = 2 * Math.PI;
        }

        int segments = Math.max(8, (int) (sweep / (2 * Math.PI) * ARC_SEGMENTS));
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            double angle;
            if (clockwise) {
                angle = startAngle - sweep * t;
            } else {
                angle = startAngle + sweep * t;
            }
            coords.add(new Coordinate(centerX + r * Math.cos(angle), centerY + r * Math.sin(angle)));
        }

        return coords;
    }
}
