package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;

/**
 * One file of a set that the caller has already parsed — for {@link PcbAnalyzer#analyzeParsed}, when
 * the documents are in memory anyway (a viewer that rendered them, say) and parsing them a second
 * time from {@link PcbFile}s would double the work.
 *
 * <p>Exactly one of {@code gerber} and {@code drill} is set. The classification is taken as final;
 * null means "not recognised", which measures the file but runs no copper, drill or mask check on it.
 *
 * @param fileName       the file's name, as it should appear in the results
 * @param classification what the file is
 * @param gerber         the parsed Gerber document, or null for a drill program
 * @param drill          the parsed Excellon program, or null for a Gerber file
 */
public record ParsedLayer(String fileName, LayerClassification classification,
                          GerberDocument gerber, DrillDocument drill) {

    public ParsedLayer {
        if ((gerber == null) == (drill == null)) {
            throw new IllegalArgumentException("exactly one of gerber and drill must be given");
        }
    }

    /** A parsed Gerber file. */
    public static ParsedLayer of(String fileName, LayerClassification classification, GerberDocument gerber) {
        return new ParsedLayer(fileName, classification, gerber, null);
    }

    /** A parsed Excellon drill program. */
    public static ParsedLayer of(String fileName, LayerClassification classification, DrillDocument drill) {
        return new ParsedLayer(fileName, classification, null, drill);
    }
}
