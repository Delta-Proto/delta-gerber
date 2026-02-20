package nl.bytesoflife.deltagerber.drc.model;

public enum Severity {
    ERROR,
    WARNING,
    IGNORE;

    public static Severity fromKicadName(String name) {
        return switch (name.toLowerCase()) {
            case "error" -> ERROR;
            case "warning" -> WARNING;
            case "ignore" -> IGNORE;
            default -> ERROR;
        };
    }
}
