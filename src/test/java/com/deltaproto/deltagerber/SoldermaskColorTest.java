package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.renderer.svg.SoldermaskColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link SoldermaskColor} palette — verifies the mask/silkscreen
 * pairings and lenient name lookup without needing any Gerber fixtures.
 */
public class SoldermaskColorTest {

    @Test
    void greenKeepsTheRealisticDarkShadeWithWhiteSilk() {
        assertEquals("#004200", SoldermaskColor.GREEN.getMaskColor(),
            "GREEN must keep the darker realistic mask shade, not the bright swatch green");
        assertEquals("#ffffff", SoldermaskColor.GREEN.getSilkscreenColor());
        assertEquals(SoldermaskColor.GREEN, SoldermaskColor.DEFAULT);
    }

    @Test
    void whiteSoldermaskUsesBlackSilkEveryOtherColorUsesWhite() {
        assertEquals("#000000", SoldermaskColor.WHITE.getSilkscreenColor(),
            "White soldermask must print black silkscreen");
        for (SoldermaskColor c : SoldermaskColor.values()) {
            if (c != SoldermaskColor.WHITE) {
                assertEquals("#ffffff", c.getSilkscreenColor(),
                    c + " should use white silkscreen");
            }
        }
    }

    @Test
    void allSevenFabColorsPresent() {
        assertEquals(7, SoldermaskColor.values().length);
        assertEquals("#ac13a6", SoldermaskColor.PURPLE.getMaskColor());
        assertEquals("#bf0100", SoldermaskColor.RED.getMaskColor());
        assertEquals("#ffaa16", SoldermaskColor.YELLOW.getMaskColor());
        assertEquals("#002d8c", SoldermaskColor.BLUE.getMaskColor());
        assertEquals("#f7f9fe", SoldermaskColor.WHITE.getMaskColor());
        assertEquals("#0f1010", SoldermaskColor.BLACK.getMaskColor());
    }

    @Test
    void fromStringIsCaseInsensitiveAndFallsBackToGreen() {
        assertEquals(SoldermaskColor.RED, SoldermaskColor.fromString("red"));
        assertEquals(SoldermaskColor.BLUE, SoldermaskColor.fromString("  BLUE "));
        assertEquals(SoldermaskColor.GREEN, SoldermaskColor.fromString(null));
        assertEquals(SoldermaskColor.GREEN, SoldermaskColor.fromString(""));
        assertEquals(SoldermaskColor.GREEN, SoldermaskColor.fromString("chartreuse"));
    }
}
