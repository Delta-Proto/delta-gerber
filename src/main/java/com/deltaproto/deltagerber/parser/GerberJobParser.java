package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.classify.GenerationSoftware;
import com.deltaproto.deltagerber.model.gerber.GerberJobDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Parses a Gerber job file ({@code .gbrjob}) per the Ucamco specification.
 *
 * <p>The job file is JSON:
 *
 * <pre>{@code
 * {
 *   "Header": { "GenerationSoftware": { "Vendor": "KiCad", "Application": "Pcbnew", "Version": "7.0.6" },
 *               "CreationDate": "2023-07-12T11:50:11+01:00" },
 *   "GeneralSpecs": { "ProjectId": { "Name": "simple_2layer", "GUID": "…", "Revision": "rev?" },
 *                     "Size": { "X": 40.1, "Y": 55.1 }, "LayerNumber": 2, "BoardThickness": 1.6 },
 *   "FilesAttributes": [ { "Path": "board-F_Cu.gbr", "FileFunction": "Copper,L1,Top",
 *                          "FilePolarity": "Positive" } ],
 *   "MaterialStackup": [ { "Type": "SolderMask", "Thickness": 0.01, "Name": "Top Solder Mask" },
 *                        { "Type": "Copper", "Thickness": 0.035, "Name": "F.Cu" },
 *                        { "Type": "Dielectric", "Thickness": 1.51, "Material": "FR4" },
 *                        { "Type": "Copper", "Thickness": 0.035, "Name": "B.Cu" } ]
 * }
 * }</pre>
 *
 * <p>{@code MaterialStackup} is the only place a Gerber set states what the board is physically
 * built from — its dielectrics, its foil weights, its materials. Most sets ship no job file at all.
 * Of those that do, KiCad writes a stack-up from version 6 on, but states thicknesses only from
 * version 8; EAGLE/Fusion writes none at all, and puts the general specs under {@code Overall}
 * rather than {@code GeneralSpecs}, which this parser reads as a fallback.
 */
public class GerberJobParser {

    private static final Logger log = LoggerFactory.getLogger(GerberJobParser.class);

    /**
     * Parse job-file content.
     *
     * @return the document, or null when {@code content} is not a Gerber job file. Content that
     *         looks like a job file but is malformed JSON also yields null, with a logged warning:
     *         a broken manifest should make a caller fall back to per-file classification, not
     *         fail the whole set.
     */
    public GerberJobDocument parse(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{' || !looksLikeJobFile(trimmed)) {
            return null;
        }

        Object root;
        try {
            root = Json.parse(trimmed);
        } catch (ParserException e) {
            log.warn("Not a readable Gerber job file: {}", e.getMessage());
            return null;
        }
        if (!(root instanceof Map)) {
            return null;
        }

        GerberJobDocument document = new GerberJobDocument();

        Map<String, Object> header = Json.object(root, "Header");
        if (header != null) {
            document.setCreationDate(Json.string(header, "CreationDate"));
            Map<String, Object> software = Json.object(header, "GenerationSoftware");
            if (software != null) {
                document.setGenerationSoftware(new GenerationSoftware(
                        Json.string(software, "Vendor"),
                        Json.string(software, "Application"),
                        Json.string(software, "Version")));
            }
        }

        // "Overall" is what EAGLE/Fusion writes where the specification says "GeneralSpecs" — the
        // same fields under an older name, and the only place those files state the board at all.
        Map<String, Object> specs = Json.object(root, "GeneralSpecs");
        if (specs == null) {
            specs = Json.object(root, "Overall");
        }
        if (specs != null) {
            document.setLayerCount(Json.integer(specs, "LayerNumber"));
            document.setBoardThicknessMm(Json.number(specs, "BoardThickness"));
            Map<String, Object> projectId = Json.object(specs, "ProjectId");
            if (projectId != null) {
                document.setProjectName(Json.string(projectId, "Name"));
                document.setProjectGuid(Json.string(projectId, "GUID"));
                document.setProjectRevision(Json.string(projectId, "Revision"));
            } else {
                // EAGLE writes the same pair the other way round: "Name": { "ProjectId": "…" }.
                Map<String, Object> name = Json.object(specs, "Name");
                if (name != null) {
                    document.setProjectName(Json.string(name, "ProjectId"));
                }
            }
            Map<String, Object> size = Json.object(specs, "Size");
            if (size != null) {
                document.setSizeXMm(Json.number(size, "X"));
                document.setSizeYMm(Json.number(size, "Y"));
            }
        }

        List<Object> stackup = Json.array(root, "MaterialStackup");
        if (stackup != null) {
            for (Object entry : stackup) {
                String rawType = Json.string(entry, "Type");
                document.addStackupEntry(new GerberJobDocument.StackupEntry(
                        GerberJobDocument.StackupType.of(rawType),
                        rawType,
                        Json.number(entry, "Thickness"),
                        Json.string(entry, "Material"),
                        Json.string(entry, "Name"),
                        Json.string(entry, "Notes")));
            }
        }

        List<Object> filesAttributes = Json.array(root, "FilesAttributes");
        if (filesAttributes != null) {
            for (Object entry : filesAttributes) {
                document.addFile(new GerberJobDocument.JobFile(
                        Json.string(entry, "Path"),
                        Json.string(entry, "FileFunction"),
                        Json.string(entry, "FilePolarity")));
            }
        }
        return document;
    }

    /**
     * A cheap sniff before committing to a full JSON parse. A job file always declares at least
     * its general specs, its file list or its stack-up; other JSON in a Gerber set (a CAD project
     * file, say) declares none of them. {@code "Overall"} is EAGLE's name for the general specs,
     * and an EAGLE job file may carry nothing else at all.
     */
    private static boolean looksLikeJobFile(String trimmed) {
        return trimmed.contains("\"GeneralSpecs\"") || trimmed.contains("\"FilesAttributes\"")
                || trimmed.contains("\"MaterialStackup\"") || trimmed.contains("\"Overall\"");
    }
}
