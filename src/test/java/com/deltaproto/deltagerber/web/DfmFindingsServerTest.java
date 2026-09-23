package com.deltaproto.deltagerber.web;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The viewer's {@code /api/gerber/render} response carries every geometric DFM figure — floating
 * copper, copper to edge, hole to copper, hole to hole, the annular ring's place — and the findings
 * list behind them, measured by the same pipeline {@code PcbAnalyzer} runs on files.
 */
class DfmFindingsServerTest {

    private static String render(String directory) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of(directory))) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path p : files) {
            byte[] body = Files.readAllBytes(p);
            String content = new String(body, StandardCharsets.ISO_8859_1);
            String type = content.contains("M48") ? "drill" : "gerber";
            String header = "FILE\t" + p.getFileName() + "\t" + type + "\tAUTO\t" + body.length + "\n";
            out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
            out.writeBytes(body);
            out.writeBytes(new byte[]{'\n'});
        }
        return GerberViewerServer.renderToJson(out.toByteArray());
    }

    @Test
    void theDeprBoardsFindingsReachTheViewer() throws IOException {
        String json = render("testdata/DEPR PR31 GBDR V04");
        // Three floating pour fragments on the top copper, one at (30.42, 69.41).
        assertTrue(json.contains("\"floatingCopperCount\":3"), json);
        assertTrue(json.contains("\"check\":\"Floating copper\",\"layer\":\"uP-H Main PCBA Assy V04.GTL\",\"valueMm\":null,\"x\":30.420,\"y\":69.408"), json);
        // The planes reach the board's edge.
        assertTrue(json.contains("\"minEdgeClearanceMm\":0.0000"), json);
        // 7.82 mil from hole to copper, 15.65 mil between holes at the connector.
        assertTrue(json.contains("\"minDrillClearanceMm\":0.1986"), json);
        assertTrue(json.contains("\"minHoleSpacingMm\":0.3975"), json);
        assertTrue(json.contains("\"minHoleSpacingAt\":{\"layer\":null,\"x\":51.600,\"y\":40.330"), json);
        // The ring's place, and floating copper left out of the clearance as the library does.
        assertTrue(json.contains("\"minAnnularRingAt\":{"), json);
        assertTrue(json.contains("\"minClearanceMm\":0.1498"), json);
    }
}
