package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the raster-size clamp in {@link MultiLayerSVGRenderer#rasterizeSvgToPng}.
 *
 * Batik allocates width*height*4 bytes for the output raster. Before the clamp, a board with
 * a pathological viewBox aspect ratio (e.g. an outline-less board whose viewBox fell back to
 * the union of all layers' bounds, inflated by a stray flash) drove the derived dimension into
 * the hundreds of thousands of pixels — a single thumbnail render allocated 3+ GB and OOM'd the
 * backend. These tests assert the rasteriser caps both dimensions and the total pixel count.
 */
class RasterizeClampTest {

    // Keep in sync with MultiLayerSVGRenderer's private caps.
    private static final int  MAX_DIM    = 8192;
    private static final long MAX_PIXELS = 16_000_000L;

    private static int[] pngSize(byte[] png) throws Exception {
        assertNotNull(png, "rasteriser returned null");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img, "output was not a readable PNG");
        return new int[]{img.getWidth(), img.getHeight()};
    }

    @Test
    void explicitOversizeDimensionsAreClampedAndAreaBounded() throws Exception {
        // A normal small viewBox, but caller asks for an absurd 1024 x 757_000 raster
        // (the shape produced by deriving height from a ~740:1 aspect ratio). Pre-fix this
        // demanded ~3 GB; it must now come back clamped.
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\" "
                + "preserveAspectRatio=\"xMidYMid meet\">"
                + "<rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"#004200\"/></svg>";

        int[] wh = pngSize(MultiLayerSVGRenderer.rasterizeSvgToPng(svg, 1024, 757_000));
        assertTrue(wh[0] <= MAX_DIM, "width not clamped: " + wh[0]);
        assertTrue(wh[1] <= MAX_DIM, "height not clamped: " + wh[1]);
        assertTrue((long) wh[0] * wh[1] <= MAX_PIXELS,
                "raster area exceeds cap: " + wh[0] + "x" + wh[1]);
    }

    @Test
    void widthOnlyCallerWithPathologicalAspectIsBackstoppedByKeyMax() throws Exception {
        // Only width is given; Batik derives height from the SVG's own 1:740 aspect ratio,
        // which would explode to ~757k px. KEY_MAX_HEIGHT must bound it.
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 740\" "
                + "preserveAspectRatio=\"xMidYMid meet\">"
                + "<rect x=\"0\" y=\"0\" width=\"1\" height=\"740\" fill=\"#004200\"/></svg>";

        int[] wh = pngSize(MultiLayerSVGRenderer.rasterizeSvgToPng(svg, 1024));
        assertTrue(wh[1] <= MAX_DIM, "derived height not backstopped: " + wh[1]);
        assertTrue((long) wh[0] * wh[1] <= (long) MAX_DIM * MAX_DIM,
                "raster area exceeds backstop: " + wh[0] + "x" + wh[1]);
    }

    @Test
    void normalSizedRequestPassesThroughUnchanged() throws Exception {
        // A reasonable request must not be altered by the clamp.
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 50\" "
                + "preserveAspectRatio=\"xMidYMid meet\">"
                + "<rect x=\"0\" y=\"0\" width=\"100\" height=\"50\" fill=\"#004200\"/></svg>";

        int[] wh = pngSize(MultiLayerSVGRenderer.rasterizeSvgToPng(svg, 1024, 512));
        assertEquals(1024, wh[0]);
        assertEquals(512, wh[1]);
    }
}
