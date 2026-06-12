package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.DrillSVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.SVGRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the per-layer PNG convenience methods {@link SVGRenderer#renderPng} and
 * {@link DrillSVGRenderer#renderPng} — single-layer SVG render piped through the
 * shared Batik rasteriser. Used by consumers that feed individual layers (fab
 * drawings, drill legends) to vision models.
 */
class PerLayerPngRenderTest {

    private static BufferedImage decode(byte[] png) throws Exception {
        assertNotNull(png, "renderPng returned null");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img, "output was not a readable PNG");
        return img;
    }

    @Test
    void gerberLayerRendersToPngAtRequestedWidthWithDocumentAspect() throws Exception {
        String content = Files.readString(Path.of("testdata/arduino-uno/arduino-uno.cmp"));
        GerberDocument doc = new GerberParser().parse(content);

        double margin = 0.5;
        byte[] png = new SVGRenderer()
            .setBackgroundColor("#ffffff")
            .setMargin(margin)
            .renderPng(doc, 800);

        BufferedImage img = decode(png);
        assertEquals(800, img.getWidth());

        // Height must follow the document's own aspect ratio — the SVG carries no
        // width/height attributes, so without explicit derivation Batik would fall
        // back to its 400px default viewport (regression guard).
        double aspect = (doc.getBoundingBox().getWidth() + 2 * margin)
                / (doc.getBoundingBox().getHeight() + 2 * margin);
        int expectedHeight = (int) Math.round(800 / aspect);
        assertEquals(expectedHeight, img.getHeight(), 1.0);
    }

    @Test
    void gerberLayerExplicitDimensions() throws Exception {
        String content = Files.readString(Path.of("testdata/arduino-uno/arduino-uno.cmp"));
        GerberDocument doc = new GerberParser().parse(content);

        BufferedImage img = decode(new SVGRenderer().renderPng(doc, 640, 480));
        assertEquals(640, img.getWidth());
        assertEquals(480, img.getHeight());
    }

    @Test
    void drillFileRendersToPng() throws Exception {
        String content = Files.readString(Path.of("testdata/arduino-uno/arduino-uno.drd"));
        DrillDocument doc = new ExcellonParser().parse(content);

        BufferedImage img = decode(new DrillSVGRenderer()
            .setBackgroundColor("#ffffff")
            .renderPng(doc, 800));
        assertEquals(800, img.getWidth());
        assertTrue(img.getHeight() > 0);
    }

    @Test
    void transparentBackgroundByDefault() throws Exception {
        String content = Files.readString(Path.of("testdata/arduino-uno/arduino-uno.cmp"));
        GerberDocument doc = new GerberParser().parse(content);

        BufferedImage img = decode(new SVGRenderer().renderPng(doc, 200));
        // corner pixel outside any drawn object should be fully transparent
        int alpha = (img.getRGB(0, 0) >>> 24);
        assertEquals(0, alpha, "background should be transparent when no background color is set");
    }
}
