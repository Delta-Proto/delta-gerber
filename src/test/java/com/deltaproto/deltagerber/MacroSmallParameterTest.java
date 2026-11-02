package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.parser.GerberParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A macro aperture whose parameter is small (KiCad 9 writes rounded-rectangle macros with
 * $1/$2 arithmetic, and a corner radius of 0.0001 mm is routine) used to fail lazily in
 * {@link Flash#getBoundingBox()}: the value was substituted into the macro's expression as
 * {@code "1.0E-4"} and the exponent's '-' was read as a subtraction.
 */
public class MacroSmallParameterTest {

    private final GerberParser parser = new GerberParser();

    @Test
    void smallMacroParameterEvaluatesAndBounds() {
        // KiCad-style rounded rectangle: $1 = width, $2 = height, $3 = corner radius.
        // Two centre lines inset by the radius plus four corner circles.
        String gerber = """
            %FSLAX46Y46*%
            %MOMM*%
            %AMRoundRect*
            0 Rectangle with rounded corners*
            0 $1 width, $2 height, $3 corner radius*
            $4=$1-2x$3*
            $5=$2-2x$3*
            21,1,$4,$2,0,0,0*
            21,1,$1,$5,0,0,0*
            1,1,2x$3,$4/2,$5/2*
            1,1,2x$3,-$4/2,$5/2*
            1,1,2x$3,-$4/2,-$5/2*
            1,1,2x$3,$4/2,-$5/2*%
            %ADD10RoundRect,1.0X0.5X0.0001*%
            D10*
            X1000000Y2000000D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        List<GraphicsObject> objects = doc.getObjects();
        assertEquals(1, objects.size(), "one flash expected");
        assertInstanceOf(Flash.class, objects.get(0));

        BoundingBox bounds = assertDoesNotThrow(() -> objects.get(0).getBoundingBox(),
            "evaluating a macro with a 0.0001 parameter must not throw");
        assertTrue(bounds.isValid(), "flash bounding box should be valid");
        assertEquals(1.0, bounds.getWidth(), 1e-6);
        assertEquals(0.5, bounds.getHeight(), 1e-6);
        assertEquals(1.0, bounds.getCenterX(), 1e-6);
        assertEquals(2.0, bounds.getCenterY(), 1e-6);
    }
}
