package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerFunction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A real Cadence Allegro set whose {@code Board_outline.art} holds only the dimension figures
 * "311.65" and "234". The board is 234 x 311.65 mm; read as the edge, the figures made it
 * 128 x 162 mm. A customer design, so it lives in the gitignored {@code excluded/} directory and
 * this test is skipped without it; {@link DimensionOnlyOutlineTest} is the synthetic twin.
 */
@EnabledIf("fixtureIsPresent")
class AllegroDimensionOutlineSetTest {

    private static final Path FIXTURE = Paths.get("excluded/allegro-dimension-outline");

    static boolean fixtureIsPresent() {
        return Files.isDirectory(FIXTURE);
    }

    private static BoardSpecification spec;

    @BeforeAll
    static void analyze() throws IOException {
        List<PcbFile> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(FIXTURE)) {
            for (Path path : entries.sorted().toList()) {
                files.add(PcbFile.of(path.getFileName().toString(), Files.readAllBytes(path)));
            }
        }
        spec = new PcbAnalyzer().analyze(files, AnalysisDepth.SPECIFICATION);
    }

    private static LayerFunction functionOf(String fileName) {
        return spec.getLayers().stream().filter(l -> l.getFileName().equals(fileName))
                .findFirst().orElseThrow().getFunction();
    }

    @Test
    void dimensionFilmIsNotTheOutline() {
        assertEquals(LayerFunction.MECHANICAL_DRAWING, functionOf("Board_outline.art"));
        assertFalse(spec.hasOutline());
    }

    @Test
    void boardIsSizedFromTheArtworkNotTheFigures() {
        // No edge survives, so the size is the artwork's extent: never the text's 128 x 162 mm.
        assertTrue(spec.getSizeXMm() > 225, "width " + spec.getSizeXMm());
        assertTrue(spec.getSizeYMm() > 300, "height " + spec.getSizeYMm());
    }

    @Test
    void sidecarTextFilesAreNotDrills() {
        assertFalse(functionOf("nc_param.txt").isDrill());
        assertFalse(functionOf("place_txt.txt").isDrill());
        assertTrue(functionOf("D891534_ORC_PulserReciever-1-6.drl").isDrill());
    }
}
