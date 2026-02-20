package nl.bytesoflife.deltagerber.drc.parser;

import nl.bytesoflife.deltagerber.drc.model.ConstraintType;
import nl.bytesoflife.deltagerber.drc.model.DrcConstraint;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.drc.model.DrcRuleSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KicadProParserTest {

    private KicadProParser parser;

    @BeforeEach
    void setUp() {
        parser = new KicadProParser();
    }

    @Test
    void parseNextPcbSimpleFile() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        assertNotNull(ruleSet);
        assertFalse(ruleSet.getRules().isEmpty());
    }

    @Test
    void parsesTrackWidthRule() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        DrcRule trackWidth = findRuleByConstraintType(ruleSet, ConstraintType.TRACK_WIDTH);
        assertNotNull(trackWidth, "Should have a track width rule");
        assertEquals(0.127, trackWidth.getConstraints().get(0).getMinMm(), 0.0001);
    }

    @Test
    void parsesHoleSizeRule() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        DrcRule holeSize = findRuleByConstraintType(ruleSet, ConstraintType.HOLE_SIZE);
        assertNotNull(holeSize, "Should have a hole size rule");
        assertEquals(0.15, holeSize.getConstraints().get(0).getMinMm(), 0.0001);
    }

    @Test
    void parsesEdgeClearanceRule() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        DrcRule edgeClearance = findRuleByConstraintType(ruleSet, ConstraintType.EDGE_CLEARANCE);
        assertNotNull(edgeClearance, "Should have an edge clearance rule");
        assertEquals(0.5, edgeClearance.getConstraints().get(0).getMinMm(), 0.0001);
    }

    @Test
    void parsesClearanceFromNetClass() throws IOException {
        // rules.min_clearance is 0 in the NextPCB file, but net_settings.classes[0].clearance is 0.2
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        DrcRule clearance = findRuleByConstraintType(ruleSet, ConstraintType.CLEARANCE);
        assertNotNull(clearance, "Should have a clearance rule from net class fallback");
        assertEquals(0.2, clearance.getConstraints().get(0).getMinMm(), 0.0001);
    }

    @Test
    void parsesAnnularWidthRule() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        DrcRule annular = findRuleByConstraintType(ruleSet, ConstraintType.ANNULAR_WIDTH);
        assertNotNull(annular, "Should have an annular width rule");
        assertEquals(0.09, annular.getConstraints().get(0).getMinMm(), 0.0001);
    }

    @Test
    void parsesHoleToHoleRule() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        DrcRule holeToHole = findRuleByConstraintType(ruleSet, ConstraintType.HOLE_TO_HOLE);
        assertNotNull(holeToHole, "Should have a hole-to-hole rule");
        assertEquals(0.25, holeToHole.getConstraints().get(0).getMinMm(), 0.0001);
    }

    @Test
    void allRulesHaveNoCondition() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        for (DrcRule rule : ruleSet.getRules()) {
            assertNull(rule.getConditionExpression(),
                    "Rule '" + rule.getName() + "' should have no condition");
        }
    }

    @Test
    void skipsZeroValueRules() throws IOException {
        // min_silk_clearance is 0.0 in the file, should be skipped
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        DrcRule silk = findRuleByConstraintType(ruleSet, ConstraintType.SILK_CLEARANCE);
        assertNull(silk, "Should skip zero-value silk clearance rule");
    }

    @Test
    void totalRuleCount() throws IOException {
        String content = Files.readString(
                Path.of("testdata/kicad-design-rules/NextPCB-Manufacturing-Rules-main/KiCad DRC Templates/Simple DRC/HQ NextPCB Simple.kicad_pro"));
        DrcRuleSet ruleSet = parser.parse(content);

        // Expected non-zero rules: track_width(0.127), clearance(0.2 from net class),
        // through_hole_diameter(0.15), hole_to_hole(0.25), edge_clearance(0.5),
        // annular_width(0.09), hole_clearance(0.25), text_height(0.762), text_thickness(0.125)
        // silk_clearance is 0.0 -> skipped
        assertEquals(9, ruleSet.getRules().size());
    }

    private DrcRule findRuleByConstraintType(DrcRuleSet ruleSet, ConstraintType type) {
        return ruleSet.getRules().stream()
                .filter(r -> r.getConstraints().stream().anyMatch(c -> c.getType() == type))
                .findFirst()
                .orElse(null);
    }
}
