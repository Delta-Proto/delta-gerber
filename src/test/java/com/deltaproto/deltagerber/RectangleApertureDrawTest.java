package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.GerberShapes;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;
import com.deltaproto.deltagerber.renderer.svg.SvgPathUtils;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A draw with a rectangular aperture inks the rectangle swept along the line (spec §4.9.1):
 * flat ends, square corners, never a round cap. ST draws every solder-bridge chevron on the
 * NUCLEO-L031K6 example this way, in copper, mask and paste alike — the bottom mask strokes a
 * 1.2192×1.016 mm rectangle 0.61 mm straight up from SB11's pad — and rendering it as a round
 * stroke of the larger side put a half-disc of bare laminate beside SB3 and SB11.
 */
public class RectangleApertureDrawTest {

    private static final double EPS = 1e-6;

    /** The SB11 mask stroke, in mm with the decimal point written. */
    private static final String VERTICAL = """
        %FSLAX46Y46*%
        %MOMM*%
        %ADD65R,1.2192X1.016*%
        D65*
        X14579600Y21310600D02*
        X14579600Y20701000D01*
        M02*
        """;

    private static final String DIAGONAL = """
        %FSLAX46Y46*%
        %MOMM*%
        %ADD10R,1.016X0.8128*%
        D10*
        X13563600Y21209000D02*
        X14071600Y20701000D01*
        M02*
        """;

    private static Draw only(String gerber) {
        GerberDocument doc = new GerberParser().parse(gerber);
        assertEquals(1, doc.getObjects().size());
        return (Draw) doc.getObjects().get(0);
    }

    @Test
    void verticalDrawIsBoundedByTheRectangleNotByItsLargerSide() {
        BoundingBox b = only(VERTICAL).getBoundingBox();
        assertEquals(14.5796 - 0.6096, b.getMinX(), EPS);
        assertEquals(14.5796 + 0.6096, b.getMaxX(), EPS);
        // Flat ends: half the height past each end, not half the width.
        assertEquals(20.7010 - 0.508, b.getMinY(), EPS);
        assertEquals(21.3106 + 0.508, b.getMaxY(), EPS);
    }

    @Test
    void verticalDrawRendersAsOneRectangle() {
        List<double[]> hull = SvgPathUtils.sweptRectangle(14.5796, 21.3106, 14.5796, 20.7010, 1.2192, 1.016);
        assertEquals(4, hull.size(), "an axis-aligned sweep is a rectangle");
        for (double[] pt : hull) {
            assertTrue(Math.abs(pt[0] - 14.5796) - 0.6096 < EPS);
            assertTrue(pt[1] >= 20.7010 - 0.508 - EPS && pt[1] <= 21.3106 + 0.508 + EPS);
        }
    }

    @Test
    void diagonalDrawIsAHexagonWithSquareCorners() {
        Draw d = only(DIAGONAL);
        List<double[]> hull = SvgPathUtils.sweptRectangle(d.getStartX(), d.getStartY(),
            d.getEndX(), d.getEndY(), 1.016, 0.8128);
        assertEquals(6, hull.size());
        // Every hull vertex is a corner of the rectangle at one end or the other.
        for (double[] pt : hull) {
            boolean atStart = Math.abs(Math.abs(pt[0] - d.getStartX()) - 0.508) < EPS
                && Math.abs(Math.abs(pt[1] - d.getStartY()) - 0.4064) < EPS;
            boolean atEnd = Math.abs(Math.abs(pt[0] - d.getEndX()) - 0.508) < EPS
                && Math.abs(Math.abs(pt[1] - d.getEndY()) - 0.4064) < EPS;
            assertTrue(atStart || atEnd, "vertex off the swept rectangle: " + pt[0] + "," + pt[1]);
        }
        BoundingBox b = d.getBoundingBox();
        assertEquals(21.2090 + 0.4064, b.getMaxY(), EPS, "no round cap above the start");
        assertEquals(20.7010 - 0.4064, b.getMinY(), EPS);
    }

    @Test
    void svgIsAFilledPathInBothModes() {
        Draw d = only(VERTICAL);
        for (boolean polygonize : new boolean[]{false, true}) {
            String svg = d.toSvg(new SvgOptions().setPolygonize(polygonize));
            assertTrue(svg.startsWith("<path d=\"M "), svg);
            assertFalse(svg.contains("stroke"), "a swept rectangle is a filled shape, not a stroke");
            assertFalse(svg.contains("21.920"), "the old round cap reached y = 21.9202");
            assertTrue(svg.contains("21.818600"), "the flat end sits at y = 21.8186");
        }
    }

    @Test
    void silhouetteShapeAgrees() {
        Rectangle2D r = GerberShapes.of(only(VERTICAL)).getBounds2D();
        assertEquals(14.5796 - 0.6096, r.getMinX(), 1e-4);
        assertEquals(14.5796 + 0.6096, r.getMaxX(), 1e-4);
        assertEquals(20.7010 - 0.508, r.getMinY(), 1e-4);
        assertEquals(21.3106 + 0.508, r.getMaxY(), 1e-4);
    }
}
