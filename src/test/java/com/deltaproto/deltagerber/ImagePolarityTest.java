package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.ImagePolarity;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.SVGRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deprecated {@code %IP%} image polarity (spec §4.5): retained on the document and rendered as a
 * true inversion in the single-layer renderer.
 */
public class ImagePolarityTest {

    private final GerberParser parser = new GerberParser();

    private String body(String ip) {
        return """
            %FSLAX26Y26*%
            %MOMM*%
            """ + ip + """
            %ADD10C,1.0*%
            D10*
            X2000000Y2000000D03*
            X4000000Y4000000D03*
            M02*
            """;
    }

    @Test
    void positiveByDefault() {
        GerberDocument doc = parser.parse(body(""));
        assertEquals(ImagePolarity.POSITIVE, doc.getImagePolarity());
    }

    @Test
    void positiveExplicit() {
        GerberDocument doc = parser.parse(body("%IPPOS*%\n"));
        assertEquals(ImagePolarity.POSITIVE, doc.getImagePolarity());
    }

    @Test
    void negativeParsed() {
        GerberDocument doc = parser.parse(body("%IPNEG*%\n"));
        assertEquals(ImagePolarity.NEGATIVE, doc.getImagePolarity());
    }

    @Test
    void negativeRendersAsInvertedField() {
        GerberDocument doc = parser.parse(body("%IPNEG*%\n"));
        String svg = new SVGRenderer().setDarkColor("#000000").render(doc);
        assertTrue(svg.contains("<mask id=\"ipneg\">"), "negative render should define the inversion mask");
        assertTrue(svg.contains("mask=\"url(#ipneg)\""), "negative render should paint a masked dark field");
    }

    @Test
    void positiveRenderHasNoInversionMask() {
        GerberDocument doc = parser.parse(body("%IPPOS*%\n"));
        String svg = new SVGRenderer().render(doc);
        assertFalse(svg.contains("ipneg"), "positive render must not use the inversion mask");
    }
}
