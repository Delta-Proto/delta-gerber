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

class TrackWidthCheckTest {

    private final TrackWidthCheck check = new TrackWidthCheck();

    @Test
    void detectTrackBelowMinWidth() {
        // Create a gerber doc with a thin track (0.1mm aperture) - rule requires 0.127mm min
        GerberDocument doc = new GerberDocument();
        doc.addAperture(new CircleAperture(10, 0.1)); // 0.1mm diameter
        doc.addObject(new Draw(0, 0, 10, 0, doc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", doc);

        DrcRule rule = new DrcRule("Min Track Width");
        rule.setLayer(new LayerSelector("outer"));
        rule.setConditionExpression("A.Type == 'track'");

        DrcConstraint constraint = new DrcConstraint(ConstraintType.TRACK_WIDTH, 0.127, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertEquals(1, violations.size());
        assertEquals(0.1, violations.get(0).getMeasuredValueMm(), 0.001);
        assertEquals(0.127, violations.get(0).getRequiredValueMm(), 0.001);
    }

    @Test
    void passTrackAboveMinWidth() {
        GerberDocument doc = new GerberDocument();
        doc.addAperture(new CircleAperture(10, 0.2)); // 0.2mm diameter
        doc.addObject(new Draw(0, 0, 10, 0, doc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", doc);

        DrcRule rule = new DrcRule("Min Track Width");
        rule.setLayer(new LayerSelector("outer"));
        rule.setConditionExpression("A.Type == 'track'");

        DrcConstraint constraint = new DrcConstraint(ConstraintType.TRACK_WIDTH, 0.127, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertTrue(violations.isEmpty());
    }

    @Test
    void skipNonCopperLayers() {
        GerberDocument doc = new GerberDocument();
        doc.addAperture(new CircleAperture(10, 0.05));
        doc.addObject(new Draw(0, 0, 10, 0, doc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Silkscreen", doc);

        DrcRule rule = new DrcRule("Min Track Width");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.TRACK_WIDTH, 0.127, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertTrue(violations.isEmpty());
    }

    @Test
    void respectLayerSelector() {
        GerberDocument doc = new GerberDocument();
        doc.addAperture(new CircleAperture(10, 0.05));
        doc.addObject(new Draw(0, 0, 10, 0, doc.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("In1.Cu", doc);

        // Rule applies only to outer layers
        DrcRule rule = new DrcRule("Min Track Width");
        rule.setLayer(new LayerSelector("outer"));
        rule.setConditionExpression("A.Type == 'track'");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.TRACK_WIDTH, 0.127, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertTrue(violations.isEmpty());
    }

    @Test
    void multipleViolations() {
        GerberDocument doc = new GerberDocument();
        doc.addAperture(new CircleAperture(10, 0.05));
        doc.addAperture(new CircleAperture(11, 0.08));
        doc.addObject(new Draw(0, 0, 5, 0, doc.getAperture(10)));
        doc.addObject(new Draw(0, 5, 10, 5, doc.getAperture(11)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", doc);

        DrcRule rule = new DrcRule("Min Track Width");
        rule.setConditionExpression("A.Type == 'track'");
        DrcConstraint constraint = new DrcConstraint(ConstraintType.TRACK_WIDTH, 0.1, null);

        List<DrcViolation> violations = check.check(rule, constraint, board);
        assertEquals(2, violations.size());
    }
}
