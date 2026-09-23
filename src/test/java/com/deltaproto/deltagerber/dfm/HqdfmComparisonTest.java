package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.spec.AnalyzedLayer;
import com.deltaproto.deltagerber.spec.BoardSpecification;
import com.deltaproto.deltagerber.spec.PcbAnalyzer;
import com.deltaproto.deltagerber.spec.PcbFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two boards that ship with the repository, checked against the DFM reports HQDFM 4.6 produced
 * for them on 2026-09-23. Each assertion is a figure both tools report; the HQDFM value is in the
 * comment beside it. Where the tools disagree the test pins delta-gerber's answer and the comment
 * says why.
 */
class HqdfmComparisonTest {

    private static final double MIL = 0.0254;

    private static BoardSpecification analyze(String directory) {
        return new PcbAnalyzer().analyze(files(directory));
    }

    private static List<PcbFile> files(String directory) {
        List<PcbFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(Path.of(directory))) {
            entries.sorted().filter(Files::isRegularFile).forEach(path -> {
                try {
                    files.add(PcbFile.of(path.getFileName().toString(), Files.readAllBytes(path)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static AnalyzedLayer layer(BoardSpecification spec, String suffix) {
        return spec.getLayers().stream().filter(l -> l.getFileName().endsWith(suffix)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Arduino: nine floating letters on the bottom, where and as many as HQDFM finds")
    void arduinoFloatingCopper() {
        BoardSpecification spec = analyze("testdata/arduino-uno");
        FloatingCopperResult bottom = layer(spec, ".sol").getFloatingCopper();
        // HQDFM: "Floating Copper", 22.96,28.99, qty 9 — and "Unconnected Traces", 22.96,31.98.
        assertEquals(9, bottom.getCount());
        assertTrue(bottom.getPieces().stream().anyMatch(p -> Math.abs(p.xMm() - 22.96) < 0.01 && Math.abs(p.yMm() - 28.99) < 0.01));
        assertTrue(bottom.getPieces().stream().anyMatch(p -> Math.abs(p.yMm() - 31.98) < 0.01 && p.objectCount() == 1));
        // The top's rotated pads are painted with strokes; the mask opening over them makes them pads.
        assertEquals(0, layer(spec, ".cmp").getFloatingCopper().getCount());
        assertEquals(9, spec.getFloatingCopperCount());
    }

    @Test
    @DisplayName("Arduino: the narrowest conductor is 8 mil, not the 3.9 mil brush that paints pads")
    void arduinoConductorWidth() {
        BoardSpecification spec = analyze("testdata/arduino-uno");
        // HQDFM: Trace Width/Spacing 8.00/7.00 mil.
        assertEquals(8.0, layer(spec, ".cmp").getConductorWidth().getMinMm() / MIL, 0.01);
        assertEquals(8.0, layer(spec, ".sol").getConductorWidth().getMinMm() / MIL, 0.01);
    }

    @Test
    @DisplayName("Arduino: 6.70 mil between nets, the letters left out — HQDFM rounds it to 7.00")
    void arduinoClearance() {
        BoardSpecification spec = analyze("testdata/arduino-uno");
        // HQDFM: Trace Width/Spacing 8.00/7.00 mil. The top's 6.70 mil is the board's minimum; the
        // bottom's floating letters, 6.40 mil apart, are not nets and are left out by default.
        assertEquals(6.70, spec.getMinClearanceMm() / MIL, 0.01);
        assertEquals(10.00, layer(spec, ".sol").getMinClearanceMm() / MIL, 0.01);

        BoardSpecification withLetters = new PcbAnalyzer().floatingCopperInClearance(true)
                .analyze(files("testdata/arduino-uno"));
        assertEquals(6.40, layer(withLetters, ".sol").getMinClearanceMm() / MIL, 0.01);
    }

    @Test
    @DisplayName("Holes: 15.65 mil of laminate at the DEPR connector, as HQDFM reports; the Arduino's slot is not a spacing")
    void holeSpacing() {
        BoardSpecification depr = analyze("testdata/DEPR PR31 GBDR V04");
        // HQDFM: "Different Net PTH Spacing" 15.65 mil at 51.60,40.33.
        HoleSpacing min = depr.getHoleSpacing().getMin();
        assertEquals(15.65, min.distanceMm() / MIL, 0.01);
        assertEquals(51.60, min.xMm(), 0.01);
        assertEquals(40.33, min.yMm(), 0.01);

        // HQDFM passes the Arduino's drill spacing. Its DC-jack slots are rows of overlapping holes.
        BoardSpecification arduino = analyze("testdata/arduino-uno");
        assertEquals(6, arduino.getHoleSpacing().getOverlapping().size());
        assertTrue(arduino.getMinHoleSpacingMm() > 0.9);
    }

    @Test
    @DisplayName("DEPR: 7.82 mil from hole to copper — inner, outer and non-plated, where HQDFM finds each")
    void deprDrillClearance() {
        BoardSpecification spec = analyze("testdata/DEPR PR31 GBDR V04");
        assertEquals(7.82, spec.getMinDrillClearanceMm() / MIL, 0.01);
        // HQDFM: "PTH-to-Trace [Inner]" 7.82 mil at 162.06,39.41; "[Outer]" at 161.69,39.08;
        // "NPTH-to-Copper" 7.82 mil at 99.77,86.25.
        assertTrue(hasDrillClearance(layer(spec, ".G1"), 162.06, 39.41));
        assertTrue(hasDrillClearance(layer(spec, ".GTL"), 161.69, 39.08));
        assertTrue(hasDrillClearance(layer(spec, ".GTL"), 99.77, 86.25));
        // Arduino: HQDFM passes drill-to-copper; the closest is 20 mil.
        assertEquals(20.0, analyze("testdata/arduino-uno").getMinDrillClearanceMm() / MIL, 0.01);
    }

    private static boolean hasDrillClearance(AnalyzedLayer layer, double x, double y) {
        return layer.getDrillClearance().getClearances().stream().anyMatch(c ->
                Math.abs(c.distanceMm() / MIL - 7.82) < 0.01 && Math.abs(c.xMm() - x) < 0.01 && Math.abs(c.yMm() - y) < 0.01);
    }

    @Test
    @DisplayName("DEPR: the pour fragment HQDFM calls floating, and two more it does not")
    void deprFloatingCopper() {
        BoardSpecification spec = analyze("testdata/DEPR PR31 GBDR V04");
        FloatingCopperResult top = layer(spec, ".GTL").getFloatingCopper();
        // HQDFM: "Floating Copper", 30.42,69.41, qty 1.
        assertTrue(top.getPieces().stream().anyMatch(p -> Math.abs(p.xMm() - 30.42) < 0.01 && Math.abs(p.yMm() - 69.41) < 0.01));
        // Two more regions touch nothing within 0.02 mm and carry no pad, hole or mask opening: a
        // 70 mm strip at (127.65, 54.71) and an island at (52.93, 69.91). HQDFM does not list them.
        assertEquals(3, top.getCount());
        assertEquals(0, layer(spec, ".GBL").getFloatingCopper().getCount());
        assertEquals(0, layer(spec, ".G1").getFloatingCopper().getCount());
    }

    @Test
    @DisplayName("DEPR: pads 5.90 mil apart, at the place HQDFM reports")
    void deprClearance() {
        BoardSpecification spec = analyze("testdata/DEPR PR31 GBDR V04");
        // HQDFM: "SMD Pad Spacing" 5.90 mil at 51.60,40.33.
        Clearance min = layer(spec, ".GBL").getClearance().getMin();
        assertEquals(5.90, min.distanceMm() / MIL, 0.01);
        assertEquals(51.60, min.xMm(), 0.01);
        assertEquals(40.33, min.yMm(), 0.01);
    }

    @Test
    @DisplayName("DEPR: the planes reach the board's edge, as HQDFM finds")
    void deprEdgeClearance() {
        BoardSpecification spec = analyze("testdata/DEPR PR31 GBDR V04");
        // HQDFM: "Copper-to-Board Edge" 0.00 mil at 8.00,33.00 — the corner of the plane region.
        assertEquals(0.0, spec.getMinEdgeClearanceMm(), 1e-9);
        for (String copper : List.of(".GTL", ".G1", ".G2", ".GBL")) {
            assertEquals(0.0, layer(spec, copper).getMinEdgeClearanceMm(), 1e-9, copper);
        }
        // The mechanical layer .GM1 is classified as an outline but is not the board's edge: nothing
        // in the thermal pad at (18.4, 68.1) is at an edge.
        assertTrue(layer(spec, ".GTL").getEdgeClearance().getClearances().stream()
                .noneMatch(c -> c.xMm() > 16 && c.xMm() < 20 && c.yMm() > 66 && c.yMm() < 71));
    }

    @Test
    @DisplayName("Arduino: no copper within 0.66 mm of the edge — HQDFM passes it")
    void arduinoEdgeClearance() {
        assertEquals(0.66, analyze("testdata/arduino-uno").getMinEdgeClearanceMm(), 0.005);
    }

    @Test
    @DisplayName("DEPR: the 0.4 mm holes' 4.88 mil ring, which HQDFM reports too")
    void deprAnnularRing() {
        BoardSpecification spec = analyze("testdata/DEPR PR31 GBDR V04");
        // HQDFM: "PTH Annular Ring" 4.88 mil at 49.86,40.33 — the thin side of the hole at (49.60, 40.33).
        assertEquals(4.88, spec.getMinAnnularRingMm() / MIL, 0.02);
        AnnularRing worst = spec.getAnnularRing().getWorstHole();
        assertEquals(0.4, worst.getHoleDiameterMm(), 0.001);
        assertEquals(40.33, worst.getY(), 0.01);
    }
}
