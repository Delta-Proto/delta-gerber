package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerClassification;

import java.nio.charset.StandardCharsets;

/**
 * One file of a Gerber/drill set, on its way into {@link PcbAnalyzer}.
 *
 * <p>Gerber and Excellon are both ASCII, so bytes are decoded as ISO-8859-1: every byte maps to a
 * character, nothing throws on stray binary, and the syntax we parse is unaffected.
 */
public final class PcbFile {

    private final String fileName;
    private final String content;
    private final LayerClassification classification;

    private PcbFile(String fileName, String content, LayerClassification classification) {
        this.fileName = fileName;
        this.content = content;
        this.classification = classification;
    }

    public static PcbFile of(String fileName, String content) {
        return new PcbFile(fileName, content, null);
    }

    public static PcbFile of(String fileName, byte[] data) {
        return of(fileName, decode(data));
    }

    /**
     * A file whose role is already known — because a job file declared it, or because a person
     * corrected it. The analyzer takes the given classification as final and does not guess.
     *
     * <p>This matters beyond the one file: the board outline decides how every copper layer's
     * track width is measured, so a corrected outline has to be the one the analyzer measures
     * against. A null {@code classification} falls back to classifying from the file itself.
     */
    public static PcbFile of(String fileName, String content, LayerClassification classification) {
        return new PcbFile(fileName, content, classification);
    }

    public static PcbFile of(String fileName, byte[] data, LayerClassification classification) {
        return new PcbFile(fileName, decode(data), classification);
    }

    private static String decode(byte[] data) {
        return data == null ? null : new String(data, StandardCharsets.ISO_8859_1);
    }

    public String getFileName() {
        return fileName;
    }

    /** File contents, or null when unavailable — classification then falls back to the name alone. */
    public String getContent() {
        return content;
    }

    /** A caller-supplied classification that overrides detection, or null to detect. */
    public LayerClassification getClassification() {
        return classification;
    }

    @Override
    public String toString() {
        return String.format("PcbFile[%s, %d chars]", fileName, content == null ? 0 : content.length());
    }
}
