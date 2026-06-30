package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.ApertureFunction;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.parser.GerberParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First-class X2/X3 attribute model — TA (aperture), TO (object), TD (delete), and typed
 * accessors. Spec §5.
 */
public class AttributeModelTest {

    private final GerberParser parser = new GerberParser();

    private static final String HEADER = """
        %FSLAX26Y26*%
        %MOMM*%
        """;

    @Test
    void apertureAttributesAreRetainedAndTyped() {
        String gerber = HEADER + """
            %TA.AperFunction,SMDPad,CuDef*%
            %ADD10R,1.2X0.6*%
            %TA.AperFunction,ViaPad*%
            %ADD11C,0.8*%
            %TD*%
            %ADD12C,0.3*%
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        Aperture ap10 = doc.getAperture(10);
        assertEquals(List.of("SMDPad", "CuDef"), ap10.getAttribute(".AperFunction"));
        assertEquals(ApertureFunction.SMD_PAD, ap10.getFunction());

        Aperture ap11 = doc.getAperture(11);
        assertEquals(ApertureFunction.VIA_PAD, ap11.getFunction());

        // %TD% cleared the aperture attribute dictionary, so D12 carries no function.
        Aperture ap12 = doc.getAperture(12);
        assertNull(ap12.getFunction());
        assertTrue(ap12.getAttributes().isEmpty());
    }

    @Test
    void objectNetAttributeAppliesUntilDeleted() {
        String gerber = HEADER + """
            %ADD13C,0.25*%
            D13*
            G01*
            %TO.N,GND*%
            X5000000Y5000000D02*
            X5000000Y10000000D01*
            %TD.N*%
            X0Y0D02*
            X1000000Y0D01*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        List<GraphicsObject> objs = doc.getObjects();
        assertEquals(2, objs.size());
        assertEquals("GND", objs.get(0).getNet(), "first draw is on net GND");
        assertNull(objs.get(1).getNet(), "%TD.N% removed the net before the second draw");
    }

    @Test
    void componentAndPinObjectAttributesOnFlash() {
        String gerber = HEADER + """
            %ADD10C,1.0*%
            D10*
            %TO.C,U1*%
            %TO.P,U1,2*%
            X5000000Y5000000D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        GraphicsObject flash = doc.getObjects().get(0);
        assertEquals("U1", flash.getComponentRef());
        assertEquals("2", flash.getPinNumber());
        assertEquals(List.of("U1", "2"), flash.getAttribute(".P"));
    }

    @Test
    void deleteAllClearsBothDictionaries() {
        // After a bare %TD%, neither aperture nor object attributes carry over.
        String gerber = HEADER + """
            %TA.AperFunction,Conductor*%
            %ADD13C,0.25*%
            %TO.N,SIG*%
            %TD*%
            %ADD14C,0.3*%
            D14*
            X0Y0D02*
            X1000000Y0D01*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        assertNull(doc.getAperture(14).getFunction());
        assertNull(doc.getObjects().get(0).getNet());
    }

    @Test
    void typedFileAttributeAccessors() {
        String gerber = HEADER + """
            %TF.FileFunction,Copper,L1,Top*%
            %TF.FilePolarity,Positive*%
            %TF.GenerationSoftware,TestSuite,GerberTestGenerator,1.0*%
            %ADD10C,0.5*%
            D10*
            X0Y0D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        assertEquals("Copper", doc.getFileFunction());
        assertEquals(List.of("Copper", "L1", "Top"), doc.getFileFunctionValues());
        assertEquals("Positive", doc.getFilePolarity());
        assertEquals("TestSuite GerberTestGenerator", doc.getGenerationSoftware());
    }

    @Test
    void aperturAndObjectAttributeFixtureParses() throws Exception {
        String content = java.nio.file.Files.readString(
            java.nio.file.Path.of("test-gerber-suite/attributes/02_aperture_object_attributes.gbr"));
        GerberDocument doc = parser.parse(content);

        // Spot-check the fixture: D10 is an SMD pad, D13 a conductor, D15 has no function (TD before it).
        assertEquals(ApertureFunction.SMD_PAD, doc.getAperture(10).getFunction());
        assertEquals(ApertureFunction.CONDUCTOR, doc.getAperture(13).getFunction());
        assertNull(doc.getAperture(15).getFunction());
    }

    @Test
    void typedFileAttributesPartDateProjectMd5() {
        String gerber = HEADER + """
            %TF.Part,Single*%
            %TF.CreationDate,2024-05-01T10:00:00+01:00*%
            %TF.ProjectId,MyProj,abc-123,RevA*%
            %TF.MD5,deadbeef*%
            %TF.SameCoordinates,Original*%
            %ADD10C,0.5*%
            D10*
            X0Y0D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        assertEquals("Single", doc.getPart());
        assertEquals("2024-05-01T10:00:00+01:00", doc.getCreationDate());
        assertEquals(List.of("MyProj", "abc-123", "RevA"), doc.getProjectId());
        assertEquals("deadbeef", doc.getMd5());
        assertEquals("Original", doc.getSameCoordinates());
    }

    @Test
    void newApertureFunctionValuesAreRecognized() {
        String gerber = HEADER + """
            %TA.AperFunction,WasherPad*%
            %ADD10C,1*%
            %TA.AperFunction,CopperBalancing*%
            %ADD11C,1*%
            %TA.AperFunction,Border*%
            %ADD12C,1*%
            %TA.AperFunction,OtherCopper,frame*%
            %ADD13C,1*%
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        assertEquals(ApertureFunction.WASHER_PAD, doc.getAperture(10).getFunction());
        assertEquals(ApertureFunction.COPPER_BALANCING, doc.getAperture(11).getFunction());
        assertEquals(ApertureFunction.BORDER, doc.getAperture(12).getFunction());
        assertEquals(ApertureFunction.OTHER_COPPER, doc.getAperture(13).getFunction());
    }

    @Test
    void drillToleranceNormalizedFromInchToMm() {
        // Inch file: .DrillTolerance 0.01"/0.005" must come back as 0.254mm / 0.127mm.
        String gerber = """
            %FSLAX24Y24*%
            %MOIN*%
            %TA.AperFunction,ViaDrill*%
            %TA.DrillTolerance,0.01,0.005*%
            %ADD10C,0.02*%
            D10*
            X0Y0D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        Aperture ap = doc.getAperture(10);
        assertEquals(ApertureFunction.VIA_DRILL, ap.getFunction());
        double[] tol = ap.getDrillTolerance();
        assertNotNull(tol);
        assertEquals(0.254, tol[0], 1e-6);
        assertEquals(0.127, tol[1], 1e-6);
        // The generic dictionary is normalized too (everything-is-mm contract).
        assertEquals(List.of("0.254", "0.127"), ap.getAttribute(".DrillTolerance"));
    }

    @Test
    void drillToleranceUnchangedForMmFile() {
        String gerber = HEADER + """
            %TA.DrillTolerance,0.1,0.05*%
            %ADD10C,1*%
            D10*
            X0Y0D03*
            M02*
            """;
        Aperture ap = parser.parse(gerber).getAperture(10);
        double[] tol = ap.getDrillTolerance();
        assertEquals(0.1, tol[0], 1e-9);
        assertEquals(0.05, tol[1], 1e-9);
    }

    @Test
    void componentCharacteristicsWithHeightNormalizedToMm() {
        // Inch file: .CHgt 0.05" must come back as 1.27mm; other characteristics are typed strings.
        String gerber = """
            %FSLAX24Y24*%
            %MOIN*%
            %TF.FileFunction,Component,L1,Top*%
            %ADD10C,0.012*%
            D10*
            %TO.C,U1*%
            %TO.CVal,100nF*%
            %TO.CMnt,SMD*%
            %TO.CRot,90*%
            %TO.CMPN,GRM155*%
            %TO.CFtp,0402*%
            %TO.CHgt,0.05*%
            X0Y0D03*
            M02*
            """;
        GerberDocument doc = parser.parse(gerber);

        GraphicsObject flash = doc.getObjects().get(0);
        assertEquals("U1", flash.getComponentRef());
        assertEquals("100nF", flash.getComponentValue());
        assertEquals("SMD", flash.getComponentMountType());
        assertEquals("GRM155", flash.getComponentPartNumber());
        assertEquals("0402", flash.getComponentFootprint());
        assertEquals(90.0, flash.getComponentRotation(), 1e-9);
        assertEquals(1.27, flash.getComponentHeight(), 1e-6); // 0.05 in → mm
    }
}
