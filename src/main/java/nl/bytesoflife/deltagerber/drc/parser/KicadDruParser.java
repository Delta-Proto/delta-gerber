package nl.bytesoflife.deltagerber.drc.parser;

import nl.bytesoflife.deltagerber.drc.model.*;

import java.util.List;

public class KicadDruParser {

    public DrcRuleSet parse(String content) {
        SExpressionParser sexprParser = new SExpressionParser();
        List<SNode> nodes = sexprParser.parse(content);
        return buildRuleSet(nodes);
    }

    private DrcRuleSet buildRuleSet(List<SNode> nodes) {
        DrcRuleSet ruleSet = new DrcRuleSet();

        for (SNode node : nodes) {
            if (node instanceof SNode.SList list) {
                String tag = getTag(list);
                if ("version".equals(tag)) {
                    ruleSet.setVersion(Integer.parseInt(getAtomValue(list, 1)));
                } else if ("rule".equals(tag)) {
                    ruleSet.addRule(parseRule(list));
                }
            }
        }

        return ruleSet;
    }

    private DrcRule parseRule(SNode.SList list) {
        String name = getAtomValue(list, 1);
        DrcRule rule = new DrcRule(name);

        for (int i = 2; i < list.children().size(); i++) {
            SNode child = list.children().get(i);
            if (child instanceof SNode.SList childList) {
                String tag = getTag(childList);
                switch (tag) {
                    case "constraint" -> parseConstraint(childList, rule);
                    case "severity" -> rule.setSeverity(Severity.fromKicadName(getAtomValue(childList, 1)));
                    case "layer" -> rule.setLayer(new LayerSelector(getAtomValue(childList, 1)));
                    case "condition" -> rule.setConditionExpression(getAtomValue(childList, 1));
                }
            }
        }

        return rule;
    }

    private void parseConstraint(SNode.SList list, DrcRule rule) {
        String typeName = getAtomValue(list, 1);
        ConstraintType type = ConstraintType.fromKicadName(typeName);

        if (type == ConstraintType.DISALLOW) {
            // (constraint disallow buried_via)
            String disallowValue = list.children().size() > 2 ? getAtomValue(list, 2) : null;
            rule.addConstraint(new DrcConstraint(type, null, null, disallowValue));
            return;
        }

        // Parse (min ...) and (max ...) sub-expressions
        Double minMm = null;
        Double maxMm = null;

        for (int i = 2; i < list.children().size(); i++) {
            SNode child = list.children().get(i);
            if (child instanceof SNode.SList subList) {
                String subTag = getTag(subList);
                if ("min".equals(subTag)) {
                    minMm = parseValueMm(getAtomValue(subList, 1));
                } else if ("max".equals(subTag)) {
                    maxMm = parseValueMm(getAtomValue(subList, 1));
                }
            }
        }

        rule.addConstraint(new DrcConstraint(type, minMm, maxMm));
    }

    static double parseValueMm(String value) {
        if (value.endsWith("mm")) {
            return Double.parseDouble(value.substring(0, value.length() - 2));
        } else if (value.endsWith("mil")) {
            return Double.parseDouble(value.substring(0, value.length() - 3)) * 0.0254;
        } else if (value.endsWith("in")) {
            return Double.parseDouble(value.substring(0, value.length() - 2)) * 25.4;
        }
        // Assume mm if no suffix
        return Double.parseDouble(value);
    }

    private String getTag(SNode.SList list) {
        if (list.children().isEmpty()) return "";
        SNode first = list.children().get(0);
        if (first instanceof SNode.SAtom atom) {
            return atom.value();
        }
        return "";
    }

    private String getAtomValue(SNode.SList list, int index) {
        if (index >= list.children().size()) return "";
        SNode node = list.children().get(index);
        if (node instanceof SNode.SAtom atom) {
            return atom.value();
        }
        return "";
    }
}
