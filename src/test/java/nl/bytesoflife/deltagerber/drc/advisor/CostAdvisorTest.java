package nl.bytesoflife.deltagerber.drc.advisor;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.drill.DrillHit;
import nl.bytesoflife.deltagerber.model.drill.Tool;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.deltagerber.model.gerber.operation.Draw;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CostAdvisorTest {

    @Test
    void boardFittingStandardTierHasNoSuggestions() {
        GerberDocument frontCopper = new GerberDocument();
        frontCopper.addAperture(new CircleAperture(10, 0.2)); // 0.2mm > 0.127mm Standard
        frontCopper.addObject(new Draw(0, 0, 10, 0, frontCopper.getAperture(10)));

        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.5); // 0.5mm > 0.3mm Standard
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 5, 5));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper)
                .addDrill(drill);

        CostAdvisorReport report = new CostAdvisor()
                .withProfile(BuiltinProfiles.nextPcb2Layer())
                .analyze(board);

        assertEquals("Standard", report.getOverallTier().name());
        assertFalse(report.hasOptimizations());
        assertTrue(report.getSuggestions().isEmpty());
    }

    @Test
    void fewNarrowTracesAmongWideOnesSuggestsWidening() {
        GerberDocument frontCopper = new GerberDocument();
        // 3 narrow traces at ~4.5mil (0.114mm) — below 5mil Standard
        frontCopper.addAperture(new CircleAperture(10, 0.114));
        for (int i = 0; i < 3; i++) {
            frontCopper.addObject(new Draw(0, i, 10, i, frontCopper.getAperture(10)));
        }
        // Many wide traces at 0.2mm — Standard tier
        frontCopper.addAperture(new CircleAperture(11, 0.2));
        for (int i = 3; i < 503; i++) {
            frontCopper.addObject(new Draw(0, i, 10, i, frontCopper.getAperture(11)));
        }

        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.5);
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 5, 5));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper)
                .addDrill(drill);

        CostAdvisorReport report = new CostAdvisor()
                .withProfile(BuiltinProfiles.nextPcb2Layer())
                .analyze(board);

        // Board should be in Advanced tier due to the narrow traces
        assertEquals("Advanced", report.getOverallTier().name());
        assertTrue(report.hasOptimizations());

        // Should suggest widening trace features
        OptimizationSuggestion traceSuggestion = report.getSuggestions().stream()
                .filter(s -> s.parameterName().equals("Trace Width"))
                .findFirst()
                .orElse(null);
        assertNotNull(traceSuggestion);
        assertEquals("Standard", traceSuggestion.targetTier().name());
        assertEquals(3, traceSuggestion.featuresBelowTarget());
        assertEquals(503, traceSuggestion.totalFeatures());
    }

    @Test
    void smallHolesIdentifiedAsBottleneck() {
        GerberDocument frontCopper = new GerberDocument();
        frontCopper.addAperture(new CircleAperture(10, 0.2)); // Standard trace
        frontCopper.addObject(new Draw(0, 0, 10, 0, frontCopper.getAperture(10)));

        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.25); // 0.25mm — Advanced tier (below 0.3mm Standard)
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 5, 5));
        // Add some standard-sized holes too
        Tool t2 = new Tool(2, 0.5);
        drill.addTool(t2);
        for (int i = 0; i < 20; i++) {
            drill.addOperation(new DrillHit(t2, i, 0));
        }

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper)
                .addDrill(drill);

        CostAdvisorReport report = new CostAdvisor()
                .withProfile(BuiltinProfiles.nextPcb2Layer())
                .analyze(board);

        // Overall tier should be Advanced because of hole size
        assertEquals("Advanced", report.getOverallTier().name());

        // Should have a hole size classification at Advanced
        TierClassification holeClassification = report.getClassifications().stream()
                .filter(c -> c.parameterName().equals("Hole Size"))
                .findFirst()
                .orElse(null);
        assertNotNull(holeClassification);
        assertEquals("Advanced", holeClassification.currentTier().name());
    }

    @Test
    void reportToStringRendersCorrectly() {
        GerberDocument frontCopper = new GerberDocument();
        frontCopper.addAperture(new CircleAperture(10, 0.114));
        frontCopper.addObject(new Draw(0, 0, 10, 0, frontCopper.getAperture(10)));

        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.5);
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 5, 5));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper)
                .addDrill(drill);

        CostAdvisorReport report = new CostAdvisor()
                .withProfile(BuiltinProfiles.nextPcb2Layer())
                .analyze(board);

        String output = report.toString();
        assertNotNull(output);
        assertTrue(output.contains("Cost Advisor Report:"));
        assertTrue(output.contains("Manufacturer:"));
        assertTrue(output.contains("Overall tier:"));
        assertTrue(output.contains("Classifications:"));
    }

    @Test
    void analyzeWithoutProfileThrows() {
        DrcBoardInput board = new DrcBoardInput();
        assertThrows(IllegalStateException.class, () ->
                new CostAdvisor().analyze(board));
    }
}
