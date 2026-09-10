package com.deltaproto.deltagerber.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The viewer's {@code /api/gerber/render} response carries the geometric clearance and conductor
 * width — the numbers, and where on the board they were found — so the frontend can show a
 * designer the spot without re-analysing the set.
 */
class ClearanceServerTest {

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

    /** Two 0.2 mm traces of different nets with their axes 0.5 mm apart, plus a 0.15 mm one. */
    private static final String F_CU = String.join("\n",
            "%FSLAX46Y46*%",
            "%MOMM*%",
            "%ADD10C,0.200000*%",
            "%ADD11C,0.150000*%",
            "D10*",
            "%TO.N,SDA*%",
            "X5000000Y10000000D02*",
            "X20000000Y10000000D01*",
            "%TO.N,SCL*%",
            "X5000000Y10500000D02*",
            "X20000000Y10500000D01*",
            "%TD*%",
            "D11*",
            "X5000000Y20000000D02*",
            "X20000000Y20000000D01*",
            "M02*");

    private static void file(ByteArrayOutputStream out, String name, String type, String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        String header = "FILE\t" + name + "\t" + type + "\tAUTO\t" + body.length + "\n";
        out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(body);
        out.writeBytes(new byte[]{'\n'});
    }

    @Test
    void renderJsonReportsClearanceAndConductorWidthWithTheirPlace() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        file(body, "board-Edge_Cuts.gbr", "gerber", EDGE_CUTS);
        file(body, "board-F_Cu.gbr", "gerber", F_CU);

        String json = GerberViewerServer.renderToJson(body.toByteArray());
        assertTrue(json.contains("\"minClearanceMm\":0.3"), json);
        assertTrue(json.contains("\"minClearanceAt\":{\"layer\":\"board-F_Cu.gbr\",\"netA\":\"SDA\",\"netB\":\"SCL\""), json);
        assertTrue(json.contains("\"minConductorUm\":150"), json);
        assertTrue(json.contains("\"minConductorAt\":{\"layer\":\"board-F_Cu.gbr\",\"kind\":\"STROKE\""), json);
        // The board-edge trace is 0.05 mm and must not have been counted as copper.
        assertTrue(!json.contains("\"minConductorUm\":50"), json);
    }

    @Test
    void renderJsonSaysNothingWithoutCopper() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        file(body, "board-Edge_Cuts.gbr", "gerber", EDGE_CUTS);

        String json = GerberViewerServer.renderToJson(body.toByteArray());
        assertTrue(json.contains("\"minClearanceMm\":null"), json);
        assertTrue(json.contains("\"minClearanceAt\":null"), json);
        assertTrue(json.contains("\"minConductorUm\":null"), json);
    }
}
