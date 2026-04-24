package com.deltaproto.deltagerber.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Simple HTTP server for the Gerber viewer web application.
 *
 * The server is stateless — the browser owns the file data (stored in IndexedDB)
 * and sends it to the server for parsing and rendering.
 *
 * Endpoints:
 * - GET /           — serves the HTML viewer app
 * - POST /api/gerber/render — receives files with metadata, returns multi-layer + realistic SVGs
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
        server.createContext("/api/gerber/render", new RenderHandler());
        server.createContext("/api/gerber/thumbnail", new ThumbnailHandler());
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
            } else if (path.equals("/api/gerber/arduino-uno-example.zip")) {
                try (InputStream is = GerberViewerServer.class.getResourceAsStream("/web/arduino-uno-example.zip")) {
                    if (is != null) {
                        byte[] data = is.readAllBytes();
                        exchange.getResponseHeaders().set("Content-Type", "application/zip");
                        exchange.sendResponseHeaders(200, data.length);
                        try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
                        return;
                    }
                }
                sendResponse(exchange, 404, "text/plain", "Not Found");
            } else {
                sendResponse(exchange, 404, "text/plain", "Not Found");
            }
        }
    }

    /**
     * Stateless render endpoint. Receives files with layer type metadata,
     * parses Gerber/drill content, and returns multi-layer + realistic SVGs.
     *
     * Request format (tab-separated, length-prefixed):
     * <pre>
     * FILE\tname\tfileType\tlayerType\tcontentLength\n
     * content bytes...
     * FILE\tname\tfileType\tlayerType\tcontentLength\n
     * content bytes...
     * </pre>
     */
    static class RenderHandler implements HttpHandler {
        private static final Logger log = LoggerFactory.getLogger(RenderHandler.class);

        private final GerberParser gerberParser = new GerberParser();
        private final ExcellonParser drillParser = new ExcellonParser();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            long startTime = System.currentTimeMillis();
            log.info("Received render request");

            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                log.info("Request body: {} bytes", body.length);

                List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
                List<LayerMeta> layerMetas = new ArrayList<>();

                // Parse the length-prefixed file protocol
                int pos = 0;
                while (pos < body.length) {
                    // Find header line end
                    int lineEnd = indexOf(body, (byte) '\n', pos);
                    if (lineEnd < 0) break;
                    String header = new String(body, pos, lineEnd - pos, StandardCharsets.UTF_8);
                    if (!header.startsWith("FILE\t")) break;

                    String[] parts = header.substring(5).split("\t");
                    if (parts.length < 4) break;
                    String name = parts[0];
                    String fileType = parts[1];
                    String layerTypeStr = parts[2];
                    int contentLength = Integer.parseInt(parts[3]);

                    pos = lineEnd + 1;
                    String content = new String(body, pos, contentLength, StandardCharsets.UTF_8);
                    pos += contentLength;
                    // Skip optional trailing newline
                    if (pos < body.length && body[pos] == '\n') pos++;

                    log.debug("File: {} type={} layerType={} size={}", name, fileType, layerTypeStr, contentLength);

                    try {
                        MultiLayerSVGRenderer.Layer layer = null;
                        LayerType layerType = LayerType.valueOf(layerTypeStr);

                        if ("drill".equals(fileType)) {
                            DrillDocument doc = drillParser.parse(content);
                            layer = new MultiLayerSVGRenderer.Layer(name, doc);
                        } else if ("gerber".equals(fileType)) {
                            GerberDocument doc = gerberParser.parse(content);
                            layer = new MultiLayerSVGRenderer.Layer(name, doc);
                        }

                        if (layer != null) {
                            String color = getLayerColor(name);
                            layer.setColor(color).setOpacity(0.85).setLayerType(layerType);
                            layers.add(layer);

                            String id = name.replaceAll("[^a-zA-Z0-9._-]", "_");
                            layerMetas.add(new LayerMeta(name, id, color, fileType, layerTypeStr));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse {}: {}", name, e.getMessage());
                    }
                }

                // Render all SVGs
                log.info("Rendering {} layers...", layers.size());
                MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
                String svg = renderer.render(layers);
                String realisticTop = renderRealisticSide(layers, true);
                String realisticBottom = renderRealisticSide(layers, false);

                // Build JSON response
                StringBuilder json = new StringBuilder();
                json.append("{\"layers\":[");
                boolean first = true;
                for (LayerMeta m : layerMetas) {
                    if (!first) json.append(",");
                    first = false;
                    json.append("{\"name\":").append(escapeJson(m.name));
                    json.append(",\"id\":").append(escapeJson(m.id));
                    json.append(",\"color\":").append(escapeJson(m.color));
                    json.append(",\"type\":").append(escapeJson(m.type));
                    json.append(",\"layerType\":").append(escapeJson(m.layerType));
                    json.append("}");
                }
                json.append("],\"svg\":").append(escapeJson(svg));
                json.append(",\"realisticTopSvg\":");
                json.append(realisticTop != null ? escapeJson(realisticTop) : "null");
                json.append(",\"realisticBottomSvg\":");
                json.append(realisticBottom != null ? escapeJson(realisticBottom) : "null");
                json.append("}");

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("Render complete: {} layers in {}ms", layerMetas.size(), elapsed);

                sendResponse(exchange, 200, "application/json", json.toString());
            } catch (Exception e) {
                log.error("Error rendering", e);
                sendResponse(exchange, 500, "application/json",
                    "{\"error\":" + escapeJson(e.getMessage()) + "}");
            }
        }

        private static class LayerMeta {
            final String name, id, color, type, layerType;
            LayerMeta(String name, String id, String color, String type, String layerType) {
                this.name = name; this.id = id; this.color = color; this.type = type; this.layerType = layerType;
            }
        }
    }

    /**
     * Returns a PNG thumbnail of the realistic top/bottom view — used by project
     * list UIs that show many boards at once. Accepts the same request body as
     * {@link RenderHandler}. Query params: {@code side=top|bottom}, {@code width=<px>}
     * (default 400, max 2000).
     */
    static class ThumbnailHandler implements HttpHandler {
        private static final Logger log = LoggerFactory.getLogger(ThumbnailHandler.class);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            try {
                Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
                String sideStr = q.getOrDefault("side", "top").toLowerCase();
                MultiLayerSVGRenderer.Side side = "bottom".equals(sideStr)
                    ? MultiLayerSVGRenderer.Side.BOTTOM : MultiLayerSVGRenderer.Side.TOP;
                int width  = parseIntOrDefault(q.get("width"),  400);
                int height = parseIntOrDefault(q.get("height"), 0);
                width  = clampDim(width,  0, 4000); // 0 = auto
                height = clampDim(height, 0, 4000);
                if (width == 0 && height == 0) width = 400;

                byte[] body = exchange.getRequestBody().readAllBytes();
                List<MultiLayerSVGRenderer.Layer> layers = parseLayerBody(body);

                byte[] png = new MultiLayerSVGRenderer().renderRealisticSidePng(layers, side, width, height);
                if (png == null) {
                    sendResponse(exchange, 422, "application/json",
                        "{\"error\":\"no outline layer or side has no content\"}");
                    return;
                }

                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, png.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(png); }
            } catch (Exception e) {
                log.error("Thumbnail render failed", e);
                sendResponse(exchange, 500, "application/json",
                    "{\"error\":" + escapeJson(e.getMessage()) + "}");
            }
        }
    }

    private static int indexOf(byte[] data, byte target, int from) {
        for (int i = from; i < data.length; i++) {
            if (data[i] == target) return i;
        }
        return -1;
    }

    /**
     * Parse the length-prefixed file protocol shared by /render and /thumbnail.
     * Silently drops files that fail to parse (per-file try/catch) so a single
     * bad layer can't take down the whole request.
     */
    static List<MultiLayerSVGRenderer.Layer> parseLayerBody(byte[] body) {
        GerberParser gerberParser = new GerberParser();
        ExcellonParser drillParser = new ExcellonParser();
        List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();

        int pos = 0;
        while (pos < body.length) {
            int lineEnd = indexOf(body, (byte) '\n', pos);
            if (lineEnd < 0) break;
            String header = new String(body, pos, lineEnd - pos, StandardCharsets.UTF_8);
            if (!header.startsWith("FILE\t")) break;
            String[] parts = header.substring(5).split("\t");
            if (parts.length < 4) break;
            String name = parts[0];
            String fileType = parts[1];
            String layerTypeStr = parts[2];
            int contentLength = Integer.parseInt(parts[3]);
            pos = lineEnd + 1;
            String content = new String(body, pos, contentLength, StandardCharsets.UTF_8);
            pos += contentLength;
            if (pos < body.length && body[pos] == '\n') pos++;
            try {
                MultiLayerSVGRenderer.Layer layer = null;
                LayerType layerType = LayerType.valueOf(layerTypeStr);
                if ("drill".equals(fileType)) {
                    layer = new MultiLayerSVGRenderer.Layer(name, drillParser.parse(content));
                } else if ("gerber".equals(fileType)) {
                    layer = new MultiLayerSVGRenderer.Layer(name, gerberParser.parse(content));
                }
                if (layer != null) {
                    layer.setColor(getLayerColor(name)).setOpacity(0.85).setLayerType(layerType);
                    layers.add(layer);
                }
            } catch (Exception e) {
                LoggerFactory.getLogger(GerberViewerServer.class)
                    .warn("Failed to parse {}: {}", name, e.getMessage());
            }
        }
        return layers;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(pair, "");
            else out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    private static int parseIntOrDefault(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static int clampDim(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    // --- Shared helpers ---

    private static final Map<String, String> LAYER_COLORS = new LinkedHashMap<>();
    static {
        LAYER_COLORS.put("gtl", "#e94560"); LAYER_COLORS.put("f_cu", "#e94560"); LAYER_COLORS.put("cmp", "#e94560");
        LAYER_COLORS.put("gbl", "#4169e1"); LAYER_COLORS.put("b_cu", "#4169e1"); LAYER_COLORS.put("sol", "#4169e1");
        LAYER_COLORS.put("g2", "#ff8c00"); LAYER_COLORS.put("g1", "#ff6600"); LAYER_COLORS.put("g3", "#9932cc");
        LAYER_COLORS.put("gts", "#00aa00"); LAYER_COLORS.put("gbs", "#006600");
        LAYER_COLORS.put("f_mask", "#00aa00"); LAYER_COLORS.put("b_mask", "#006600");
        LAYER_COLORS.put("stc", "#00aa00"); LAYER_COLORS.put("sts", "#006600");
        LAYER_COLORS.put("gto", "#ffffff"); LAYER_COLORS.put("gbo", "#cccccc");
        LAYER_COLORS.put("f_silks", "#ffffff"); LAYER_COLORS.put("b_silks", "#cccccc");
        LAYER_COLORS.put("plc", "#ffffff"); LAYER_COLORS.put("pls", "#cccccc");
        LAYER_COLORS.put("gtp", "#888888"); LAYER_COLORS.put("gbp", "#666666");
        LAYER_COLORS.put("gko", "#ffff00"); LAYER_COLORS.put("gm1", "#ffff00"); LAYER_COLORS.put("edge", "#ffff00");
        LAYER_COLORS.put("drl", "#00ffff"); LAYER_COLORS.put("xln", "#00ffff"); LAYER_COLORS.put("drd", "#00ffff");
    }

    public static String getLayerColor(String filename) {
        String lower = filename.toLowerCase();
        for (Map.Entry<String, String> entry : LAYER_COLORS.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        return "#aaaaaa";
    }

    public static String renderRealisticSide(List<MultiLayerSVGRenderer.Layer> allLayers, boolean topSide) {
        try {
            List<MultiLayerSVGRenderer.Layer> sideLayers = new ArrayList<>();
            for (MultiLayerSVGRenderer.Layer layer : allLayers) {
                LayerType lt = layer.getLayerType();
                if (lt == LayerType.OUTLINE) {
                    sideLayers.add(layer);
                } else if (topSide && (lt == LayerType.COPPER_TOP || lt == LayerType.SOLDERMASK_TOP
                        || lt == LayerType.SILKSCREEN_TOP)) {
                    sideLayers.add(layer);
                } else if (!topSide && (lt == LayerType.COPPER_BOTTOM || lt == LayerType.SOLDERMASK_BOTTOM
                        || lt == LayerType.SILKSCREEN_BOTTOM)) {
                    sideLayers.add(layer);
                } else if (lt == LayerType.DRILL || lt == LayerType.DRILL_PLATED
                        || lt == LayerType.DRILL_NON_PLATED) {
                    sideLayers.add(layer);
                }
            }
            boolean hasOutline = sideLayers.stream().anyMatch(l -> l.getLayerType() == LayerType.OUTLINE);
            if (!hasOutline || sideLayers.size() < 2) return null;
            return new MultiLayerSVGRenderer().renderRealistic(sideLayers);
        } catch (Exception e) {
            LoggerFactory.getLogger(GerberViewerServer.class)
                .warn("Failed to render realistic {} side: {}", topSide ? "top" : "bottom", e.getMessage());
            return null;
        }
    }

    static void sendResponse(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String escapeJson(String s) {
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
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String indexHtmlCache;

    public static String getIndexHtml() {
        if (indexHtmlCache != null) return indexHtmlCache;
        try (InputStream is = GerberViewerServer.class.getResourceAsStream("/web/index.html")) {
            if (is != null) {
                indexHtmlCache = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return indexHtmlCache;
            }
        } catch (IOException e) {
            log.warn("Failed to load index.html from classpath", e);
        }
        return "<html><body><h1>Error: index.html not found on classpath</h1></body></html>";
    }

    public static void main(String[] args) throws IOException {
        java.util.Locale.setDefault(java.util.Locale.US);

        int port = 938;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        GerberViewerServer server = new GerberViewerServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
