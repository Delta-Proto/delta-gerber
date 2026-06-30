package com.deltaproto.deltagerber.model.gerber.aperture;

/**
 * Typed view of the standard {@code .AperFunction} aperture attribute (Gerber X2/X3, spec §5.6.10).
 *
 * <p>The {@code .AperFunction} attribute classifies what an aperture represents on the board —
 * a via pad, an SMD pad, a conductor, a drilled hole, a fiducial, and so on. It is the key input
 * for design-rule / DFM checks (annular ring, conductor width, drill tables), which is why it is
 * surfaced as a typed enum here in addition to the raw attribute values.
 *
 * <p>Unknown or vendor-specific values map to {@link #OTHER}; the raw string remains available via
 * {@link Aperture#getAttribute(String)}.
 */
public enum ApertureFunction {
    // Pads
    VIA_PAD("ViaPad"),
    SMD_PAD("SMDPad"),
    COMPONENT_PAD("ComponentPad"),
    CONNECTOR_PAD("ConnectorPad"),
    HEATSINK_PAD("HeatsinkPad"),
    BGA_PAD("BGAPad"),
    TEST_PAD("TestPad"),
    FIDUCIAL_PAD("FiducialPad"),
    THERMAL_RELIEF_PAD("ThermalReliefPad"),
    CASTELLATED_PAD("CastellatedPad"),
    WASHER_PAD("WasherPad"),
    ANTI_PAD("AntiPad"),
    OTHER_PAD("OtherPad"),
    // Tracks / copper
    CONDUCTOR("Conductor"),
    ETCHED_COMPONENT("EtchedComponent"),
    NON_CONDUCTOR("NonConductor"),
    COPPER_BALANCING("CopperBalancing"),
    BORDER("Border"),
    OTHER_COPPER("OtherCopper"),
    // Drills
    VIA_DRILL("ViaDrill"),
    COMPONENT_DRILL("ComponentDrill"),
    MECHANICAL_DRILL("MechanicalDrill"),
    BACK_DRILL("BackDrill"),
    CASTELLATED_DRILL("CastellatedDrill"),
    OTHER_DRILL("OtherDrill"),
    // Component / assembly (X3)
    COMPONENT_MAIN("ComponentMain"),
    COMPONENT_OUTLINE("ComponentOutline"),
    COMPONENT_PIN("ComponentPin"),
    // Documentation / profile
    PROFILE("Profile"),
    MATERIAL("Material"),
    NON_MATERIAL("NonMaterial"),
    // Fallback for unknown / vendor-specific values
    OTHER("Other");

    private final String token;

    ApertureFunction(String token) {
        this.token = token;
    }

    /** The exact token as written in the Gerber {@code .AperFunction} attribute. */
    public String token() {
        return token;
    }

    /**
     * Maps a {@code .AperFunction} first value to its enum constant (case-insensitive),
     * returning {@link #OTHER} for {@code null} or unrecognised values.
     */
    public static ApertureFunction fromValue(String value) {
        if (value != null) {
            for (ApertureFunction f : values()) {
                if (f.token.equalsIgnoreCase(value)) {
                    return f;
                }
            }
        }
        return OTHER;
    }
}
