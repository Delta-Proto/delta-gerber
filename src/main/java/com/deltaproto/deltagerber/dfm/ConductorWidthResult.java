package com.deltaproto.deltagerber.dfm;

import com.deltaproto.deltagerber.Beta;
import java.util.Collections;
import java.util.List;

/**
 * The conductor-width measurement of one copper layer: the narrowest copper of each kind,
 * narrowest first — one entry per distinct stroke width and one per pour that necks.
 *
 * <p>This is the geometric answer to "how narrow does the copper get", and it is deliberately not
 * {@code PcbAnalyzer.minTrackWidthUm}, the quote-form figure: that one reads the aperture table,
 * this one measures the outline. A board that quotes as 0.15 mm but necks to 0.10 mm in a pour is
 * exactly what the difference between the two is for.
 *
 * <p>Necks wider than the cutoff are not measured, so {@link #getMinMm()} is {@code null} on a
 * layer of pours with nothing under the cutoff and no strokes at all.
 */
@Beta("not yet validated against an external DFM tool")
public final class ConductorWidthResult {

    private final String fileName;
    private final double cutoffMm;
    private final List<ConductorWidth> widths;
    private final List<String> warnings;

    ConductorWidthResult(String fileName, double cutoffMm, List<ConductorWidth> widths, List<String> warnings) {
        this.fileName = fileName;
        this.cutoffMm = cutoffMm;
        this.widths = Collections.unmodifiableList(widths);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public String getFileName() {
        return fileName;
    }

    /** Necks wider than this were not looked for. */
    public double getCutoffMm() {
        return cutoffMm;
    }

    /** Narrowest first. */
    public List<ConductorWidth> getWidths() {
        return widths;
    }

    /** The narrowest copper on the layer, or null when nothing was measured. */
    public Double getMinMm() {
        return widths.isEmpty() ? null : widths.get(0).widthMm();
    }

    public ConductorWidth getMin() {
        return widths.isEmpty() ? null : widths.get(0);
    }

    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return "ConductorWidthResult[" + fileName + ": min=" + getMinMm() + " mm]";
    }
}
