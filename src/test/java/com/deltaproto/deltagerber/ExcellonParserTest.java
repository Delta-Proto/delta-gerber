package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.drill.*;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Unit;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.renderer.svg.DrillSVGRenderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the ExcellonParser.
 */
public class ExcellonParserTest {

    private final ExcellonParser parser = new ExcellonParser();

    @Test
    void platingIsReadPerToolFromTheTypeComment() {
        // Altium writes one drill file holding both, switching partway down the tool table. Plating
        // therefore belongs to the tool, not to the file — an annular ring is asked of the plated
        // holes and not of the mounting hole below them.
        String drill = """
            M48
            METRIC,LZ
            ;TYPE=PLATED
            T1C0.300
            T2C0.800
            ;TYPE=NON_PLATED
            T3C3.200
            %
            T1
            X10000Y10000
            T3
            X50000Y50000
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertEquals(Boolean.TRUE, doc.getTool(1).getPlated());
        assertEquals(Boolean.TRUE, doc.getTool(2).getPlated());
        assertEquals(Boolean.FALSE, doc.getTool(3).getPlated());
    }

    @Test
    void aFileThatStatesNoPlatingLeavesItUnknown() {
        String drill = """
            M48
            METRIC,LZ
            T1C0.800
            %
            T1
            X10000Y10000
            M30
            """;

        assertNull(parser.parse(drill).getTool(1).getPlated());
    }

    @Test
    void testParseBasicDrill() {
        String drill = """
            M48
            METRIC,LZ
            T1C0.8
            T2C1.0
            %
            T1
            X10000Y10000
            X20000Y10000
            T2
            X15000Y20000
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertNotNull(doc);
        assertEquals(Unit.MM, doc.getUnit());

        // Check tools
        assertEquals(2, doc.getTools().size());
        Tool t1 = doc.getTool(1);
        assertNotNull(t1);
        assertEquals(0.8, t1.getDiameter(), 0.001);

        Tool t2 = doc.getTool(2);
        assertNotNull(t2);
        assertEquals(1.0, t2.getDiameter(), 0.001);

        // Check operations
        assertEquals(3, doc.getOperations().size());
    }

    @Test
    void testParseInchFormat() {
        String drill = """
            M48
            INCH,TZ
            T1C0.035
            %
            T1
            X1000Y1000
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        // Unit is normalized to MM after parsing
        assertEquals(Unit.MM, doc.getUnit());
        assertFalse(doc.isLeadingZeros());

        // Tool diameter should be converted: 0.035 inch = 0.889 mm
        assertEquals(0.889, doc.getTool(1).getDiameter(), 0.001);

        // Coordinate: TZ format, 2.4: "1000" padded to "001000" -> 00.1000 = 0.1 inch = 2.54 mm
        DrillHit hit = (DrillHit) doc.getOperations().get(0);
        assertEquals(2.54, hit.getX(), 0.01);
        assertEquals(2.54, hit.getY(), 0.01);
    }

    @Test
    void testParseDrillHit() {
        String drill = """
            M48
            METRIC
            T1C1.0
            %
            T1
            X1.5Y2.5
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertEquals(1, doc.getOperations().size());
        DrillOperation op = doc.getOperations().get(0);
        assertInstanceOf(DrillHit.class, op);

        DrillHit hit = (DrillHit) op;
        assertEquals(1.5, hit.getX(), 0.001);
        assertEquals(2.5, hit.getY(), 0.001);
    }

    @Test
    void testParseSlot() {
        String drill = """
            M48
            METRIC
            T1C1.0
            %
            T1
            X1.0Y1.0G85X5.0Y1.0
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertEquals(1, doc.getOperations().size());
        DrillOperation op = doc.getOperations().get(0);
        assertInstanceOf(DrillSlot.class, op);

        DrillSlot slot = (DrillSlot) op;
        assertEquals(1.0, slot.getStartX(), 0.001);
        assertEquals(1.0, slot.getStartY(), 0.001);
        assertEquals(5.0, slot.getEndX(), 0.001);
        assertEquals(1.0, slot.getEndY(), 0.001);
    }

    @Test
    void testBoundingBox() {
        String drill = """
            M48
            METRIC
            T1C1.0
            %
            T1
            X0.0Y0.0
            X10.0Y5.0
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        BoundingBox bounds = doc.getBoundingBox();
        assertTrue(bounds.isValid());
        // Width should be 10 + tool diameter
        assertEquals(11.0, bounds.getWidth(), 0.1);
        // Height should be 5 + tool diameter
        assertEquals(6.0, bounds.getHeight(), 0.1);
    }

    @Test
    void testRenderToSvg() {
        String drill = """
            M48
            METRIC
            T1C1.0
            %
            T1
            X5.0Y5.0
            M30
            """;

        DrillDocument doc = parser.parse(drill);
        DrillSVGRenderer renderer = new DrillSVGRenderer();
        String svg = renderer.render(doc);

        assertNotNull(svg);
        assertTrue(svg.contains("<svg"));
        assertTrue(svg.contains("</svg>"));
        assertTrue(svg.contains("circle"));
    }

    @Test
    void testParseWithComments() {
        String drill = """
            ; This is a drill file
            M48
            ; Tool definitions
            METRIC
            T1C0.8
            %
            T1
            ; First hole
            X1.0Y1.0
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertEquals(3, doc.getComments().size());
        assertEquals("This is a drill file", doc.getComments().get(0));
    }

    @Test
    void testFileFormatComment44() {
        // Altium-style drill file with ;FILE_FORMAT=4:4 comment
        // The METRIC,LZ line would normally set 3:3 format, but the explicit
        // FILE_FORMAT comment should take precedence
        String drill = """
            M48
            ;FILE_FORMAT=4:4
            METRIC,LZ
            T01F00S00C0.2500
            %
            T01
            X00191614Y-00218386
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertEquals(Unit.MM, doc.getUnit());
        assertEquals(4, doc.getIntegerDigits());
        assertEquals(4, doc.getDecimalDigits());
        assertTrue(doc.isLeadingZeros());

        assertEquals(1, doc.getOperations().size());
        DrillHit hit = (DrillHit) doc.getOperations().get(0);
        // With 4:4 format and LZ: X00191614 = 0019.1614 = 19.1614
        assertEquals(19.1614, hit.getX(), 0.0001);
        // Y-00218386 = -0021.8386
        assertEquals(-21.8386, hit.getY(), 0.0001);
    }

    @Test
    void testFileFormatCommentNotOverriddenByMetric() {
        // Verify that FILE_FORMAT comment is not overridden by subsequent METRIC line
        // This was the original bug: METRIC,LZ always set 3:3 format, causing
        // 4:4 coordinates to be parsed 10x too large
        String drill = """
            M48
            ;FILE_FORMAT=4:4
            METRIC,LZ
            T01F00S00C0.5000
            %
            T01
            X00100000Y00100000
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        DrillHit hit = (DrillHit) doc.getOperations().get(0);
        // With 4:4 format: X00100000 = 0010.0000 = 10.0 mm
        // Bug would give 3:3 format: X00100000 -> 00100.000 = 100.0 mm (10x too large!)
        assertEquals(10.0, hit.getX(), 0.0001);
        assertEquals(10.0, hit.getY(), 0.0001);
    }

    @Test
    void testMetricDefaultFormat33WithoutFileFormat() {
        // Without FILE_FORMAT comment, METRIC should still default to 3:3
        String drill = """
            M48
            METRIC,LZ
            T1C0.8
            %
            T1
            X010000Y010000
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertEquals(3, doc.getIntegerDigits());
        assertEquals(3, doc.getDecimalDigits());

        DrillHit hit = (DrillHit) doc.getOperations().get(0);
        // With 3:3 format: X010000 = 010.000 = 10.0 mm
        assertEquals(10.0, hit.getX(), 0.0001);
        assertEquals(10.0, hit.getY(), 0.0001);
    }

    @Test
    void testCadenceAllegroHolesizeFormat() {
        // Cadence Allegro format: tools defined via Holesize comments,
        // M00 separates tool groups, repeat codes for stepped holes
        String drill = """
            ;   Holesize 1. = 0.200000 Tolerance = +0.000000/-0.000000 PLATED MM Quantity = 3
            ;   Holesize 2. = 0.350000 Tolerance = +0.000000/-0.000000 PLATED MM Quantity = 5
            %
            G90
            X00100000Y00200000
            X00300000Y00400000
            X00500000Y00600000
            M00
            X01000000Y01000000
            R02X00100000
            X02000000Y02000000
            X03000000Y03000000
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertEquals(Unit.MM, doc.getUnit());
        assertEquals(3, doc.getIntegerDigits());
        assertEquals(5, doc.getDecimalDigits());

        // Should have 2 tools from Holesize comments
        assertEquals(2, doc.getTools().size());
        assertEquals(0.2, doc.getTool(1).getDiameter(), 0.001);
        assertEquals(0.35, doc.getTool(2).getDiameter(), 0.001);

        // Tool 1: 3 hits, Tool 2: 3 direct + 2 repeat = 5 hits, total = 8
        assertEquals(8, doc.getOperations().size());

        // First 3 hits should use tool 1
        DrillHit hit1 = (DrillHit) doc.getOperations().get(0);
        assertEquals(0.2, hit1.getTool().getDiameter(), 0.001);
        // X00100000 with 3:5 = 001.00000 = 1.0mm
        assertEquals(1.0, hit1.getX(), 0.001);
        // Y00200000 with 3:5 = 002.00000 = 2.0mm
        assertEquals(2.0, hit1.getY(), 0.001);

        // After M00, tool 2 should be selected
        DrillHit hit4 = (DrillHit) doc.getOperations().get(3);
        assertEquals(0.35, hit4.getTool().getDiameter(), 0.001);
        // X01000000 = 010.00000 = 10.0mm
        assertEquals(10.0, hit4.getX(), 0.001);

        // Repeat code R02X00100000: repeat 2 times with X offset 001.00000 = 1.0mm
        DrillHit hit5 = (DrillHit) doc.getOperations().get(4);
        assertEquals(11.0, hit5.getX(), 0.001);
        assertEquals(10.0, hit5.getY(), 0.001);

        DrillHit hit6 = (DrillHit) doc.getOperations().get(5);
        assertEquals(12.0, hit6.getX(), 0.001);
        assertEquals(10.0, hit6.getY(), 0.001);
    }

    @Test
    void testCadenceAllegroMultiToolWithRepeat() {
        // Realistic Cadence Allegro drill file with 3 tool groups separated by M00,
        // repeat codes, and 3:5 metric format — no header, no tool select commands
        String drill = """
            ;   Holesize 1. = 0.350000 Tolerance = +0.000000/-0.000000 PLATED MM Quantity = 4
            ;   Holesize 2. = 1.000000 Tolerance = +0.000000/-0.000000 PLATED MM Quantity = 6
            ;   Holesize 3. = 3.200000 Tolerance = +0.000000/-0.000000 NON_PLATED MM Quantity = 4
            %
            G90
            X03960000Y32500000
            X07015000Y33110000
            X13982500Y33196000
            X14262400Y32740000
            M00
            X01000000Y01000000
            R02X00550000
            X05000000Y02000000
            R02X00550000
            M00
            X00491000Y16145000
            X07349000Y16145000
            X00491000Y08195000
            X07349000Y08195000
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertEquals(Unit.MM, doc.getUnit());
        assertEquals(3, doc.getIntegerDigits());
        assertEquals(5, doc.getDecimalDigits());
        assertEquals(3, doc.getTools().size());

        // Verify tool diameters
        assertEquals(0.35, doc.getTool(1).getDiameter(), 0.001);
        assertEquals(1.0, doc.getTool(2).getDiameter(), 0.001);
        assertEquals(3.2, doc.getTool(3).getDiameter(), 0.001);

        // Tool 1: 4 hits, Tool 2: 2 + 2*R02 = 6 hits, Tool 3: 4 hits = total 14
        assertEquals(14, doc.getOperations().size());

        // Verify first tool 1 hit: X03960000 = 039.60000 = 39.6mm
        DrillHit h1 = (DrillHit) doc.getOperations().get(0);
        assertEquals(0.35, h1.getTool().getDiameter(), 0.001);
        assertEquals(39.6, h1.getX(), 0.001);
        assertEquals(325.0, h1.getY(), 0.001);

        // Tool 2 starts after M00 (4 hits from tool 1)
        DrillHit h5 = (DrillHit) doc.getOperations().get(4);
        assertEquals(1.0, h5.getTool().getDiameter(), 0.001);
        assertEquals(10.0, h5.getX(), 0.001);
        assertEquals(10.0, h5.getY(), 0.001);

        // Repeat code: R02X00550000 = repeat 2 times with X+5.5mm
        DrillHit h6 = (DrillHit) doc.getOperations().get(5);
        assertEquals(15.5, h6.getX(), 0.001);
        assertEquals(10.0, h6.getY(), 0.001);
        DrillHit h7 = (DrillHit) doc.getOperations().get(6);
        assertEquals(21.0, h7.getX(), 0.001);

        // Tool 3 after second M00
        DrillHit h11 = (DrillHit) doc.getOperations().get(10);
        assertEquals(3.2, h11.getTool().getDiameter(), 0.001);

        // Bounding box should be reasonable
        BoundingBox bbox = doc.getBoundingBox();
        assertTrue(bbox.isValid());
        assertTrue(bbox.getWidth() > 50);
        assertTrue(bbox.getHeight() > 200);

        // SVG renders successfully
        DrillSVGRenderer renderer = new DrillSVGRenderer();
        String svg = renderer.render(doc);
        assertNotNull(svg);
        assertTrue(svg.contains("<svg"));
        assertTrue(svg.contains("circle"));
    }

    @Test
    void testCoordinateParsing() {
        // Test implicit decimal point based on format
        String drill = """
            M48
            METRIC,LZ
            2.4
            T1C1.0
            %
            T1
            X15000Y25000
            M30
            """;

        DrillDocument doc = parser.parse(drill);

        assertEquals(1, doc.getOperations().size());
        DrillHit hit = (DrillHit) doc.getOperations().get(0);
        // With LZ format and 2.4, 15000 = 15.0000, 25000 = 25.0000
        assertEquals(15.0, hit.getX(), 0.1);
        assertEquals(25.0, hit.getY(), 0.1);
    }
}
