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
