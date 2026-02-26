package nl.bytesoflife.deltagerber.drc.advisor;

import java.util.List;

/**
 * Factory for built-in manufacturer profiles with known cost tier breakpoints.
 */
public class BuiltinProfiles {

    private static volatile ManufacturerProfile cachedNextPcb2Layer;
    private static volatile ManufacturerProfile cachedNextPcb4Layer;

    /**
     * NextPCB 2-layer board profile.
     * <ul>
     *   <li>Standard: trace/space >= 5mil (0.127mm), hole >= 0.3mm</li>
     *   <li>Advanced: trace/space >= 4mil (0.1016mm), hole >= 0.2mm</li>
     *   <li>HDI: trace/space < 4mil, hole >= 0.15mm</li>
     * </ul>
     */
    public static ManufacturerProfile nextPcb2Layer() {
        if (cachedNextPcb2Layer == null) {
            synchronized (BuiltinProfiles.class) {
                if (cachedNextPcb2Layer == null) {
                    cachedNextPcb2Layer = createNextPcb2Layer();
                }
            }
        }
        return cachedNextPcb2Layer;
    }

    /**
     * NextPCB 4-layer board profile.
     * <ul>
     *   <li>Standard: trace/space >= 8mil (0.2032mm), hole >= 0.3mm</li>
     *   <li>Advanced: trace/space >= 6mil (0.1524mm), hole >= 0.2mm</li>
     *   <li>Fine: trace/space >= 4mil (0.1016mm), hole >= 0.2mm</li>
     *   <li>HDI: trace/space < 4mil, hole >= 0.15mm</li>
     * </ul>
     */
    public static ManufacturerProfile nextPcb4Layer() {
        if (cachedNextPcb4Layer == null) {
            synchronized (BuiltinProfiles.class) {
                if (cachedNextPcb4Layer == null) {
                    cachedNextPcb4Layer = createNextPcb4Layer();
                }
            }
        }
        return cachedNextPcb4Layer;
    }

    private static ManufacturerProfile createNextPcb2Layer() {
        return new ManufacturerProfile("NextPCB 2-Layer", List.of(
                new ManufacturingTier("Standard", 0, 0.127, 0.127, 0.3,
                        "Standard 2-layer: >= 5/5mil trace/space, >= 0.3mm holes"),
                new ManufacturingTier("Advanced", 1, 0.1016, 0.1016, 0.2,
                        "Advanced 2-layer: >= 4/4mil trace/space, >= 0.2mm holes"),
                new ManufacturingTier("HDI", 2, null, null, 0.15,
                        "HDI 2-layer: < 4mil trace/space, >= 0.15mm holes")
        ));
    }

    private static ManufacturerProfile createNextPcb4Layer() {
        return new ManufacturerProfile("NextPCB 4-Layer", List.of(
                new ManufacturingTier("Standard", 0, 0.2032, 0.2032, 0.3,
                        "Standard 4-layer: >= 8/8mil trace/space, >= 0.3mm holes"),
                new ManufacturingTier("Advanced", 1, 0.1524, 0.1524, 0.2,
                        "Advanced 4-layer: >= 6/6mil trace/space, >= 0.2mm holes"),
                new ManufacturingTier("Fine", 2, 0.1016, 0.1016, 0.2,
                        "Fine 4-layer: >= 4/4mil trace/space, >= 0.2mm holes"),
                new ManufacturingTier("HDI", 3, null, null, 0.15,
                        "HDI 4-layer: < 4mil trace/space, >= 0.15mm holes")
        ));
    }
}
