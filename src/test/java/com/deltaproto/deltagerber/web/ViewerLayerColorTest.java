package com.deltaproto.deltagerber.web;

import com.deltaproto.deltagerber.renderer.svg.LayerPalette;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The viewer colours a layer by what the classifier (or the user) says it is, never by its file
 * name. The shipped NUCLEO-L031K6 example is the case that showed the difference: its files are
 * named in words ("top layer.gbr", "bottom solder mask.gbr"), classify perfectly from their X2
 * attributes, and still came back in the file-name lookup's fallback grey — except the two masks,
 * which "solder" made bottom-copper blue through the EAGLE {@code .sol} fragment.
 */
class ViewerLayerColorTest {

    private static final Pattern LAYER = Pattern.compile(
        "\\{\"name\":\"([^\"]+)\",\"id\":\"[^\"]*\",\"color\":\"([^\"]+)\",\"type\":\"[^\"]*\",\"layerType\":\"([^\"]+)\",\"layerNumber\":(null|\\d+)}");

    /** name → [color, layerType, layerNumber] from the render response. */
    private static Map<String, String[]> layersOf(String json) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Matcher m = LAYER.matcher(json);
        while (m.find()) {
            out.put(m.group(1), new String[]{m.group(2), m.group(3), m.group(4)});
        }
        return out;
    }

    private static byte[] exampleRequest(String retypedFile, String retypedAs, String number) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = GerberViewerServer.class.getResourceAsStream("/web/nucleo-l031k6-example.zip");
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (!(name.endsWith(".gbr") || name.endsWith(".exc"))) continue;
                byte[] body = zip.readAllBytes();
                String type = name.endsWith(".exc") ? "drill" : "gerber";
                String layerType = name.equals(retypedFile) ? retypedAs : "AUTO";
                String header = "FILE\t" + name + "\t" + type + "\t" + layerType + "\t" + body.length
                    + (name.equals(retypedFile) && number != null ? "\t" + number : "") + "\n";
                out.writeBytes(header.getBytes(StandardCharsets.UTF_8));
                out.writeBytes(body);
                out.writeBytes(new byte[]{'\n'});
            }
        }
        return out.toByteArray();
    }

    @Test
    void theExampleSetIsColoredByTypeAndNoTwoLayersAlike() throws IOException {
        Map<String, String[]> layers = layersOf(GerberViewerServer.renderToJson(exampleRequest(null, null, null)));
        assertEquals(13, layers.size(), layers.keySet().toString());

        Map<String, String> owners = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : layers.entrySet()) {
            String name = e.getKey(), color = e.getValue()[0], type = e.getValue()[1];
            assertNotEquals(LayerPalette.UNKNOWN, color, name + " classified as " + type + " but drawn as unknown");
            assertNotEquals("#aaaaaa", color, name);
            String previous = owners.put(color, name);
            // The two drill files are one program split in two and are the one allowed pair.
            if (previous != null) {
                assertTrue(name.endsWith(".exc") && previous.endsWith(".exc"),
                    name + " and " + previous + " are both " + color);
            }
        }
        assertEquals(LayerPalette.colorOf(LayerType.COPPER_TOP, null), layers.get("top layer.gbr")[0]);
        assertEquals(LayerPalette.colorOf(LayerType.COPPER_BOTTOM, null), layers.get("bottom layer.gbr")[0]);
        assertEquals(LayerPalette.colorOf(LayerType.SOLDERMASK_BOTTOM, null), layers.get("bottom solder mask.gbr")[0],
            "'solder' must not read as EAGLE's .sol bottom copper");
        assertEquals(LayerPalette.colorOf(LayerType.COPPER_INNER, 1), layers.get("inner1 layer.gbr")[0]);
        assertEquals(LayerPalette.colorOf(LayerType.COPPER_INNER, 2), layers.get("inner2 layer.gbr")[0]);
        assertEquals(LayerPalette.colorOf(LayerType.OUTLINE, null), layers.get("board outline.gbr")[0]);
    }

    @Test
    void aRetypedLayerChangesColorWithItsType() throws IOException {
        // The user calls the top silkscreen inner copper 2: it takes In2's colour, and the dot
        // the viewer shows (this JSON) and the SVG's fill (the Layer's colour) are set together.
        String json = GerberViewerServer.renderToJson(exampleRequest("top silkscreen.gbr", "COPPER_INNER", "2"));
        String[] retyped = layersOf(json).get("top silkscreen.gbr");
        assertEquals("COPPER_INNER", retyped[1]);
        assertEquals("2", retyped[2]);
        assertEquals(LayerPalette.colorOf(LayerType.COPPER_INNER, 2), retyped[0]);
        // The SVG travels inside the JSON string, quotes escaped.
        assertTrue(json.contains("id=\\\"top_silkscreen.gbr\\\" display=\\\"inline\\\" color=\\\"" + retyped[0] + "\\\""),
            "the combined SVG fills the layer in the same colour");
    }
}
