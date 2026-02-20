package nl.bytesoflife.deltagerber.drc;

import nl.bytesoflife.deltagerber.drc.model.DrcRuleSet;
import nl.bytesoflife.deltagerber.drc.parser.KicadDruParser;
import nl.bytesoflife.deltagerber.drc.parser.KicadProParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Provides built-in DRC rule sets bundled as classpath resources.
 */
public class BuiltinRuleSets {

    private static volatile DrcRuleSet cachedPcbWay;
    private static volatile DrcRuleSet cachedNextPcb;

    public static DrcRuleSet pcbWay() {
        if (cachedPcbWay == null) {
            synchronized (BuiltinRuleSets.class) {
                if (cachedPcbWay == null) {
                    cachedPcbWay = loadPcbWay();
                }
            }
        }
        return cachedPcbWay;
    }

    public static DrcRuleSet nextPcb() {
        if (cachedNextPcb == null) {
            synchronized (BuiltinRuleSets.class) {
                if (cachedNextPcb == null) {
                    cachedNextPcb = loadNextPcb();
                }
            }
        }
        return cachedNextPcb;
    }

    public static Map<String, DrcRuleSet> all() {
        return Map.of("pcbway", pcbWay(), "nextpcb", nextPcb());
    }

    private static DrcRuleSet loadPcbWay() {
        try (InputStream is = BuiltinRuleSets.class.getResourceAsStream("/drc-rules/pcbway.kicad_dru")) {
            if (is == null) throw new IllegalStateException("Resource not found: /drc-rules/pcbway.kicad_dru");
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return new KicadDruParser().parse(content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load PCBWay rules", e);
        }
    }

    private static DrcRuleSet loadNextPcb() {
        try (InputStream is = BuiltinRuleSets.class.getResourceAsStream("/drc-rules/nextpcb-simple.kicad_pro")) {
            if (is == null) throw new IllegalStateException("Resource not found: /drc-rules/nextpcb-simple.kicad_pro");
            return new KicadProParser().parse(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load NextPCB rules", e);
        }
    }
}
