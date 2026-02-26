package nl.bytesoflife.deltagerber.drc.advisor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinProfilesTest {

    @Test
    void nextPcb2LayerLoadsAndHasExpectedTiers() {
        ManufacturerProfile profile = BuiltinProfiles.nextPcb2Layer();
        assertNotNull(profile);
        assertEquals("NextPCB 2-Layer", profile.getName());
        assertEquals(3, profile.getTiers().size());
    }

    @Test
    void nextPcb2LayerTiersAreOrdered() {
        ManufacturerProfile profile = BuiltinProfiles.nextPcb2Layer();
        for (int i = 0; i < profile.getTiers().size(); i++) {
            assertEquals(i, profile.getTiers().get(i).order());
        }
    }

    @Test
    void nextPcb2LayerStandardTierHasCorrectValues() {
        ManufacturingTier standard = BuiltinProfiles.nextPcb2Layer().getTiers().get(0);
        assertEquals("Standard", standard.name());
        assertEquals(0.127, standard.minTraceWidthMm());
        assertEquals(0.127, standard.minSpaceMm());
        assertEquals(0.3, standard.minHoleSizeMm());
    }

    @Test
    void nextPcb4LayerLoadsAndHasExpectedTiers() {
        ManufacturerProfile profile = BuiltinProfiles.nextPcb4Layer();
        assertNotNull(profile);
        assertEquals("NextPCB 4-Layer", profile.getName());
        assertEquals(4, profile.getTiers().size());
    }

    @Test
    void nextPcb4LayerTiersAreOrdered() {
        ManufacturerProfile profile = BuiltinProfiles.nextPcb4Layer();
        for (int i = 0; i < profile.getTiers().size(); i++) {
            assertEquals(i, profile.getTiers().get(i).order());
        }
    }

    @Test
    void nextPcb4LayerStandardTierHas8milTrace() {
        ManufacturingTier standard = BuiltinProfiles.nextPcb4Layer().getTiers().get(0);
        assertEquals("Standard", standard.name());
        assertEquals(0.2032, standard.minTraceWidthMm());
    }
}
