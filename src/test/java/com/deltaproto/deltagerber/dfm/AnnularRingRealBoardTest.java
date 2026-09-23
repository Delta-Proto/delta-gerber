package com.deltaproto.deltagerber.dfm;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The annular-ring check against the two real board sets that ship with the repository — an EAGLE
 * two-layer board and an Altium four-layer one.
 *
 * <p>Synthetic fixtures pin the arithmetic; these pin the things only real artwork contains. EAGLE
 * draws most of its through-hole pads as a <em>swept aperture</em> rather than a flash, so a check
 * that only looked at flashes would find no pad for two thirds of the Arduino's holes. Altium
 * writes its rounded-rectangle pads as aperture macros, whose outline exists nowhere in the file
 * except as primitives to be assembled. And both boards carry mounting holes that legitimately have
 * no pad at all.
 */
class AnnularRingRealBoardTest {

    private static BoardSpecification analyze(String directory) {
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
        return new PcbAnalyzer().analyze(files);
    }

    // ------------------------------------------------------------------------
    // Arduino Uno — EAGLE, two layers, pads drawn as strokes
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("The Arduino's tightest ring is 0.211 mm, and it clears the standard rule")
    void arduinoUnoRing() {
        BoardSpecification spec = analyze("testdata/arduino-uno");

        // A ⌀0.6096 (24 mil) hole in an octagonal pad — the tightest of 165 measured holes.
        assertEquals(0.2115, spec.getMinAnnularRingMm(), 5e-4);
        assertEquals(Boolean.TRUE, spec.isAnnularRingWithinPolicy());
        assertTrue(spec.getAnnularRingViolations().isEmpty());
        assertFalse(spec.getAnnularRing().hasBreakout());
    }

    @Test
    @DisplayName("Most of the Arduino's pads are strokes, not flashes")
    void arduinoUnoPadsAreDrawnAsSweptApertures() {
        AnnularRingResult rings = analyze("testdata/arduino-uno").getAnnularRing();

        long stroked = rings.getRings().stream()
                .flatMap(r -> r.getPads().stream())
                .filter(pad -> "stroke".equals(pad.getPadShape()))
                .count();
        assertEquals(120, stroked);     // ... against 210 flashed ones
        assertEquals(165, rings.getRings().size());
    }

    @Test
    @DisplayName("The Arduino's four ⌀3.2 mm mounting holes have no pad, and that is not a fault")
    void arduinoUnoMountingHoles() {
        AnnularRingResult rings = analyze("testdata/arduino-uno").getAnnularRing();

        assertEquals(4, rings.getHolesWithoutPad().size());
        assertTrue(rings.getHolesWithoutPad().stream()
                .allMatch(hole -> Math.abs(hole.getHoleDiameterMm() - 3.2) < 0.01));
        // The EAGLE drill file states no plating, so none of them is called a missing pad.
        assertTrue(rings.getPlatedHolesWithoutPad().isEmpty());
    }

    // ------------------------------------------------------------------------
    // DEPR PR31 — Altium, four layers, macro pads
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("The Altium board's ⌀3.2 mm holes in ⌀3.2 mm pads are non-plated holes, not rings of nothing")
    void deprPr31HasAPadTheSizeOfItsHole() {
        BoardSpecification spec = analyze("testdata/DEPR PR31 GBDR V04");

        // The pad is written 3.1999 and the hole 3.2004: Altium's way of writing a non-plated
        // hole, in a drill file that does not state plating. They join the four ⌀2.5 mm holes that
        // have no pad at all.
        AnnularRingResult rings = spec.getAnnularRing();
        assertEquals(8, rings.getHolesWithoutPad().size());
        assertEquals(4, rings.getHolesWithoutPad().stream()
                .filter(hole -> Math.abs(hole.getHoleDiameterMm() - 3.2) < 0.01).count());
        assertFalse(rings.hasBreakout());

        // The tightest real ring is the 0.4 mm holes' 0.124 mm (4.87 mil) — HQDFM reports the same
        // holes at 4.88 mil. Twenty-six holes are under the standard 0.15 mm rule.
        assertEquals(0.1237, spec.getMinAnnularRingMm(), 5e-4);
        assertEquals(Boolean.FALSE, spec.isAnnularRingWithinPolicy());
        assertEquals(26, spec.getAnnularRingViolations().size());
    }

    @Test
    @DisplayName("Altium's macro pads are measured to their real outline")
    void deprPr31MeasuresMacroPads() {
        AnnularRingResult rings = analyze("testdata/DEPR PR31 GBDR V04").getAnnularRing();

        assertEquals(366, rings.getRings().size());
        long macroPads = rings.getRings().stream()
                .flatMap(r -> r.getPads().stream())
                .filter(pad -> pad.getPadShape().startsWith("ROUNDEDRECT"))
                .count();
        assertEquals(12, macroPads);
    }

    @Test
    @DisplayName("A via is measured on every layer whose pad it passes through")
    void deprPr31MeasuresTheWholeStack() {
        AnnularRingResult rings = analyze("testdata/DEPR PR31 GBDR V04").getAnnularRing();

        // The board is four layers, but its inner layers are planes: most holes only have pads on
        // the two outer ones, and the ones that reach an inner layer are measured there as well.
        assertTrue(rings.getRings().stream().anyMatch(r -> r.getPads().size() > 2));
        assertTrue(rings.getRings().stream().allMatch(r -> r.getPads().size() <= 4));
    }
}
