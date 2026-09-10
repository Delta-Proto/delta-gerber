package com.deltaproto.deltagerber.dfm.geometry;

import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry.Feature;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which drawn objects of a copper layer are one piece of copper — one net, as far as this layer can
 * tell. Two features join when they share a point that survives every clear drawn after the
 * earlier of the two; union-find over those joins gives the components.
 *
 * <p>The <em>survives</em> clause is the whole difficulty, and it is answered with witness points
 * rather than a boolean: the crossing points of the two boundaries, each object's vertices that
 * lie in the other, and — for the case that matters most — the points where a later clear's
 * boundary crosses one object inside the other. That last set is what connects a thermal spoke to
 * its plane: the spoke starts inside the antipad, where the plane no longer exists, and the only
 * place plane and spoke provably meet is where the spoke crosses the antipad's edge. A witness
 * <em>on</em> a clear's boundary is not inside it, by {@link PolygonShape#EDGE_EPSILON_MM}.
 *
 * <p>An object drawn with no later clear over it and a pad flashed entirely inside an antipad both
 * come out right by the same rule: the pad's every witness against the plane is inside the antipad,
 * which was drawn after the plane, so the two never join — even though the pad itself, drawn after
 * the antipad, is intact.
 *
 * <p>Names come from the {@code .N} attribute where the file carries it (KiCad does; Altium does
 * not), and a component is called what most of its objects are called. A component seen under two
 * names is reported as a {@linkplain #warnings() conflict}: a net tie, a short, or a mislabelled
 * object. Names never join components — see {@link #sameName} for what they are used for.
 */
public final class CopperNets {

    private final CopperGeometry geometry;
    private final int[] netOf;          // per feature; -1 for a clear
    private final String[] names;       // per net
    private final int[] nameIds;        // per net: index of its file name, -1 when it has none
    private final int netCount;
    private final List<String> warnings;

    private CopperNets(CopperGeometry geometry, int[] netOf, String[] names, int[] nameIds, int netCount,
                       List<String> warnings) {
        this.geometry = geometry;
        this.netOf = netOf;
        this.names = names;
        this.nameIds = nameIds;
        this.netCount = netCount;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public static CopperNets of(CopperGeometry geometry) {
        List<Feature> features = geometry.features();
        int n = features.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (Feature b : features) {
            if (b.clear) {
                continue;
            }
            geometry.forEachFeatureNear(b.bounds, 0, i -> {
                Feature a = features.get(i);
                if (a.clear || a.index >= b.index || !CopperGeometry.overlaps(a.bounds, b.bounds)) {
                    return;
                }
                if (find(parent, a.index) == find(parent, b.index)) {
                    return;
                }
                if (connected(geometry, a, b)) {
                    union(parent, a.index, b.index);
                }
            });
        }

        int[] netOf = new int[n];
        Map<Integer, Integer> rootToNet = new LinkedHashMap<>();
        for (Feature f : features) {
            if (f.clear) {
                netOf[f.index] = -1;
                continue;
            }
            int root = find(parent, f.index);
            netOf[f.index] = rootToNet.computeIfAbsent(root, r -> rootToNet.size());
        }
        int netCount = rootToNet.size();
        // A component's name is the one most of its objects carry. The file's names are not
        // trusted over the geometry: KiCad gives a knockout-text region the net of whatever it wrote
        // before, and a union by name would fold a whole signal net into the ground it sits in.
        List<Map<String, Integer>> seen = new ArrayList<>(netCount);
        for (int i = 0; i < netCount; i++) {
            seen.add(new LinkedHashMap<>());
        }
        for (Feature f : features) {
            if (!f.clear && f.net != null) {
                seen.get(netOf[f.index]).merge(f.net, 1, Integer::sum);
            }
        }
        String[] names = new String[netCount];
        int[] nameIds = new int[netCount];
        Map<String, Integer> nameIndex = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (int i = 0; i < netCount; i++) {
            Map<String, Integer> counts = seen.get(i);
            if (counts.isEmpty()) {
                names[i] = "net#" + i;
                nameIds[i] = -1;
                continue;
            }
            String best = counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).get().getKey();
            names[i] = best;
            nameIds[i] = nameIndex.computeIfAbsent(best, key -> nameIndex.size());
            if (counts.size() > 1) {
                warnings.add("nets " + String.join(", ", counts.keySet()) + " are one piece of copper on this layer");
            }
        }
        return new CopperNets(geometry, netOf, names, nameIds, netCount, warnings);
    }

    // ------------------------------------------------------------------------
    // The pair test
    // ------------------------------------------------------------------------

    /**
     * Whether dark features {@code a} (drawn first) and {@code b} share copper: some point in both
     * that no clear drawn after {@code a} erases.
     */
    static boolean connected(CopperGeometry g, Feature a, Feature b) {
        if (swallowed(g, a, b)) {
            return false;
        }
        List<double[]> witnesses = new ArrayList<>();
        overlapWitnesses(g, a, b, witnesses);
        if (witnesses.isEmpty()) {
            return false;
        }
        for (double[] w : witnesses) {
            if (!g.erasedAt(a, w[0], w[1])) {
                return true;
            }
        }
        // Every plain witness is under a later clear. The copper may still meet along that clear's
        // edge — a spoke leaving an antipad — so look at where the clear's boundary runs through both.
        for (int ci : a.laterClears()) {
            Feature c = g.feature(ci);
            if (!CopperGeometry.overlaps(c.bounds, b.bounds)) {
                continue;
            }
            List<double[]> onEdge = new ArrayList<>();
            clearEdgeWitnesses(g, c, a, b, onEdge);
            for (double[] w : onEdge) {
                if (!g.erasedAt(a, w[0], w[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a clear drawn after {@code a} covers the whole of {@code b}, so that no point of
     * {@code b} can still be {@code a}'s copper. Altium pours a board-sized plane, clears nearly all
     * of it with one region, and draws the real pours on top: every one of those pairs ends here,
     * before walking the boundaries of the thousand antipads that also lie in the cleared area.
     */
    private static boolean swallowed(CopperGeometry g, Feature a, Feature b) {
        BoundingBox bb = b.bounds;
        for (int ci : a.laterClears()) {
            Feature c = g.feature(ci);
            BoundingBox cb = c.bounds;
            if (cb.getMinX() > bb.getMinX() || cb.getMaxX() < bb.getMaxX()
                    || cb.getMinY() > bb.getMinY() || cb.getMaxY() < bb.getMaxY()) {
                continue;
            }
            if (!c.contains(bb.getMinX(), bb.getMinY()) || !c.contains(bb.getMaxX(), bb.getMinY())
                    || !c.contains(bb.getMaxX(), bb.getMaxY()) || !c.contains(bb.getMinX(), bb.getMaxY())) {
                continue;
            }
            // Corners inside and no edge of the clear entering the box: the box is inside the clear.
            boolean[] enters = new boolean[1];
            g.forEachEntryNear(bb, 0, e -> {
                if (!enters[0] && g.entryFeature(e) == c.index
                        && CopperGeometry.overlaps(g.entryCapsule(e).bounds(), bb)) {
                    enters[0] = true;
                }
            });
            if (!enters[0]) {
                return true;
            }
        }
        return false;
    }

    /** Points in both footprints, clears ignored: boundary crossings and contained vertices. */
    private static void overlapWitnesses(CopperGeometry g, Feature a, Feature b, List<double[]> out) {
        if (a.polygon == null && b.polygon == null) {
            for (Capsule ca : a.solids) {
                for (Capsule cb : b.solids) {
                    if (!CopperGeometry.overlaps(ca.bounds(), cb.bounds())) {
                        continue;
                    }
                    if (ca.distance(cb) <= 0) {
                        double[] p = ca.closestSurfacePoints(cb);
                        out.add(new double[]{p[0], p[1]});
                    }
                    if (cb.contains(ca.x1, ca.y1)) out.add(new double[]{ca.x1, ca.y1});
                    if (cb.contains(ca.x2, ca.y2)) out.add(new double[]{ca.x2, ca.y2});
                    if (ca.contains(cb.x1, cb.y1)) out.add(new double[]{cb.x1, cb.y1});
                    if (ca.contains(cb.x2, cb.y2)) out.add(new double[]{cb.x2, cb.y2});
                }
            }
            return;
        }
        if (a.polygon == null) {
            capsulesAgainstPolygon(g, a, b, out);
            return;
        }
        if (b.polygon == null) {
            capsulesAgainstPolygon(g, b, a, out);
            return;
        }
        // Two polygons: crossings of their edges, walking the smaller one's edges through the grid.
        Feature small = a.polygon.edgeCount() <= b.polygon.edgeCount() ? a : b;
        Feature large = small == a ? b : a;
        for (int r = 0; r < small.polygon.ringCount(); r++) {
            for (int k = 0; k < small.polygon.ringSize(r); k++) {
                Capsule edge = small.polygon.edge(r, k);
                if (!CopperGeometry.overlaps(edge.bounds(), large.bounds)) {
                    continue;
                }
                g.forEachEntryAlong(edge, 0, e -> {
                    if (g.entryFeature(e) != large.index) {
                        return;
                    }
                    double[] x = edge.axisIntersection(g.entryCapsule(e));
                    if (x != null) {
                        out.add(x);
                    }
                });
            }
        }
        vertexWitnesses(small, large, out);
        vertexWitnesses(large, small, out);
    }

    /** Where capsule feature {@code caps} meets polygon feature {@code poly}. */
    private static void capsulesAgainstPolygon(CopperGeometry g, Feature caps, Feature poly, List<double[]> out) {
        for (Capsule c : caps.solids) {
            if (!CopperGeometry.overlaps(c.bounds(), poly.bounds)) {
                continue;
            }
            if (poly.polygon.contains(c.x1, c.y1)) out.add(new double[]{c.x1, c.y1});
            if (poly.polygon.contains(c.x2, c.y2)) out.add(new double[]{c.x2, c.y2});
            g.forEachEntryAlong(c, 0, e -> {
                if (g.entryFeature(e) != poly.index) {
                    return;
                }
                Capsule edge = g.entryCapsule(e);
                double[] p = c.closestAxisPoints(edge);
                if (Math.hypot(p[0] - p[2], p[1] - p[3]) < c.r) {
                    out.add(new double[]{p[2], p[3]});    // on the polygon's edge, inside the capsule
                }
            });
        }
    }

    /**
     * Points just inside {@code inner} — an edge midpoint nudged to its copper side — that lie
     * inside {@code outer}. Not vertices: a profile drawn twice, or a pour whose edge runs along
     * another's, shares its vertices with the other's boundary, and on the boundary is not inside.
     */
    private static void vertexWitnesses(Feature inner, Feature outer, List<double[]> out) {
        for (int r = 0; r < inner.polygon.ringCount(); r++) {
            int n = inner.polygon.ringSize(r);
            for (int k : new int[]{0, n / 3, 2 * n / 3}) {
                Capsule edge = inner.polygon.edge(r, k);
                double len = edge.length();
                if (len == 0) {
                    continue;
                }
                double nx = -(edge.y2 - edge.y1) / len, ny = (edge.x2 - edge.x1) / len;
                double x = (edge.x1 + edge.x2) / 2 + nx * INWARD_NUDGE_MM;
                double y = (edge.y1 + edge.y2) / 2 + ny * INWARD_NUDGE_MM;
                if (inner.polygon.contains(x, y) && outer.contains(x, y)) {
                    out.add(new double[]{x, y});
                }
            }
        }
    }

    /** How far a containment witness steps in from its edge — well above the edge epsilon. */
    private static final double INWARD_NUDGE_MM = 1e-5;

    /**
     * Points on clear {@code c}'s boundary that lie in both {@code a} and {@code b}: where the
     * boundary crosses {@code a}'s edges inside {@code b} and vice versa, where it runs through a
     * capsule of either inside the other, and its own vertices inside both.
     */
    private static void clearEdgeWitnesses(CopperGeometry g, Feature c, Feature a, Feature b, List<double[]> out) {
        for (int r = 0; r < c.polygon.ringCount(); r++) {
            for (int k = 0; k < c.polygon.ringSize(r); k++) {
                Capsule edge = c.polygon.edge(r, k);
                BoundingBox eb = edge.bounds();
                if (!CopperGeometry.overlaps(eb, a.bounds) || !CopperGeometry.overlaps(eb, b.bounds)) {
                    continue;
                }
                if (a.contains(edge.x1, edge.y1) && b.contains(edge.x1, edge.y1)) {
                    out.add(new double[]{edge.x1, edge.y1});
                }
                g.forEachEntryAlong(edge, 0, e -> {
                    int fi = g.entryFeature(e);
                    if (fi != a.index && fi != b.index) {
                        return;
                    }
                    Feature f = fi == a.index ? a : b;
                    Feature other = fi == a.index ? b : a;
                    Capsule entry = g.entryCapsule(e);
                    if (entry.r > 0) {
                        // The clear edge passes through a stroke: take the edge's point nearest the axis.
                        double[] p = edge.closestAxisPoints(entry);
                        if (Math.hypot(p[0] - p[2], p[1] - p[3]) < entry.r && other.contains(p[0], p[1])) {
                            out.add(new double[]{p[0], p[1]});
                        }
                    } else {
                        double[] x = edge.axisIntersection(entry);
                        if (x != null && other.contains(x[0], x[1])) {
                            out.add(x);
                        }
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------------

    public CopperGeometry geometry() {
        return geometry;
    }

    /** Net of a dark feature, dense from 0; -1 for a clear feature. */
    public int netOf(int feature) {
        return netOf[feature];
    }

    public int netCount() {
        return netCount;
    }

    /** The {@code .N} name of a net, or {@code net#k} when the file names nothing. */
    public String nameOf(int net) {
        return names[net];
    }

    /** Whether the net's name came from the file rather than being made up here. */
    public boolean isNamed(int net) {
        return nameIds[net] >= 0;
    }

    /**
     * Whether the file calls both pieces of copper by the same name — two pads of {@code +3V3}
     * joined on another layer. A gap between them is not a clearance.
     */
    public boolean sameName(int netA, int netB) {
        return nameIds[netA] >= 0 && nameIds[netA] == nameIds[netB];
    }

    public List<String> warnings() {
        return warnings;
    }

    private static int find(int[] p, int x) {
        while (p[x] != x) {
            p[x] = p[p[x]];
            x = p[x];
        }
        return x;
    }

    private static void union(int[] p, int a, int b) {
        a = find(p, a);
        b = find(p, b);
        if (a != b) {
            p[Math.max(a, b)] = Math.min(a, b);
        }
    }
}
