package com.deltaproto.deltagerber.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
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
        server.createContext("/", new StaticHandler());
        server.createContext("/api/parse", new ParseHandler());
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

        private static class LayerInfo {
            String name;
            String id;
            String color;
            String type;

            LayerInfo(String name, String id, String color, String type) {
                this.name = name;
                this.id = id;
                this.color = color;
                this.type = type;
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
                            log.debug("  Drill parsed: {} operations", doc.getOperations().size());
                        } else if (layerType.equals("gerber")) {
                            log.debug("  Parsing as Gerber file...");
                            GerberDocument doc = gerberParser.parse(contentStr);
                            layer = new MultiLayerSVGRenderer.Layer(name, doc);
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

                            // Create layer info for JSON response
                            String id = name.replaceAll("[^a-zA-Z0-9._-]", "_");
                            layerInfos.add(new LayerInfo(name, id, color, layerType));

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
            width: 280px;
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
    </style>
</head>
<body>
    <header>
        <h1>Gerber Viewer</h1>
        <label class="upload-btn">
            <input type="file" id="file-input" accept=".zip">
            Open ZIP File
        </label>
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
        </div>
    </div>

    <script>
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

                // Store layer metadata with visibility state
                layers = data.layers.map((layer, index) => ({
                    ...layer,
                    visible: true,
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

            layerList.innerHTML = layers.map((layer, index) => `
                <div class="layer-item" onclick="toggleLayer(${index})">
                    <input type="checkbox" ${layer.visible ? 'checked' : ''} onclick="event.stopPropagation(); toggleLayer(${index})">
                    <div class="color-dot" style="background: ${layer.color}"></div>
                    <span class="name" title="${layer.name}">${layer.name}</span>
                </div>
            `).join('');
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
