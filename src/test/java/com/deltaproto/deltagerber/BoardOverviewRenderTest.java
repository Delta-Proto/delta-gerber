package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer.Layer;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer.Side;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MultiLayerSVGRenderer#renderBoardOverviewPng}: realistic board
 * composited over an all-layers underlay so annotation content drawn outside the
 * board outline (drill charts, fab notes) stays visible in a single image.
 */
class BoardOverviewRenderTest {

    private static Layer gerber(String file, LayerType type) throws Exception {
        String content = Files.readString(Path.of("testdata/arduino-uno/" + file));
        Layer l = new Layer(file, new GerberParser().parse(content));
        l.setLayerType(type);
        return l;
    }

    private static List<Layer> arduinoLayers() throws Exception {
        List<Layer> layers = new ArrayList<>();
        layers.add(gerber("arduino-uno.gko", LayerType.OUTLINE));
        layers.add(gerber("arduino-uno.cmp", LayerType.COPPER_TOP));
        layers.add(gerber("arduino-uno.sol", LayerType.COPPER_BOTTOM));
        layers.add(gerber("arduino-uno.stc", LayerType.SOLDERMASK_TOP));
        layers.add(gerber("arduino-uno.sts", LayerType.SOLDERMASK_BOTTOM));
        layers.add(gerber("arduino-uno.plc", LayerType.SILKSCREEN_TOP));
        Layer drill = new Layer("arduino-uno.drd",
                new ExcellonParser().parse(Files.readString(Path.of("testdata/arduino-uno/arduino-uno.drd"))));
        drill.setLayerType(LayerType.DRILL);
        layers.add(drill);
        return layers;
    }

    @Test
    void overviewRendersAtRequestedWidthWithWhiteBackground() throws Exception {
        byte[] png = new MultiLayerSVGRenderer()
                .renderBoardOverviewPng(arduinoLayers(), Side.TOP, 1200);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertEquals(1200, img.getWidth());
        assertTrue(img.getHeight() > 0);
        // white background in the corner (canvas is opaque RGB)
        assertEquals(0xFFFFFF, img.getRGB(0, 0) & 0xFFFFFF);
        // board content somewhere in the middle — not all white
        boolean nonWhite = false;
        for (int x = 0; x < img.getWidth() && !nonWhite; x += 20) {
            for (int y = 0; y < img.getHeight() && !nonWhite; y += 20) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) nonWhite = true;
            }
        }
        assertTrue(nonWhite, "overview should contain rendered content");
    }

    @Test
    void overviewWithoutOutlineDegradesToUnderlay() throws Exception {
        // Only a silkscreen layer — no outline, no copper to derive one from:
        // the realistic side fails, the overview must still return the underlay.
        List<Layer> layers = List.of(gerber("arduino-uno.plc", LayerType.SILKSCREEN_TOP));
        byte[] png = new MultiLayerSVGRenderer().renderBoardOverviewPng(layers, Side.TOP, 800);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
        assertEquals(800, img.getWidth());
    }
}
