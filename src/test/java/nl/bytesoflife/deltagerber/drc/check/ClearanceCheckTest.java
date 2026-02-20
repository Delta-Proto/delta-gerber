package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.DrcViolation;
import nl.bytesoflife.deltagerber.drc.model.*;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.deltagerber.model.gerber.operation.Draw;
import nl.bytesoflife.deltagerber.model.gerber.operation.Flash;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClearanceCheckTest {

    private final ClearanceCheck check = new ClearanceCheck();

    @Test
    void detectClearanceViolationBetweenTracks() {
        GerberDocument doc = new GerberDocument();
        doc.addAperture(new CircleAperture(10, 0.2)); // 0.2mm diameter, 0.1mm radius

        // Two parallel tracks 0.15mm apart (edge-to-edge = 0.15 - 0.1 - 0.1 = -0.05mm overlap? no)
        // Track 1: y=0, Track 2: y=0.25
        // Edge of track 1: y=0.1, Edge of track 2: y=0.15
        // Gap = 0.25 - 0.1 - 0.1 = 0.05mm
        doc.addObject(new Draw(0, 0, 10, 0, doc.getAperture(10)));
        doc.addObject(new Draw(0, 0.25, 10, 0.25, doc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", doc);

        DrcRule rule = new DrcRule("Min Clearance");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.CLEARANCE, 0.127, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).getMeasuredValueMm() < 0.127);
    }

    @Test
    void noClearanceViolationWhenFarApart() {
        GerberDocument doc = new GerberDocument();
        doc.addAperture(new CircleAperture(10, 0.2));

        // Two tracks 5mm apart - no violation
        doc.addObject(new Draw(0, 0, 10, 0, doc.getAperture(10)));
        doc.addObject(new Draw(0, 5, 10, 5, doc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", doc);

        DrcRule rule = new DrcRule("Min Clearance");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.CLEARANCE, 0.127, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertTrue(violations.isEmpty());
    }

    @Test
    void detectClearanceViolationBetweenPads() {
        GerberDocument doc = new GerberDocument();
        doc.addAperture(new CircleAperture(10, 1.0)); // 1mm diameter pad

        // Two pads 1.05mm apart center-to-center
        // Edge-to-edge = 1.05 - 0.5 - 0.5 = 0.05mm gap
        doc.addObject(new Flash(0, 0, doc.getAperture(10)));
        doc.addObject(new Flash(1.05, 0, doc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", doc);

        DrcRule rule = new DrcRule("Min Clearance");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.CLEARANCE, 0.127, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertEquals(1, violations.size());
    }

    @Test
    void skipNonCopperLayers() {
        GerberDocument doc = new GerberDocument();
        doc.addAperture(new CircleAperture(10, 0.2));
        doc.addObject(new Draw(0, 0, 10, 0, doc.getAperture(10)));
        doc.addObject(new Draw(0, 0.15, 10, 0.15, doc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Silkscreen", doc);

        DrcRule rule = new DrcRule("Min Clearance");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.CLEARANCE, 0.127, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertTrue(violations.isEmpty());
    }
}
