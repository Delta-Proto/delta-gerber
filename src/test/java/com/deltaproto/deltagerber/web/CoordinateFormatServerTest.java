package com.deltaproto.deltagerber.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The viewer's {@code /api/gerber/render} response carries the coordinate format of every file, so
 * the PCB info panel — and any host application built on {@link GerberViewerServer#renderToJson} —
 * can show the "4:3, leading zeros suppressed" a fabricator asks for, and say whether the drill
 * program was exported in the same terms as the artwork.
 */
class CoordinateFormatServerTest {

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

    private static final String METRIC_DRILL =
            "M48\nMETRIC,TZ\n;FILE_FORMAT=3:3\nT1C0.300\n%\nT1\nX010000Y010000\nM30\n";

    private static final String INCH_DRILL =
            "M48\nINCH,LZ\nT1C0.0118\n%\nT1\nX0019685Y0019685\nM30\n";

    /** Build one length-prefixed file record for the viewer request protocol. */
    private static void file(ByteArrayOutputStream out, String name, String type, String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(("FILE\t" + name + "\t" + type + "\tAUTO\t" + body.length + "\n")
                .getBytes(StandardCharsets.UTF_8));
        out.writeBytes(body);
        out.writeBytes(new byte[]{'\n'});
    }

    private static String render(String drill) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        file(body, "board-Edge_Cuts.gbr", "gerber", EDGE_CUTS);
        file(body, "board-PTH.drl", "drill", drill);
        return GerberViewerServer.renderToJson(body.toByteArray());
    }

    @Test
    void renderJsonReportsTheFormatsAndTheirAlignment() {
        String json = render(METRIC_DRILL);

        assertTrue(json.contains("\"gerberFormat\":{\"digits\":\"4:6\""), json);
        assertTrue(json.contains("\"drillFormat\":{\"digits\":\"3:3\""), json);
        assertTrue(json.contains("\"zeroSuppression\":\"LEADING\""), json);
        assertTrue(json.contains("\"formatConsistent\":true"), json);
        // The parts as well as the sentence — a caller reading this as an API should not have to
        // parse the wording back apart.
        assertTrue(json.contains("\"text\":\"4:6 mm, leading zeros suppressed\""), json);
        // And per file, which is the only way to see which one differs when they disagree.
        assertTrue(json.contains("\"format\":{\"digits\":\"3:3\""), json);
    }

    @Test
    void renderJsonFlagsADrillExportedInAnotherUnit() {
        String json = render(INCH_DRILL);

        assertTrue(json.contains("\"formatConsistent\":false"), json);
        assertTrue(json.contains("\"unit\":\"INCH\""), json);
        // Excellon does not require a format, and this file states none: the digits are the
        // library's assumption and the response says so rather than passing them off as declared.
        assertTrue(json.contains("\"declared\":false"), json);
    }
}
