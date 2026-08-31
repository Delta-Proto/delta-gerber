package com.deltaproto.deltagerber.web;

import com.deltaproto.deltagerber.renderer.step.StepExporter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The viewer's STEP export, entered the way the browser and a host application enter it — with a
 * viewer request body. The geometry itself is pinned by
 * {@link com.deltaproto.deltagerber.renderer.step.StepExporter}'s own tests; what matters here is
 * that a set reaches the exporter, that the thickness the caller asked for is the thickness that
 * comes out, and that a set with no board edge is reported rather than exported empty.
 */
class StepEndpointTest {

    private static final String EDGE_CUTS = String.join("\n",
            "%FSLAX46Y46*%",
            "%MOMM*%",
            "%ADD10C,0.050000*%",
            "D10*",
            "X0Y0D02*",
            "X40000000Y0D01*",
            "X40000000Y30000000D01*",
            "X0Y30000000D01*",
            "X0Y0D01*",
            "M02*");

    private static final String SILKSCREEN = String.join("\n",
            "%FSLAX46Y46*%",
            "%MOMM*%",
            "%ADD10C,0.150000*%",
            "D10*",
            "X5000000Y5000000D02*",
            "X8000000Y5000000D01*",
            "M02*");

    private static int countOf(String step, String entity) {
        int n = 0;
        for (int i = step.indexOf(entity); i >= 0; i = step.indexOf(entity, i + 1)) n++;
        return n;
    }

    private static void file(ByteArrayOutputStream out, String name, String type, String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(("FILE\t" + name + "\t" + type + "\tAUTO\t" + body.length + "\n")
            .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(body);
        out.writeBytes(new byte[]{'\n'});
    }

    /** Four 1 mm holes centred on the board's y=0 edge: mouse bites on a break-off tab. */
    private static final String MOUSE_BITES =
            "M48\nMETRIC,TZ\nT1C1.000\n%\nT1\nX10.000Y0.000\nX12.000Y0.000\n"
            + "X14.000Y0.000\nX20.000Y15.000\nM30\n";

    private static byte[] boardWithOutline() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        file(body, "board-Edge_Cuts.gbr", "gerber", EDGE_CUTS);
        return body.toByteArray();
    }

    private static byte[] boardWithDrill() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        file(body, "board-Edge_Cuts.gbr", "gerber", EDGE_CUTS);
        file(body, "board-PTH.drl", "drill", MOUSE_BITES);
        return body.toByteArray();
    }

    @Test
    void exportsTheOutlineAtTheRequestedThickness() {
        String step = GerberViewerServer.exportStep(boardWithOutline(), 0.8, "demo");

        assertNotNull(step);
        assertTrue(step.startsWith("ISO-10303-21;"), step.substring(0, Math.min(80, step.length())));
        assertTrue(step.contains("MANIFOLD_SOLID_BREP('demo'"), "the part is named after the board");
        assertTrue(step.contains("CARTESIAN_POINT('',(0.,0.,0.8))")
            || step.contains("CARTESIAN_POINT('',(40.,0.,0.8))"), "extruded to the requested 0.8 mm");
        assertFalse(step.contains(",1.6))"), "nothing should sit at the default thickness");
    }

    @Test
    void defaultsToAStandardBoard() {
        String step = GerberViewerServer.exportStep(boardWithOutline(),
            StepExporter.DEFAULT_THICKNESS_MM, "board");
        assertNotNull(step);
        assertTrue(step.contains(",1.6))"), "the default board is 1.6 mm thick");
    }

    @Test
    void drilledHolesAreInTheSolidUnlessTheCallerOptsOut() {
        String drilled = GerberViewerServer.exportStep(boardWithDrill(), 1.6, "board");
        String bare = GerberViewerServer.exportStep(boardWithDrill(), 1.6, "board", false, false);

        assertNotNull(drilled);
        assertNotNull(bare);
        // The bare board is the plain prism: four walls, a top and a bottom.
        assertEquals(6, countOf(bare, "ADVANCED_FACE"));
        assertTrue(countOf(drilled, "ADVANCED_FACE") > 20,
            "the drilled board carries the hole and the three edge bites");
        assertEquals(1, countOf(drilled, "MANIFOLD_SOLID_BREP"), "still one board");
    }

    @Test
    void reportsASetWithNoBoardEdge() {
        // Silkscreen alone: nothing to take a board edge from — not a profile, not copper.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        file(body, "board-F_Silkscreen.gbr", "gerber", SILKSCREEN);
        assertNull(GerberViewerServer.exportStep(body.toByteArray(), 1.6, "board"));

        assertNull(GerberViewerServer.exportStep(new byte[0], 1.6, "board"), "empty request");
    }
}
