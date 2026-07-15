package com.deltaproto.deltagerber.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The viewer's {@code /api/gerber/render} response carries the via-in-pad verdict so the frontend
 * (and any host application built on {@link GerberViewerServer#renderToJson}) can surface the
 * IPC-4761 Type VII requirement without re-analysing the set.
 */
class ViaInPadServerTest {

    private static final String F_PASTE = String.join("\n",
            "%FSLAX46Y46*%",
            "%MOMM*%",
            "%ADD10C,1.000000*%",
            "D10*",
            "X10000000Y10000000D03*",
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

    private static final String DRILL = "M48\nMETRIC,TZ\nT1C0.300\n%\nT1\nX010000Y010000\nM30\n";

    /** Build one length-prefixed file record for the viewer request protocol. */
    private static void file(ByteArrayOutputStream out, String name, String type, String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        String header = "FILE\t" + name + "\t" + type + "\tAUTO\t" + body.length + "\n";
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(h);
        out.writeBytes(body);
        out.writeBytes(new byte[]{'\n'});
    }

    @Test
    void renderJsonReportsViaInPad() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        file(body, "board-Edge_Cuts.gbr", "gerber", EDGE_CUTS);
        file(body, "board-F_Paste.gbr", "gerber", F_PASTE);
        file(body, "board-PTH.drl", "drill", DRILL);

        String json = GerberViewerServer.renderToJson(body.toByteArray());
        assertTrue(json.contains("\"hasViaInPad\":true"), json);
        assertTrue(json.contains("\"viaInPadCount\":1"), json);
        assertTrue(json.contains("\"viaInPadSide\":\"TOP\""), json);
    }
}
