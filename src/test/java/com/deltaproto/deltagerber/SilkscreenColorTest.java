package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.renderer.svg.SilkscreenColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link SilkscreenColor} palette — the legend inks a fab can print,
 * plus the {@link SilkscreenColor#NONE} case for a board ordered without a legend.
 */
public class SilkscreenColorTest {

    @Test
    void everyInkHasAFillAndNoneHasNot() {
        assertEquals("#ffffff", SilkscreenColor.WHITE.getColor());
        assertEquals("#000000", SilkscreenColor.BLACK.getColor());
        assertEquals("#ffdd00", SilkscreenColor.YELLOW.getColor());
        assertNull(SilkscreenColor.NONE.getColor(), "NONE has no ink to fill with");
        assertEquals(4, SilkscreenColor.values().length);
    }

    @Test
    void noneIsTheOnlyColorThatIsNotPrinted() {
        assertFalse(SilkscreenColor.NONE.isPrinted());
        for (SilkscreenColor c : SilkscreenColor.values()) {
            if (c != SilkscreenColor.NONE) {
                assertTrue(c.isPrinted(), c + " is a real ink");
            }
        }
    }

    @Test
    void fromStringIsCaseInsensitiveAndFallsBackToWhite() {
        assertEquals(SilkscreenColor.BLACK, SilkscreenColor.fromString("black"));
        assertEquals(SilkscreenColor.YELLOW, SilkscreenColor.fromString("  YELLOW "));
        assertEquals(SilkscreenColor.WHITE, SilkscreenColor.fromString(null));
        assertEquals(SilkscreenColor.WHITE, SilkscreenColor.fromString(""));
        assertEquals(SilkscreenColor.WHITE, SilkscreenColor.fromString("magenta"));
    }

    @Test
    void fromStringResolvesNoneRatherThanFallingBack() {
        assertEquals(SilkscreenColor.NONE, SilkscreenColor.fromString("none"),
            "a board ordered without a legend must not silently gain a white one");
        assertEquals(SilkscreenColor.NONE, SilkscreenColor.fromString("NONE"));
    }
}
