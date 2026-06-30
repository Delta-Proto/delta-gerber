package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroTemplate;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.VectorLinePrimitive;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.SVGRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Legacy macro primitive code 2 (spec §4.5.1.3): the deprecated spelling of the vector-line
 * primitive (code 20). It must map to the same primitive and render.
 */
public class MacroPrimitive2Test {

    private final GerberParser parser = new GerberParser();

    @Test
    void code2MapsToVectorLinePrimitive() {
        String gerber = """
            %FSLAX26Y26*%
            %MOMM*%
            %AMLINE2*
            2,1,0.2,0,0,1,0,0*%
            %ADD10LINE2*%
            D10*
            X0Y0D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        MacroTemplate tmpl = doc.getMacroTemplate("LINE2");
        assertNotNull(tmpl, "macro LINE2 should be defined");
        assertEquals(1, tmpl.getPrimitives().size(), "code 2 should yield one primitive");
        assertInstanceOf(VectorLinePrimitive.class, tmpl.getPrimitives().get(0));

        // It renders to a visible shape (vector line → polygon/path).
        String svg = new SVGRenderer().render(doc);
        assertTrue(svg.contains("polygon") || svg.contains("path") || svg.contains("<use"),
            "code-2 macro aperture should render geometry");
    }
}
