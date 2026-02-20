package nl.bytesoflife.deltagerber.drc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DrcRuleSet {

    private int version;
    private final List<DrcRule> rules = new ArrayList<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<DrcRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    public void addRule(DrcRule rule) {
        rules.add(rule);
    }

    @Override
    public String toString() {
        return "DrcRuleSet{version=" + version + ", rules=" + rules.size() + "}";
    }
}
