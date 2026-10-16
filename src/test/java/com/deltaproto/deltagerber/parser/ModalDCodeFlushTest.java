package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A bare coordinate word ("X..Y..*", no D-code) repeats the last D-code — deprecated, but it is
 * how every Protel/Altium file is written. The lexer hands the parser the coordinate before it
 * can know whether a D-code follows, so the operation waits for the next token; that token must
 * then execute it <em>before</em> doing its own work. ST's NUCLEO-L031K6 set is the case that
 * found it: every solder-bridge chevron is a G36 region whose closing segment is a bare word,
 * then {@code G37*}, then {@code G54D10*} — and the segment came out as a draw with the pad
 * aperture, a half-disc of copper and mask opening beside the pad.
 */
public class ModalDCodeFlushTest {

    private static final double EPS = 1e-9;
    private static final String HEADER = """
        %FSTAX23Y23*%
        %MOIN*%
        %ADD10R,0.040000X0.032000*%
        %ADD25R,0.039370X0.039370*%
        %ADD30C,0.005000*%
        """;

    private static GerberDocument parse(String body) {
        return new GerberParser().parse(HEADER + body);
    }

    @Test
    void regionClosingSegmentStaysInTheRegion() {
        // SB11's bottom-copper chevron, verbatim.
        GerberDocument doc = parse("""
            %LPD*%
            G36*
            X00554Y00815D02*
            X00534Y00795D01*
            X00524*
            Y00835*
            X00534*
            X00554Y00815*
            G37*
            G54D10*
            X00571Y01014D03*
            M02*
            """);
        List<GraphicsObject> objects = doc.getObjects();
        assertEquals(2, objects.size(), objects.toString());
        Region region = assertInstanceOf(Region.class, objects.get(0));
        assertEquals(1, region.getContours().size(), "one G36 block is one contour");
        Contour c = region.getContours().get(0);
        assertEquals(5, c.getSegments().size(), "five sides, the last one back to the start");
        Contour.ContourSegment last = c.getSegments().get(4);
        assertEquals(c.getStartX(), last.getX(), EPS);
        assertEquals(c.getStartY(), last.getY(), EPS);
        assertInstanceOf(Flash.class, objects.get(1));
        assertTrue(objects.stream().noneMatch(o -> o instanceof Draw),
            "the closing segment must not become a draw with the next aperture");
    }

    @Test
    void lastVertexBeforeG37IsNotLost() {
        // A contour that relies on the implicit close: the bare final word is a real vertex.
        GerberDocument doc = parse("""
            G36*
            X001Y001D02*
            X002D01*
            X002Y002*
            X001Y002*
            G37*
            M02*
            """);
        Region region = assertInstanceOf(Region.class, doc.getObjects().get(0));
        Contour c = region.getContours().get(0);
        assertEquals(3, c.getSegments().size());
        assertEquals(0.1 * 25.4, c.getSegments().get(2).getX(), 1e-6);
        assertEquals(0.2 * 25.4, c.getSegments().get(2).getY(), 1e-6);
    }

    @Test
    void consecutiveRegionsDoNotLeakIntoEachOther() {
        // SB3's top-mask pair, verbatim: the second block used to open with a two-point ghost
        // contour made of the first block's last segment.
        GerberDocument doc = parse("""
            G36*
            X00305Y01036D02*
            X00281Y0106D01*
            X00257Y01036*
            Y01074*
            X00305*
            Y01036*
            G37*
            G36*
            Y01034D02*
            Y01024D01*
            X00257*
            Y01034*
            X00281Y01058*
            X00305Y01034*
            G37*
            G54D25*
            X00564Y00126D03*
            M02*
            """);
        List<GraphicsObject> objects = doc.getObjects();
        assertEquals(3, objects.size(), objects.toString());
        for (int i = 0; i < 2; i++) {
            Region r = assertInstanceOf(Region.class, objects.get(i));
            assertEquals(1, r.getContours().size(), "region " + i);
            assertEquals(5, r.getContours().get(0).getSegments().size(), "region " + i);
        }
        assertInstanceOf(Flash.class, objects.get(2));
    }

    @Test
    void bareDrawTakesTheApertureAndPolarityInForceWhenItWasWritten() {
        GerberDocument doc = parse("""
            %LPD*%
            G54D30*
            X001Y001D02*
            X002Y001D01*
            X002Y002*
            G54D10*
            %LPC*%
            X003Y003D01*
            M02*
            """);
        List<GraphicsObject> objects = doc.getObjects();
        assertEquals(3, objects.size(), objects.toString());
        Draw second = assertInstanceOf(Draw.class, objects.get(1));
        assertEquals(30, second.getAperture().getDCode(), "D30 was selected when the word was written");
        assertEquals(Polarity.DARK, second.getPolarity());
        Draw third = assertInstanceOf(Draw.class, objects.get(2));
        assertEquals(10, third.getAperture().getDCode());
        assertEquals(Polarity.CLEAR, third.getPolarity());
    }

    @Test
    void bareDrawBeforeEndOfFileIsKept() {
        GerberDocument withM02 = parse("""
            G54D30*
            X001Y001D02*
            X002Y001D01*
            X002Y002*
            M02*
            """);
        assertEquals(2, withM02.getObjects().size(), "the draw before M02 is an object");

        GerberDocument truncated = parse("""
            G54D30*
            X001Y001D02*
            X002Y001D01*
            X002Y002*
            """);
        assertEquals(2, truncated.getObjects().size(), "and so is one at the end of a file with no M02");
    }
}
