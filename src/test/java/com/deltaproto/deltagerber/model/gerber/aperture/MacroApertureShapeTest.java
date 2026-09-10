package com.deltaproto.deltagerber.model.gerber.aperture;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.api.Test;

import java.awt.Shape;
import java.awt.geom.Rectangle2D;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MacroAperture#getShape()}: the outline a macro really has, as opposed to the bounding box
 * that used to stand in for it.
 *
 * <p>A macro is the one aperture whose shape is not a few numbers, so anything that measures rather
 * than draws — is this point inside the pad, how far is it from the edge — depends on assembling it
 * from the primitives. Each test here declares one macro and asks the shape a question the bounding
 * box would get wrong.
 */
class MacroApertureShapeTest {

    /** The shape of the single flash in a one-macro document. */
    private static Shape shapeOf(String macroBody, String aperture) {
        GerberDocument doc = new GerberParser().parse(
                "%FSLAX46Y46*%\n%MOMM*%\n" + macroBody + aperture + "D10*\nX0Y0D03*\nM02*\n");
        Flash flash = (Flash) doc.getObjects().get(0);
        return ((MacroAperture) flash.getAperture()).getShape();
    }

    @Test
    void aRoundedRectanglesCornerIsRoundedAndItsBoxIsNot() {
        // 2.0 × 1.0 with 0.25 mm corners: two crossing bars and a circle in each corner.
        Shape shape = shapeOf("""
                %AMROUNDRECT*
                21,1,2.000000,0.500000,0,0,0*
                21,1,1.500000,1.000000,0,0,0*
                1,1,0.500000,-0.750000,-0.250000*
                1,1,0.500000,-0.750000,0.250000*
                1,1,0.500000,0.750000,-0.250000*
                1,1,0.500000,0.750000,0.250000*
                %
                """, "%ADD10ROUNDRECT*%\n");

        assertEquals(new Rectangle2D.Double(-1, -0.5, 2, 1), shape.getBounds2D());
        assertTrue(shape.contains(0.99, 0.0), "the middle of the long side is inside");
        assertTrue(shape.contains(0.0, 0.49), "so is the middle of the short side");
        assertFalse(shape.contains(0.99, 0.49), "but the corner is cut away");
    }

    @Test
    void anOutlinePrimitiveIsItsOwnPolygon() {
        // A triangle: inside near its base, outside past its apex.
        Shape shape = shapeOf("""
                %AMTRI*
                4,1,3,-1,-1,1,-1,0,1,-1,-1,0*
                %
                """, "%ADD10TRI*%\n");

        assertTrue(shape.contains(0, -0.9));
        assertFalse(shape.contains(0.9, 0.9));
    }

    @Test
    void anUnexposedPrimitiveIsSubtractedFromTheOnesBeforeIt() {
        // A ⌀2 disc with a ⌀1 hole punched in it — exposure 0 clears rather than adds.
        Shape shape = shapeOf("""
                %AMWASHER*
                1,1,2.000000,0,0*
                1,0,1.000000,0,0*
                %
                """, "%ADD10WASHER*%\n");

        assertTrue(shape.contains(0.75, 0));
        assertFalse(shape.contains(0, 0), "the centre was cleared");
    }

    @Test
    void aThermalKeepsItsFourSpokesAndItsHole() {
        // Outer ⌀3, inner ⌀1.5, 0.5 mm gaps: copper on the diagonals, nothing on the axes.
        Shape shape = shapeOf("""
                %AMTHERM*
                7,0,0,3.000000,1.500000,0.500000,0*
                %
                """, "%ADD10THERM*%\n");

        double diagonal = 1.0 / Math.sqrt(2);
        assertTrue(shape.contains(diagonal, diagonal), "a spoke");
        assertFalse(shape.contains(1.0, 0), "a gap");
        assertFalse(shape.contains(0, 0), "the hole in the middle");
        assertFalse(shape.contains(1.6, 0), "outside altogether");
    }

    @Test
    void aPrimitivesOwnRotationTurnsIt() {
        // A 2.0 × 0.4 bar turned 90°: what was wide is now tall.
        Shape shape = shapeOf("""
                %AMBAR*
                21,1,2.000000,0.400000,0,0,90*
                %
                """, "%ADD10BAR*%\n");

        assertTrue(shape.contains(0, 0.9));
        assertFalse(shape.contains(0.9, 0));
    }
}
