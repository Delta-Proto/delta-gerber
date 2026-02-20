package nl.bytesoflife.deltagerber.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import nl.bytesoflife.deltagerber.drc.*;
import nl.bytesoflife.deltagerber.drc.check.*;
import nl.bytesoflife.deltagerber.drc.model.DrcRule;
import nl.bytesoflife.deltagerber.drc.model.DrcRuleSet;
import nl.bytesoflife.deltagerber.model.drill.DrillDocument;
import nl.bytesoflife.deltagerber.model.gerber.GerberDocument;
import nl.bytesoflife.deltagerber.parser.ExcellonParser;
import nl.bytesoflife.deltagerber.parser.GerberParser;
import nl.bytesoflife.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Simple HTTP server for the Gerber viewer web application.
 */
public class GerberViewerServer {

    private static final Logger log = LoggerFactory.getLogger(GerberViewerServer.class);

    private final int port;
    private HttpServer server;

    public GerberViewerServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        ParseHandler parseHandler = new ParseHandler();
        server.createContext("/", new StaticHandler());
        server.createContext("/api/parse", parseHandler);
        server.createContext("/api/layer-type", new LayerTypeHandler(parseHandler));
        server.createContext("/api/drc", new DrcHandler(parseHandler));
        server.setExecutor(null);
        server.start();
        log.info("Gerber Viewer Server started at http://localhost:{}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Serves the static HTML page.
     */
    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                String html = getIndexHtml();
                sendResponse(exchange, 200, "text/html", html);
            } else {
                sendResponse(exchange, 404, "text/plain", "Not Found");
            }
        }
    }

    /**
     * Handles ZIP file uploads and returns a multi-layer SVG.
     */
    static class ParseHandler implements HttpHandler {
        private static final Logger log = LoggerFactory.getLogger(ParseHandler.class);

        private final GerberParser gerberParser = new GerberParser();
        private final ExcellonParser drillParser = new ExcellonParser();
        private final MultiLayerSVGRenderer multiLayerRenderer = new MultiLayerSVGRenderer();

        // Stored state for DRC
        private volatile List<ParsedFile> lastParsedFiles = List.of();

        // Layer type overrides (filename → KiCAD layer name), populated during parse and editable via API
        private final Map<String, String> layerOverrides = Collections.synchronizedMap(new LinkedHashMap<>());

        // Layer colors for different file types
        private static final Map<String, String> LAYER_COLORS = new LinkedHashMap<>();
        static {
            // Copper layers
            LAYER_COLORS.put("gtl", "#e94560"); LAYER_COLORS.put("top", "#e94560"); LAYER_COLORS.put("f_cu", "#e94560");
            LAYER_COLORS.put("gbl", "#4169e1"); LAYER_COLORS.put("bottom", "#4169e1"); LAYER_COLORS.put("b_cu", "#4169e1");
            LAYER_COLORS.put("g2", "#ff8c00"); LAYER_COLORS.put("g1", "#ff6600");
            LAYER_COLORS.put("g3", "#9932cc"); LAYER_COLORS.put("in1", "#ff8c00"); LAYER_COLORS.put("in2", "#9932cc");
            // Solder mask
            LAYER_COLORS.put("gts", "#00aa00"); LAYER_COLORS.put("gbs", "#006600");
            LAYER_COLORS.put("f_mask", "#00aa00"); LAYER_COLORS.put("b_mask", "#006600");
            // Silkscreen
            LAYER_COLORS.put("gto", "#ffffff"); LAYER_COLORS.put("gbo", "#cccccc");
            LAYER_COLORS.put("f_silks", "#ffffff"); LAYER_COLORS.put("b_silks", "#cccccc");
            // Paste
            LAYER_COLORS.put("gtp", "#888888"); LAYER_COLORS.put("gbp", "#666666");
            LAYER_COLORS.put("f_paste", "#888888"); LAYER_COLORS.put("b_paste", "#666666");
            // Outline/Edge
            LAYER_COLORS.put("gko", "#ffff00"); LAYER_COLORS.put("gm1", "#ffff00"); LAYER_COLORS.put("edge", "#ffff00");
            // Drill
            LAYER_COLORS.put("drl", "#00ffff"); LAYER_COLORS.put("xln", "#00ffff");
            LAYER_COLORS.put("drill", "#00ffff"); LAYER_COLORS.put("txt", "#00ffff");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            long startTime = System.currentTimeMillis();
            log.info("Received parse request");

            try {
                // Read the uploaded ZIP file
                log.debug("Reading uploaded ZIP data...");
                byte[] zipData = exchange.getRequestBody().readAllBytes();
                log.info("Received ZIP file: {} bytes", zipData.length);

                ParseResult result = parseZipFile(zipData);

                // Build JSON response with layer metadata and combined SVG
                log.debug("Building JSON response...");
                StringBuilder json = new StringBuilder();
                json.append("{\"layers\":[");
                boolean first = true;
                for (LayerInfo layer : result.layerInfos) {
                    if (!first) json.append(",");
                    first = false;
                    json.append("{\"name\":");
                    json.append(escapeJson(layer.name));
                    json.append(",\"id\":");
                    json.append(escapeJson(layer.id));
                    json.append(",\"color\":");
                    json.append(escapeJson(layer.color));
                    json.append(",\"type\":");
                    json.append(escapeJson(layer.type));
                    json.append(",\"drcLayer\":");
                    json.append(escapeJson(layer.drcLayer));
                    json.append("}");
                }
                json.append("],\"svg\":");
                json.append(escapeJson(result.svg));
                json.append("}");

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("Parse complete: {} layers, {} chars SVG in {}ms",
                    result.layerInfos.size(), result.svg.length(), elapsed);

                sendResponse(exchange, 200, "application/json", json.toString());
            } catch (Exception e) {
                log.error("Error parsing file", e);
                sendResponse(exchange, 500, "application/json",
                    "{\"error\":" + escapeJson(e.getMessage()) + "}");
            }
        }

        static class ParsedFile {
            final String filename;
            final String type; // "gerber" or "drill"
            final GerberDocument gerberDoc;
            final DrillDocument drillDoc;

            ParsedFile(String filename, String type, GerberDocument gerberDoc, DrillDocument drillDoc) {
                this.filename = filename;
                this.type = type;
                this.gerberDoc = gerberDoc;
                this.drillDoc = drillDoc;
            }
        }

        List<ParsedFile> getLastParsedFiles() {
            return lastParsedFiles;
        }

        Map<String, String> getLayerOverrides() {
            return layerOverrides;
        }

        private static class LayerInfo {
            String name;
            String id;
            String color;
            String type;
            String drcLayer;

            LayerInfo(String name, String id, String color, String type, String drcLayer) {
                this.name = name;
                this.id = id;
                this.color = color;
                this.type = type;
                this.drcLayer = drcLayer;
            }
        }

        private static class ParseResult {
            List<LayerInfo> layerInfos;
            String svg;

            ParseResult(List<LayerInfo> layerInfos, String svg) {
                this.layerInfos = layerInfos;
                this.svg = svg;
            }
        }

        private ParseResult parseZipFile(byte[] zipData) throws IOException {
            List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
            List<LayerInfo> layerInfos = new ArrayList<>();
            List<ParsedFile> parsedFiles = new ArrayList<>();

            log.debug("Opening ZIP stream...");
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
                ZipEntry entry;
                int fileCount = 0;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;

                    String name = entry.getName();
                    // Skip hidden files and directories
                    if (name.contains("__MACOSX") || name.startsWith(".")) {
                        log.trace("Skipping hidden file: {}", name);
                        continue;
                    }

                    // Get just the filename
                    int lastSlash = name.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        name = name.substring(lastSlash + 1);
                    }

                    fileCount++;
                    log.debug("Processing file {}: {}", fileCount, name);
                    long fileStart = System.currentTimeMillis();

                    byte[] content = zis.readAllBytes();
                    String contentStr = new String(content, StandardCharsets.UTF_8);
                    String layerType = detectLayerType(name, contentStr);
                    log.debug("  Detected type: {}, size: {} bytes", layerType, content.length);

                    try {
                        MultiLayerSVGRenderer.Layer layer = null;
                        if (layerType.equals("drill")) {
                            log.debug("  Parsing as drill file...");
                            DrillDocument doc = drillParser.parse(contentStr);
                            layer = new MultiLayerSVGRenderer.Layer(name, doc);
                            parsedFiles.add(new ParsedFile(name, "drill", null, doc));
                            log.debug("  Drill parsed: {} operations", doc.getOperations().size());
                        } else if (layerType.equals("gerber")) {
                            log.debug("  Parsing as Gerber file...");
                            GerberDocument doc = gerberParser.parse(contentStr);
                            layer = new MultiLayerSVGRenderer.Layer(name, doc);
                            parsedFiles.add(new ParsedFile(name, "gerber", doc, null));
                            log.debug("  Gerber parsed: {} objects, {} apertures",
                                doc.getObjects().size(), doc.getApertures().size());
                        } else {
                            log.debug("  Skipping unknown file type");
                        }

                        if (layer != null) {
                            String color = getLayerColor(name);
                            layer.setColor(color);
                            layer.setOpacity(0.85);
                            layers.add(layer);

                            // Compute drcLayer from file function or filename
                            String drcLayer = null;
                            if ("drill".equals(layerType)) {
                                drcLayer = "Drill";
                            } else if ("gerber".equals(layerType) && parsedFiles.get(parsedFiles.size() - 1).gerberDoc != null) {
                                GerberDocument gDoc = parsedFiles.get(parsedFiles.size() - 1).gerberDoc;
                                if (gDoc.getFileFunction() != null) {
                                    drcLayer = DrcBoardInput.mapFileFunction(gDoc.getFileFunction());
                                }
                                if (drcLayer == null) {
                                    drcLayer = DrcBoardInput.mapFilenameToLayer(name);
                                }
                                if (drcLayer == null) {
                                    drcLayer = DrcBoardInput.mapAltiumExtension(name);
                                }
                            }

                            // Create layer info for JSON response
                            String id = name.replaceAll("[^a-zA-Z0-9._-]", "_");
                            layerInfos.add(new LayerInfo(name, id, color, layerType, drcLayer));

                            long fileElapsed = System.currentTimeMillis() - fileStart;
                            log.info("  Parsed {} in {}ms", name, fileElapsed);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse {}: {}", name, e.getMessage());
                        log.debug("Parse error details", e);
                    }
                }
                log.info("Processed {} files from ZIP", fileCount);
            }

            // Store parsed files for DRC
            this.lastParsedFiles = List.copyOf(parsedFiles);

            // Populate layer overrides from auto-detected drcLayer values
            layerOverrides.clear();
            for (LayerInfo li : layerInfos) {
                if (li.drcLayer != null) {
                    layerOverrides.put(li.name, li.drcLayer);
                }
            }

            // Render all layers into a single multi-layer SVG
            log.info("Rendering {} layers to SVG...", layers.size());
            long renderStart = System.currentTimeMillis();
            String svg = multiLayerRenderer.render(layers);
            long renderElapsed = System.currentTimeMillis() - renderStart;
            log.info("SVG rendering complete: {} chars in {}ms", svg.length(), renderElapsed);

            return new ParseResult(layerInfos, svg);
        }

        private String getLayerColor(String filename) {
            String lower = filename.toLowerCase();
            for (Map.Entry<String, String> entry : LAYER_COLORS.entrySet()) {
                if (lower.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return "#aaaaaa"; // default
        }

        private String detectLayerType(String filename, String content) {
            String lower = filename.toLowerCase();

            // Check by extension
            if (lower.endsWith(".drl") || lower.endsWith(".xln") ||
                lower.endsWith(".exc") || lower.endsWith(".ncd") ||
                lower.endsWith(".txt")) {
                // .txt files need content check
                if (lower.endsWith(".txt")) {
                    if (content.contains("M48") || content.contains("T01C") ||
                        content.contains("METRIC") || content.contains("INCH")) {
                        return "drill";
                    }
                } else {
                    return "drill";
                }
            }

            // Check by content for drill
            if (content.contains("M48") || content.contains("T01C")) {
                return "drill";
            }

            // Check for Gerber content
            if (content.contains("%FS") || content.contains("%MO") ||
                content.contains("G04") || content.contains("%ADD")) {
                return "gerber";
            }

            // Common Gerber extensions
            if (lower.endsWith(".gbr") || lower.endsWith(".ger") ||
                lower.endsWith(".gtl") || lower.endsWith(".gbl") ||
                lower.endsWith(".gts") || lower.endsWith(".gbs") ||
                lower.endsWith(".gto") || lower.endsWith(".gbo") ||
                lower.endsWith(".gtp") || lower.endsWith(".gbp") ||
                lower.endsWith(".gm1") || lower.endsWith(".gko") ||
                lower.endsWith(".g2") || lower.endsWith(".g3") ||
                lower.endsWith(".g1")) {
                return "gerber";
            }

            return "unknown";
        }
    }

    /**
     * Handles layer type override requests.
     */
    static class LayerTypeHandler implements HttpHandler {
        private final ParseHandler parseHandler;

        LayerTypeHandler(ParseHandler parseHandler) {
            this.parseHandler = parseHandler;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String filename = null;
                String drcLayer = null;
                for (String param : body.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                        String value = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                        if ("filename".equals(key)) filename = value;
                        else if ("drcLayer".equals(key)) drcLayer = value;
                    }
                }

                if (filename == null) {
                    sendResponse(exchange, 400, "application/json", "{\"error\":\"Missing filename parameter\"}");
                    return;
                }

                if (drcLayer == null || drcLayer.isEmpty()) {
                    parseHandler.getLayerOverrides().remove(filename);
                } else {
                    parseHandler.getLayerOverrides().put(filename, drcLayer);
                }

                sendResponse(exchange, 200, "application/json", "{\"ok\":true}");
            } catch (Exception e) {
                sendResponse(exchange, 500, "application/json",
                        "{\"error\":" + escapeJson(e.getMessage()) + "}");
            }
        }
    }

    /**
     * Handles DRC requests against the last parsed Gerber/drill data.
     */
    static class DrcHandler implements HttpHandler {
        private static final Logger log = LoggerFactory.getLogger(DrcHandler.class);
        private final ParseHandler parseHandler;

        DrcHandler(ParseHandler parseHandler) {
            this.parseHandler = parseHandler;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            try {
                // Parse query parameter
                String query = exchange.getRequestURI().getQuery();
                String rulesName = "pcbway";
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2 && "rules".equals(kv[0])) {
                            rulesName = kv[1].toLowerCase();
                        }
                    }
                }

                // Check if files have been loaded
                List<ParseHandler.ParsedFile> parsedFiles = parseHandler.getLastParsedFiles();
                if (parsedFiles.isEmpty()) {
                    sendResponse(exchange, 400, "application/json",
                            "{\"error\":\"No Gerber files loaded. Please upload a ZIP file first.\"}");
                    return;
                }

                // Load the requested rule set
                DrcRuleSet ruleSet;
                try {
                    ruleSet = switch (rulesName) {
                        case "pcbway" -> BuiltinRuleSets.pcbWay();
                        case "nextpcb" -> BuiltinRuleSets.nextPcb();
                        default -> throw new IllegalArgumentException("Unknown rule set: " + rulesName);
                    };
                } catch (IllegalArgumentException e) {
                    sendResponse(exchange, 400, "application/json",
                            "{\"error\":" + escapeJson(e.getMessage()) + "}");
                    return;
                }

                // Build DrcBoardInput from parsed files using layer overrides
                DrcBoardInput board = new DrcBoardInput();
                Map<String, String> overrides = parseHandler.getLayerOverrides();
                for (ParseHandler.ParsedFile pf : parsedFiles) {
                    String mapped = overrides.get(pf.filename);
                    if ("gerber".equals(pf.type) && pf.gerberDoc != null) {
                        if (mapped != null && !mapped.equals("Drill")) {
                            board.addGerberLayer(mapped, pf.gerberDoc);
                        }
                    } else if ("drill".equals(pf.type) && pf.drillDoc != null) {
                        board.addDrill(pf.drillDoc);
                    }
                }

                // Create runner with all checks
                DrcRunner runner = new DrcRunner()
                        .registerCheck(new TrackWidthCheck())
                        .registerCheck(new HoleSizeCheck())
                        .registerCheck(new AnnularWidthCheck())
                        .registerCheck(new ClearanceCheck())
                        .registerCheck(new HoleToHoleCheck())
                        .registerCheck(new EdgeClearanceCheck());

                log.info("Running DRC with {} rules ({})", ruleSet.getRules().size(), rulesName);
                long startTime = System.currentTimeMillis();
                DrcReport report = runner.run(ruleSet, board);
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("DRC complete in {}ms: {} errors, {} warnings, {} skipped",
                        elapsed, report.getErrors().size(), report.getWarnings().size(),
                        report.getSkippedRules().size());

                // Build JSON response
                StringBuilder json = new StringBuilder();
                json.append("{\"errors\":").append(report.getErrors().size());
                json.append(",\"warnings\":").append(report.getWarnings().size());
                json.append(",\"skipped\":").append(report.getSkippedRules().size());

                json.append(",\"violations\":[");
                boolean first = true;
                for (DrcViolation v : report.getViolations()) {
                    if (!first) json.append(",");
                    first = false;
                    json.append("{\"severity\":").append(escapeJson(v.getSeverity().name()));
                    json.append(",\"rule\":").append(escapeJson(v.getRule().getName()));
                    json.append(",\"description\":").append(escapeJson(v.getDescription()));
                    if (v.getMeasuredValueMm() != null) {
                        json.append(",\"measured\":").append(String.format(Locale.US, "%.4f", v.getMeasuredValueMm()));
                    }
                    if (v.getRequiredValueMm() != null) {
                        json.append(",\"required\":").append(String.format(Locale.US, "%.4f", v.getRequiredValueMm()));
                    }
                    json.append(",\"x\":").append(String.format(Locale.US, "%.4f", v.getX()));
                    json.append(",\"y\":").append(String.format(Locale.US, "%.4f", v.getY()));
                    if (v.getLayer() != null) {
                        json.append(",\"layer\":").append(escapeJson(v.getLayer()));
                    }
                    json.append("}");
                }
                json.append("]");

                json.append(",\"skippedRules\":[");
                first = true;
                for (DrcRule r : report.getSkippedRules()) {
                    if (!first) json.append(",");
                    first = false;
                    json.append(escapeJson(r.getName()));
                }
                json.append("]");

                json.append("}");

                sendResponse(exchange, 200, "application/json", json.toString());
            } catch (Exception e) {
                log.error("Error running DRC", e);
                sendResponse(exchange, 500, "application/json",
                        "{\"error\":" + escapeJson(e.getMessage()) + "}");
            }
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String getIndexHtml() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Gerber Viewer</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, sans-serif;
            background: #1a1a2e;
            color: #eee;
            height: 100vh;
            display: flex;
            flex-direction: column;
        }

        header {
            background: #16213e;
            padding: 12px 20px;
            display: flex;
            align-items: center;
            gap: 20px;
            border-bottom: 1px solid #0f3460;
        }

        header h1 {
            font-size: 1.3rem;
            font-weight: 500;
            color: #e94560;
        }

        .upload-btn {
            background: #0f3460;
            color: #fff;
            border: none;
            padding: 8px 16px;
            border-radius: 6px;
            cursor: pointer;
            font-size: 0.9rem;
            transition: background 0.2s;
        }

        .upload-btn:hover {
            background: #1a4f7a;
        }

        #file-input {
            display: none;
        }

        .zoom-controls {
            display: flex;
            gap: 8px;
            margin-left: auto;
        }

        .zoom-btn {
            background: #0f3460;
            color: #fff;
            border: none;
            width: 32px;
            height: 32px;
            border-radius: 6px;
            cursor: pointer;
            font-size: 1.2rem;
            display: flex;
            align-items: center;
            justify-content: center;
        }

        .zoom-btn:hover {
            background: #1a4f7a;
        }

        .zoom-level {
            background: #0f3460;
            padding: 6px 12px;
            border-radius: 6px;
            font-size: 0.85rem;
            min-width: 60px;
            text-align: center;
        }

        .main-container {
            display: flex;
            flex: 1;
            overflow: hidden;
        }

        .sidebar {
            width: 320px;
            background: #16213e;
            border-right: 1px solid #0f3460;
            padding: 16px;
            overflow-y: auto;
        }

        .sidebar h2 {
            font-size: 0.85rem;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            color: #888;
            margin-bottom: 12px;
        }

        .layer-list {
            display: flex;
            flex-direction: column;
            gap: 4px;
        }

        .layer-item {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 10px 12px;
            background: #1a1a2e;
            border-radius: 6px;
            cursor: pointer;
            transition: background 0.2s;
        }

        .layer-item:hover {
            background: #252542;
        }

        .layer-item input[type="checkbox"] {
            width: 16px;
            height: 16px;
            accent-color: #e94560;
        }

        .layer-item .color-dot {
            width: 14px;
            height: 14px;
            border-radius: 50%;
            flex-shrink: 0;
        }

        .layer-item .name {
            flex: 1;
            font-size: 0.9rem;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }

        .layer-tooltip {
            position: fixed;
            position-area: top;
            position-try-fallbacks: bottom, right, left;
            padding: 4px 8px;
            background: #0d0d1a;
            color: #eee;
            font-size: 0.8rem;
            border-radius: 4px;
            border: 1px solid #333;
            white-space: nowrap;
            pointer-events: none;
            opacity: 0;
            transition: opacity 0.15s;
            z-index: 10;
        }

        .layer-item .name:hover + .layer-tooltip {
            opacity: 1;
        }

        .viewer-container {
            flex: 1;
            position: relative;
            overflow: hidden;
            background: #0d0d1a;
            background-image:
                linear-gradient(rgba(255,255,255,0.03) 1px, transparent 1px),
                linear-gradient(90deg, rgba(255,255,255,0.03) 1px, transparent 1px);
            background-size: 20px 20px;
        }

        #svg-container {
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            cursor: grab;
        }

        #svg-container:active {
            cursor: grabbing;
        }

        #svg-content {
            transform-origin: 0 0;
        }

        .drop-zone {
            position: absolute;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            text-align: center;
            color: #666;
        }

        .drop-zone svg {
            width: 80px;
            height: 80px;
            margin-bottom: 16px;
            opacity: 0.5;
        }

        .drop-zone p {
            font-size: 1.1rem;
            margin-bottom: 8px;
        }

        .drop-zone small {
            font-size: 0.85rem;
            color: #555;
        }

        .drag-over {
            background: rgba(233, 69, 96, 0.1) !important;
        }

        .loading {
            position: absolute;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            text-align: center;
            background: rgba(22, 33, 62, 0.95);
            padding: 32px 48px;
            border-radius: 12px;
            box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
            border: 1px solid #0f3460;
        }

        .spinner {
            width: 48px;
            height: 48px;
            border: 3px solid #333;
            border-top-color: #e94560;
            border-radius: 50%;
            animation: spin 1s linear infinite;
            margin: 0 auto;
        }

        @keyframes spin {
            to { transform: rotate(360deg); }
        }

        .loading-text {
            margin-top: 16px;
            font-size: 1rem;
            color: #eee;
        }

        .loading-status {
            margin-top: 8px;
            font-size: 0.85rem;
            color: #888;
            min-height: 20px;
        }

        .progress-bar {
            width: 200px;
            height: 4px;
            background: #333;
            border-radius: 2px;
            margin: 16px auto 0;
            overflow: hidden;
        }

        .progress-bar-fill {
            height: 100%;
            background: linear-gradient(90deg, #e94560, #ff6b8a);
            border-radius: 2px;
            transition: width 0.3s ease;
            width: 0%;
        }

        .progress-bar-indeterminate .progress-bar-fill {
            width: 30%;
            animation: indeterminate 1.5s ease-in-out infinite;
        }

        @keyframes indeterminate {
            0% { transform: translateX(-100%); }
            100% { transform: translateX(400%); }
        }

        .hidden {
            display: none !important;
        }

        .no-layers {
            color: #666;
            font-size: 0.9rem;
            padding: 20px;
            text-align: center;
        }

        .drc-dropdown {
            position: relative;
            display: inline-block;
        }

        .drc-btn {
            background: #0f3460;
            color: #fff;
            border: none;
            padding: 8px 16px;
            border-radius: 6px;
            cursor: pointer;
            font-size: 0.9rem;
            transition: background 0.2s;
        }

        .drc-btn:hover {
            background: #1a4f7a;
        }

        .drc-btn:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }

        .drc-menu {
            display: none;
            position: absolute;
            top: 100%;
            left: 0;
            margin-top: 4px;
            background: #16213e;
            border: 1px solid #0f3460;
            border-radius: 6px;
            overflow: hidden;
            z-index: 100;
            min-width: 160px;
        }

        .drc-menu.open {
            display: block;
        }

        .drc-menu-item {
            padding: 10px 16px;
            cursor: pointer;
            font-size: 0.9rem;
            white-space: nowrap;
            transition: background 0.2s;
        }

        .drc-menu-item:hover {
            background: #0f3460;
        }

        .drc-panel {
            display: none;
            position: absolute;
            top: 0;
            right: 0;
            width: 420px;
            height: 100%;
            background: #16213e;
            border-left: 1px solid #0f3460;
            z-index: 50;
            flex-direction: column;
            overflow: hidden;
        }

        .drc-panel.open {
            display: flex;
        }

        .drc-panel-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 12px 16px;
            border-bottom: 1px solid #0f3460;
            flex-shrink: 0;
        }

        .drc-panel-header h3 {
            font-size: 1rem;
            font-weight: 500;
        }

        .drc-close-btn {
            background: none;
            border: none;
            color: #888;
            font-size: 1.3rem;
            cursor: pointer;
            padding: 4px 8px;
            border-radius: 4px;
        }

        .drc-close-btn:hover {
            color: #fff;
            background: #0f3460;
        }

        .drc-summary {
            padding: 12px 16px;
            border-bottom: 1px solid #0f3460;
            flex-shrink: 0;
            font-size: 0.95rem;
            font-weight: 500;
        }

        .drc-summary.pass {
            color: #4caf50;
        }

        .drc-summary.fail {
            color: #e94560;
        }

        .drc-violations {
            flex: 1;
            overflow-y: auto;
            padding: 8px 0;
        }

        .drc-violation {
            padding: 10px 16px;
            border-bottom: 1px solid rgba(15, 52, 96, 0.5);
            font-size: 0.85rem;
            cursor: pointer;
        }

        .drc-violation:hover {
            background: rgba(15, 52, 96, 0.3);
        }

        .drc-violation.active {
            background: rgba(15, 52, 96, 0.4);
            border-left: 3px solid #e94560;
        }

        @keyframes drc-pulse {
            0% { r: 0.3; opacity: 1; }
            50% { r: 0.8; opacity: 0.6; }
            100% { r: 0.3; opacity: 1; }
        }

        .drc-violation-header {
            display: flex;
            align-items: center;
            gap: 8px;
            margin-bottom: 4px;
        }

        .drc-severity {
            padding: 2px 6px;
            border-radius: 3px;
            font-size: 0.75rem;
            font-weight: 600;
            text-transform: uppercase;
        }

        .drc-severity.error {
            background: rgba(233, 69, 96, 0.2);
            color: #e94560;
        }

        .drc-severity.warning {
            background: rgba(255, 165, 0, 0.2);
            color: #ffa500;
        }

        .drc-rule-name {
            font-weight: 500;
            color: #ddd;
        }

        .drc-violation-detail {
            color: #999;
            margin-top: 2px;
        }

        .drc-violation-values {
            color: #bbb;
            margin-top: 2px;
        }

        .drc-skipped {
            padding: 10px 16px;
            border-top: 1px solid #0f3460;
            flex-shrink: 0;
        }

        .drc-skipped-header {
            cursor: pointer;
            color: #888;
            font-size: 0.85rem;
            user-select: none;
        }

        .drc-skipped-header:hover {
            color: #bbb;
        }

        .drc-skipped-list {
            display: none;
            margin-top: 8px;
            font-size: 0.8rem;
            color: #666;
            max-height: 150px;
            overflow-y: auto;
        }

        .drc-skipped-list.open {
            display: block;
        }

        .drc-skipped-list div {
            padding: 2px 0;
        }

        .drc-loading {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 12px;
            padding: 32px;
            color: #888;
        }

        .drc-loading .spinner {
            width: 24px;
            height: 24px;
            border-width: 2px;
        }

        .layer-quick-select {
            display: flex;
            gap: 4px;
            margin-bottom: 10px;
        }

        .layer-quick-select button {
            flex: 1;
            background: #0f3460;
            color: #ccc;
            border: none;
            padding: 5px 0;
            border-radius: 4px;
            cursor: pointer;
            font-size: 0.75rem;
            transition: background 0.2s;
        }

        .layer-quick-select button:hover {
            background: #1a4f7a;
            color: #fff;
        }

        .layer-type-select {
            background: #0d0d1a;
            color: #aaa;
            border: 1px solid #333;
            border-radius: 3px;
            padding: 2px 4px;
            font-size: 0.7rem;
            cursor: pointer;
            max-width: 90px;
            flex-shrink: 0;
        }

        .layer-type-select:focus {
            outline: 1px solid #e94560;
            border-color: #e94560;
        }
    </style>
</head>
<body>
    <header>
        <h1>Gerber Viewer</h1>
        <label class="upload-btn">
            <input type="file" id="file-input" accept=".zip">
            Open ZIP File
        </label>
        <div class="drc-dropdown" id="drc-dropdown">
            <button class="drc-btn" id="drc-btn" disabled onclick="toggleDrcMenu()">Run DRC &#9662;</button>
            <div class="drc-menu" id="drc-menu">
                <div class="drc-menu-item" onclick="runDrc('pcbway')">PCBWay Rules</div>
                <div class="drc-menu-item" onclick="runDrc('nextpcb')">NextPCB Rules</div>
            </div>
        </div>
        <div class="zoom-controls">
            <button class="zoom-btn" id="zoom-out" title="Zoom Out">-</button>
            <span class="zoom-level" id="zoom-level">100%</span>
            <button class="zoom-btn" id="zoom-in" title="Zoom In">+</button>
            <button class="zoom-btn" id="zoom-fit" title="Fit to View">&#8644;</button>
        </div>
    </header>

    <div class="main-container">
        <aside class="sidebar">
            <h2>Layers</h2>
            <div class="layer-quick-select hidden" id="layer-quick-select">
                <button onclick="quickSelectLayers('all')">All</button>
                <button onclick="quickSelectLayers('none')">None</button>
                <button onclick="quickSelectLayers('top')">Top</button>
                <button onclick="quickSelectLayers('bottom')">Bottom</button>
            </div>
            <div class="layer-list" id="layer-list">
                <div class="no-layers">No layers loaded</div>
            </div>
        </aside>

        <div class="viewer-container" id="viewer-container">
            <div class="drop-zone" id="drop-zone">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM14 13v4h-4v-4H7l5-5 5 5h-3z"/>
                </svg>
                <p>Drop a Gerber ZIP file here</p>
                <small>or click "Open ZIP File" above</small>
            </div>
            <div class="loading hidden" id="loading">
                <div class="spinner"></div>
                <div class="loading-text">Processing Gerber files</div>
                <div class="loading-status" id="loading-status">Uploading...</div>
                <div class="progress-bar progress-bar-indeterminate">
                    <div class="progress-bar-fill" id="progress-fill"></div>
                </div>
            </div>
            <div id="svg-container">
                <div id="svg-content"></div>
            </div>
            <div class="drc-panel" id="drc-panel">
                <div class="drc-panel-header">
                    <h3 id="drc-panel-title">DRC Results</h3>
                    <button class="drc-close-btn" onclick="closeDrcPanel()">&times;</button>
                </div>
                <div class="drc-summary" id="drc-summary"></div>
                <div class="drc-violations" id="drc-violations"></div>
                <div class="drc-skipped" id="drc-skipped-section"></div>
            </div>
        </div>
    </div>

    <script>
        // Available KiCAD layer types
        const KICAD_LAYERS = ['', 'F.Cu', 'B.Cu', 'In1.Cu', 'In2.Cu', 'In3.Cu', 'In4.Cu',
            'F.Silkscreen', 'B.Silkscreen', 'F.Mask', 'B.Mask',
            'F.Paste', 'B.Paste', 'Edge.Cuts', 'Drill'];

        // State
        let layers = [];       // Layer metadata from server
        let combinedSvg = '';  // The multi-layer SVG string
        let scale = 1;
        let panX = 0;
        let panY = 0;
        let isPanning = false;
        let startX, startY;

        // DOM elements
        const fileInput = document.getElementById('file-input');
        const layerList = document.getElementById('layer-list');
        const svgContainer = document.getElementById('svg-container');
        const svgContent = document.getElementById('svg-content');
        const viewerContainer = document.getElementById('viewer-container');
        const dropZone = document.getElementById('drop-zone');
        const loading = document.getElementById('loading');
        const zoomLevel = document.getElementById('zoom-level');

        // File input handler
        fileInput.addEventListener('change', (e) => {
            if (e.target.files.length > 0) {
                loadFile(e.target.files[0]);
            }
        });

        // Drag and drop
        viewerContainer.addEventListener('dragover', (e) => {
            e.preventDefault();
            viewerContainer.classList.add('drag-over');
        });

        viewerContainer.addEventListener('dragleave', () => {
            viewerContainer.classList.remove('drag-over');
        });

        viewerContainer.addEventListener('drop', (e) => {
            e.preventDefault();
            viewerContainer.classList.remove('drag-over');
            if (e.dataTransfer.files.length > 0) {
                loadFile(e.dataTransfer.files[0]);
            }
        });

        // Load and parse file
        async function loadFile(file) {
            if (!file.name.endsWith('.zip')) {
                alert('Please select a ZIP file');
                return;
            }

            const loadingStatus = document.getElementById('loading-status');
            const progressBar = document.querySelector('.progress-bar');
            const progressFill = document.getElementById('progress-fill');

            dropZone.classList.add('hidden');
            loading.classList.remove('hidden');
            loadingStatus.textContent = 'Uploading ' + file.name + '...';
            progressBar.classList.add('progress-bar-indeterminate');

            try {
                // Use XMLHttpRequest for upload progress
                const data = await new Promise((resolve, reject) => {
                    const xhr = new XMLHttpRequest();

                    xhr.upload.addEventListener('progress', (e) => {
                        if (e.lengthComputable) {
                            const percent = Math.round((e.loaded / e.total) * 100);
                            loadingStatus.textContent = 'Uploading... ' + percent + '%';
                            progressBar.classList.remove('progress-bar-indeterminate');
                            progressFill.style.width = percent + '%';
                        }
                    });

                    xhr.upload.addEventListener('load', () => {
                        loadingStatus.textContent = 'Parsing Gerber and drill files...';
                        progressBar.classList.add('progress-bar-indeterminate');
                        progressFill.style.width = '0%';
                    });

                    xhr.addEventListener('load', () => {
                        if (xhr.status === 200) {
                            try {
                                resolve(JSON.parse(xhr.responseText));
                            } catch (e) {
                                reject(new Error('Invalid response from server'));
                            }
                        } else {
                            reject(new Error('Server error: ' + xhr.status));
                        }
                    });

                    xhr.addEventListener('error', () => {
                        reject(new Error('Network error'));
                    });

                    xhr.open('POST', '/api/parse');
                    xhr.send(file);
                });

                if (data.error) {
                    throw new Error(data.error);
                }

                loadingStatus.textContent = 'Rendering ' + data.layers.length + ' layers...';

                // Store layer metadata with visibility state and drcLayer
                layers = data.layers.map((layer, index) => ({
                    ...layer,
                    visible: true,
                    drcLayer: layer.drcLayer || '',
                    index
                }));

                // Store the combined SVG
                combinedSvg = data.svg;

                // Small delay to show the rendering message
                await new Promise(r => setTimeout(r, 100));

                // Render the SVG
                renderSvg();
                renderLayerList();
                fitToView();

                // Enable DRC button and show quick-select buttons
                document.getElementById('drc-btn').disabled = false;
                document.getElementById('layer-quick-select').classList.remove('hidden');
            } catch (error) {
                alert('Error parsing file: ' + error.message);
                dropZone.classList.remove('hidden');
            } finally {
                loading.classList.add('hidden');
                progressBar.classList.remove('progress-bar-indeterminate');
                progressFill.style.width = '0%';
            }
        }

        // Render layer list in sidebar
        function renderLayerList() {
            if (layers.length === 0) {
                layerList.innerHTML = '<div class="no-layers">No layers loaded</div>';
                return;
            }

            layerList.innerHTML = layers.map((layer, index) => {
                const options = KICAD_LAYERS.map(l => {
                    const label = l || '(none)';
                    const selected = (l === layer.drcLayer) ? 'selected' : '';
                    return '<option value="' + l + '" ' + selected + '>' + label + '</option>';
                }).join('');
                return '<div class="layer-item">' +
                    '<input type="checkbox" ' + (layer.visible ? 'checked' : '') + ' onclick="event.stopPropagation(); toggleLayer(' + index + ')">' +
                    '<div class="color-dot" style="background: ' + layer.color + '"></div>' +
                    '<span class="name" style="anchor-name: --layer-' + index + '" onclick="toggleLayer(' + index + ')">' + layer.name + '</span>' +
                    '<div class="layer-tooltip" style="position-anchor: --layer-' + index + '">' + layer.name + '</div>' +
                    '<select class="layer-type-select" onclick="event.stopPropagation()" onchange="updateLayerType(' + index + ', this.value)">' + options + '</select>' +
                '</div>';
            }).join('');
        }

        // Toggle layer visibility - just update display attribute on layer group
        function toggleLayer(index) {
            layers[index].visible = !layers[index].visible;
            const layerId = layers[index].id;
            const layerGroup = svgContent.querySelector(`#${CSS.escape(layerId)}`);
            if (layerGroup) {
                layerGroup.setAttribute('display', layers[index].visible ? 'inline' : 'none');
            }
            renderLayerList();
        }

        // Render the combined SVG
        function renderSvg() {
            if (!combinedSvg) {
                svgContent.innerHTML = '';
                return;
            }

            // Parse and insert the multi-layer SVG
            const parser = new DOMParser();
            const doc = parser.parseFromString(combinedSvg, 'image/svg+xml');
            const svg = doc.querySelector('svg');

            if (svg) {
                // Set dimensions based on viewBox
                const viewBox = svg.getAttribute('viewBox');
                if (viewBox) {
                    const [, , w, h] = viewBox.split(' ').map(Number);
                    svg.setAttribute('width', w + 'mm');
                    svg.setAttribute('height', h + 'mm');
                }
                svg.style.overflow = 'visible';

                svgContent.innerHTML = '';
                svgContent.appendChild(svg);
            }

            updateTransform();
        }

        // Pan and zoom handlers
        svgContainer.addEventListener('mousedown', (e) => {
            if (e.button === 0) {
                isPanning = true;
                startX = e.clientX - panX;
                startY = e.clientY - panY;
            }
        });

        document.addEventListener('mousemove', (e) => {
            if (isPanning) {
                panX = e.clientX - startX;
                panY = e.clientY - startY;
                updateTransform();
            }
        });

        document.addEventListener('mouseup', () => {
            isPanning = false;
        });

        svgContainer.addEventListener('wheel', (e) => {
            e.preventDefault();
            const rect = svgContainer.getBoundingClientRect();
            const mouseX = e.clientX - rect.left;
            const mouseY = e.clientY - rect.top;

            const delta = e.deltaY > 0 ? 0.9 : 1.1;
            const newScale = Math.max(0.1, Math.min(50, scale * delta));

            // Zoom toward mouse position
            panX = mouseX - (mouseX - panX) * (newScale / scale);
            panY = mouseY - (mouseY - panY) * (newScale / scale);
            scale = newScale;

            updateTransform();
        });

        // Zoom buttons
        document.getElementById('zoom-in').addEventListener('click', () => {
            const rect = svgContainer.getBoundingClientRect();
            const centerX = rect.width / 2;
            const centerY = rect.height / 2;

            const newScale = Math.min(50, scale * 1.25);
            panX = centerX - (centerX - panX) * (newScale / scale);
            panY = centerY - (centerY - panY) * (newScale / scale);
            scale = newScale;
            updateTransform();
        });

        document.getElementById('zoom-out').addEventListener('click', () => {
            const rect = svgContainer.getBoundingClientRect();
            const centerX = rect.width / 2;
            const centerY = rect.height / 2;

            const newScale = Math.max(0.1, scale * 0.8);
            panX = centerX - (centerX - panX) * (newScale / scale);
            panY = centerY - (centerY - panY) * (newScale / scale);
            scale = newScale;
            updateTransform();
        });

        document.getElementById('zoom-fit').addEventListener('click', fitToView);

        function fitToView() {
            const svg = svgContent.querySelector('svg');
            if (!svg) return;

            const rect = svgContainer.getBoundingClientRect();
            const viewBox = svg.getAttribute('viewBox');
            if (!viewBox) return;

            const [, , w, h] = viewBox.split(' ').map(Number);

            // Convert mm to pixels (assuming 96 DPI, 1mm = 3.78px)
            const pxPerMm = 3.78;
            const svgWidth = w * pxPerMm;
            const svgHeight = h * pxPerMm;

            const padding = 40;
            const scaleX = (rect.width - padding * 2) / svgWidth;
            const scaleY = (rect.height - padding * 2) / svgHeight;
            scale = Math.min(scaleX, scaleY, 10);

            panX = (rect.width - svgWidth * scale) / 2;
            panY = (rect.height - svgHeight * scale) / 2;

            updateTransform();
        }

        function updateTransform() {
            svgContent.style.transform = `translate(${panX}px, ${panY}px) scale(${scale})`;
            zoomLevel.textContent = Math.round(scale * 100) + '%';
        }

        function focusViolation(x, y) {
            const svg = svgContent.querySelector('svg');
            if (!svg) return;

            const viewBox = svg.getAttribute('viewBox');
            if (!viewBox) return;
            const [vbMinX, vbMinY, vbW, vbH] = viewBox.split(' ').map(Number);

            // The viewport group has transform="translate(0, flipOffset) scale(1,-1)"
            // where flipOffset = vbMinY + vbH + vbMinY = 2*vbMinY + vbH
            const flipOffset = 2 * vbMinY + vbH;

            // In SVG coordinate space (before the viewport Y-flip), the point is at:
            const svgX = x;
            const svgY = flipOffset - y;

            // Convert from viewBox mm to pixel position within the SVG element
            const pxPerMm = 3.78;
            const svgPixelWidth = vbW * pxPerMm;
            const svgPixelHeight = vbH * pxPerMm;

            const pixelX = (svgX - vbMinX) / vbW * svgPixelWidth;
            const pixelY = (svgY - vbMinY) / vbH * svgPixelHeight;

            // Set zoom level and center the point in the container
            scale = 8;
            const rect = svgContainer.getBoundingClientRect();
            panX = rect.width / 2 - pixelX * scale;
            panY = rect.height / 2 - pixelY * scale;

            updateTransform();
            showMarker(x, y);
        }

        function showMarker(x, y) {
            const svg = svgContent.querySelector('svg');
            if (!svg) return;
            const viewport = svg.querySelector('#viewport');
            if (!viewport) return;

            // Remove existing marker
            const existing = viewport.querySelector('#drc-marker');
            if (existing) existing.remove();

            const ns = 'http://www.w3.org/2000/svg';
            const g = document.createElementNS(ns, 'g');
            g.setAttribute('id', 'drc-marker');

            // Pulsing ring
            const ring = document.createElementNS(ns, 'circle');
            ring.setAttribute('cx', x);
            ring.setAttribute('cy', y);
            ring.setAttribute('r', '0.5');
            ring.setAttribute('fill', 'none');
            ring.setAttribute('stroke', '#e94560');
            ring.setAttribute('stroke-width', '0.06');
            ring.setAttribute('style', 'animation: drc-pulse 1.2s ease-in-out infinite;');
            g.appendChild(ring);

            // Center dot
            const dot = document.createElementNS(ns, 'circle');
            dot.setAttribute('cx', x);
            dot.setAttribute('cy', y);
            dot.setAttribute('r', '0.08');
            dot.setAttribute('fill', '#e94560');
            g.appendChild(dot);

            // Crosshair lines
            const lineLen = 0.7;
            [[ -lineLen, 0, lineLen, 0 ], [ 0, -lineLen, 0, lineLen ]].forEach(([x1, y1, x2, y2]) => {
                const line = document.createElementNS(ns, 'line');
                line.setAttribute('x1', x + x1);
                line.setAttribute('y1', y + y1);
                line.setAttribute('x2', x + x2);
                line.setAttribute('y2', y + y2);
                line.setAttribute('stroke', '#e94560');
                line.setAttribute('stroke-width', '0.04');
                g.appendChild(line);
            });

            viewport.appendChild(g);
        }

        // Update layer type on server
        function updateLayerType(index, value) {
            layers[index].drcLayer = value;
            const body = 'filename=' + encodeURIComponent(layers[index].name) + '&drcLayer=' + encodeURIComponent(value);
            fetch('/api/layer-type', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: body
            });
        }

        // Quick-select layer visibility
        function quickSelectLayers(mode) {
            layers.forEach((layer, index) => {
                let visible;
                if (mode === 'all') {
                    visible = true;
                } else if (mode === 'none') {
                    visible = false;
                } else if (mode === 'top') {
                    visible = layer.drcLayer.startsWith('F.') || layer.drcLayer === 'Edge.Cuts' || layer.drcLayer === 'Drill';
                } else if (mode === 'bottom') {
                    visible = layer.drcLayer.startsWith('B.') || layer.drcLayer === 'Edge.Cuts' || layer.drcLayer === 'Drill';
                }
                layer.visible = visible;
                const layerGroup = svgContent.querySelector('#' + CSS.escape(layer.id));
                if (layerGroup) {
                    layerGroup.setAttribute('display', visible ? 'inline' : 'none');
                }
            });
            renderLayerList();
        }

        // DRC functionality
        let drcMenuOpen = false;

        function toggleDrcMenu() {
            drcMenuOpen = !drcMenuOpen;
            document.getElementById('drc-menu').classList.toggle('open', drcMenuOpen);
        }

        // Close menu when clicking outside
        document.addEventListener('click', (e) => {
            const dropdown = document.getElementById('drc-dropdown');
            if (!dropdown.contains(e.target)) {
                drcMenuOpen = false;
                document.getElementById('drc-menu').classList.remove('open');
            }
        });

        async function runDrc(rulesName) {
            drcMenuOpen = false;
            document.getElementById('drc-menu').classList.remove('open');

            const panel = document.getElementById('drc-panel');
            const summary = document.getElementById('drc-summary');
            const violations = document.getElementById('drc-violations');
            const skippedSection = document.getElementById('drc-skipped-section');
            const title = document.getElementById('drc-panel-title');

            title.textContent = 'DRC Results (' + (rulesName === 'pcbway' ? 'PCBWay' : 'NextPCB') + ')';
            panel.classList.add('open');
            summary.className = 'drc-summary';
            summary.textContent = '';
            violations.innerHTML = '<div class="drc-loading"><div class="spinner"></div>Running DRC...</div>';
            skippedSection.innerHTML = '';

            try {
                const response = await fetch('/api/drc?rules=' + rulesName);
                const data = await response.json();

                if (data.error) {
                    summary.className = 'drc-summary fail';
                    summary.textContent = data.error;
                    violations.innerHTML = '';
                    return;
                }

                // Summary
                if (data.errors === 0 && data.warnings === 0) {
                    summary.className = 'drc-summary pass';
                    summary.textContent = 'No violations found';
                } else {
                    summary.className = 'drc-summary fail';
                    let parts = [];
                    if (data.errors > 0) parts.push(data.errors + ' error' + (data.errors !== 1 ? 's' : ''));
                    if (data.warnings > 0) parts.push(data.warnings + ' warning' + (data.warnings !== 1 ? 's' : ''));
                    summary.textContent = parts.join(', ');
                }

                // Violations list
                if (data.violations.length === 0) {
                    violations.innerHTML = '<div style="padding: 20px; text-align: center; color: #666;">No violations</div>';
                } else {
                    violations.innerHTML = data.violations.map(v => {
                        let detail = v.description;
                        let values = '';
                        if (v.measured !== undefined && v.required !== undefined) {
                            values = 'Measured: ' + v.measured + 'mm, Required: ' + v.required + 'mm';
                        }
                        let location = 'at (' + v.x + ', ' + v.y + ')';
                        if (v.layer) location += ' on ' + v.layer;

                        return '<div class="drc-violation" onclick="document.querySelectorAll(\\'.drc-violation.active\\').forEach(el => el.classList.remove(\\'active\\')); this.classList.add(\\'active\\'); focusViolation(' + v.x + ', ' + v.y + ')">' +
                            '<div class="drc-violation-header">' +
                                '<span class="drc-severity ' + v.severity.toLowerCase() + '">' + v.severity + '</span>' +
                                '<span class="drc-rule-name">' + escapeHtml(v.rule) + '</span>' +
                            '</div>' +
                            '<div class="drc-violation-detail">' + escapeHtml(detail) + '</div>' +
                            (values ? '<div class="drc-violation-values">' + values + '</div>' : '') +
                            '<div class="drc-violation-detail">' + location + '</div>' +
                        '</div>';
                    }).join('');
                }

                // Skipped rules
                if (data.skippedRules && data.skippedRules.length > 0) {
                    skippedSection.innerHTML =
                        '<div class="drc-skipped-header" onclick="this.nextElementSibling.classList.toggle(&quot;open&quot;)">' +
                            'Skipped ' + data.skippedRules.length + ' rule' + (data.skippedRules.length !== 1 ? 's' : '') + ' &#9662;' +
                        '</div>' +
                        '<div class="drc-skipped-list">' +
                            data.skippedRules.map(r => '<div>' + escapeHtml(r) + '</div>').join('') +
                        '</div>';
                }
            } catch (err) {
                summary.className = 'drc-summary fail';
                summary.textContent = 'Error: ' + err.message;
                violations.innerHTML = '';
            }
        }

        function closeDrcPanel() {
            document.getElementById('drc-panel').classList.remove('open');
            const marker = document.querySelector('#drc-marker');
            if (marker) marker.remove();
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        // Initialize
        updateTransform();
    </script>
</body>
</html>
""";
    }

    public static void main(String[] args) throws IOException {
        // Set default locale to US for consistent number formatting in SVG
        java.util.Locale.setDefault(java.util.Locale.US);

        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        GerberViewerServer server = new GerberViewerServer(port);
        server.start();

        // Keep running until interrupted
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
