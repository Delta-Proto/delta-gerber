package nl.bytesoflife.deltagerber.drc.geometry;

import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;

import java.util.ArrayList;
import java.util.List;

public class SpatialIndex {

    private final STRtree tree = new STRtree();
    private boolean built = false;

    public void insert(Geometry geometry) {
        tree.insert(geometry.getEnvelopeInternal(), geometry);
    }

    public void insertAll(List<Geometry> geometries) {
        for (Geometry geom : geometries) {
            insert(geom);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Geometry> queryNeighbors(Geometry geometry, double searchDistance) {
        ensureBuilt();
        Envelope searchEnvelope = geometry.getEnvelopeInternal().copy();
        searchEnvelope.expandBy(searchDistance);
        return (List<Geometry>) tree.query(searchEnvelope);
    }

    public List<GeometryPair> findPairsWithinDistance(List<Geometry> geometries, double maxDistance) {
        SpatialIndex index = new SpatialIndex();
        index.insertAll(geometries);
        index.ensureBuilt();

        List<GeometryPair> pairs = new ArrayList<>();

        for (int i = 0; i < geometries.size(); i++) {
            Geometry geom = geometries.get(i);
            List<Geometry> neighbors = index.queryNeighbors(geom, maxDistance);

            for (Geometry neighbor : neighbors) {
                if (geom == neighbor) continue; // Skip self

                double distance = geom.distance(neighbor);
                if (distance < maxDistance) {
                    pairs.add(new GeometryPair(geom, neighbor, distance));
                }
            }
        }

        return pairs;
    }

    private void ensureBuilt() {
        if (!built) {
            tree.build();
            built = true;
        }
    }

    public record GeometryPair(Geometry geom1, Geometry geom2, double distance) {}
}
