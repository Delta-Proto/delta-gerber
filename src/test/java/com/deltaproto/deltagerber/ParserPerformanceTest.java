package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.GerberParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for the Gerber parser.
 * Uses DEPR testdata which is tracked in git.
 */
public class ParserPerformanceTest {

    private static final Path TESTDATA_DIR = Paths.get("testdata");
    private static final Path DEPR_DIR = TESTDATA_DIR.resolve("DEPR PR31 GBDR V04");

    @Test
    void testParseLargestFilePerformance() throws IOException {
        // GTL is the largest file in DEPR (226 KB)
        Path file = DEPR_DIR.resolve("uP-H Main PCBA Assy V04.GTL");
        assertTrue(Files.exists(file), "Test file not found: " + file);

        String content = Files.readString(file);
        System.out.println("File: " + file.getFileName());
        System.out.println("Size: " + content.length() + " chars (" + (content.length() / 1024) + " KB)");

        GerberParser parser = new GerberParser();

        // Warm up
        parser.parse(content);

        // Timed run
        long start = System.currentTimeMillis();
        GerberDocument doc = parser.parse(content);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("Parse time: " + elapsed + "ms");
        System.out.println("Objects: " + doc.getObjects().size());
        System.out.println("Apertures: " + doc.getApertures().size());

        // Performance assertion - should parse in under 500ms
        assertTrue(elapsed < 500, "Parsing took too long: " + elapsed + "ms (expected < 500ms)");
    }

    @Test
    void testParseDEPRProjectPerformance() throws IOException {
        assertTrue(Files.exists(DEPR_DIR), "Test directory not found: " + DEPR_DIR);

        GerberParser parser = new GerberParser();
        long totalTime = 0;
        int fileCount = 0;

        System.out.println("Parsing DEPR PR31 project files:");
        System.out.println("=====================================");

        try (var stream = Files.list(DEPR_DIR)) {
        for (var entry : stream.sorted().toList()) {
            String name = entry.getFileName().toString().toLowerCase();
            // Only parse Gerber files
            if (!name.endsWith(".gbr") && !name.endsWith(".ger") &&
                !name.endsWith(".gtl") && !name.endsWith(".gbl") &&
                !name.endsWith(".gts") && !name.endsWith(".gbs") &&
                !name.endsWith(".gto") && !name.endsWith(".gbo") &&
                !name.endsWith(".gtp") && !name.endsWith(".gbp") &&
                !name.endsWith(".gko") && !name.endsWith(".g1") &&
                !name.endsWith(".g2") && !name.endsWith(".gm1")) {
                continue;
            }

            try {
                String content = Files.readString(entry);
                long start = System.currentTimeMillis();
                GerberDocument doc = parser.parse(content);
                long elapsed = System.currentTimeMillis() - start;

                totalTime += elapsed;
                fileCount++;

                System.out.printf("  %-40s %6d KB  %5dms  %5d objects%n",
                    entry.getFileName(), content.length() / 1024, elapsed, doc.getObjects().size());

                // Each file should parse in under 1 second
                assertTrue(elapsed < 1000,
                    "Parsing " + entry.getFileName() + " took too long: " + elapsed + "ms");

            } catch (Exception e) {
                System.out.println("  " + entry.getFileName() + " - ERROR: " + e.getMessage());
            }
        }

        System.out.println("=====================================");
        System.out.println("Total: " + fileCount + " files in " + totalTime + "ms");
        System.out.println("Average: " + (fileCount > 0 ? totalTime / fileCount : 0) + "ms per file");
        }

        assertTrue(fileCount > 0, "No Gerber files found in " + DEPR_DIR);
    }
}
