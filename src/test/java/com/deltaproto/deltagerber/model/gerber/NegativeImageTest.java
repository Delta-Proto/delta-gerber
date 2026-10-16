package com.deltaproto.deltagerber.model.gerber;

import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;
import com.deltaproto.deltagerber.parser.GerberParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.geom.Rectangle2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NegativeImageTest {

    /**
     * An Altium-style plane for a 10×10 mm board: negative, a 1 mm pull-back stroked along the
     * edge and one 1.2 mm antipad in the middle. What it draws is the clearances.
     */
    public static final String PLANE = String.join("\n",
            "G04 #@! TF.GenerationSoftware,Altium Limited,Altium Designer,25.8.1 (18)*",
            "G04 Layer_Physical_Order=2*",
            "G04 #@! TF.FilePolarity,Negative*",
            "%FSLAX46Y46*%",
            "%MOMM*%",
            "%ADD10C,1.000000*%",
            "%ADD11C,1.200000*%",
            "D10*",
            "X0Y0D02*",
            "X10000000Y0D01*",
            "X10000000Y10000000D01*",
            "X0Y10000000D01*",
            "X0Y0D01*",
            "D11*",
            "X5000000Y5000000D03*",
            "M02*");

    static GerberDocument parse(String content) {
        return new GerberParser().parse(content);
    }

    @Test
    @DisplayName("Altium's comment-form .FilePolarity,Negative is read, from the document and from the header")
    void negativeIsRecognised() {
        GerberDocument plane = parse(PLANE);
        assertEquals("Negative", plane.getFilePolarity());
        assertTrue(NegativeImage.declaresNegative(plane));
        assertTrue(NegativeImage.isNegative(plane));
        assertTrue(NegativeImage.headerDeclaresNegative(PLANE));
        assertTrue(NegativeImage.headerDeclaresNegative("%TF.FilePolarity,Negative*%\n%FSLAX46Y46*%"));
        assertTrue(NegativeImage.headerDeclaresNegative("%FSLAX46Y46*%\n%IPNEG*%"));

        String positive = PLANE.replace("Negative", "Positive");
        assertFalse(NegativeImage.isNegative(parse(positive)));
        assertFalse(NegativeImage.headerDeclaresNegative(positive));
    }

    @Test
    @DisplayName("The positive is a dark field over the extent with every object's polarity flipped")
    void toPositiveInvertsOverTheExtent() {
        GerberDocument plane = parse(PLANE);
        GerberDocument copper = NegativeImage.toPositive(plane, new Rectangle2D.Double(0, 0, 10, 10));

        List<GraphicsObject> objects = copper.getObjects();
        assertEquals(1 + plane.getObjects().size(), objects.size());
        Region field = assertInstanceOf(Region.class, objects.get(0));
        assertEquals(Polarity.DARK, field.getPolarity());
        BoundingBox fieldBounds = field.getBoundingBox();
        assertEquals(0, fieldBounds.getMinX(), 1e-9);
        assertEquals(10, fieldBounds.getMaxX(), 1e-9);
        assertEquals(0, fieldBounds.getMinY(), 1e-9);
        assertEquals(10, fieldBounds.getMaxY(), 1e-9);
        for (GraphicsObject object : objects.subList(1, objects.size())) {
            assertEquals(Polarity.CLEAR, object.getPolarity(), object.toString());
            assertTrue(object instanceof Draw || object instanceof Flash, object.toString());
        }

        // Marked, so no consumer inverts it twice — while the file is still described as negative.
        assertTrue(copper.isInvertedImage());
        assertTrue(NegativeImage.declaresNegative(copper));
        assertFalse(NegativeImage.isNegative(copper));
        assertEquals(ImagePolarity.POSITIVE, copper.getImagePolarity());
        assertEquals(plane.getApertures().keySet(), copper.getApertures().keySet());

        // The original is left as it was.
        assertFalse(plane.isInvertedImage());
        assertEquals(5, plane.getObjects().size());
        for (GraphicsObject object : plane.getObjects()) {
            assertEquals(Polarity.DARK, object.getPolarity());
            assertNotSame(object, objects.get(1 + plane.getObjects().indexOf(object)));
        }
    }

    @Test
    @DisplayName("Without an outline the image's own extent serves — the pull-back bounds the plane")
    void toPositiveWithoutExtentUsesTheImageBounds() {
        GerberDocument plane = parse(PLANE);
        GerberDocument copper = NegativeImage.toPositive(plane);
        BoundingBox field = copper.getObjects().get(0).getBoundingBox();
        BoundingBox own = plane.getBoundingBox();
        assertEquals(own.getMinX(), field.getMinX(), 1e-9);
        assertEquals(own.getMaxX(), field.getMaxX(), 1e-9);
        assertEquals(own.getMinY(), field.getMinY(), 1e-9);
        assertEquals(own.getMaxY(), field.getMaxY(), 1e-9);
    }

    @Test
    @DisplayName("An extent with a hole keeps the hole: one contour per loop")
    void extentHolesBecomeContours() {
        java.awt.geom.Area extent = new java.awt.geom.Area(new Rectangle2D.Double(0, 0, 10, 10));
        extent.subtract(new java.awt.geom.Area(new Rectangle2D.Double(4, 4, 2, 2)));
        GerberDocument copper = NegativeImage.toPositive(parse(PLANE), extent);
        Region field = assertInstanceOf(Region.class, copper.getObjects().get(0));
        assertEquals(2, field.getContours().size());
    }
}
