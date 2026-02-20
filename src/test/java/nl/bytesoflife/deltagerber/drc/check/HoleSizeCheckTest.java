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

class HoleSizeCheckTest {

    private final HoleSizeCheck check = new HoleSizeCheck();

    @Test
    void detectHoleBelowMinSize() {
        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.1); // 0.1mm - below 0.15mm min
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 5.0, 5.0));

        DrcBoardInput board = new DrcBoardInput().addDrill(drill);

        DrcRule rule = new DrcRule("Min Hole Size");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.HOLE_SIZE, 0.15, 6.3);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertEquals(1, violations.size());
        assertEquals(0.1, violations.get(0).getMeasuredValueMm(), 0.001);
    }

    @Test
    void detectHoleAboveMaxSize() {
        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 7.0); // 7.0mm - above 6.3mm max
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 5.0, 5.0));

        DrcBoardInput board = new DrcBoardInput().addDrill(drill);

        DrcRule rule = new DrcRule("Max Hole Size");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.HOLE_SIZE, 0.15, 6.3);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertEquals(1, violations.size());
        assertEquals(7.0, violations.get(0).getMeasuredValueMm(), 0.001);
    }

    @Test
    void passHoleWithinRange() {
        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.8); // 0.8mm - within range
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 5.0, 5.0));

        DrcBoardInput board = new DrcBoardInput().addDrill(drill);

        DrcRule rule = new DrcRule("Hole Size");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.HOLE_SIZE, 0.15, 6.3);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertTrue(violations.isEmpty());
    }

    @Test
    void checkMultipleHolesWithMixedResults() {
        DrillDocument drill = new DrillDocument();
        Tool small = new Tool(1, 0.1);
        Tool ok = new Tool(2, 1.0);
        Tool big = new Tool(3, 7.0);
        drill.addTool(small);
        drill.addTool(ok);
        drill.addTool(big);
        drill.addOperation(new DrillHit(small, 1, 1));
        drill.addOperation(new DrillHit(ok, 5, 5));
        drill.addOperation(new DrillHit(big, 10, 10));

        DrcBoardInput board = new DrcBoardInput().addDrill(drill);

        DrcRule rule = new DrcRule("Hole Size");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.HOLE_SIZE, 0.15, 6.3);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertEquals(2, violations.size());
    }
}
