package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.GerberJobDocument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GerberJobParserTest {

    /** A real KiCad 7 job file, trimmed to the parts we read. */
    private static final String KICAD_JOB = """
            {
              "Header": {
                "GenerationSoftware": { "Vendor": "KiCad", "Application": "Pcbnew", "Version": "7.0.6" },
                "CreationDate": "2023-07-12T11:50:11+01:00"
              },
              "GeneralSpecs": {
                "ProjectId": { "Name": "simple_2layer", "GUID": "73696d70-6c65-45f3", "Revision": "rev?" },
                "Size": { "X": 40.1, "Y": 55.1 },
                "LayerNumber": 2,
                "BoardThickness": 1.6,
                "Finish": "None"
              },
              "DesignRules": [ { "Layers": "Outer", "MinLineWidth": 0.2 } ],
              "FilesAttributes": [
                { "Path": "simple_2layer-F_Cu.gbr", "FileFunction": "Copper,L1,Top", "FilePolarity": "Positive" },
                { "Path": "simple_2layer-B_Cu.gbr", "FileFunction": "Copper,L2,Bot", "FilePolarity": "Positive" },
                { "Path": "simple_2layer-Edge_Cuts.gbr", "FileFunction": "Profile", "FilePolarity": "Positive" }
              ],
              "MaterialStackup": [ { "Type": "Copper", "Name": "F.Cu" } ]
            }
            """;

    private final GerberJobParser parser = new GerberJobParser();

    @Test
    void readsTheGeneralSpecs() {
        GerberJobDocument job = parser.parse(KICAD_JOB);
        assertNotNull(job);
        assertEquals("KiCad", job.getVendor());
        assertEquals("Pcbnew", job.getGenerationSoftware().application());
        assertEquals("2023-07-12T11:50:11+01:00", job.getCreationDate());
        assertEquals("simple_2layer", job.getProjectName());
        assertEquals("rev?", job.getProjectRevision());
        assertEquals(40.1, job.getSizeXMm(), 1e-9);
        assertEquals(55.1, job.getSizeYMm(), 1e-9);
        assertEquals(2, job.getLayerCount());
        assertEquals(1.6, job.getBoardThicknessMm(), 1e-9);
    }

    @Test
    @DisplayName("Every file in the set classifies from its declared FileFunction")
    void classifiesEveryFileItDeclares() {
        GerberJobDocument job = parser.parse(KICAD_JOB);
        assertEquals(3, job.getFiles().size());

        GerberJobDocument.JobFile topCopper = job.getFiles().get(0);
        assertEquals("simple_2layer-F_Cu.gbr", topCopper.getPath());
        assertEquals("Positive", topCopper.getFilePolarity());
        assertEquals(LayerFunction.COPPER, topCopper.getClassification().function());
        assertEquals(LayerSide.TOP, topCopper.getClassification().side());

        assertEquals(LayerFunction.COPPER, job.getFiles().get(1).getClassification().function());
        assertEquals(LayerSide.BOTTOM, job.getFiles().get(1).getClassification().side());
        assertEquals(LayerFunction.OUTLINE, job.getFiles().get(2).getClassification().function());
    }

    @Test
    void toleratesMissingSections() {
        GerberJobDocument job = parser.parse("{\"FilesAttributes\": []}");
        assertNotNull(job);
        assertNull(job.getVendor());
        assertNull(job.getSizeXMm());
        assertEquals(0, job.getFiles().size());
    }

    @Test
    @DisplayName("Anything that is not a job file is declined, never guessed at")
    void rejectsNonJobFiles() {
        assertNull(parser.parse(null));
        assertNull(parser.parse(""));
        assertNull(parser.parse("   "));
        assertNull(parser.parse("%TF.FileFunction,Copper,L1,Top*%\n"));
        assertNull(parser.parse("{\"NotAJobFile\": true}"));
        assertNull(parser.parse("[1, 2, 3]"));
    }

    @Test
    @DisplayName("A job file with broken JSON yields null, so the caller can fall back per file")
    void malformedJsonIsDeclined() {
        assertNull(parser.parse("{\"GeneralSpecs\": {\"LayerNumber\": }}"));
        assertNull(parser.parse("{\"FilesAttributes\": [ {\"Path\": \"a.gbr\" ,]}"));
        assertNull(parser.parse("{\"GeneralSpecs\": {} "));
    }

    @Test
    @DisplayName("Strings keep their escapes; a Windows path survives intact")
    void jsonEscapes() {
        GerberJobDocument job = parser.parse(
                "{\"FilesAttributes\":[{\"Path\":\"sub\\\\dir\\/a\\u0042.gbr\",\"FileFunction\":\"Profile\"}]}");
        assertNotNull(job);
        assertEquals("sub\\dir/aB.gbr", job.getFiles().get(0).getPath());
    }
}
