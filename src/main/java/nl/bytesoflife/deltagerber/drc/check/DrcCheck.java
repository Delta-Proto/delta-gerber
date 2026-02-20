package nl.bytesoflife.deltagerber.drc.check;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.drc.DrcViolation;
import nl.bytesoflife.deltagerber.drc.model.ConstraintType;
import nl.bytesoflife.deltagerber.drc.model.DrcConstraint;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;

import java.util.List;

public interface DrcCheck {

    List<DrcViolation> check(DrcRule rule, DrcConstraint constraint, DrcBoardInput board);

    ConstraintType getSupportedType();
}
