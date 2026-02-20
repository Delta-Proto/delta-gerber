package nl.bytesoflife.deltagerber.drc.parser;

import nl.bytesoflife.deltagerber.drc.model.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KicadDruParserTest {

    private final KicadDruParser parser = new KicadDruParser();

    @Test
    void parsePcbWayRulesEndToEnd() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/KiCAD_Custom_DRC_Rules_for_PCBWay/KiCAD_Custom_DRC_Rules_for_PCBWay.kicad_dru"));
        DrcRuleSet ruleSet = parser.parse(content);

        assertEquals(1, ruleSet.getVersion());

        // Count active (non-commented) rules
        List<DrcRule> rules = ruleSet.getRules();
        assertEquals(22, rules.size());

        // Verify first rule: "Minimum Trace Width and Spacing (outer layer)"
        DrcRule outerTraceRule = rules.get(0);
        assertEquals("Minimum Trace Width and Spacing (outer layer)", outerTraceRule.getName());
        assertEquals(2, outerTraceRule.getConstraints().size());

        DrcConstraint trackWidth = outerTraceRule.getConstraints().get(0);
        assertEquals(ConstraintType.TRACK_WIDTH, trackWidth.getType());
        assertEquals(0.127, trackWidth.getMinMm(), 0.0001);

        DrcConstraint clearance = outerTraceRule.getConstraints().get(1);
        assertEquals(ConstraintType.CLEARANCE, clearance.getType());
        assertEquals(0.127, clearance.getMinMm(), 0.0001);

        assertEquals("A.Type == 'track'", outerTraceRule.getConditionExpression());
        assertNotNull(outerTraceRule.getLayer());
        assertTrue(outerTraceRule.getLayer().matches("F.Cu"));
        assertTrue(outerTraceRule.getLayer().matches("B.Cu"));
        assertFalse(outerTraceRule.getLayer().matches("In1.Cu"));
    }

    @Test
    void parseHoleSizeWithMinAndMax() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/KiCAD_Custom_DRC_Rules_for_PCBWay/KiCAD_Custom_DRC_Rules_for_PCBWay.kicad_dru"));
        DrcRuleSet ruleSet = parser.parse(content);

        // "drill hole size (mechanical)" has both min and max
        DrcRule drillRule = ruleSet.getRules().get(2);
        assertEquals("drill hole size (mechanical)", drillRule.getName());
        DrcConstraint holeSize = drillRule.getConstraints().get(0);
        assertEquals(ConstraintType.HOLE_SIZE, holeSize.getType());
        assertEquals(0.15, holeSize.getMinMm(), 0.0001);
        assertEquals(6.3, holeSize.getMaxMm(), 0.0001);
    }

    @Test
    void parseRuleWithoutCondition() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/KiCAD_Custom_DRC_Rules_for_PCBWay/KiCAD_Custom_DRC_Rules_for_PCBWay.kicad_dru"));
        DrcRuleSet ruleSet = parser.parse(content);

        // "drill hole size (mechanical)" has no condition
        DrcRule drillRule = ruleSet.getRules().get(2);
        assertNull(drillRule.getConditionExpression());
    }

    @Test
    void parseLayerSelectorWildcard() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/KiCAD_Custom_DRC_Rules_for_PCBWay/KiCAD_Custom_DRC_Rules_for_PCBWay.kicad_dru"));
        DrcRuleSet ruleSet = parser.parse(content);

        // "Minimum Text" rule has layer "?.Silkscreen"
        DrcRule textRule = ruleSet.getRules().stream()
                .filter(r -> r.getName().equals("Minimum Text"))
                .findFirst().orElseThrow();
        assertNotNull(textRule.getLayer());
        assertTrue(textRule.getLayer().matches("F.Silkscreen"));
        assertTrue(textRule.getLayer().matches("B.Silkscreen"));
        assertFalse(textRule.getLayer().matches("F.Cu"));
    }

    @Test
    void parseSyntheticRuleWithSeverity() {
        String content = """
                (version 1)
                (rule "test rule"
                    (severity warning)
                    (constraint clearance (min 0.1mm)))
                """;
        DrcRuleSet ruleSet = parser.parse(content);
        assertEquals(1, ruleSet.getRules().size());
        assertEquals(Severity.WARNING, ruleSet.getRules().get(0).getSeverity());
    }

    @Test
    void parseValueWithMilUnit() {
        double value = KicadDruParser.parseValueMm("6mil");
        assertEquals(0.1524, value, 0.0001);
    }

    @Test
    void parseValueWithMmUnit() {
        double value = KicadDruParser.parseValueMm("0.127mm");
        assertEquals(0.127, value, 0.0001);
    }

    @Test
    void parseValueNoUnit() {
        double value = KicadDruParser.parseValueMm("0.5");
        assertEquals(0.5, value, 0.0001);
    }

    @Test
    void parseMultipleConstraintsPerRule() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/KiCAD_Custom_DRC_Rules_for_PCBWay/KiCAD_Custom_DRC_Rules_for_PCBWay.kicad_dru"));
        DrcRuleSet ruleSet = parser.parse(content);

        // "Pad Size" has hole_size and annular_width
        DrcRule padRule = ruleSet.getRules().stream()
                .filter(r -> r.getName().equals("Pad Size"))
                .findFirst().orElseThrow();
        assertEquals(2, padRule.getConstraints().size());
        assertEquals(ConstraintType.HOLE_SIZE, padRule.getConstraints().get(0).getType());
        assertEquals(ConstraintType.ANNULAR_WIDTH, padRule.getConstraints().get(1).getType());
        assertEquals(0.25, padRule.getConstraints().get(1).getMinMm(), 0.0001);
    }
}
