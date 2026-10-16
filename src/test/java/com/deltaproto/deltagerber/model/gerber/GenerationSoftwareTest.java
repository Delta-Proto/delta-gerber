package com.deltaproto.deltagerber.model.gerber;

import com.deltaproto.deltagerber.parser.GerberParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenerationSoftwareTest {

    private static GerberDocument parse(String header) {
        return new GerberParser().parse(header + "\n%FSLAX46Y46*%\n%MOMM*%\nM02*\n");
    }

    @Test
    @DisplayName("Vendor, application and version — the version is what a bug report needs")
    void versionIsPartOfTheLine() {
        assertEquals("Altium Limited Altium Designer 25.8.1 (18)",
                parse("G04 #@! TF.GenerationSoftware,Altium Limited,Altium Designer,25.8.1 (18)*").getGenerationSoftware());
        assertEquals("KiCad Pcbnew 8.0.4",
                parse("%TF.GenerationSoftware,KiCad,Pcbnew,8.0.4*%").getGenerationSoftware());
    }

    @Test
    void twoValuesStayTwoWords() {
        assertEquals("TestSuite GerberTestGenerator",
                parse("%TF.GenerationSoftware,TestSuite,GerberTestGenerator*%").getGenerationSoftware());
        assertNull(parse("G04 nothing declared*").getGenerationSoftware());
    }
}
