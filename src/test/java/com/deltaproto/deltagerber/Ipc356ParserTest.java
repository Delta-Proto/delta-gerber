package com.deltaproto.deltagerber;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Unit;
import com.deltaproto.deltagerber.model.netlist.*;
import com.deltaproto.deltagerber.parser.Ipc356Parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Ipc356Parser}: synthetic {@code CUST}/{@code SI} fixtures verifying every
 * supported record type and the mm unit conversions, plus real-world Allegro and EAGLE fixtures
 * vendored from gerbonara.
 */
public class Ipc356ParserTest {

    private final Ipc356Parser parser = new Ipc356Parser();

    private static final double EPS = 1e-4;

    private static String load(String name) throws IOException {
        return Files.readString(Path.of("testdata/ipc356/" + name));
    }

    // ---------------------------------------------------------------- synthetic CUST

    @Test
    void parsesSyntheticCustFixture() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-cust.ipc"));

        // A well-formed file produces no warnings.
        assertTrue(doc.getWarnings().isEmpty(), () -> "unexpected warnings: " + doc.getWarnings());

        // Coordinates come back in mm (CUST = 0.0001 inch → inch × 25.4).
        assertEquals(Unit.MM, doc.getUnit());
        assertEquals("CUST 0", doc.getUnitsDeclaration());
        assertEquals("SYNTH-CUST", doc.getJob());
        assertEquals("IPC-D-356A", doc.getVersion());
        assertTrue(doc.getImages().contains("PRIMARY"));

        assertEquals(8, doc.getTestRecords().size());
        assertEquals(1, doc.getConductors().size());
        assertEquals(1, doc.getAdjacencies().size());
        assertEquals(2, doc.getOutlines().size());
    }

    @Test
    void custThroughHoleRecordFieldsInMm() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-cust.ipc"));
        TestRecord r = doc.getTestRecords().get(0);

        assertEquals(TestPointType.THROUGH_HOLE, r.getType());
        assertEquals("317", r.getOpCode());
        assertEquals("GND", r.getNetName());
        assertTrue(r.isConnected());
        assertEquals("U1", r.getRefDes());
        assertEquals("1", r.getPin());
        assertFalse(r.isVia());
        assertFalse(r.isMidNet());
        assertTrue(r.hasHole());
        assertEquals(0.508, r.getHoleDiameterMm(), EPS);   // D0200 = 0.02 inch
        assertEquals(Plating.PLATED, r.getPlating());
        assertEquals(0, r.getAccessSide());                 // A00 = both sides
        assertEquals(25.4, r.getX(), EPS);                  // 10000 counts = 1.0 inch × 25.4
        assertEquals(50.8, r.getY(), EPS);                  // 20000 counts = 2.0 inch × 25.4
        assertEquals(0.9652, r.getFeatureSizeXMm(), EPS);   // X0380 = 0.038 inch
        assertEquals(0, r.getFeatureSizeYMm(), EPS);        // round pad (Y absent)
        assertEquals(0, r.getSolderMask());
    }

    @Test
    void custSmdRecordWithContinuationLocationAndMaskClearance() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-cust.ipc"));
        TestRecord r = doc.getTestRecords().get(1);

        assertEquals(TestPointType.SMD, r.getType());
        assertEquals("VCC", r.getNetName());
        assertEquals("R1", r.getRefDes());
        assertEquals("2", r.getPin());
        assertFalse(r.hasHole());
        assertEquals(1, r.getAccessSide());                 // A01 = primary side
        assertEquals(30.48, r.getX(), EPS);
        assertEquals(60.96, r.getY(), EPS);
        assertEquals(1.524, r.getFeatureSizeXMm(), EPS);
        assertEquals(0.762, r.getFeatureSizeYMm(), EPS);
        assertEquals(90, r.getRotationDegrees(), EPS);
        assertEquals(1, r.getSolderMask());

        // 099 continuation supplied the actual probe location.
        assertTrue(r.hasTestPointLocation());
        assertEquals(30.5054, r.getTestPointXMm(), EPS);
        assertEquals(60.9854, r.getTestPointYMm(), EPS);

        // 088 continuation supplied the solder-mask clearance feature size.
        assertTrue(r.hasSolderMaskClearance());
        assertEquals(1.778, r.getSolderMaskClearanceXMm(), EPS);
        assertEquals(1.016, r.getSolderMaskClearanceYMm(), EPS);
    }

    @Test
    void custToolingHoleViaNcAndMidNet() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-cust.ipc"));

        TestRecord tooling = doc.getTestRecords().get(2);
        assertEquals(TestPointType.TOOLING_HOLE, tooling.getType());
        assertEquals(Plating.UNPLATED, tooling.getPlating());
        assertEquals(8.128, tooling.getHoleDiameterMm(), EPS); // D3200 = 0.32 inch
        assertFalse(tooling.isConnected());     // blank net field = no connection
        assertNull(tooling.getNetName());

        TestRecord via = doc.getTestRecords().get(3);
        assertEquals(TestPointType.VIA, via.getType());
        assertTrue(via.isVia());
        assertEquals(1, via.getViaStartLayer());
        assertEquals(3, via.getViaEndLayer());
        assertEquals(0.381, via.getHoleDiameterMm(), EPS);
        assertEquals(1, via.getAccessSide());   // primary A01 from the 307 line
        // The attached 027 continuation supplies the secondary-side access and the surface feature.
        assertEquals(4, via.getSecondaryAccessSide());          // A04
        assertTrue(via.hasAttachedFeature());
        assertEquals(19.812, via.getAttachedFeatureXMm(), EPS); // 7800 counts = 0.78 inch × 25.4
        assertEquals(22.86, via.getAttachedFeatureYMm(), EPS);
        assertEquals(3.048, via.getAttachedFeatureSizeXMm(), EPS); // X1200
        assertEquals(1.27, via.getAttachedFeatureSizeYMm(), EPS);  // Y0500

        TestRecord nc = doc.getTestRecords().get(4);
        assertFalse(nc.isConnected());          // N/C
        assertNull(nc.getNetName());
        assertEquals("TP9", nc.getRefDes());

        TestRecord mid = doc.getTestRecords().get(5);
        assertTrue(mid.isMidNet());
        assertEquals("GND", mid.getNetName());
    }

    @Test
    void custNnameAliasResolvedAndContinuationHoleApplied() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-cust.ipc"));

        // P NNAME alias resolution (EAGLE form: the netlist references the full "NNAME1" token).
        assertEquals("A_VERY_LONG_NET_NAME_OVER_14", doc.getNetNameAliases().get("NNAME1"));
        TestRecord aliased = doc.getTestRecords().get(6);
        assertEquals("NNAME1", aliased.getRawNetName());
        assertEquals("A_VERY_LONG_NET_NAME_OVER_14", aliased.getNetName());

        // NET2's 317 record carried no hole; a 017 continuation supplied it.
        TestRecord net2 = doc.getTestRecords().get(7);
        assertEquals("NET2", net2.getNetName());
        assertTrue(net2.hasHole(), "hole should be merged from the 017 continuation");
        assertEquals(0.508, net2.getHoleDiameterMm(), EPS);
        assertEquals(Plating.PLATED, net2.getPlating());
    }

    @Test
    void custConductorModalAndAsteriskSegments() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-cust.ipc"));
        Conductor c = doc.getConductors().get(0);

        assertEquals("SIG1", c.getNetName());
        assertEquals(1, c.getLayer());
        assertTrue(c.isRound());
        assertEquals(0.381, c.getApertureWidthMm(), EPS);   // round aperture X150

        List<List<NetPoint>> chains = c.getChains();
        assertEquals(2, chains.size(), "asterisk starts a second chain");

        // Chain 1: modal coordinates (missing X or Y repeats the previous value).
        List<NetPoint> chain1 = chains.get(0);
        assertEquals(4, chain1.size());
        assertEquals(25.4, chain1.get(0).x(), EPS);
        assertEquals(50.8, chain1.get(0).y(), EPS);
        assertEquals(38.1, chain1.get(1).x(), EPS);
        assertEquals(50.8, chain1.get(1).y(), EPS);  // modal Y from previous point
        assertEquals(38.1, chain1.get(2).x(), EPS);  // modal X from previous point
        assertEquals(63.5, chain1.get(2).y(), EPS);

        // Chain 2: the 078 continuation appends a third point, sharing modal state.
        List<NetPoint> chain2 = chains.get(1);
        assertEquals(3, chain2.size());
        assertEquals(76.2, chain2.get(0).x(), EPS);
        assertEquals(101.6, chain2.get(2).x(), EPS); // X40000 from the 078 line
        assertEquals(76.2, chain2.get(2).y(), EPS);  // modal Y carried across the continuation
    }

    @Test
    void custAdjacencyList() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-cust.ipc"));
        Adjacency a = doc.getAdjacencies().get(0);

        assertEquals("GND", a.getNetName());
        assertEquals(List.of("VCC", "SIG1", "N$1", "N$2"), a.getAdjacentNets());
    }

    @Test
    void custOutlines() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-cust.ipc"));

        Outline board = doc.getOutlines().get(0);
        assertEquals("BOARD_EDGE", board.getOutlineType());
        assertEquals(0, board.getDrawingWidthMm(), EPS);    // no leading drawing-size aperture
        assertEquals(1, board.getChains().size());
        // 4 corners + the 089 closing point.
        assertEquals(5, board.getChains().get(0).size());
        assertEquals(101.6, board.getChains().get(0).get(1).x(), EPS); // X40000

        Outline other = doc.getOutlines().get(1);
        assertEquals("OTHER_FAB", other.getOutlineType());
        assertEquals(0.381, other.getDrawingWidthMm(), EPS); // leading X150 = round drawing size
        assertEquals(2, other.getChains().get(0).size());
    }

    @Test
    void custBoundingBoxIsValidMm() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-cust.ipc"));
        BoundingBox bb = doc.getBoundingBox();
        assertTrue(bb.isValid());
        assertTrue(bb.getWidth() > 0 && bb.getHeight() > 0);
        assertTrue(bb.getMaxX() <= 200, "coordinates should be in mm, not raw counts");
    }

    // ---------------------------------------------------------------- synthetic SI

    @Test
    void parsesSyntheticSiFixtureInMm() throws IOException {
        Ipc356Document doc = parser.parse(load("synthetic-si.ipc"));

        assertEquals(Unit.MM, doc.getUnit());
        assertEquals("SI", doc.getUnitsDeclaration());
        assertTrue(doc.getWarnings().isEmpty(), () -> "unexpected warnings: " + doc.getWarnings());

        // SI = 0.001 mm grid: X000150 → 0.150 mm.
        TestRecord r = doc.getTestRecords().get(0);
        assertEquals(0.150, r.getX(), EPS);
        assertEquals(0.150, r.getY(), EPS);
        assertEquals(0.300, r.getHoleDiameterMm(), EPS);
        assertEquals(0.500, r.getFeatureSizeXMm(), EPS);

        TestRecord r2 = doc.getTestRecords().get(1);
        assertEquals(250.0, r2.getX(), EPS);
        assertEquals(300.0, r2.getY(), EPS);
    }

    @Test
    void siAndCustScaleDifferently() throws IOException {
        // The same raw count means different mm under SI vs CUST — proving the unit is consumed.
        Ipc356Document si = parser.parse(load("synthetic-si.ipc"));
        Ipc356Document cust = parser.parse(load("synthetic-cust.ipc"));
        // SI X000150 = 0.150 mm; CUST X010000 = 1.0 inch = 25.4 mm.
        assertEquals(0.150, si.getTestRecords().get(0).getX(), EPS);
        assertEquals(25.4, cust.getTestRecords().get(0).getX(), EPS);
    }

    // ---------------------------------------------------------------- Allegro quirk

    @Test
    void tolerateAllegroNnameAliasComment() {
        // Allegro emits long-net-name aliases as non-standard comments rather than P records.
        String ipc = """
            C  IPC-D-356 Ouptut File from Allegro
            P  UNITS CUST 0
            C  NNAMEm0000 UNNAMED_2_VERY_LONG_NET
            317m0000            U5    -1    D0450PA00X+031400Y+018650X0650Y0650R270 S3
            999
            """;
        Ipc356Document doc = parser.parse(ipc);

        assertEquals("UNNAMED_2_VERY_LONG_NET", doc.getNetNameAliases().get("m0000"));
        TestRecord r = doc.getTestRecords().get(0);
        assertEquals("m0000", r.getRawNetName());
        assertEquals("UNNAMED_2_VERY_LONG_NET", r.getNetName());
        // Tolerated, but flagged.
        assertTrue(doc.getWarnings().stream().anyMatch(w -> w.toLowerCase().contains("allegro")),
            "expected a warning about the non-standard alias comment");
    }

    // ---------------------------------------------------------------- real fixtures

    @Test
    void parsesRealAllegroMinnowMaxFixture() throws IOException {
        Ipc356Document doc = parser.parse(load("minnowmax-revA1.ipc"));

        assertEquals(Unit.MM, doc.getUnit());
        // 1996×317 + 3763×327 + 4×367 = 5763 test records.
        assertEquals(5763, doc.getTestRecords().size());

        BoundingBox bb = doc.getBoundingBox();
        assertTrue(bb.isValid());
        // MinnowBoard Max is ~99 × 74 mm — sanity-check the mm-scale bounding box.
        assertTrue(bb.getWidth() > 10 && bb.getWidth() < 500, "width mm = " + bb.getWidth());
        assertTrue(bb.getHeight() > 10 && bb.getHeight() < 500, "height mm = " + bb.getHeight());
    }

    @Test
    void parsesRealEagleFixtureWithAliasAndOutline() throws IOException {
        Ipc356Document doc = parser.parse(load("eagle-ipc-d-356.ipc"));

        assertEquals(Unit.MM, doc.getUnit());
        assertEquals(105, doc.getTestRecords().size()); // 30×317 + 75×327
        assertEquals(1, doc.getOutlines().size());
        assertEquals("BOARD_EDGE", doc.getOutlines().get(0).getOutlineType());

        // P NNAME alias defined in the header and referenced by a record.
        assertEquals("A_REALLY_LONG_NET_NAME", doc.getNetNameAliases().get("NNAME1"));
        boolean resolved = doc.getTestRecords().stream()
            .anyMatch(r -> "NNAME1".equals(r.getRawNetName())
                        && "A_REALLY_LONG_NET_NAME".equals(r.getNetName()));
        assertTrue(resolved, "the NNAME1 record should resolve to the long net name");

        assertTrue(doc.getBoundingBox().isValid());
    }
}
