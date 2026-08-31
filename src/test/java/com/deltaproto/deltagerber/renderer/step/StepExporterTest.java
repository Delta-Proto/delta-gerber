package com.deltaproto.deltagerber.renderer.step;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.BoardOutline;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The STEP export: the board outline — however it was resolved — extruded into a solid.
 *
 * <p>The assertions are structural rather than golden-file: a STEP file is a graph of numbered
 * entities and the numbering shifts whenever anything upstream changes, so the tests check the
 * things a CAD system checks — that the shell is closed (every edge used exactly twice, once in
 * each direction), that the solid occupies the board's millimetres in X/Y and exactly the
 * requested thickness in Z, and that a cut-out is a hole in the board rather than a second board.
 */
public class StepExporterTest {

    private static int u(double mm) { return (int) Math.round(mm * 10000); } // FSLAX44 MM

    /** A profile layer: one stroked rectangle, centreline = the board edge. */
    private static GerberDocument strokedRectangleOutline(double w, double h) {
        String g = "G04 synthetic profile*\n%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\nD10*\n"
            + "X0Y0D02*\n"
            + "X" + u(w) + "Y0D01*\n"
            + "X" + u(w) + "Y" + u(h) + "D01*\n"
            + "X0Y" + u(h) + "D01*\n"
            + "X0Y0D01*\n"
            + "M02*\n";
        return new GerberParser().parse(g);
    }

    /** As above, plus a filled region inside it — the way EDA tools emit an internal cut-out. */
    private static GerberDocument outlineWithCutOut() {
        String g = "G04 synthetic profile with cut-out*\n%FSLAX44Y44*%\n%MOMM*%\nG01*\n"
            + "%ADD10C,0.1000*%\nD10*\n"
            + "X0Y0D02*\nX" + u(40) + "Y0D01*\nX" + u(40) + "Y" + u(30) + "D01*\n"
            + "X0Y" + u(30) + "D01*\nX0Y0D01*\n"
            + "G36*\n"
            + "X" + u(10) + "Y" + u(10) + "D02*\n"
            + "X" + u(20) + "Y" + u(10) + "D01*\n"
            + "X" + u(20) + "Y" + u(20) + "D01*\n"
            + "X" + u(10) + "Y" + u(20) + "D01*\n"
            + "X" + u(10) + "Y" + u(10) + "D01*\n"
            + "G37*\n"
            + "M02*\n";
        return new GerberParser().parse(g);
    }

    /** A copper pour and nothing else — the set that forces the derived-silhouette path. */
    private static GerberDocument copperPour(double w, double h) {
        String g = "G04 synthetic copper pour*\n%FSLAX44Y44*%\n%MOMM*%\nG01*\n%ADD10C,0.1000*%\n"
            + "G36*\n"
            + "X0Y0D02*\nX" + u(w) + "Y0D01*\nX" + u(w) + "Y" + u(h) + "D01*\n"
            + "X0Y" + u(h) + "D01*\nX0Y0D01*\n"
            + "G37*\nM02*\n";
        return new GerberParser().parse(g);
    }

    private static List<MultiLayerSVGRenderer.Layer> outlineLayers(GerberDocument outline) {
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("board.gko", outline)
            .setLayerType(LayerType.OUTLINE));
        return layers;
    }

    @Test
    void rectangularOutlineBecomesASixFacedBox() {
        String step = new StepExporter().export(outlineLayers(strokedRectangleOutline(40, 30)));

        assertTrue(step.startsWith("ISO-10303-21;\n"), "part 21 header");
        assertTrue(step.contains("FILE_SCHEMA(('AUTOMOTIVE_DESIGN"), "AP214 schema");
        assertTrue(step.trim().endsWith("END-ISO-10303-21;"), "part 21 footer");
        assertTrue(step.contains("LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.)"), "millimetres");

        assertEquals(1, count(step, "MANIFOLD_SOLID_BREP"), "one board, one solid");
        assertEquals(1, count(step, "CLOSED_SHELL"));
        assertEquals(6, count(step, "ADVANCED_FACE"), "four walls, a top and a bottom");
        assertEquals(6, count(step, "FACE_OUTER_BOUND"), "one outer bound per face");
        assertEquals(0, count(step, "FACE_BOUND"), "a rectangle has no cut-outs");
    }

    @Test
    void solidSpansTheBoardAndTheDefaultThickness() {
        String step = new StepExporter().export(outlineLayers(strokedRectangleOutline(40, 30)));
        double[][] extent = pointExtent(step);

        assertArrayEquals(new double[]{0, 40}, extent[0], 1e-6, "X spans the board");
        assertArrayEquals(new double[]{0, 30}, extent[1], 1e-6, "Y spans the board");
        assertArrayEquals(new double[]{0, StepExporter.DEFAULT_THICKNESS_MM}, extent[2], 1e-6,
            "Z spans the default 1.6 mm board");
    }

    @Test
    void thicknessIsCallerSettable() {
        String step = new StepExporter().setThicknessMm(0.8)
            .export(outlineLayers(strokedRectangleOutline(40, 30)));
        assertArrayEquals(new double[]{0, 0.8}, pointExtent(step)[2], 1e-6);

        assertThrows(IllegalArgumentException.class, () -> new StepExporter().setThicknessMm(0));
        assertThrows(IllegalArgumentException.class, () -> new StepExporter().setThicknessMm(-1));
    }

    @Test
    void shellIsClosed() {
        assertClosedShell(new StepExporter().export(outlineLayers(strokedRectangleOutline(40, 30))));
        assertClosedShell(new StepExporter().export(outlineLayers(outlineWithCutOut())));
    }

    @Test
    void cutOutIsAHoleInTheBoardNotASecondSolid() {
        String step = new StepExporter().export(outlineLayers(outlineWithCutOut()));

        assertEquals(1, count(step, "MANIFOLD_SOLID_BREP"), "the cut-out must not become a solid");
        // 4 walls around the board, 4 around the cut-out, plus the two end faces.
        assertEquals(10, count(step, "ADVANCED_FACE"));
        // Each end face carries the board's outer bound and the cut-out as an inner bound.
        assertEquals(2, count(step, "FACE_BOUND('',#"), "one inner bound per end face");

        double[][] extent = pointExtent(step);
        assertArrayEquals(new double[]{0, 40}, extent[0], 1e-6, "the cut-out doesn't move the board");
        assertArrayEquals(new double[]{0, 30}, extent[1], 1e-6);
    }

    @Test
    void exportsASetWithNoProfileLayerFromTheDerivedSilhouette() {
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
        layers.add(new MultiLayerSVGRenderer.Layer("board.gtl", copperPour(40, 30))
            .setLayerType(LayerType.COPPER_TOP));

        BoardOutline outline = new MultiLayerSVGRenderer().resolveBoardOutline(layers);
        assertTrue(outline.isDerived(), "no profile layer, so the edge is derived from the copper");

        String step = new StepExporter().export(layers);
        assertEquals(1, count(step, "MANIFOLD_SOLID_BREP"));
        assertClosedShell(step);

        // The silhouette is outset by the copper-to-edge clearance, so it is a shade larger
        // than the pour, and raster-traced, so it is not exactly rectangular.
        double[][] extent = pointExtent(step);
        assertEquals(0.0, extent[0][0], 0.5);
        assertEquals(40.0, extent[0][1], 0.5);
        assertEquals(30.0, extent[1][1], 0.5);
        assertArrayEquals(new double[]{0, StepExporter.DEFAULT_THICKNESS_MM}, extent[2], 1e-6);
    }

    @Test
    void refusesASetWithNoResolvableOutline() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> new StepExporter().export(List.of()));
        assertTrue(e.getMessage().contains("No board outline"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> new StepExporter().export(BoardOutline.none()));
    }

    @Test
    void outputIsReproducibleForAFixedTimestamp() {
        StepExporter exporter = new StepExporter()
            .setProductName("demo-board")
            .setTimestamp(Instant.parse("2026-01-02T03:04:05Z"));
        List<MultiLayerSVGRenderer.Layer> layers = outlineLayers(strokedRectangleOutline(40, 30));

        String first = exporter.export(layers);
        assertEquals(first, exporter.export(layers));
        assertTrue(first.contains("'2026-01-02T03:04:05'"), "the timestamp is the one we set");
        assertTrue(first.contains("PRODUCT('demo-board'"), "the product name reaches the CAD tree");
    }

    /** An Excellon file drilling one tool's worth of holes at the given mm coordinates. */
    private static DrillDocument drill(double diameterMm, double[][] holes) {
        StringBuilder d = new StringBuilder("M48\nMETRIC,TZ\n");
        d.append(String.format(java.util.Locale.US, "T1C%.3f%n", diameterMm));
        d.append("%\nT1\n");
        for (double[] h : holes) {
            d.append(String.format(java.util.Locale.US, "X%.3fY%.3f%n", h[0], h[1]));
        }
        d.append("M30\n");
        return new ExcellonParser().parse(d.toString());
    }

    private static List<MultiLayerSVGRenderer.Layer> boardWithDrill(DrillDocument drill) {
        List<MultiLayerSVGRenderer.Layer> layers = outlineLayers(strokedRectangleOutline(40, 30));
        layers.add(new MultiLayerSVGRenderer.Layer("board.drl", drill)
            .setLayerType(LayerType.DRILL));
        return layers;
    }

    @Test
    void drilledHolesArePunchedThroughTheSolid() {
        // Two 1 mm holes, well inside a 40 x 30 board.
        String step = new StepExporter().export(boardWithDrill(
            drill(1.0, new double[][]{{10, 15}, {30, 15}})));

        assertEquals(1, count(step, "MANIFOLD_SOLID_BREP"), "one board");
        assertEquals(4, count(step, "FACE_BOUND('',#"), "two holes, on the top and bottom face");
        assertClosedShell(step);
        // The board's own extent is untouched...
        double[][] extent = pointExtent(step);
        assertArrayEquals(new double[]{0, 40}, extent[0], 1e-6);
        assertArrayEquals(new double[]{0, 30}, extent[1], 1e-6);
        // ...and each hole contributes a wall of its own.
        assertTrue(count(step, "ADVANCED_FACE") > 6 + 2 * 8,
            "each hole is walled all the way through: " + count(step, "ADVANCED_FACE"));
    }

    @Test
    void holesOnTheBoardEdgeBiteIntoTheOutline() {
        // A row of 1 mm holes centred ON the y=0 edge — mouse bites on a break-off tab. Each
        // takes a semicircular notch out of the board rather than opening an enclosed hole.
        String step = new StepExporter().export(boardWithDrill(
            drill(1.0, new double[][]{{10, 0}, {12, 0}, {14, 0}, {16, 0}})));

        assertEquals(1, count(step, "MANIFOLD_SOLID_BREP"), "the board is still one piece");
        assertEquals(0, count(step, "FACE_BOUND('',#"),
            "a bite is part of the outer bound, not an enclosed hole");
        assertClosedShell(step);

        // The notches are in the outline: the material that was at the centre of a bite is gone.
        List<double[]> outer = topOuterBound(step);
        assertFalse(inside(outer, 12.0, 0.2), "the middle of a mouse bite must be routed away");
        assertTrue(inside(outer, 12.0, 1.0), "a millimetre in from the edge is still board");
        assertTrue(inside(outer, 20.0, 0.2), "the edge between bites is untouched");
    }

    @Test
    void drillHolesCanBeLeftOut() {
        List<MultiLayerSVGRenderer.Layer> layers = boardWithDrill(
            drill(1.0, new double[][]{{10, 15}, {30, 15}, {12, 0}}));

        String bare = new StepExporter().setIncludeDrillHoles(false).export(layers);
        assertEquals(6, count(bare, "ADVANCED_FACE"), "the plain prism, holes and bites ignored");
        assertEquals(0, count(bare, "FACE_BOUND('',#"));

        assertTrue(new StepExporter().isIncludeDrillHoles(), "holes are in by default");
    }

    @Test
    void bothFacesAreLabelled() {
        List<MultiLayerSVGRenderer.Layer> layers = outlineLayers(strokedRectangleOutline(40, 30));
        String step = new StepExporter().export(layers);

        assertTrue(step.contains("GEOMETRIC_CURVE_SET('side labels'"), "the labels are annotation");
        assertTrue(step.contains("GEOMETRICALLY_BOUNDED_WIREFRAME_SHAPE_REPRESENTATION"),
            "kept in their own representation, out of the solid model");
        assertTrue(step.contains("SHAPE_REPRESENTATION_RELATIONSHIP"), "tied back to the board");
        assertTrue(step.contains("ADVANCED_FACE('top',"), "the faces say which is which too");
        assertTrue(step.contains("ADVANCED_FACE('bottom',"));

        // Strokes on both faces, and every one of them lies exactly in its face's plane.
        List<double[]> top = polylinePoints(step, StepExporter.DEFAULT_THICKNESS_MM);
        List<double[]> bottom = polylinePoints(step, 0);
        assertFalse(top.isEmpty(), "TOP is written on the top face");
        assertFalse(bottom.isEmpty(), "BOTTOM is written on the underside");
        for (double[] p : top) {
            assertTrue(p[0] >= 0 && p[0] <= 40 && p[1] >= 0 && p[1] <= 30,
                "a label must stay on the board: " + p[0] + "," + p[1]);
        }

        // The solid itself is untouched by the labelling.
        String unlabelled = new StepExporter().setLabelSides(false).export(layers);
        assertEquals(count(unlabelled, "ADVANCED_FACE"), count(step, "ADVANCED_FACE"));
        assertEquals(0, count(unlabelled, "POLYLINE"), "labels off means no strokes at all");
        assertClosedShell(step);
    }

    @Test
    void theUndersideLabelIsMirroredSoItReadsFromBelow() {
        double capHeight = 4;
        List<double[][]> plain = StrokeFont.strokes("BOTTOM", 10, 5, capHeight, false);
        List<double[][]> mirrored = StrokeFont.strokes("BOTTOM", 10, 5, capHeight, true);

        assertEquals(plain.size(), mirrored.size(), "mirroring draws the same strokes");
        double left = 10, right = 10 + StrokeFont.width("BOTTOM", capHeight);
        for (int i = 0; i < plain.size(); i++) {
            for (int j = 0; j < plain.get(i).length; j++) {
                assertEquals(left + right - plain.get(i)[j][0], mirrored.get(i)[j][0], 1e-9,
                    "x is reflected about the word's centre");
                assertEquals(plain.get(i)[j][1], mirrored.get(i)[j][1], 1e-9, "y is untouched");
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------

    /** The vertices of the top face's outer bound, in order — the board's silhouette. */
    private static List<double[]> topOuterBound(String step) {
        Matcher face = Pattern.compile(
            "ADVANCED_FACE\\('[a-z]*',\\(([^)]*)\\),#(\\d+),\\.T\\.\\)").matcher(step);
        while (face.find()) {
            String plane = entity(step, face.group(2), "PLANE");
            if (plane == null) continue;
            String placement = entity(step, ref(plane, 0), "AXIS2_PLACEMENT_3D");
            String origin = entity(step, ref(placement, 0), "CARTESIAN_POINT");
            String axis = entity(step, ref(placement, 1), "DIRECTION");
            if (origin.contains(",0.))") || !axis.contains("(0.,0.,1.)")) continue;  // bottom face
            String bound = entity(step, ref(face.group(1), 0), "FACE_OUTER_BOUND");
            if (bound == null) continue;
            String loop = entity(step, ref(bound, 0), "EDGE_LOOP");
            List<double[]> ring = new ArrayList<>();
            Matcher edges = Pattern.compile("#(\\d+)").matcher(loop);
            while (edges.find()) {
                String oriented = entity(step, edges.group(1), "ORIENTED_EDGE");
                String curve = entity(step, ref(oriented, 0), "EDGE_CURVE");
                String vertex = entity(step, ref(curve, oriented.endsWith(".T.") ? 0 : 1), "VERTEX_POINT");
                String point = entity(step, ref(vertex, 0), "CARTESIAN_POINT");
                Matcher xyz = Pattern.compile("\\(([^)]*)\\)").matcher(point);
                assertTrue(xyz.find(), point);
                String[] c = xyz.group(1).split(",");
                ring.add(new double[]{Double.parseDouble(c[0]), Double.parseDouble(c[1])});
            }
            if (ring.size() > 2) return ring;
        }
        throw new AssertionError("no top face with an outer bound in the file");
    }

    /** The body of entity {@code #id}, or {@code null} if it is not of the expected type. */
    private static String entity(String step, String id, String type) {
        Matcher m = Pattern.compile("(?m)^#" + id + "=([A-Z0-9_]+)\\((.*)\\);$").matcher(step);
        if (!m.find() || !m.group(1).equals(type)) return null;
        return m.group(2);
    }

    /** The {@code n}-th {@code #id} reference in an entity body. */
    private static String ref(String body, int n) {
        Matcher m = Pattern.compile("#(\\d+)").matcher(body);
        for (int i = 0; i <= n; i++) {
            if (!m.find()) throw new AssertionError("no reference " + n + " in " + body);
        }
        return m.group(1);
    }

    /** Crossing test — is (x,y) inside this ring? */
    private static boolean inside(List<double[]> ring, double x, double y) {
        boolean in = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double[] a = ring.get(i), b = ring.get(j);
            if ((a[1] > y) != (b[1] > y)
                    && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    /** Every point of every label polyline lying in the plane {@code z}. */
    private static List<double[]> polylinePoints(String step, double z) {
        Map<String, double[]> points = new HashMap<>();
        Matcher p = Pattern.compile(
            "(?m)^#(\\d+)=CARTESIAN_POINT\\('',\\(([^)]*)\\)\\);$").matcher(step);
        while (p.find()) {
            String[] xyz = p.group(2).split(",");
            points.put(p.group(1), new double[]{Double.parseDouble(xyz[0]),
                Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2])});
        }
        List<double[]> out = new ArrayList<>();
        Matcher line = Pattern.compile("POLYLINE\\('',\\(([^)]*)\\)\\)").matcher(step);
        while (line.find()) {
            Matcher ids = Pattern.compile("#(\\d+)").matcher(line.group(1));
            while (ids.find()) {
                double[] pt = points.get(ids.group(1));
                if (pt != null && Math.abs(pt[2] - z) < 1e-9) out.add(pt);
            }
        }
        return out;
    }

    private static int count(String step, String entity) {
        int n = 0;
        for (int i = step.indexOf(entity); i >= 0; i = step.indexOf(entity, i + 1)) n++;
        return n;
    }

    /**
     * A shell is closed when every edge is used by exactly two faces, once in each direction.
     * A hole in the shell (a missing wall) or a wall wound the wrong way both show up here.
     */
    private static void assertClosedShell(String step) {
        Pattern p = Pattern.compile("ORIENTED_EDGE\\('',\\*,\\*,#(\\d+),\\.([TF])\\.\\)");
        Map<String, Integer> forward = new HashMap<>();
        Map<String, Integer> reverse = new HashMap<>();
        Matcher m = p.matcher(step);
        int total = 0;
        while (m.find()) {
            total++;
            (m.group(2).equals("T") ? forward : reverse).merge(m.group(1), 1, Integer::sum);
        }
        assertTrue(total > 0, "no oriented edges in the file");
        assertEquals(forward.keySet(), reverse.keySet(), "every edge must be used in both directions");
        for (String edge : forward.keySet()) {
            assertEquals(1, forward.get(edge), "edge #" + edge + " used forwards more than once");
            assertEquals(1, reverse.get(edge), "edge #" + edge + " used backwards more than once");
        }
    }

    /**
     * {@code [[minX,maxX],[minY,maxY],[minZ,maxZ]]} over every CARTESIAN_POINT in the file — the
     * shape representation's placement origin included, which is why the fixtures put their
     * boards on the origin: elsewhere it would widen the extent by itself.
     */
    private static double[][] pointExtent(String step) {
        Pattern p = Pattern.compile(
            "CARTESIAN_POINT\\('',\\((-?[\\d.E+-]+),(-?[\\d.E+-]+),(-?[\\d.E+-]+)\\)\\)");
        double[][] extent = {{Double.MAX_VALUE, -Double.MAX_VALUE},
                             {Double.MAX_VALUE, -Double.MAX_VALUE},
                             {Double.MAX_VALUE, -Double.MAX_VALUE}};
        Matcher m = p.matcher(step);
        int found = 0;
        while (m.find()) {
            found++;
            for (int axis = 0; axis < 3; axis++) {
                double v = Double.parseDouble(m.group(axis + 1));
                extent[axis][0] = Math.min(extent[axis][0], v);
                extent[axis][1] = Math.max(extent[axis][1], v);
            }
        }
        assertTrue(found > 0, "no cartesian points in the file");
        return extent;
    }
}
