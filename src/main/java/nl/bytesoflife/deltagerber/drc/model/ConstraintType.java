package nl.bytesoflife.deltagerber.drc.model;

import java.util.Map;

public enum ConstraintType {
    CLEARANCE,
    TRACK_WIDTH,
    HOLE_SIZE,
    HOLE_TO_HOLE,
    EDGE_CLEARANCE,
    ANNULAR_WIDTH,
    SILK_CLEARANCE,
    TEXT_HEIGHT,
    TEXT_THICKNESS,
    VIA_DIAMETER,
    HOLE_CLEARANCE,
    DISALLOW;

    private static final Map<String, ConstraintType> KICAD_NAMES = Map.ofEntries(
            Map.entry("clearance", CLEARANCE),
            Map.entry("track_width", TRACK_WIDTH),
            Map.entry("hole_size", HOLE_SIZE),
            Map.entry("hole_to_hole", HOLE_TO_HOLE),
            Map.entry("edge_clearance", EDGE_CLEARANCE),
            Map.entry("annular_width", ANNULAR_WIDTH),
            Map.entry("silk_clearance", SILK_CLEARANCE),
            Map.entry("text_height", TEXT_HEIGHT),
            Map.entry("text_thickness", TEXT_THICKNESS),
            Map.entry("via_diameter", VIA_DIAMETER),
            Map.entry("hole_clearance", HOLE_CLEARANCE),
            Map.entry("disallow", DISALLOW)
    );

    public static ConstraintType fromKicadName(String name) {
        ConstraintType type = KICAD_NAMES.get(name.toLowerCase());
        if (type == null) {
            throw new IllegalArgumentException("Unknown constraint type: " + name);
        }
        return type;
    }
}
