package com.deltaproto.deltagerber.model.gerber.aperture.macro;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates arithmetic expressions in macro definitions.
 * Supports variables ($1, $2, etc.), operators (+, -, x, /), and parentheses.
 */
public class MacroExpression {

    private final String expression;

    public MacroExpression(String expression) {
        this.expression = expression.trim();
    }

    /**
     * Evaluate the expression with the given variable values.
     */
    public double evaluate(Map<Integer, Double> variables) {
        String expr = substituteVariables(expression, variables);
        return parseExpression(expr);
    }

    /**
     * Parse a constant expression (no variables).
     */
    public double evaluate() {
        return parseExpression(expression);
    }

    private String substituteVariables(String expr, Map<Integer, Double> variables) {
        Pattern varPattern = Pattern.compile("\\$(\\d+)");
        Matcher matcher = varPattern.matcher(expr);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            int varNum = Integer.parseInt(matcher.group(1));
            Double value = variables.get(varNum);
            if (value == null) {
                value = 0.0;
            }
            matcher.appendReplacement(result, String.valueOf(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private double parseExpression(String expr) {
        expr = expr.trim();
        if (expr.isEmpty()) {
            return 0;
        }

        // Handle parentheses first
        while (expr.contains("(")) {
            int start = expr.lastIndexOf("(");
            int end = expr.indexOf(")", start);
            if (end == -1) {
                throw new IllegalArgumentException("Mismatched parentheses: " + expr);
            }
            String inner = expr.substring(start + 1, end);
            double innerResult = parseAddSub(inner);
            expr = expr.substring(0, start) + innerResult + expr.substring(end + 1);
        }

        return parseAddSub(expr);
    }

    private double parseAddSub(String expr) {
        // Find the last + or - at the top level (not inside parentheses)
        // We search from the end to ensure left-to-right associativity
        int parenDepth = 0;
        int lastAddSub = -1;
        char lastOp = 0;

        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth--;
            else if (parenDepth == 0 && (c == '+' || c == '-')) {
                // Check if this is a sign vs operator
                if (i > 0) {
                    char prev = expr.charAt(i - 1);
                    if (prev != 'x' && prev != 'X' && prev != '/' && prev != '+' && prev != '-') {
                        lastAddSub = i;
                        lastOp = c;
                        break;
                    }
                }
            }
        }

        if (lastAddSub > 0) {
            double left = parseAddSub(expr.substring(0, lastAddSub));
            double right = parseMulDiv(expr.substring(lastAddSub + 1));
            return lastOp == '+' ? left + right : left - right;
        }

        return parseMulDiv(expr);
    }

    private double parseMulDiv(String expr) {
        // Find the last x or / at the top level
        int parenDepth = 0;
        int lastMulDiv = -1;
        char lastOp = 0;

        for (int i = expr.length() - 1; i >= 0; i--) {
            char c = expr.charAt(i);
            if (c == ')') parenDepth++;
            else if (c == '(') parenDepth--;
            else if (parenDepth == 0 && (c == 'x' || c == 'X' || c == '/')) {
                lastMulDiv = i;
                lastOp = c;
                break;
            }
        }

        if (lastMulDiv > 0) {
            double left = parseMulDiv(expr.substring(0, lastMulDiv));
            double right = parseUnary(expr.substring(lastMulDiv + 1));
            return (lastOp == 'x' || lastOp == 'X') ? left * right : left / right;
        }

        return parseUnary(expr);
    }

    private double parseUnary(String expr) {
        expr = expr.trim();
        if (expr.startsWith("-")) {
            return -parseUnary(expr.substring(1));
        }
        if (expr.startsWith("+")) {
            return parseUnary(expr.substring(1));
        }
        return Double.parseDouble(expr);
    }

    @Override
    public String toString() {
        return expression;
    }
}
