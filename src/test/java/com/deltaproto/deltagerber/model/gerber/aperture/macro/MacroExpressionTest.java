package com.deltaproto.deltagerber.model.gerber.aperture.macro;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Macro arithmetic is evaluated by substituting values into the expression text and re-parsing
 * it. A value must therefore be written back the way the Gerber grammar reads numbers: plain
 * digits, never an exponent. {@code Double.toString(0.0001)} is {@code "1.0E-4"}, whose '-'
 * read as a subtraction and ended in {@code Double.parseDouble("1.0E")}.
 */
class MacroExpressionTest {

    private static final double EPS = 1e-12;

    private static double eval(String expr, double... values) {
        Map<Integer, Double> vars = new java.util.HashMap<>();
        for (int i = 0; i < values.length; i++) {
            vars.put(i + 1, values[i]);
        }
        return new MacroExpression(expr).evaluate(vars);
    }

    @Test
    void smallValueTimesConstant() {
        assertEquals(0.0002, eval("$1x2", 0.0001), EPS);
    }

    @Test
    void smallValuePlusSmallValue() {
        assertEquals(0.0002, eval("$1+$2", 0.0001, 0.0001), EPS);
    }

    @Test
    void smallIntermediateResultThroughParentheses() {
        // ($1x$2) evaluates to 0.0001 and is written back into the expression text
        assertEquals(0.0002, eval("($1x$2)x$3", 0.01, 0.01, 2), EPS);
    }

    @Test
    void negativeSmallValues() {
        assertEquals(-0.0001, eval("$1x-1", 0.0001), EPS);
        assertEquals(0.0002, eval("$1-$2", 0.0001, -0.0001), EPS);
        assertEquals(-0.0002, eval("$1+$2", -0.0001, -0.0001), EPS);
        assertEquals(-0.0002, eval("-$1x2", 0.0001), EPS);
        assertEquals(0.0001, eval("-$1", -0.0001), EPS);
    }

    @Test
    void negativeSmallIntermediateResultThroughParentheses() {
        assertEquals(-0.0002, eval("($1x$2)x$3", -0.01, 0.01, 2), EPS);
        assertEquals(0.0002, eval("$3-($1x$2)", 0.01, 0.01, 0.0003), EPS);
    }

    @Test
    void largeValueTimesConstant() {
        assertEquals(20000000, eval("$1x2", 10000000), EPS);
        assertEquals(20000000, eval("($1x1)x2", 10000000), EPS);
    }

    @Test
    void constantExpressionStillParses() {
        assertEquals(7, new MacroExpression("1+2x3").evaluate(), EPS);
        assertEquals(7, eval("1+2x3"), EPS);
        assertEquals(0.5, eval("$1/2", 1), EPS);
        assertEquals(2.5, eval("(1+4)/2"), EPS);
    }

    @Test
    void ordinaryValuesUnchanged() {
        assertEquals(1.05, eval("$1+0.05", 1.0), EPS);
        assertEquals(0.25, eval("$1x$1", 0.5), EPS);
        assertEquals(-0.5, eval("0.5x-$1", 1), EPS);
    }
}
