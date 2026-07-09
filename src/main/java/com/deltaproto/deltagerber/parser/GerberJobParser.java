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
 *                          "FilePolarity": "Positive" } ]
 * }
 * }</pre>
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

        Map<String, Object> specs = Json.object(root, "GeneralSpecs");
        if (specs != null) {
            document.setLayerCount(Json.integer(specs, "LayerNumber"));
            document.setBoardThicknessMm(Json.number(specs, "BoardThickness"));
            Map<String, Object> projectId = Json.object(specs, "ProjectId");
            if (projectId != null) {
                document.setProjectName(Json.string(projectId, "Name"));
                document.setProjectGuid(Json.string(projectId, "GUID"));
                document.setProjectRevision(Json.string(projectId, "Revision"));
            }
            Map<String, Object> size = Json.object(specs, "Size");
            if (size != null) {
                document.setSizeXMm(Json.number(size, "X"));
                document.setSizeYMm(Json.number(size, "Y"));
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
     * its general specs or its file list; other JSON in a Gerber set (a CAD project file, say)
     * declares neither.
     */
    private static boolean looksLikeJobFile(String trimmed) {
        return trimmed.contains("\"GeneralSpecs\"") || trimmed.contains("\"FilesAttributes\"");
    }
}
