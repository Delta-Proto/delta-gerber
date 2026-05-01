package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.*;
import com.deltaproto.deltagerber.model.gerber.aperture.*;
import com.deltaproto.deltagerber.model.gerber.aperture.MacroAperture;
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

        // Check macro template was stored and body primitives were not discarded
        MacroAperture ap10Template = (MacroAperture) doc.getAperture(10);
        assertNotNull(ap10Template);
        assertEquals("ROUNDRECT", ap10Template.getTemplateCode());
        assertEquals(5, ap10Template.getTemplate().getPrimitives().size(),
            "macro body must survive lexer tokenisation (1 rect + 4 circles)");

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

        MacroAperture ap15 = (MacroAperture) doc.getAperture(15);
        assertNotNull(ap15);
        assertEquals("TARGET", ap15.getTemplateCode());
        assertEquals(2, ap15.getTemplate().getPrimitives().size(),
            "TARGET macro must retain both circle primitives");
    }

    @Test
    void testMacroApertureDefDoesNotBakeCurrentColor() {
        // Regression: macro primitive shapes used to emit fill="currentColor" in their SVG.
        // In SVG, currentColor reads the CSS `color` property, not `fill`, so
        // <use fill="white"> in the cf-mask (copper-finish mask) left primitives black —
        // no gold showed through at RoundRect pad positions in the realistic render.
        // After the fix, toSvgDef strips fill="currentColor" so shapes inherit fill from <use>.
        String gerber = """
            %FSLAX46Y46*%
            %MOMM*%
            %AMRoundRect*
            4,1,4,$2,$3,$4,$5,$6,$7,$8,$9,$2,$3,0*
            1,1,$1+$1,$2,$3*
            20,1,$1+$1,$2,$3,$4,$5,0*%
            %ADD10RoundRect,0.150000X-0.175000X-0.200000X0.175000X-0.200000X0.175000X0.200000X-0.175000X0.200000X0*%
            D10*
            X0Y0D03*
            M02*
            """;

        MacroAperture ap = (MacroAperture) new GerberParser().parse(gerber).getAperture(10);
        String def = ap.toSvgDef("D10", new com.deltaproto.deltagerber.renderer.svg.SvgOptions());
        assertFalse(def.contains("fill=\"currentColor\""),
            "macro aperture SVG def must not contain fill=\"currentColor\" — " +
            "it prevents fill from <use> elements (e.g., cf-mask fill=\"white\") from cascading in");
        assertFalse(def.equals("<g id=\"D10\"></g>"),
            "def must contain non-empty primitive shapes");
    }

    @Test
    void testKiCadRoundRectMacroBodyNotDiscarded() {
        // Regression: the lexer used to split every %...% block by '*', which stripped
        // the aperture macro body (each statement is '*'-terminated within the block).
        // The KiCad variant uses a single %AM...body...*% block (not a bare '%' closer).
        // After the fix, all 9 primitives (1 outline polygon, 4 circles, 4 vector-lines)
        // must survive tokenisation and produce non-empty SVG defs.
        String gerber = """
            %FSLAX46Y46*%
            %MOMM*%
            %AMRoundRect*
            0 Rectangle with rounded corners*
            0 $1 Rounding radius*
            0 $2 $3 $4 $5 $6 $7 $8 $9 X,Y pos of 4 corners*
            4,1,4,$2,$3,$4,$5,$6,$7,$8,$9,$2,$3,0*
            1,1,$1+$1,$2,$3*
            1,1,$1+$1,$4,$5*
            1,1,$1+$1,$6,$7*
            1,1,$1+$1,$8,$9*
            20,1,$1+$1,$2,$3,$4,$5,0*
            20,1,$1+$1,$4,$5,$6,$7,0*
            20,1,$1+$1,$6,$7,$8,$9,0*
            20,1,$1+$1,$8,$9,$2,$3,0*%
            %ADD10RoundRect,0.150000X-0.175000X-0.200000X0.175000X-0.200000X0.175000X0.200000X-0.175000X0.200000X0*%
            D10*
            X1750000Y1750000D03*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        MacroAperture ap = (MacroAperture) doc.getAperture(10);
        assertNotNull(ap);
        assertEquals("RoundRect", ap.getTemplateCode());
        assertEquals(9, ap.getTemplate().getPrimitives().size(),
            "KiCad RoundRect: 1 outline + 4 circles + 4 vector-lines");
        assertFalse(ap.toSvgDef("D10", new com.deltaproto.deltagerber.renderer.svg.SvgOptions()).equals("<g id=\"D10\"></g>"),
            "SVG def must not be empty after macro body is parsed");
        assertEquals(1, doc.getObjects().size());
    }

    @Test
    void testMultiLineMacroPrimitiveBody() {
        // Regression: a macro primitive whose parameter list spans multiple lines
        // (like KiCad's FreePoly outline with many vertices) must be parsed correctly.
        // The newlines inside the parameter list must not confuse the split-by-'*' logic.
        String gerber = """
            %FSLAX46Y46*%
            %MOMM*%
            %AMFreePoly*
            4,1,3,
            0.100000,0.000000,
            -0.050000,0.086603,
            -0.050000,-0.086603,
            0.100000,0.000000,0*%
            %ADD10FreePoly,0*%
            D10*
            X0Y0D03*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        MacroAperture ap = (MacroAperture) doc.getAperture(10);
        assertNotNull(ap);
        assertEquals("FreePoly", ap.getTemplateCode());
        assertEquals(1, ap.getTemplate().getPrimitives().size(),
            "multi-line outline primitive must be parsed as a single primitive");
        assertEquals(1, doc.getObjects().size());
    }

    @Test
    void testCombinedFormatAndUnitBlock() {
        // Cadence Allegro style: format spec and unit in a single %...% block
        // separated by *, e.g. %FSLAX25Y25*MOIN*%
        // This tests that both commands are correctly parsed from one block.
        String gerber = """
            G04 Test combined FS+MO block*
            %FSLAX25Y25*MOIN*%
            %ADD10C,.050000*%
            %ADD11R,.060000X.040000*%
            D10*
            X50000Y50000D03*
            X150000Y50000D03*
            D11*
            X50000Y50000D01*
            X150000Y50000D01*
            X150000Y150000D01*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        // Unit should be MM after normalization, but coordinates must have been
        // converted from inches to mm during parsing
        assertEquals(Unit.MM, doc.getUnit());
        assertEquals(2, doc.getApertures().size());

        // Circle aperture: 0.05 inch = 1.27 mm
        Aperture ap10 = doc.getAperture(10);
        assertNotNull(ap10);
        assertInstanceOf(CircleAperture.class, ap10);
        assertEquals(1.27, ((CircleAperture) ap10).getDiameter(), 0.01);

        // Rectangle aperture: 0.06 x 0.04 inch = 1.524 x 1.016 mm
        Aperture ap11 = doc.getAperture(11);
        assertNotNull(ap11);
        assertInstanceOf(RectangleAperture.class, ap11);
        assertEquals(1.524, ((RectangleAperture) ap11).getWidth(), 0.01);
        assertEquals(1.016, ((RectangleAperture) ap11).getHeight(), 0.01);

        // Flash at X50000 with format 2:5 = 0.50000 inch = 12.7 mm
        // Flash at X150000 = 1.50000 inch = 38.1 mm
        BoundingBox bbox = doc.getBoundingBox();
        assertTrue(bbox.isValid());
        // Width: 38.1 - 12.7 = 25.4mm + aperture (~1.5mm) ≈ 26-27mm
        assertTrue(bbox.getWidth() > 25, "Width in mm: " + bbox.getWidth());
        assertTrue(bbox.getWidth() < 30, "Width in mm: " + bbox.getWidth());

        SVGRenderer renderer = new SVGRenderer();
        String svg = renderer.render(doc);
        assertNotNull(svg);
        assertTrue(svg.contains("<svg"));
    }

    @Test
    void testFormatSpecMissingZeroSuppressionFlag() {
        // Reproduces a real-world bug: some EDA tools (observed: Altium Designer 25.8.1)
        // emit the FS block WITHOUT the L/T zero-suppression character — e.g. "FSAX44Y44"
        // instead of the spec-compliant "FSLAX44Y44". Strict FS parsing silently dropped
        // the format spec, leaving coordFormat null, which then made every coordinate
        // throw NPE — the server catches per-file, so every Gerber layer ended up empty
        // and only the drill (different parser) appeared in the rendered SVG.
        //
        // Modern Gerber only uses L (leading zeros omitted) and A (absolute), so the
        // parser defaults to those when the flags are absent and records a warning.
        String gerber = """
            G04 Non-standard FS with no L/T flag*
            %FSAX44Y44*%
            %MOMM*%
            %ADD10C,0.1000*%
            D10*
            X00312000Y00346250D02*
            X00354250Y00304000D01*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);

        // Coordinate 00312000 in 4.4 format, leading-zeros-omitted = 31.2000 mm
        assertEquals(1, doc.getObjects().size(),
            "Missing L/T in FS spec must not drop all coordinates");
        assertInstanceOf(Draw.class, doc.getObjects().get(0));
        Draw draw = (Draw) doc.getObjects().get(0);
        assertEquals(31.2,   draw.getStartX(), 0.001);
        assertEquals(34.625, draw.getStartY(), 0.001);
        assertEquals(35.425, draw.getEndX(),   0.001);
        assertEquals(30.4,   draw.getEndY(),   0.001);

        // A warning should be surfaced so callers can flag the non-standard file
        assertTrue(doc.getWarnings().stream().anyMatch(w -> w.contains("FS spec")),
            "Expected a warning about non-standard FS spec, got: " + doc.getWarnings());
    }

    @Test
    void testFormatSpecMissingBothFlags() {
        // Belt-and-braces: a hypothetical FS with neither L/T nor A/I still parses,
        // defaulting to L (leading-zero-omitted) + A (absolute) per modern Gerber.
        String gerber = """
            %FSX23Y23*%
            %MOMM*%
            %ADD10C,0.1*%
            D10*
            X1000Y2000D03*
            M02*
            """;

        GerberDocument doc = parser.parse(gerber);
        assertEquals(1, doc.getObjects().size());
        // 2:3 leading-zero-omitted: 1000 = 01.000 mm, 2000 = 02.000 mm
        Flash flash = (Flash) doc.getObjects().get(0);
        assertEquals(1.0, flash.getX(), 0.001);
        assertEquals(2.0, flash.getY(), 0.001);
    }
}
