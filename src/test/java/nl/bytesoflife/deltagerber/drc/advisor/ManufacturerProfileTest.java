package nl.bytesoflife.deltagerber.drc.advisor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManufacturerProfileTest {

    private ManufacturerProfile createTestProfile() {
        return new ManufacturerProfile("Test", List.of(
                new ManufacturingTier("Standard", 0, 0.127, 0.127, 0.3,
                        "Standard tier"),
                new ManufacturingTier("Advanced", 1, 0.1016, 0.1016, 0.2,
                        "Advanced tier"),
                new ManufacturingTier("HDI", 2, null, null, 0.15,
                        "HDI tier")
        ));
    }

    @Test
    void classifyTrace5milReturnsStandard() {
        ManufacturerProfile profile = createTestProfile();
        ManufacturingTier tier = profile.classifyTrace(0.127); // exactly 5mil
        assertEquals("Standard", tier.name());
    }

    @Test
    void classifyTrace6milReturnsStandard() {
        ManufacturerProfile profile = createTestProfile();
        ManufacturingTier tier = profile.classifyTrace(0.1524); // 6mil, comfortably above 5mil
        assertEquals("Standard", tier.name());
    }

    @Test
    void classifyTrace4milReturnsAdvanced() {
        ManufacturerProfile profile = createTestProfile();
        ManufacturingTier tier = profile.classifyTrace(0.1016); // exactly 4mil
        assertEquals("Advanced", tier.name());
    }

    @Test
    void classifyTraceBetween4and5milReturnsAdvanced() {
        ManufacturerProfile profile = createTestProfile();
        ManufacturingTier tier = profile.classifyTrace(0.11); // between 4 and 5mil
        assertEquals("Advanced", tier.name());
    }

    @Test
    void classifyTraceBelow4milReturnsHDI() {
        ManufacturerProfile profile = createTestProfile();
        ManufacturingTier tier = profile.classifyTrace(0.08); // 3mil
        assertEquals("HDI", tier.name());
    }

    @Test
    void overallTierIsMostExpensiveAcrossParameters() {
        ManufacturerProfile profile = createTestProfile();
        // Standard trace (5mil), but Advanced hole (0.25mm)
        ManufacturingTier tier = profile.classify(0.127, null, 0.25);
        assertEquals("Advanced", tier.name());
    }

    @Test
    void nextCheaperTierFromAdvancedReturnsStandard() {
        ManufacturerProfile profile = createTestProfile();
        ManufacturingTier advanced = profile.getTiers().get(1);
        ManufacturingTier cheaper = profile.nextCheaperTier(advanced);
        assertNotNull(cheaper);
        assertEquals("Standard", cheaper.name());
    }

    @Test
    void nextCheaperTierFromStandardReturnsNull() {
        ManufacturerProfile profile = createTestProfile();
        ManufacturingTier standard = profile.getTiers().get(0);
        ManufacturingTier cheaper = profile.nextCheaperTier(standard);
        assertNull(cheaper);
    }

    @Test
    void nextCheaperTierFromHDIReturnsAdvanced() {
        ManufacturerProfile profile = createTestProfile();
        ManufacturingTier hdi = profile.getTiers().get(2);
        ManufacturingTier cheaper = profile.nextCheaperTier(hdi);
        assertNotNull(cheaper);
        assertEquals("Advanced", cheaper.name());
    }

    @Test
    void classifyWithAllNullParametersReturnsCheapest() {
        ManufacturerProfile profile = createTestProfile();
        ManufacturingTier tier = profile.classify(null, null, null);
        assertEquals("Standard", tier.name());
    }
}
