package com.deltaproto.deltagerber.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The viewer's {@code /api/gerber/render} response carries the annular-ring measurement, so the
 * frontend (and any host application built on {@link GerberViewerServer#renderToJson}) can show the
 * board's tightest ring without re-analysing the set.
 */
class AnnularRingServerTest {

    /** Two ⌀0.9 pads and one ⌀0.5 pad, all on the top copper. */
    private static final String F_CU = String.join("\n",
            "%FSLAX46Y46*%",
            "%MOMM*%",
            "%ADD10C,0.900000*%",
            "%ADD11C,0.500000*%",
            "D10*",
            "X10000000Y10000000D03*",
            "X20000000Y10000000D03*",
            "D11*",
            "X30000000Y10000000D03*",
            "M02*");

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

    /** ⌀0.4 holes in all three pads: rings of 0.25, 0.25 and 0.05 mm. */
    private static final String DRILL = "M48\nMETRIC,TZ\n;TYPE=PLATED\nT1C0.400\n%\nT1\n"
            + "X010000Y010000\nX020000Y010000\nX030000Y010000\nM30\n";

    /** Build one length-prefixed file record for the viewer request protocol. */
    private static void file(ByteArrayOutputStream out, String name, String type, String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        String header = "FILE\t" + name + "\t" + type + "\tAUTO\t" + body.length + "\n";
        out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(body);
        out.writeBytes(new byte[]{'\n'});
    }

    private static String render(String drill) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        file(body, "board-Edge_Cuts.gbr", "gerber", EDGE_CUTS);
        file(body, "board-F_Cu.gbr", "gerber", F_CU);
        file(body, "board-PTH.drl", "drill", drill);
        return GerberViewerServer.renderToJson(body.toByteArray());
    }

    @Test
    void renderJsonReportsTheTightestRingAndWhetherItClearsTheRule() {
        String json = render(DRILL);

        assertTrue(json.contains("\"minAnnularRingMm\":0.05"), json);
        assertTrue(json.contains("\"annularRingWithinPolicy\":false"), json);
        assertTrue(json.contains("\"annularRingViolations\":1"), json);
        assertTrue(json.contains("\"annularRingBreakout\":false"), json);
        assertTrue(json.contains("\"annularRingMeasured\":3"), json);
    }

    @Test
    void aSetWithNoDrillReportsNoRingRatherThanZero() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        file(body, "board-Edge_Cuts.gbr", "gerber", EDGE_CUTS);
        file(body, "board-F_Cu.gbr", "gerber", F_CU);

        String json = GerberViewerServer.renderToJson(body.toByteArray());
        assertTrue(json.contains("\"minAnnularRingMm\":null"), json);
        assertTrue(json.contains("\"annularRingWithinPolicy\":null"), json);
    }

    @Test
    void aPlatedHoleWithNoPadIsCountedApartFromTheRings() {
        // The same three holes plus one drilled where there is no copper at all.
        String drill = "M48\nMETRIC,TZ\n;TYPE=PLATED\nT1C0.400\n%\nT1\n"
                + "X010000Y010000\nX020000Y010000\nX030000Y010000\nX035000Y025000\nM30\n";
        String json = render(drill);

        assertTrue(json.contains("\"annularRingMeasured\":3"), json);
        assertTrue(json.contains("\"platedHolesWithoutPad\":1"), json);
    }
}
