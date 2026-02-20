package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.DrcViolation;
import nl.bytesoflife.deltagerber.drc.model.*;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.deltagerber.model.gerber.operation.Draw;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EdgeClearanceCheckTest {

    private final EdgeClearanceCheck check = new EdgeClearanceCheck();

    @Test
    void detectTrackTooCloseToEdge() {
        // Board outline at y=0, track at y=0.2 with 0.2mm aperture
        // Track edge closest to outline: y=0.2 - 0.1 = 0.1mm from edge
        GerberDocument edgeDoc = new GerberDocument();
        edgeDoc.addAperture(new CircleAperture(10, 0.01)); // Thin outline
        edgeDoc.addObject(new Draw(0, 0, 50, 0, edgeDoc.getAperture(10)));

        GerberDocument copperDoc = new GerberDocument();
        copperDoc.addAperture(new CircleAperture(10, 0.2)); // 0.2mm track
        copperDoc.addObject(new Draw(5, 0.2, 45, 0.2, copperDoc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("Edge.Cuts", edgeDoc)
                .addGerberLayer("F.Cu", copperDoc);

        DrcRule rule = new DrcRule("Trace to Outline");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.EDGE_CLEARANCE, 0.3, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertFalse(violations.isEmpty());
        assertTrue(violations.get(0).getMeasuredValueMm() < 0.3);
    }

    @Test
    void noViolationWhenTrackFarFromEdge() {
        GerberDocument edgeDoc = new GerberDocument();
        edgeDoc.addAperture(new CircleAperture(10, 0.01));
        edgeDoc.addObject(new Draw(0, 0, 50, 0, edgeDoc.getAperture(10)));

        GerberDocument copperDoc = new GerberDocument();
        copperDoc.addAperture(new CircleAperture(10, 0.2));
        copperDoc.addObject(new Draw(5, 5, 45, 5, copperDoc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("Edge.Cuts", edgeDoc)
                .addGerberLayer("F.Cu", copperDoc);

        DrcRule rule = new DrcRule("Trace to Outline");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.EDGE_CLEARANCE, 0.3, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertTrue(violations.isEmpty());
    }

    @Test
    void noViolationWithoutEdgeCutsLayer() {
        GerberDocument copperDoc = new GerberDocument();
        copperDoc.addAperture(new CircleAperture(10, 0.2));
        copperDoc.addObject(new Draw(0, 0, 10, 0, copperDoc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", copperDoc);

        DrcRule rule = new DrcRule("Trace to Outline");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.EDGE_CLEARANCE, 0.3, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertTrue(violations.isEmpty());
    }
}
