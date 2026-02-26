package nl.bytesoflife.deltagerber.drc.advisor;

import nl.bytesoflife.deltagerber.drc.DrcBoardInput;
import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.drill.DrillHit;
import nl.bytesoflife.deltagerber.model.drill.Tool;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.model.gerber.aperture.CircleAperture;
import nl.bytesoflife.deltagerber.model.gerber.operation.Draw;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BoardProfileScannerTest {

    @Test
    void scanTraceWidths() {
        GerberDocument frontCopper = new GerberDocument();
        frontCopper.addAperture(new CircleAperture(10, 0.1));   // 0.1mm
        frontCopper.addAperture(new CircleAperture(11, 0.2));   // 0.2mm
        frontCopper.addObject(new Draw(0, 0, 10, 0, frontCopper.getAperture(10)));
        frontCopper.addObject(new Draw(0, 1, 10, 1, frontCopper.getAperture(11)));
        frontCopper.addObject(new Draw(0, 2, 10, 2, frontCopper.getAperture(11)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper);

        BoardProfile profile = new BoardProfileScanner().scan(board);

        assertEquals(3, profile.getTraceCount());
        assertEquals(0.1, profile.getMinTraceWidthMm(), 0.001);
        assertEquals("F.Cu", profile.getMinTraceWidthLayer());
        assertEquals(1, profile.getCopperLayerCount());
    }

    @Test
    void scanTraceWidthDistribution() {
        GerberDocument frontCopper = new GerberDocument();
        frontCopper.addAperture(new CircleAperture(10, 0.08));  // below 0.1016
        frontCopper.addAperture(new CircleAperture(11, 0.15));  // between 0.127 and 0.1524
        frontCopper.addAperture(new CircleAperture(12, 0.25));  // above 0.2032
        frontCopper.addObject(new Draw(0, 0, 10, 0, frontCopper.getAperture(10)));
        frontCopper.addObject(new Draw(0, 1, 10, 1, frontCopper.getAperture(11)));
        frontCopper.addObject(new Draw(0, 2, 10, 2, frontCopper.getAperture(12)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper);

        BoardProfile profile = new BoardProfileScanner().scan(board);

        assertNotNull(profile.getTraceWidthDistribution());
        assertFalse(profile.getTraceWidthDistribution().isEmpty());

        // Total features across all buckets should equal trace count
        int totalInBuckets = profile.getTraceWidthDistribution().stream()
                .mapToInt(FeatureBucket::count).sum();
        assertEquals(3, totalInBuckets);
    }

    @Test
    void scanHoleSizes() {
        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.2);
        Tool t2 = new Tool(2, 0.5);
        drill.addTool(t1);
        drill.addTool(t2);
        drill.addOperation(new DrillHit(t1, 1.0, 1.0));
        drill.addOperation(new DrillHit(t1, 2.0, 2.0));
        drill.addOperation(new DrillHit(t2, 3.0, 3.0));

        DrcBoardInput board = new DrcBoardInput()
                .addDrill(drill);

        BoardProfile profile = new BoardProfileScanner().scan(board);

        assertEquals(3, profile.getHoleCount());
        assertEquals(0.2, profile.getMinHoleSizeMm(), 0.001);
    }

    @Test
    void scanHoleSizeDistribution() {
        DrillDocument drill = new DrillDocument();
        Tool t1 = new Tool(1, 0.1);  // below 0.15
        Tool t2 = new Tool(2, 0.25); // between 0.2 and 0.3
        Tool t3 = new Tool(3, 0.5);  // above 0.3
        drill.addTool(t1);
        drill.addTool(t2);
        drill.addTool(t3);
        drill.addOperation(new DrillHit(t1, 1, 1));
        drill.addOperation(new DrillHit(t2, 2, 2));
        drill.addOperation(new DrillHit(t3, 3, 3));

        DrcBoardInput board = new DrcBoardInput()
                .addDrill(drill);

        BoardProfile profile = new BoardProfileScanner().scan(board);

        assertNotNull(profile.getHoleSizeDistribution());
        int totalInBuckets = profile.getHoleSizeDistribution().stream()
                .mapToInt(FeatureBucket::count).sum();
        assertEquals(3, totalInBuckets);
    }

    @Test
    void clearanceAnalysisDisabledByDefault() {
        GerberDocument frontCopper = new GerberDocument();
        frontCopper.addAperture(new CircleAperture(10, 0.2));
        frontCopper.addObject(new Draw(0, 0, 10, 0, frontCopper.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", frontCopper);

        BoardProfile profile = new BoardProfileScanner().scan(board);

        assertNull(profile.getMinClearanceMm());
        assertNull(profile.getClearanceDistribution());
    }

    @Test
    void multipleLayersCountedCorrectly() {
        GerberDocument front = new GerberDocument();
        front.addAperture(new CircleAperture(10, 0.2));
        front.addObject(new Draw(0, 0, 10, 0, front.getAperture(10)));

        GerberDocument back = new GerberDocument();
        back.addAperture(new CircleAperture(10, 0.15));
        back.addObject(new Draw(0, 0, 10, 0, back.getAperture(10)));

        DrcBoardInput board = new DrcBoardInput()
                .addGerberLayer("F.Cu", front)
                .addGerberLayer("B.Cu", back);

        BoardProfile profile = new BoardProfileScanner().scan(board);

        assertEquals(2, profile.getCopperLayerCount());
        assertEquals(2, profile.getTraceCount());
        assertEquals(0.15, profile.getMinTraceWidthMm(), 0.001);
        assertEquals("B.Cu", profile.getMinTraceWidthLayer());
    }

    @Test
    void buildDistributionProducesCorrectBuckets() {
        List<Double> measurements = List.of(0.05, 0.08, 0.11, 0.13, 0.2, 0.3);
        List<Double> boundaries = List.of(0.1, 0.15, 0.25);

        List<FeatureBucket> buckets = BoardProfileScanner.buildDistribution(measurements, boundaries);

        // Should have 4 buckets: <0.1, 0.1-0.15, 0.15-0.25, >=0.25
        int total = buckets.stream().mapToInt(FeatureBucket::count).sum();
        assertEquals(6, total);
    }

    @Test
    void buildDistributionEmptyMeasurementsReturnsEmpty() {
        List<FeatureBucket> buckets = BoardProfileScanner.buildDistribution(
                List.of(), List.of(0.1, 0.2));
        assertTrue(buckets.isEmpty());
    }
}
