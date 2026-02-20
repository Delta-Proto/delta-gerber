package nl.bytesoflife.deltagerber.drc.model;

import java.util.Set;

public class LayerSelector {

    private static final Set<String> OUTER_LAYERS = Set.of("F.Cu", "B.Cu");
    private static final Set<String> INNER_LAYER_PREFIXES = Set.of("In");

    private final String expression;

    public LayerSelector(String expression) {
        this.expression = expression;
    }

    public String getExpression() {
        return expression;
    }

    public boolean matches(String kicadLayerName) {
        if (expression == null || expression.isEmpty()) {
            return true;
        }

        String expr = expression.replace("\"", "").trim();

        if (expr.equalsIgnoreCase("outer")) {
            return OUTER_LAYERS.contains(kicadLayerName);
        }
        if (expr.equalsIgnoreCase("inner")) {
            return isInnerLayer(kicadLayerName);
        }

        // Wildcard support: "?.Cu" matches "F.Cu", "B.Cu", etc.
        if (expr.contains("?") || expr.contains("*")) {
            String regex = expr.replace(".", "\\.").replace("?", ".").replace("*", ".*");
            return kicadLayerName.matches(regex);
        }

        return expr.equals(kicadLayerName);
    }

    private boolean isInnerLayer(String layerName) {
        for (String prefix : INNER_LAYER_PREFIXES) {
            if (layerName.startsWith(prefix) && layerName.endsWith(".Cu")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return expression != null ? expression : "(all layers)";
    }
}
