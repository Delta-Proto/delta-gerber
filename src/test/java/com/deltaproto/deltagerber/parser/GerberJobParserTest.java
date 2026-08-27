package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.GerberJobDocument;
import com.deltaproto.deltagerber.model.gerber.GerberJobDocument.StackupEntry;
import com.deltaproto.deltagerber.model.gerber.GerberJobDocument.StackupType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GerberJobParserTest {

    /** A KiCad 8 job file, trimmed to the parts we read. */
    private static final String KICAD_JOB = """
            {
              "Header": {
                "GenerationSoftware": { "Vendor": "KiCad", "Application": "Pcbnew", "Version": "8.0.2" },
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
              "MaterialStackup": [
                { "Type": "Legend", "Name": "Top Silk Screen" },
                { "Type": "SolderPaste", "Name": "Top Solder Paste" },
                { "Type": "SolderMask", "Thickness": 0.01, "Name": "Top Solder Mask" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "F.Cu" },
                { "Type": "Dielectric", "Thickness": 1.51, "Material": "FR4", "Name": "F.Cu/B.Cu",
                  "Notes": "Type: dielectric layer 1 (from F.Cu to B.Cu)" },
                { "Type": "Copper", "Thickness": 0.035, "Name": "B.Cu" },
                { "Type": "SolderMask", "Thickness": 0.01, "Name": "Bottom Solder Mask" },
                { "Type": "SolderPaste", "Name": "Bottom Solder Paste" },
                { "Type": "Legend", "Name": "Bottom Silk Screen" }
              ]
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

    @Test
    @DisplayName("The MaterialStackup is read in order, top of the board first")
    void readsTheMaterialStackup() {
        GerberJobDocument job = parser.parse(KICAD_JOB);
        List<StackupEntry> stackup = job.getMaterialStackup();
        assertEquals(9, stackup.size());

        assertEquals(StackupType.LEGEND, stackup.get(0).type());
        assertEquals("Top Silk Screen", stackup.get(0).name());
        assertNull(stackup.get(0).thicknessMm(), "the file states no thickness for the legend");

        assertEquals(StackupType.SOLDERPASTE, stackup.get(1).type());
        assertEquals(StackupType.SOLDERMASK, stackup.get(2).type());
        assertEquals(0.01, stackup.get(2).thicknessMm(), 1e-9);

        StackupEntry topCopper = stackup.get(3);
        assertEquals(StackupType.COPPER, topCopper.type());
        assertEquals("Copper", topCopper.rawType());
        assertEquals("F.Cu", topCopper.name());
        assertEquals(0.035, topCopper.thicknessMm(), 1e-9);

        StackupEntry dielectric = stackup.get(4);
        assertEquals(StackupType.DIELECTRIC, dielectric.type());
        assertEquals("FR4", dielectric.material());
        assertEquals(1.51, dielectric.thicknessMm(), 1e-9);
        assertTrue(dielectric.notes().startsWith("Type: dielectric layer 1"));

        assertEquals(StackupType.LEGEND, stackup.get(8).type());
        assertEquals("Bottom Silk Screen", stackup.get(8).name());
    }

    @Test
    @DisplayName("A job file without a stack-up reads fine and simply has none")
    void jobFileWithoutAStackup() {
        GerberJobDocument job = parser.parse("""
                {
                  "GeneralSpecs": { "Size": { "X": 20.0, "Y": 10.0 }, "LayerNumber": 2, "BoardThickness": 1.6 },
                  "FilesAttributes": [
                    { "Path": "board-F_Cu.gbr", "FileFunction": "Copper,L1,Top", "FilePolarity": "Positive" }
                  ]
                }
                """);
        assertNotNull(job);
        assertEquals(1.6, job.getBoardThicknessMm(), 1e-9);
        assertEquals(1, job.getFiles().size());
        assertTrue(job.getMaterialStackup().isEmpty());
    }

    @Test
    @DisplayName("A job file that leads with its stack-up is still recognised as one")
    void detectsAJobFileThatOnlyDeclaresItsStackup() {
        GerberJobDocument job = parser.parse(
                "{\"MaterialStackup\": [ {\"Type\": \"Copper\", \"Name\": \"F.Cu\"} ]}");
        assertNotNull(job);
        assertEquals(1, job.getMaterialStackup().size());
    }

    @Test
    @DisplayName("An unmodelled Type is carried through rather than dropped")
    void unknownStackupTypeKeepsItsName() {
        GerberJobDocument job = parser.parse(
                "{\"MaterialStackup\": [ {\"Type\": \"Coverlay\", \"Thickness\": 0.025} ]}");
        StackupEntry entry = job.getMaterialStackup().get(0);
        assertEquals(StackupType.OTHER, entry.type());
        assertEquals("Coverlay", entry.rawType());
        assertEquals(0.025, entry.thicknessMm(), 1e-9);
    }

    @Test
    @DisplayName("Type spellings differ between tools; case and spacing do not decide the type")
    void stackupTypeSpelling() {
        assertEquals(StackupType.SOLDERMASK, StackupType.of("Solder Mask"));
        assertEquals(StackupType.SOLDERMASK, StackupType.of("soldermask"));
        assertEquals(StackupType.SOLDERPASTE, StackupType.of("Solder-Paste"));
        assertEquals(StackupType.FINISH, StackupType.of("Surface Finish"));
        assertEquals(StackupType.OTHER, StackupType.of(null));
    }
}
