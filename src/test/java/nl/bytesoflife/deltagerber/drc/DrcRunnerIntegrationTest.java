package nl.bytesoflife.deltagerber.drc;

import nl.bytesoflife.deltagerber.drc.check.AnnularWidthCheck;
import nl.bytesoflife.deltagerber.drc.check.HoleSizeCheck;
import nl.bytesoflife.deltagerber.drc.check.TrackWidthCheck;
import nl.bytesoflife.deltagerber.drc.model.DrcRuleSet;
import nl.bytesoflife.deltagerber.drc.parser.KicadDruParser;
import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.drill.DrillHit;
import nl.bytesoflife.deltagerber.model.drill.Tool;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.deltagerber.model.gerber.operation.Draw;
import nl.bytesoflife.deltagerber.model.gerber.operation.Flash;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DrcRunnerIntegrationTest {

    @Test
    void endToEndWithPcbWayRulesAndSyntheticBoard() throws IOException {
        // Parse PCBWay rules
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/KiCAD_Custom_DRC_Rules_for_PCBWay/KiCAD_Custom_DRC_Rules_for_PCBWay.kicad_dru"));
        DrcRuleSet ruleSet = new KicadDruParser().parse(content);

        // Build synthetic board with known violations
        GerberDocument frontCopper = new GerberDocument();
        // Track with 0.1mm width (below PCBWay 0.127mm minimum for outer layers)
        frontCopper.addAperture(new CircleAperture(10, 0.1));
        frontCopper.addObject(new Draw(0, 0, 10, 0, frontCopper.getAperture(10)));
        // Pad flash for annular width check
        frontCopper.addAperture(new CircleAperture(11, 0.7));
        frontCopper.addObject(new Flash(5, 5, frontCopper.getAperture(11)));

        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.1); // 0.1mm - below PCBWay 0.15mm minimum
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 5.0, 5.0)); // Matches pad at (5,5)

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper)
                .addDrill(drill);

        // Run DRC
        DrcRunner runner = new DrcRunner()
                .registerCheck(new TrackWidthCheck())
                .registerCheck(new HoleSizeCheck())
                .registerCheck(new AnnularWidthCheck());

        DrcReport report = runner.run(ruleSet, board);

        // Should have violations
        assertFalse(report.getViolations().isEmpty());

        // Should have skipped rules (those with unsupported conditions)
        assertFalse(report.getSkippedRules().isEmpty());

        // Track width violation should be present
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> v.getDescription().contains("Track width too small")));

        // Hole size violation should be present
        assertTrue(report.getViolations().stream()
                .anyMatch(v -> v.getDescription().contains("Hole size too small")));

        // Verify report renders
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("DRC Report"));
    }

    @Test
    void noViolationsWithCompliantBoard() {
        DrcRuleSet ruleSet = new KicadDruParser().parse("""
                (version 1)
                (rule "Min Track Width"
                    (constraint track_width (min 0.127mm))
                    (condition "A.Type == 'track'"))
                """);

        GerberDocument frontCopper = new GerberDocument();
        frontCopper.addAperture(new CircleAperture(10, 0.2)); // 0.2mm > 0.127mm
        frontCopper.addObject(new Draw(0, 0, 10, 0, frontCopper.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper);

        DrcRunner runner = new DrcRunner()
                .registerCheck(new TrackWidthCheck());

        DrcReport report = runner.run(ruleSet, board);
        assertTrue(report.getViolations().isEmpty());
    }

    @Test
    void annularWidthViolation() {
        DrcRuleSet ruleSet = new KicadDruParser().parse("""
                (version 1)
                (rule "Pad Size"
                    (constraint annular_width (min 0.25mm)))
                """);

        // Pad diameter 0.7mm, drill 0.5mm -> annular width = (0.7-0.5)/2 = 0.1mm < 0.25mm
        GerberDocument frontCopper = new GerberDocument();
        frontCopper.addAperture(new CircleAperture(10, 0.7));
        frontCopper.addObject(new Flash(5, 5, frontCopper.getAperture(10)));

        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.5);
        drill.addTool(t1);
        drill.addOperation(new DrillHit(t1, 5.0, 5.0));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper)
                .addDrill(drill);

        DrcRunner runner = new DrcRunner()
                .registerCheck(new AnnularWidthCheck());

        DrcReport report = runner.run(ruleSet, board);
        assertEquals(1, report.getViolations().size());
        assertEquals(0.1, report.getViolations().get(0).getMeasuredValueMm(), 0.001);
    }
}
