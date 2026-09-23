package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PcbAnalyzer#analyzeParsed} is the same analysis as {@link PcbAnalyzer#analyze(List)} for a
 * caller that already holds the documents — every figure agrees on the real boards.
 */
class AnalyzeParsedTest {

    @ParameterizedTest
    @ValueSource(strings = {"testdata/arduino-uno", "testdata/DEPR PR31 GBDR V04"})
    void parsedDocumentsGiveTheSameSpecificationAsFiles(String directory) throws IOException {
        List<PcbFile> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(Path.of(directory))) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                files.add(PcbFile.of(p.getFileName().toString(), Files.readAllBytes(p)));
            }
        }
        PcbAnalyzer analyzer = new PcbAnalyzer();
        BoardSpecification fromFiles = analyzer.analyze(files);

        List<ParsedLayer> parsed = new ArrayList<>();
        for (PcbFile f : files) {
            LayerClassification c = analyzer.classify(f);
            if (c != null && c.function().isDrill() && f.getContent().contains("M48")) {
                parsed.add(ParsedLayer.of(f.getFileName(), c, new ExcellonParser().parse(f.getContent())));
            } else if (c != null && PcbAnalyzer.hasGeometry(f.getContent())) {
                parsed.add(ParsedLayer.of(f.getFileName(), c, new GerberParser().parse(f.getContent())));
            }
        }
        BoardSpecification fromParsed = analyzer.analyzeParsed(parsed);

        assertEquals(fromFiles.getMinConductorWidthUm(), fromParsed.getMinConductorWidthUm());
        assertEquals(fromFiles.getMinClearanceMm(), fromParsed.getMinClearanceMm());
        assertEquals(fromFiles.getFloatingCopperCount(), fromParsed.getFloatingCopperCount());
        assertEquals(fromFiles.getMinEdgeClearanceMm(), fromParsed.getMinEdgeClearanceMm());
        assertEquals(fromFiles.getMinDrillClearanceMm(), fromParsed.getMinDrillClearanceMm());
        assertEquals(fromFiles.getMinHoleSpacingMm(), fromParsed.getMinHoleSpacingMm());
        assertEquals(fromFiles.getMinAnnularRingMm(), fromParsed.getMinAnnularRingMm());
        assertEquals(fromFiles.getAnnularRingViolations().size(), fromParsed.getAnnularRingViolations().size());
    }
}
