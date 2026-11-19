package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.drill.DrillHit;
import com.deltaproto.deltagerber.model.drill.DrillSpan;
import com.deltaproto.deltagerber.model.drill.Tool;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.Unit;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.spec.AnalysisDepth;
import com.deltaproto.deltagerber.spec.BoardSpecification;
import com.deltaproto.deltagerber.spec.PcbAnalyzer;
import com.deltaproto.deltagerber.spec.PcbFile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Via in pad on HDI boards: one hole arriving from several drill files, and drills that never reach
 * the pad's surface.
 *
 * <p>The shapes come from real sets (a 12-layer BGA board with stacked microvias, a buried 3–10
 * span, and every drill pair written both as Gerber X2 and as NC drill). Counting drill hits there
 * turned every 0.05 mm² ball land into a "via field" and called it thermal; counting a buried via
 * under a top pad wicked solder through copper that never reaches the surface. The fixtures are
 * synthetic: round sizes, a clean origin, generic names.
 */
class ViaInPadHdiTest {

    private static final double MICRO = 0.1;

    /** Top paste: one ⌀0.25 mm ball land (0.049 mm²) per point, from a file named {@code top.gtp}. */
    private static GerberDocument topBallLands(double[]... at) {
        return paste("top.gtp", 0.25, at);
    }

    private static GerberDocument paste(String name, double diameter, double[]... at) {
        StringBuilder body = new StringBuilder(String.format(Locale.US,
                "%%FSLAX46Y46*%%\n%%MOMM*%%\n%%ADD10C,%.6f*%%\nD10*\n", diameter));
        for (double[] p : at) {
            body.append(String.format("X%dY%dD03*\n", Math.round(p[0] * 1e6), Math.round(p[1] * 1e6)));
        }
        GerberDocument doc = new GerberParser().parse(body + "M02*\n");
        doc.setFileName(name);
        return doc;
    }

    /** A drill program of {@code diameter} mm holes, connecting the given span ({@code null}: unstated). */
    private static DrillDocument drill(DrillSpan span, double diameter, double[]... at) {
        DrillDocument doc = new DrillDocument();
        doc.setUnit(Unit.MM);
        doc.setSpan(span);
        Tool tool = new Tool(1, diameter);
        doc.addTool(tool);
        for (double[] p : at) {
            doc.addOperation(new DrillHit(tool, p[0], p[1]));
        }
        return doc;
    }

    private static double[] p(double x, double y) {
        return new double[]{x, y};
    }

    // ------------------------------------------------------------------------
    // Fault 1: one location, several drill hits
    // ------------------------------------------------------------------------

    @Test
    void theSameHoleInTwoDrillFilesIsOneViaNotAViaField() {
        // Altium writes each drill pair as an X2 drill layer and again as an NC drill file.
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topBallLands(p(10, 10))), List.of(),
                List.of(drill(null, MICRO, p(10, 10)), drill(null, MICRO, p(10, 10))));
        assertEquals(1, r.getCount());
        ViaInPadGroup land = r.getGroups().get(0);
        assertEquals(1, land.getViaCount());
        assertEquals(2, land.getVias().get(0).getDrillHits());
        assertTrue(land.requiresFilledAndCapped(), "a lone via in a ball land wicks it dry");
        assertTrue(r.requiresFilledAndCapped());
    }

    @Test
    void hitsAFewMicrometresApartAreOneHoleButAViaPitchApartAreTwo() {
        ViaInPadResult r = ViaInPadDetector.detect(List.of(paste("top.gtp", 1.0, p(10, 10))), List.of(),
                List.of(drill(null, MICRO, p(10, 10), p(10.3, 10)), drill(null, MICRO, p(10.004, 10.003))));
        assertEquals(2, r.getGroups().get(0).getViaCount());
    }

    @Test
    void aStackedMicroviaIsOneHoleWhateverItsPairs() {
        // 1-2 over 2-3 over the buried 3-10: one location, three drill pairs.
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topBallLands(p(5, 5))), List.of(), List.of(
                drill(new DrillSpan(1, 2), MICRO, p(5, 5)),
                drill(new DrillSpan(2, 3), MICRO, p(5, 5)),
                drill(new DrillSpan(3, 10), 0.15, p(5, 5)),
                drill(new DrillSpan(1, 12), 0.3, p(30, 30))));
        assertEquals(1, r.getCount());
        ViaInPad via = r.getViaInPads().get(0);
        assertEquals(1, via.getDrillHits(), "only the 1-2 pair reaches the top surface");
        assertEquals(MICRO, via.getHoleDiameterMm(), 1e-9, "the buried 0.15 mm span is not the hole in the pad");
        assertEquals(List.of(new DrillSpan(1, 2)), via.getSpans());
        assertTrue(via.isSpanKnown());
        assertTrue(r.requiresFilledAndCapped());
    }

    // ------------------------------------------------------------------------
    // Fault 2: spans that never reach the pad
    // ------------------------------------------------------------------------

    @Test
    void aBuriedViaUnderATopPadIsNotAViaInPad() {
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topBallLands(p(5, 5))), List.of(), List.of(
                drill(new DrillSpan(3, 10), 0.15, p(5, 5)),
                drill(new DrillSpan(1, 12), 0.3, p(30, 30))));
        assertFalse(r.hasViaInPad());
        assertEquals(1, r.getExcludedBySpanCount());
        assertFalse(r.requiresFilledAndCapped());
    }

    @Test
    void aMicroviaCountsOnlyOnTheSideItIsDrilledFrom() {
        GerberDocument top = topBallLands(p(5, 5), p(8, 8));
        GerberDocument bottom = paste("bottom.gbp", 0.25, p(5, 5), p(8, 8));
        ViaInPadResult r = ViaInPadDetector.detect(List.of(top), List.of(bottom), List.of(
                drill(new DrillSpan(1, 2), MICRO, p(5, 5)),
                drill(new DrillSpan(3, 4), MICRO, p(8, 8))), 4);
        assertEquals(2, r.getCount());
        ViaInPad fromTop = r.getViaInPads().stream().filter(v -> v.getX() == 5).findFirst().orElseThrow();
        ViaInPad fromBottom = r.getViaInPads().stream().filter(v -> v.getX() == 8).findFirst().orElseThrow();
        assertTrue(fromTop.isTop());
        assertFalse(fromTop.isBottom());
        assertTrue(fromBottom.isBottom());
        assertFalse(fromBottom.isTop());
        assertEquals(2, r.getGroups().size(), "one top land and one bottom land, not four");
    }

    @Test
    void theCopperLayerCountNumbersTheBottomWhenNoDrillReachesIt() {
        // A blind 1-2 file and nothing else stating a span: without the layer count, layer 2 would
        // pass for the bottom and the microvia for a hole in the bottom pad.
        GerberDocument bottom = paste("bottom.gbp", 0.25, p(5, 5));
        DrillDocument blind = drill(new DrillSpan(1, 2), MICRO, p(5, 5));
        assertFalse(ViaInPadDetector.detect(List.of(), List.of(bottom), List.of(blind), 4).hasViaInPad());
    }

    @Test
    void aThroughHoleReachesBothSides() {
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topBallLands(p(5, 5))),
                List.of(paste("bottom.gbp", 0.25, p(5, 5))),
                List.of(drill(new DrillSpan(1, 6), 0.2, p(5, 5))), 6);
        ViaInPad via = r.getViaInPads().get(0);
        assertTrue(via.isTop() && via.isBottom());
        assertEquals(2, r.getGroups().size());
        assertSame(r.getGroups().get(0).getVias().get(0), r.getGroups().get(1).getVias().get(0));
    }

    @Test
    void anUnstatedSpanStillCountsAndSaysSo() {
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topBallLands(p(5, 5))), List.of(),
                List.of(drill(null, 0.2, p(5, 5))));
        assertTrue(r.hasViaInPad());
        assertFalse(r.getViaInPads().get(0).isSpanKnown());
        assertFalse(r.getGroups().get(0).isSpanKnown());
        assertTrue(r.hasUnknownSpan());
        assertTrue(r.summarize().isSpanUnknown());
    }

    @Test
    void anNcCopyOfABuriedX2DrillTakesTheX2Answer() {
        // The same buried hole, once in an X2 drill layer that states 3-10 and once in an NC file
        // that states nothing: the NC hit is that hole, and it does not reach the top.
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topBallLands(p(5, 5))), List.of(), List.of(
                drill(new DrillSpan(3, 10), 0.15, p(5, 5)),
                drill(null, 0.15, p(5, 5))), 12);
        assertFalse(r.hasViaInPad());
        assertFalse(r.hasUnknownSpan());
        assertEquals(1, r.getExcludedBySpanCount());
    }

    @Test
    void anUnstatedHitOfAnotherToolIsNotExplainedByAStatedOne() {
        ViaInPadResult r = ViaInPadDetector.detect(List.of(topBallLands(p(5, 5))), List.of(), List.of(
                drill(new DrillSpan(3, 10), 0.15, p(5, 5)),
                drill(null, MICRO, p(5, 5))), 12);
        assertTrue(r.hasViaInPad());
        assertTrue(r.hasUnknownSpan());
        assertEquals(MICRO, r.getViaInPads().get(0).getHoleDiameterMm(), 1e-9);
    }

    // ------------------------------------------------------------------------
    // Fault 3: the evidence behind the verdict
    // ------------------------------------------------------------------------

    @Test
    void theSummaryCountsPadsAndGroupsIdenticalFillPads() {
        GerberDocument top = new GerberParser().parse("""
                %FSLAX46Y46*%
                %MOMM*%
                %ADD10C,0.250000*%
                %ADD11R,5.000000X5.000000*%
                D10*
                X1000000Y1000000D03*
                X2000000Y1000000D03*
                X3000000Y1000000D03*
                D11*
                X20000000Y20000000D03*
                M02*
                """);
        top.setFileName("board-F_Paste.gbr");
        DrillDocument d = drill(new DrillSpan(1, 4), 0.3,
                p(1, 1), p(2, 1), p(3, 1), p(19, 19), p(21, 21), p(40, 40));
        ViaInPadSummary s = ViaInPadDetector.detect(List.of(top), List.of(), List.of(d), 4).summarize();

        assertEquals(4, s.getPadsWithHole());
        assertEquals(1, s.getThermalPads());
        assertEquals(3, s.getFillPads());
        assertEquals(5, s.getHoles());
        assertTrue(s.requiresFilledAndCapped());
        assertFalse(s.isSpanUnknown());
        assertEquals(1, s.getFillPadKinds().size(), "three identical ball lands are one kind");
        ViaInPadSummary.PadKind kind = s.getFillPadKinds().get(0);
        assertEquals(3, kind.getPadCount());
        assertEquals("board-F_Paste.gbr", kind.getPasteFileName());
        assertTrue(kind.isTop());
        assertEquals(1, kind.getViaCount());
        assertEquals(0.3, kind.getHoleDiameterMm(), 1e-9);
        assertEquals(Math.PI * 0.125 * 0.125, kind.getPadAreaMm2(), 1e-6);
        assertEquals(3, kind.getLocations().size());
        assertEquals(1.0, kind.getLocations().get(0)[0], 1e-9);
        assertTrue(s.toString().contains("board-F_Paste.gbr"), s.toString());
        assertTrue(s.toString().contains("1 thermal, 3 need fill"), s.toString());
    }

    @Test
    void theSummaryNamesThePasteFileAVerdictRestsOn() {
        GerberDocument foreign = paste("OTHER-DESIGN.GBP", 0.8, p(5, 5));
        ViaInPadSummary s = ViaInPadDetector.detect(List.of(), List.of(foreign),
                List.of(drill(null, 0.3, p(5, 5)))).summarize();
        assertEquals("OTHER-DESIGN.GBP", s.getFillPadKinds().get(0).getPasteFileName());
        assertTrue(s.getFillPadKinds().get(0).isBottom());
        assertEquals(s.getFillPadKinds().get(0).getPasteFileName(),
                ViaInPadDetector.detect(List.of(), List.of(foreign), List.of(drill(null, 0.3, p(5, 5))))
                        .getGroups().get(0).getPasteFileName());
    }

    @Test
    void theSummaryCapsKindsAndLocations() {
        List<double[]> lands = new ArrayList<>();
        List<double[]> holes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            lands.add(p(i, 0));
            holes.add(p(i, 0));
        }
        GerberDocument small = paste("a.gtp", 0.25, lands.toArray(double[][]::new));
        GerberDocument other = paste("b.gtp", 0.30, p(50, 50));
        holes.add(p(50, 50));
        ViaInPadResult r = ViaInPadDetector.detect(List.of(small, other), List.of(),
                List.of(drill(null, MICRO, holes.toArray(double[][]::new))));
        ViaInPadSummary s = r.summarize(ViaInPadPolicy.DEFAULT, 1);
        assertEquals(9, s.getFillPads());
        assertEquals(1, s.getFillPadKinds().size());
        assertEquals(1, s.getOmittedFillPadKinds());
        assertEquals(8, s.getFillPadKinds().get(0).getPadCount(), "the most numerous kind comes first");
        assertEquals(ViaInPadSummary.MAX_LOCATIONS_PER_KIND, s.getFillPadKinds().get(0).getLocations().size());
    }

    @Test
    void anEmptyResultSummarisesToNothing() {
        ViaInPadSummary s = ViaInPadResult.empty().summarize();
        assertEquals(0, s.getPadsWithHole());
        assertFalse(s.requiresFilledAndCapped());
        assertTrue(s.getFillPadKinds().isEmpty());
    }

    // ------------------------------------------------------------------------
    // Through PcbAnalyzer: spans read from Gerber X2 drill layers
    // ------------------------------------------------------------------------

    @Test
    void theAnalyzerReadsSpansFromX2DrillLayersAndCountsEachHoleOnce() {
        // A 4-layer HDI board: ball lands on top; at (10,10) a 1-2 microvia over a buried 2-3,
        // at (12,10) only the buried 2-3; the NC file repeats the 2-3 pair without saying so.
        String paste = "%FSLAX46Y46*%\n%MOMM*%\n%TF.FileFunction,Paste,Top*%\n%ADD10C,0.250000*%\nD10*\n"
                + "X10000000Y10000000D03*\nX12000000Y10000000D03*\nM02*\n";
        List<PcbFile> files = List.of(
                PcbFile.of("board-Edge_Cuts.gbr", "%FSLAX46Y46*%\n%MOMM*%\n%TF.FileFunction,Profile,NP*%\n"
                        + "%ADD10C,0.050000*%\nD10*\nX0Y0D02*\nX30000000Y0D01*\nX30000000Y20000000D01*\n"
                        + "X0Y20000000D01*\nX0Y0D01*\nM02*\n"),
                PcbFile.of("board-F_Paste.gbr", paste),
                PcbFile.of("board-1-2-drl.gbr", x2Drill("Plated,1,2,Blind", 10, 10)),
                PcbFile.of("board-2-3-drl.gbr", x2Drill("Plated,2,3,Buried", 10, 10, 12, 10)),
                PcbFile.of("board-PTH-drl.gbr", x2Drill("Plated,1,4,PTH", 25, 15)),
                PcbFile.of("board-2-3.drl", "M48\nMETRIC,TZ\nT1C0.100\n%\nT1\nX010000Y010000\nX012000Y010000\nM30\n"));
        BoardSpecification spec = new PcbAnalyzer().analyze(files, AnalysisDepth.SPECIFICATION);

        assertEquals(1, spec.getViaInPadCount(), "one stacked via; the buried-only hole never reaches the land");
        assertEquals(Boolean.TRUE, spec.requiresFilledAndCappedVias());
        ViaInPadSummary s = spec.getViaInPadSummary();
        assertNotNull(s);
        assertEquals(1, s.getPadsWithHole());
        assertEquals(1, s.getFillPads());
        assertEquals(1, s.getExcludedBySpan());
        assertFalse(s.isSpanUnknown());
        assertEquals("board-F_Paste.gbr", s.getFillPadKinds().get(0).getPasteFileName());
    }

    /** A Gerber X2 drill layer: each hole a flashed ⌀0.1 mm circle. */
    private static String x2Drill(String function, double... xy) {
        StringBuilder sb = new StringBuilder("%FSLAX46Y46*%\n%MOMM*%\n%TF.FileFunction," + function + ",Drill*%\n"
                + "%TF.FilePolarity,Positive*%\n%ADD10C,0.100000*%\nD10*\n");
        for (int i = 0; i < xy.length; i += 2) {
            sb.append(String.format("X%dY%dD03*\n", Math.round(xy[i] * 1e6), Math.round(xy[i + 1] * 1e6)));
        }
        return sb.append("M02*\n").toString();
    }
}
