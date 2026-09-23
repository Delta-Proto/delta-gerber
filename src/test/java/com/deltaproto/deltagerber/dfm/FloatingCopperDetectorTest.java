package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.dfm.geometry.CopperGeometry;
import com.deltaproto.deltagerber.dfm.geometry.CopperNets;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Copper nothing connects to — and the three ways a piece of copper is connected. */
class FloatingCopperDetectorTest {

    private static CopperNets nets(String body) {
        return CopperNets.of(CopperGeometry.of(
                new GerberParser().parse("%FSLAX46Y46*%\n%MOMM*%\n" + body + "M02*\n")));
    }

    private static final String PAD_AND_TRACE = "%ADD10C,1.0*%\n%ADD11C,0.2*%\n"
            + "D10*\nX0Y0D03*\nD11*\nX0Y0D02*\nX5000000Y0D01*\n";

    /** Two short strokes crossing 10 mm away from everything: a letter "X" in copper. */
    private static final String LETTER = "%ADD12C,0.1*%\nD12*\n"
            + "X10000000Y10000000D02*\nX11000000Y11000000D01*\n"
            + "X10000000Y11000000D02*\nX11000000Y10000000D01*\n";

    @Test
    void aTraceLeavingAPadIsAnchoredAndALetterIsNot() {
        FloatingCopperResult r = FloatingCopperDetector.detect(nets(PAD_AND_TRACE + LETTER), "top.gbr", null, false);
        assertEquals(1, r.getCount());
        FloatingCopper letter = r.getPieces().get(0);
        assertEquals(10.5, letter.xMm(), 1e-9, "reported at the centre of its bounds, as HQDFM does");
        assertEquals(10.5, letter.yMm(), 1e-9);
        assertEquals(2, letter.objectCount());
    }

    @Test
    void aPlatedHoleAnchorsCopperWithNoPadOfItsOwn() {
        // A pour with no flash, joined to its net only by a via whose pad is on another layer.
        String pour = "G36*\nX20000000Y0D02*\nX25000000Y0D01*\nX25000000Y5000000D01*\nX20000000Y5000000D01*\nX20000000Y0D01*\nG37*\n";
        assertEquals(1, FloatingCopperDetector.detect(nets(pour), "in1.gbr", null, false).getCount());
        FloatingCopperResult r = FloatingCopperDetector.detect(nets(pour), "in1.gbr",
                List.of(new double[]{22.5, 2.5}), true);
        assertEquals(0, r.getCount());
        assertTrue(r.isHoleAware());
    }

    @Test
    void aMaskOpeningOverPaintedCopperMakesItAPad() {
        // EAGLE paints a rotated pad with strokes: no flash, but the mask opens over it.
        String painted = "%ADD13C,0.1*%\nD13*\nX0Y0D02*\nX700000Y0D01*\nX0Y80000D02*\nX700000Y80000D01*\n";
        assertEquals(1, FloatingCopperDetector.detect(nets(painted), "top.gbr", null, true).getCount());
        assertEquals(0, FloatingCopperDetector.detect(nets(painted), "top.gbr",
                List.of(new double[]{0.35, 0.04}), true).getCount());
    }

    @Test
    void anAnchorOverACutAwayPartOfThePourAnchorsNothing() {
        // The anchor sits in an antipad drawn after the pour: there is no copper there to connect.
        String pourWithAntipad = "G36*\nX0Y0D02*\nX5000000Y0D01*\nX5000000Y5000000D01*\nX0Y5000000D01*\nX0Y0D01*\nG37*\n"
                + "%ADD14C,1.0*%\n%LPC*%\nD14*\nX2500000Y2500000D03*\n%LPD*%\n";
        assertEquals(1, FloatingCopperDetector.detect(nets(pourWithAntipad), "in1.gbr",
                List.of(new double[]{2.5, 2.5}), true).getCount());
    }

    @Test
    void aPinAttributeMarksACustomPadDrawnAsARegion() {
        String region = "%TO.P,U1,5*%\nG36*\nX0Y0D02*\nX1000000Y0D01*\nX1000000Y1000000D01*\nX0Y1000000D01*\nX0Y0D01*\nG37*\n%TD*%\n";
        assertEquals(0, FloatingCopperDetector.detect(nets(region), "top.gbr", null, false).getCount());
    }
}
