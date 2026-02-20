package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.DrcViolation;
import nl.bytesoflife.deltagerber.drc.model.*;
import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.drill.DrillHit;
import nl.bytesoflife.deltagerber.model.drill.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HoleToHoleCheckTest {

    private final HoleToHoleCheck check = new HoleToHoleCheck();

    @Test
    void detectHolesCloserThanMinSpacing() {
        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.8); // radius 0.4mm

        drill.addTool(t1);
        // Two holes 1.0mm apart center-to-center
        // Edge-to-edge = 1.0 - 0.4 - 0.4 = 0.2mm
        drill.addOperation(new DrillHit(t1, 0, 0));
        drill.addOperation(new DrillHit(t1, 1.0, 0));

        DrcBoardInput board = new DrcBoardInput().addDrill(drill);

        DrcRule rule = new DrcRule("Hole to Hole");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.HOLE_TO_HOLE, 0.5, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertEquals(1, violations.size());
        assertEquals(0.2, violations.get(0).getMeasuredValueMm(), 0.01);
    }

    @Test
    void noViolationWhenHolesFarApart() {
        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.8);
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 0, 0));
        drill.addOperation(new DrillHit(t1, 5, 0));

        DrcBoardInput board = new DrcBoardInput().addDrill(drill);

        DrcRule rule = new DrcRule("Hole to Hole");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.HOLE_TO_HOLE, 0.5, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertTrue(violations.isEmpty());
    }

    @Test
    void checkMultipleHolePairs() {
        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.8); // radius 0.4mm
        drill.addTool(t1);
        // Three holes in a line, 0.9mm apart
        // Edge-to-edge = 0.9 - 0.4 - 0.4 = 0.1mm
        drill.addOperation(new DrillHit(t1, 0, 0));
        drill.addOperation(new DrillHit(t1, 0.9, 0));
        drill.addOperation(new DrillHit(t1, 1.8, 0));

        DrcBoardInput board = new DrcBoardInput().addDrill(drill);

        DrcRule rule = new DrcRule("Hole to Hole");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.HOLE_TO_HOLE, 0.5, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        // Pairs: (0,0)-(0.9,0), (0.9,0)-(1.8,0), and (0,0)-(1.8,0)
        // First two pairs: edge-to-edge = 0.1mm < 0.5mm
        // Third pair: edge-to-edge = 1.8 - 0.4 - 0.4 = 1.0mm > 0.5mm
        assertEquals(2, violations.size());
    }
}
