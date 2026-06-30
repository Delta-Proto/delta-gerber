package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.aperture.BlockAperture;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.parser.GerberParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Block aperture ({@code %AB%}) expansion — spec §4.11. Block flashes are expanded at parse time
 * into the document object list at the flash point.
 */
public class BlockApertureTest {

    private final GerberParser parser = new GerberParser();

    private static final String HEADER = """
        %FSLAX26Y26*%
        %MOMM*%
        """;

    @Test
    void blockIsRegisteredAndNotLeakedUntilFlashed() {
        // A block is defined but never flashed: it must register as an aperture and contribute
        // NO graphics objects to the image.
        String gerber = HEADER + """
            %ADD10C,0.1*%
            %ABD12*%
            D10*
            X-1000000Y0D02*
            X1000000Y0D01*
            %AB*%
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        assertInstanceOf(BlockAperture.class, doc.getAperture(12), "block must register as an aperture");
        assertEquals(1, ((BlockAperture) doc.getAperture(12)).getObjects().size(),
            "block should capture its single draw");
        assertEquals(0, doc.getObjects().size(),
            "an unflashed block must not leak geometry into the image");
    }

    @Test
    void flashingABlockExpandsItAtEachFlashPoint() {
        String gerber = HEADER + """
            %ADD10C,0.1*%
            %ABD12*%
            D10*
            X-1000000Y0D02*
            X1000000Y0D01*
            %AB*%
            D12*
            X5000000Y5000000D03*
            X10000000Y0D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        List<GraphicsObject> objs = doc.getObjects();
        assertEquals(2, objs.size(), "one draw per flash");

        Draw d0 = assertInstanceOf(Draw.class, objs.get(0));
        assertEquals(4.0, d0.getStartX(), 1e-6);
        assertEquals(5.0, d0.getStartY(), 1e-6);
        assertEquals(6.0, d0.getEndX(), 1e-6);
        assertEquals(5.0, d0.getEndY(), 1e-6);

        Draw d1 = assertInstanceOf(Draw.class, objs.get(1));
        assertEquals(9.0, d1.getStartX(), 1e-6);
        assertEquals(0.0, d1.getStartY(), 1e-6);
        assertEquals(11.0, d1.getEndX(), 1e-6);
    }

    @Test
    void nestedBlocksExpandFully() {
        // D13 flashes block D12 twice; flashing D13 once must yield both copies.
        String gerber = HEADER + """
            %ADD10C,0.1*%
            %ABD12*%
            D10*
            X0Y0D02*
            X1000000Y0D01*
            %AB*%
            %ABD13*%
            D12*
            X0Y0D03*
            X0Y2000000D03*
            %AB*%
            D13*
            X5000000Y5000000D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        assertEquals(2, doc.getObjects().size(), "nested block must expand to two draws");
        Draw a = assertInstanceOf(Draw.class, doc.getObjects().get(0));
        Draw b = assertInstanceOf(Draw.class, doc.getObjects().get(1));
        // The two inner copies are 2mm apart in Y (from D13's two flashes), shifted by (5,5).
        assertEquals(5.0, a.getStartY(), 1e-6);
        assertEquals(7.0, b.getStartY(), 1e-6);
    }

    @Test
    void blockPolarityIsPreservedOnExpansion() {
        // Block D20 has a dark pad and a clear pad; flashing it must keep both polarities so the
        // existing clear-polarity mask pipeline cuts the hole.
        String gerber = HEADER + """
            %ADD11C,2.0*%
            %ADD12C,1.0*%
            %ABD20*%
            %LPD*%
            D11*
            X0Y0D03*
            %LPC*%
            D12*
            X0Y0D03*
            %LPD*%
            %AB*%
            D20*
            X5000000Y5000000D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        assertEquals(2, doc.getObjects().size());
        assertEquals(Polarity.DARK, doc.getObjects().get(0).getPolarity());
        assertEquals(Polarity.CLEAR, doc.getObjects().get(1).getPolarity());
    }

    @Test
    void clearFlashOfBlockTogglesContents() {
        // Flashing a block under LPC toggles the polarity of every object in the block (spec §4.11).
        String gerber = HEADER + """
            %ADD11C,2.0*%
            %ABD20*%
            %LPD*%
            D11*
            X0Y0D03*
            %AB*%
            %LPC*%
            D20*
            X5000000Y5000000D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        assertEquals(1, doc.getObjects().size());
        assertEquals(Polarity.CLEAR, doc.getObjects().get(0).getPolarity(),
            "a dark object in a block flashed under LPC becomes clear");
    }

    @Test
    void blockRotationTransformsGeometry() {
        // A block draw along +X, flashed with LR90, rotates to run along +Y (matches the order
        // Flash applies object transforms, so an expanded block renders like a single flash).
        String gerber = HEADER + """
            %ADD10C,0.1*%
            %ABD12*%
            D10*
            X0Y0D02*
            X2000000Y0D01*
            %AB*%
            %LR90*%
            D12*
            X0Y0D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        assertEquals(1, doc.getObjects().size());
        Draw d = assertInstanceOf(Draw.class, doc.getObjects().get(0));
        assertEquals(0.0, d.getStartX(), 1e-6);
        assertEquals(0.0, d.getStartY(), 1e-6);
        assertEquals(0.0, d.getEndX(), 1e-6);
        assertEquals(2.0, d.getEndY(), 1e-6);
    }

    @Test
    void blockMirrorTransformsGeometry() {
        String gerber = HEADER + """
            %ADD10C,0.1*%
            %ABD12*%
            D10*
            X0Y0D02*
            X2000000Y0D01*
            %AB*%
            %LMX*%
            D12*
            X0Y0D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        Draw d = assertInstanceOf(Draw.class, doc.getObjects().get(0));
        assertEquals(-2.0, d.getEndX(), 1e-6, "LMX mirrors the +X draw to -X");
        assertEquals(0.0, d.getEndY(), 1e-6);
    }

    @Test
    void blockFlashesDoNotThrowOnSuiteFixtures() {
        // Smoke test the bundled conformance fixtures parse and expand without error.
        assertDoesNotThrow(() -> {
            for (String name : List.of(
                    "test-gerber-suite/blocks/01_block_aperture_basic.gbr",
                    "test-gerber-suite/blocks/02_block_aperture_transforms.gbr",
                    "test-gerber-suite/blocks/03_block_aperture_polarity.gbr")) {
                String content = java.nio.file.Files.readString(java.nio.file.Path.of(name));
                GerberDocument doc = parser.parse(content);
                assertFalse(doc.getObjects().isEmpty(), name + " should expand to objects");
            }
        });
    }
}
