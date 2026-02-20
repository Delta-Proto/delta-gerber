package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.*;
import com.deltaproto.deltagerber.model.gerber.aperture.*;
import com.deltaproto.deltagerber.model.gerber.operation.*;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.SVGRenderer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the GerberParser.
 */
public class GerberParserTest {

    private final GerberParser parser = new GerberParser();

    @Test
    void testParseBasicGerber() {
        String gerber = """
            G04 Test file*
            %FSLAX26Y26*%
            %MOMM*%
            %ADD10C,0.5*%
            D10*
            X1000000Y1000000D03*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        assertNotNull(doc);
        assertEquals(Unit.MM, doc.getUnit());

        // Check aperture
        Aperture ap10 = doc.getAperture(10);
        assertNotNull(ap10);
        assertInstanceOf(CircleAperture.class, ap10);
        assertEquals(0.5, ((CircleAperture) ap10).getDiameter(), 0.001);

        // Check that we have a flash operation
        assertEquals(1, doc.getObjects().size());
        assertInstanceOf(Flash.class, doc.getObjects().get(0));
    }

    @Test
    void testParseRectangleAperture() {
        String gerber = """
            %FSLAX26Y26*%
            %MOMM*%
            %ADD11R,1.0X0.5*%
            D11*
            X0Y0D03*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        Aperture ap11 = doc.getAperture(11);
        assertNotNull(ap11);
        assertInstanceOf(RectangleAperture.class, ap11);
        RectangleAperture rect = (RectangleAperture) ap11;
        assertEquals(1.0, rect.getWidth(), 0.001);
        assertEquals(0.5, rect.getHeight(), 0.001);
    }

    @Test
    void testParseDraw() {
        String gerber = """
            %FSLAX26Y26*%
            %MOMM*%
            %ADD10C,0.1*%
            D10*
            X0Y0D02*
            G01*
            X1000000Y1000000D01*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        assertEquals(1, doc.getObjects().size());
        assertInstanceOf(Draw.class, doc.getObjects().get(0));
        Draw draw = (Draw) doc.getObjects().get(0);
        assertEquals(0, draw.getStartX(), 0.001);
        assertEquals(0, draw.getStartY(), 0.001);
        assertEquals(1.0, draw.getEndX(), 0.001);
        assertEquals(1.0, draw.getEndY(), 0.001);
    }

    @Test
    void testParseRegion() {
        String gerber = """
            %FSLAX26Y26*%
            %MOMM*%
            G36*
            X0Y0D02*
            G01*
            X1000000Y0D01*
            X1000000Y1000000D01*
            X0Y1000000D01*
            X0Y0D01*
            G37*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        assertEquals(1, doc.getObjects().size());
        assertInstanceOf(Region.class, doc.getObjects().get(0));
        Region region = (Region) doc.getObjects().get(0);
        assertTrue(region.getContours().size() > 0);
    }

    @Test
    void testParseFileAttribute() {
        String gerber = """
            %FSLAX26Y26*%
            %MOMM*%
            %TF.FileFunction,Copper,L1,Top*%
            %TF.GenerationSoftware,KiCad,Pcbnew,8.0*%
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        assertEquals("Copper", doc.getFileFunction());
        assertTrue(doc.getGenerationSoftware().contains("KiCad"));
    }

    @Test
    void testParseKiCadCommentAttribute() {
        String gerber = """
            G04 #@! TF.FileFunction,Copper,L1,Top*
            G04 #@! TF.GenerationSoftware,KiCad,Pcbnew,8.0*
            %FSLAX26Y26*%
            %MOMM*%
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        assertEquals("Copper", doc.getFileFunction());
        assertTrue(doc.getGenerationSoftware().contains("KiCad"));
    }

    @Test
    void testRenderToSvg() {
        String gerber = """
            %FSLAX26Y26*%
            %MOMM*%
            %ADD10C,0.5*%
            D10*
            X1000000Y1000000D03*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);
        SVGRenderer renderer = new SVGRenderer();
        String svg = renderer.render(doc);

        assertNotNull(svg);
        assertTrue(svg.contains("<svg"));
        assertTrue(svg.contains("</svg>"));
        assertTrue(svg.contains("viewBox"));
    }

    @Test
    void testBoundingBox() {
        String gerber = """
            %FSLAX26Y26*%
            %MOMM*%
            %ADD10C,0.5*%
            D10*
            X0Y0D03*
            X10000000Y5000000D03*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        BoundingBox bounds = doc.getBoundingBox();
        assertTrue(bounds.isValid());
        assertEquals(10.0, bounds.getWidth(), 0.5);
        assertEquals(5.0, bounds.getHeight(), 0.5);
    }

    @Test
    void testParseMacroAperture() {
        String gerber = """
            %FSLAX26Y26*%
            %MOMM*%
            %AMROUNDRECT*
            21,1,$1,$2,0,0,0*
            1,1,$3,$1/2-$3/2,0*
            1,1,$3,-$1/2+$3/2,0*
            1,1,$3,0,$2/2-$3/2*
            1,1,$3,0,-$2/2+$3/2*
            %
            %ADD10ROUNDRECT,2.0X1.0X0.25*%
            D10*
            X1000000Y1000000D03*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        // Check macro template was stored
        assertNotNull(doc.getMacroTemplate("ROUNDRECT"));

        // Check aperture was created
        Aperture ap10 = doc.getAperture(10);
        assertNotNull(ap10);
        assertEquals("ROUNDRECT", ap10.getTemplateCode());

        // Check we have a flash operation
        assertEquals(1, doc.getObjects().size());
    }

    @Test
    void testParseMacroWithCircle() {
        String gerber = """
            %FSLAX26Y26*%
            %MOMM*%
            %AMTARGET*
            1,1,0.5,0,0*
            1,0,0.25,0,0*
            %
            %ADD15TARGET*%
            D15*
            X0Y0D03*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        assertNotNull(doc.getMacroTemplate("TARGET"));
        Aperture ap15 = doc.getAperture(15);
        assertNotNull(ap15);
        assertEquals("TARGET", ap15.getTemplateCode());
    }
}
