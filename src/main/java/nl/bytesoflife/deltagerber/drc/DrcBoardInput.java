package nl.bytesoflife.deltagerber.drc;

import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;

import java.util.*;

public class DrcBoardInput {

    private final Map<String, GerberDocument> layers = new LinkedHashMap<>();
    private final List<DrillDocument> drillFiles = new ArrayList<>();

    private static final Set<String> OUTER_LAYERS = Set.of("F.Cu", "B.Cu");

    public DrcBoardInput addGerberLayer(String kicadLayerName, GerberDocument doc) {
        layers.put(kicadLayerName, doc);
        return this;
    }

    public DrcBoardInput addGerberLayerAuto(GerberDocument doc) {
        String function = doc.getFileFunction();
        if (function != null) {
            String mapped = mapFileFunction(function);
            if (mapped != null) {
                layers.put(mapped, doc);
            }
        }
        return this;
    }

    public DrcBoardInput addDrill(DrillDocument doc) {
        drillFiles.add(doc);
        return this;
    }

    public Map<String, GerberDocument> getLayers() {
        return Collections.unmodifiableMap(layers);
    }

    public GerberDocument getLayer(String kicadLayerName) {
        return layers.get(kicadLayerName);
    }

    public List<DrillDocument> getDrillFiles() {
        return Collections.unmodifiableList(drillFiles);
    }

    public static boolean isOuterLayer(String layerName) {
        return OUTER_LAYERS.contains(layerName);
    }

    public static boolean isInnerLayer(String layerName) {
        return layerName.startsWith("In") && layerName.endsWith(".Cu");
    }

    public static boolean isCopperLayer(String layerName) {
        return layerName.endsWith(".Cu");
    }

    /**
     * Maps a filename (from a Gerber ZIP) to a KiCAD layer name using common naming conventions.
     * Returns null if no mapping is found.
     */
    public static String mapFilenameToLayer(String filename) {
        if (filename == null) return null;
        String lower = filename.toLowerCase();

        // Copper layers
        if (lower.contains("gtl") || lower.contains("f_cu") || lower.endsWith(".top")) return "F.Cu";
        if (lower.contains("gbl") || lower.contains("b_cu") || lower.endsWith(".bot") || lower.endsWith(".bottom")) return "B.Cu";
        if (lower.contains("g2") || lower.contains("in1")) return "In1.Cu";
        if (lower.contains("g3") || lower.contains("in2")) return "In2.Cu";

        // Silkscreen
        if (lower.contains("gto") || lower.contains("f_silks")) return "F.Silkscreen";
        if (lower.contains("gbo") || lower.contains("b_silks")) return "B.Silkscreen";

        // Solder mask
        if (lower.contains("gts") || lower.contains("f_mask")) return "F.Mask";
        if (lower.contains("gbs") || lower.contains("b_mask")) return "B.Mask";

        // Paste
        if (lower.contains("gtp") || lower.contains("f_paste")) return "F.Paste";
        if (lower.contains("gbp") || lower.contains("b_paste")) return "B.Paste";

        // Outline / Edge cuts
        if (lower.contains("gko") || lower.contains("gm1") || lower.contains("edge")) return "Edge.Cuts";

        return null;
    }

    /**
     * Maps Altium-specific file extensions to KiCAD layer names.
     * Used as a third fallback after fileFunction and mapFilenameToLayer.
     * Returns null if the extension is not recognized or should be skipped.
     */
    public static String mapAltiumExtension(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        String ext = filename.substring(dot + 1).toUpperCase();

        switch (ext) {
            case "GTL": return "F.Cu";
            case "GBL": return "B.Cu";
            case "GTO": return "F.Silkscreen";
            case "GBO": return "B.Silkscreen";
            case "GTS": return "F.Mask";
            case "GBS": return "B.Mask";
            case "GTP": return "F.Paste";
            case "GBP": return "B.Paste";
            case "GKO": return "Edge.Cuts";
            case "G1": return "In1.Cu";
            case "G2": return "In2.Cu";
            case "G3": return "In3.Cu";
            case "G4": return "In4.Cu";
            case "GP1": return "In5.Cu";
            case "GP2": return "In6.Cu";
            case "GP3": return "In7.Cu";
            case "GP4": return "In8.Cu";
            default: break;
        }

        // Skip known non-layer files
        // GM* (mechanical), GPT/GPB (pad masters), GD*/GG* (drill drawing/guide),
        // APR/APT (aperture files), P* (panels)
        return null;
    }

    public static String mapFileFunction(String fileFunction) {
        if (fileFunction == null) return null;
        String lower = fileFunction.toLowerCase().trim();

        // Copper layers
        if (lower.startsWith("copper")) {
            if (lower.contains("top") || lower.contains("l1")) return "F.Cu";
            if (lower.contains("bot") || lower.contains("l2")) return "B.Cu";
            // Try to parse inner layer number
            // "Copper,L3,Inr" -> In1.Cu, "Copper,L4,Inr" -> In2.Cu
            var parts = fileFunction.split(",");
            for (String part : parts) {
                part = part.trim();
                if (part.matches("(?i)L\\d+")) {
                    int num = Integer.parseInt(part.substring(1));
                    if (num > 1) return "In" + (num - 1) + ".Cu";
                }
            }
        }

        // Silkscreen / Legend
        if (lower.startsWith("legend") || lower.startsWith("silkscreen")) {
            if (lower.contains("top")) return "F.Silkscreen";
            if (lower.contains("bot")) return "B.Silkscreen";
        }

        // Solder mask
        if (lower.startsWith("soldermask")) {
            if (lower.contains("top")) return "F.Mask";
            if (lower.contains("bot")) return "B.Mask";
        }

        // Solder paste
        if (lower.startsWith("paste")) {
            if (lower.contains("top")) return "F.Paste";
            if (lower.contains("bot")) return "B.Paste";
        }

        // Board outline
        if (lower.startsWith("profile") || lower.startsWith("outline")) {
            return "Edge.Cuts";
        }

        return null;
    }
}
