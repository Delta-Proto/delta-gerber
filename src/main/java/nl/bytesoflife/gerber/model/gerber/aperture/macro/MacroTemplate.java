package nl.bytesoflife.gerber.model.gerber.aperture.macro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a parsed aperture macro template.
 * Macros are defined with AM command and can be instantiated with parameters.
 */
public class MacroTemplate {

    private final String name;
    private final List<MacroPrimitive> primitives;
    private final List<VariableAssignment> assignments;

    private static final Pattern VARIABLE_ASSIGN = Pattern.compile("\\$(\\d+)=(.+)");

    public MacroTemplate(String name) {
        this.name = name;
        this.primitives = new ArrayList<>();
        this.assignments = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public List<MacroPrimitive> getPrimitives() {
        return primitives;
    }

    /**
     * Parse a macro definition body.
     * Each statement is separated by '*' and can be either a variable assignment or a primitive.
     */
    public void parse(String body) {
        // Split by '*' but handle multiline macros
        String[] statements = body.split("\\*");

        for (String stmt : statements) {
            stmt = stmt.trim();
            if (stmt.isEmpty()) continue;

            // Check for variable assignment
            Matcher assignMatcher = VARIABLE_ASSIGN.matcher(stmt);
            if (assignMatcher.matches()) {
                int varNum = Integer.parseInt(assignMatcher.group(1));
                String expression = assignMatcher.group(2);
                assignments.add(new VariableAssignment(varNum, new MacroExpression(expression)));
                continue;
            }

            // Check for comment (code 0)
            if (stmt.startsWith("0 ") || stmt.equals("0")) {
                continue; // Skip comments
            }

            // Parse primitive
            MacroPrimitive primitive = parsePrimitive(stmt);
            if (primitive != null) {
                primitives.add(primitive);
            }
        }
    }

    private MacroPrimitive parsePrimitive(String stmt) {
        // Split by comma to get primitive code and parameters
        String[] parts = stmt.split(",");
        if (parts.length == 0) return null;

        int code;
        try {
            code = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            return null;
        }

        // Extract parameters (everything after the code)
        String[] params = new String[parts.length - 1];
        for (int i = 1; i < parts.length; i++) {
            params[i - 1] = parts[i].trim();
        }

        return switch (code) {
            case 1 -> new CirclePrimitive(params);
            case 4 -> new OutlinePrimitive(params);
            case 5 -> new PolygonPrimitive(params);
            case 6 -> new MoirePrimitive(params);
            case 7 -> new ThermalPrimitive(params);
            case 20 -> new VectorLinePrimitive(params);
            case 21 -> new CenterLinePrimitive(params);
            default -> null;
        };
    }

    /**
     * Evaluate all variable assignments given initial parameter values.
     */
    public Map<Integer, Double> evaluateVariables(List<Double> parameters) {
        Map<Integer, Double> variables = new HashMap<>();

        // Initialize with parameters ($1, $2, etc.)
        for (int i = 0; i < parameters.size(); i++) {
            variables.put(i + 1, parameters.get(i));
        }

        // Apply assignments in order
        for (VariableAssignment assign : assignments) {
            double value = assign.expression().evaluate(variables);
            variables.put(assign.variableNumber(), value);
        }

        return variables;
    }

    private record VariableAssignment(int variableNumber, MacroExpression expression) {}
}
