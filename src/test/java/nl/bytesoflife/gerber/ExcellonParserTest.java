package nl.bytesoflife.gerber;

import nl.bytesoflife.gerber.model.drill.*;
import nl.bytesoflife.gerber.model.gerber.BoundingBox;
import nl.bytesoflife.gerber.model.gerber.Unit;
import nl.bytesoflife.gerber.parser.ExcellonParser;
import nl.bytesoflife.gerber.renderer.svg.DrillSVGRenderer;

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

        assertEquals(Unit.INCH, doc.getUnit());
        assertFalse(doc.isLeadingZeros());
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
