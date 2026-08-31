package com.deltaproto.deltagerber.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.classify.LayerClassifier;
import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.drill.DrillDocument;
import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.ComponentPlacement;
import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.parser.ExcellonParser;
import com.deltaproto.deltagerber.parser.GerberParser;
import com.deltaproto.deltagerber.renderer.svg.LayerType;
import com.deltaproto.deltagerber.renderer.svg.MultiLayerSVGRenderer;
import com.deltaproto.deltagerber.renderer.svg.SilkscreenColor;
import com.deltaproto.deltagerber.renderer.svg.SoldermaskColor;
import com.deltaproto.deltagerber.renderer.step.StepExporter;
import com.deltaproto.deltagerber.dfm.ViaInPadDetector;
import com.deltaproto.deltagerber.dfm.ViaInPadGroup;
import com.deltaproto.deltagerber.dfm.ViaInPadResult;
import com.deltaproto.deltagerber.spec.AnalyzedLayer;
import com.deltaproto.deltagerber.spec.BoardSpecification;
import com.deltaproto.deltagerber.spec.PcbAnalyzer;
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
 * - POST /api/gerber/thumbnail — the realistic view as a PNG
 * - POST /api/gerber/step — the board outline, drilled, extruded into a STEP (ISO 10303-21) solid
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
        server.createContext("/api/gerber/step", new StepHandler());
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

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                sendResponse(exchange, 200, "application/json", renderToJson(body));
            } catch (Exception e) {
                log.error("Error rendering", e);
                sendResponse(exchange, 500, "application/json",
                    "{\"error\":" + escapeJson(e.getMessage()) + "}");
            }
        }

        /**
         * Parse, classify and render one viewer request body, as the JSON the viewer's JS expects.
         *
         * <p>Separate from {@link #handle} so a host application can serve this viewer from its own
         * HTTP stack — dp-3 runs it inside a sandboxed child JVM behind its own tracked, rate-limited
         * endpoint — without reimplementing the flow and drifting from it.
         */
        static String renderToJson(byte[] body) {
            long startTime = System.currentTimeMillis();
            log.info("Rendering request body: {} bytes", body.length);

            GerberParser gerberParser = new GerberParser();
            ExcellonParser drillParser = new ExcellonParser();

            List<MultiLayerSVGRenderer.Layer> layers = new ArrayList<>();
            List<LayerMeta> layerMetas = new ArrayList<>();
            List<ComponentPlacement> allComponents = new ArrayList<>();
            List<FileWarnings> allWarnings = new ArrayList<>();
            // What each file is, by name. The client's layer type wins — the user can correct it
            // in the dropdown, and a corrected outline changes how every copper layer is measured.
            Map<String, LayerClassification> classifications = new LinkedHashMap<>();
            List<PendingLayer> pending = new ArrayList<>();

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
                // Optional 5th field: the inner-copper layer number the user picked.
                Integer clientNumber = parts.length >= 5 && !parts[4].isBlank()
                        ? Integer.valueOf(parts[4].trim()) : null;

                pos = lineEnd + 1;
                String content = new String(body, pos, contentLength, StandardCharsets.UTF_8);
                pos += contentLength;
                // Skip optional trailing newline
                if (pos < body.length && body[pos] == '\n') pos++;

                log.debug("File: {} type={} layerType={} size={}", name, fileType, layerTypeStr, contentLength);

                try {
                    MultiLayerSVGRenderer.Layer layer = null;
                    GerberDocument gerberDoc = null;

                    if ("drill".equals(fileType)) {
                        DrillDocument doc = drillParser.parse(content);
                        layer = new MultiLayerSVGRenderer.Layer(name, doc);
                    } else if ("gerber".equals(fileType)) {
                        gerberDoc = gerberParser.parse(content);
                        allComponents.addAll(gerberDoc.getComponents());
                        layer = new MultiLayerSVGRenderer.Layer(name, gerberDoc);
                    }

                    if (layer != null) {
                        LayerClassification detected = LayerClassifier.classify(name, content);
                        LayerType layerType = resolveLayerType(layerTypeStr, fileType, detected, gerberDoc);

                        String color = getLayerColor(name);
                        double opacity = (layerType == LayerType.PNP_TOP || layerType == LayerType.PNP_BOTTOM)
                            ? 0.45 : 0.85;
                        layer.setColor(color).setOpacity(opacity).setLayerType(layerType);
                        layers.add(layer);
                        // Classification waits for the whole set: an inner layer's index cannot
                        // be normalized until every inner layer has been seen.
                        pending.add(new PendingLayer(name, fileType, layerTypeStr, clientNumber,
                                detected, layerType, color));
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse {}: {}", name, e.getMessage());
                }
            }

            // The set is complete, so the inner copper layers can be renumbered from 1 whether
            // the generator counted from 1 (Protel, Allegro) or from 2 (Gerber X2).
            List<LayerClassification> normalized = LayerClassifier.normalizeInnerCopperNumbers(
                    pending.stream().map(PendingLayer::detected).toList());

            for (int i = 0; i < pending.size(); i++) {
                PendingLayer p = pending.get(i);
                LayerClassification detected = normalized.get(i);

                // What a layer *is* and what it is *drawn as* are different questions. Left to
                // itself, the classifier's answer is the better one: a sideless soldermask is
                // still a soldermask even though no renderer can place it. Once the user has
                // picked a type, that pick is the answer.
                LayerClassification classification = AUTO.equals(p.layerTypeStr()) && detected != null
                        ? detected
                        : classify(p.layerType(), p.clientNumber(), detected, p.name());
                if (p.layerType().isDrill() && !classification.function().isDrill()) {
                    classification = new LayerClassification(p.name(), LayerFunction.DRILL, LayerSide.NA);
                }
                classifications.put(p.name(), classification);

                String id = p.name().replaceAll("[^a-zA-Z0-9._-]", "_");
                layerMetas.add(new LayerMeta(p.name(), id, p.color(), p.fileType(),
                        p.layerType().name(), classification.number()));
            }

            // Detect and correct a drill/Gerber origin mismatch before rendering (render() itself
            // stays pure). The correction is baked into a copy of the drill document with a
            // reversible originOffset stamp, and the explanatory warning is recorded on it. The
            // server re-detects on every request (cheap, deterministic) so the client never has
            // to track or echo offsets.
            layers = MultiLayerSVGRenderer.alignDrillLayers(layers);

            // Render all SVGs
            log.info("Rendering {} layers...", layers.size());
            MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
            String svg = renderer.render(layers);
            String realisticTop = renderRealisticSide(layers, true);
            String realisticBottom = renderRealisticSide(layers, false);

            // Collect warnings from the final layer documents — parse-time anomalies plus any
            // drill re-alignment explanation recorded above.
            for (MultiLayerSVGRenderer.Layer layer : layers) {
                List<String> w = layer.isDrill() ? layer.getDrillDoc().getWarnings()
                    : layer.isGerber() ? layer.getGerberDoc().getWarnings() : null;
                if (w != null && !w.isEmpty()) {
                    allWarnings.add(new FileWarnings(layer.getName(), new ArrayList<>(w)));
                }
            }

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
                json.append(",\"layerNumber\":").append(m.layerNumber);
                json.append("}");
            }
            json.append("],\"svg\":").append(escapeJson(svg));
            json.append(",\"realisticTopSvg\":");
            json.append(realisticTop != null ? escapeJson(realisticTop) : "null");
            json.append(",\"realisticBottomSvg\":");
            json.append(realisticBottom != null ? escapeJson(realisticBottom) : "null");

            // Component placement data from PnP files
            json.append(",\"components\":[");
            boolean firstComp = true;
            for (ComponentPlacement c : allComponents) {
                if (!firstComp) json.append(",");
                firstComp = false;
                json.append("{\"refdes\":").append(escapeJson(c.getRefdes()));
                json.append(",\"value\":").append(escapeJson(c.getValue()));
                json.append(",\"footprint\":").append(escapeJson(c.getFootprint()));
                json.append(",\"mountType\":").append(escapeJson(c.getMountType()));
                json.append(",\"x\":").append(String.format(java.util.Locale.US, "%.4f", c.getX()));
                json.append(",\"y\":").append(String.format(java.util.Locale.US, "%.4f", c.getY()));
                json.append(",\"rotation\":").append(String.format(java.util.Locale.US, "%.2f", c.getRotation()));
                json.append(",\"side\":").append(escapeJson(c.getSide()));
                json.append("}");
            }
            json.append("]");

            // Per-file parser warnings (truncated blocks, undefined apertures, bad
            // coordinates, …). The viewer surfaces these in a dedicated tab.
            json.append(",\"warnings\":[");
            boolean firstW = true;
            for (FileWarnings fw : allWarnings) {
                if (!firstW) json.append(",");
                firstW = false;
                json.append("{\"file\":").append(escapeJson(fw.file));
                json.append(",\"messages\":[");
                boolean firstM = true;
                for (String m : fw.messages) {
                    if (!firstM) json.append(",");
                    firstM = false;
                    json.append(escapeJson(m));
                }
                json.append("]}");
            }
            json.append("]");

            // Everything the library derives about the board itself. Measured from the
            // documents already parsed above — a viewer that renders a set has no business
            // parsing it a second time to describe it.
            appendPcbInfo(json, layers, classifications);

            json.append("}");

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Render complete: {} layers in {}ms", layerMetas.size(), elapsed);

            return json.toString();
        }

        /** Layer type the client sends when it has no opinion and the library should classify. */
        private static final String AUTO = "AUTO";

        /**
         * A parsed layer, held until the whole set is known. Its classification cannot be settled
         * file-by-file: an inner copper index is only meaningful relative to the other inner layers.
         */
        private record PendingLayer(String name, String fileType, String layerTypeStr, Integer clientNumber,
                                    LayerClassification detected, LayerType layerType, String color) {
        }

        /**
         * What to draw this file as. {@code AUTO} means the client has no opinion and the library's
         * classifier decides; anything else is the user's choice from the dropdown and stands.
         */
        static LayerType resolveLayerType(String layerTypeStr, String fileType,
                                          LayerClassification detected, GerberDocument document) {
            LayerType layerType;
            if (AUTO.equals(layerTypeStr)) {
                layerType = renderTypeOf(detected, document);
            } else {
                try {
                    layerType = LayerType.valueOf(layerTypeStr);
                } catch (IllegalArgumentException e) {
                    layerType = LayerType.OTHER;
                }
            }
            // The client read this file as Excellon; whatever its name suggests, it is a drill.
            return "drill".equals(fileType) && !layerType.isDrill() ? LayerType.DRILL : layerType;
        }

        /**
         * A classification restated as the {@link LayerType} the renderer draws with.
         *
         * <p>A pick-and-place file is the one thing the classifier cannot name — {@code .FileFunction
         * Component} is not a fabrication layer, so it comes back UNKNOWN. The parsed document knows,
         * because it collected the component placements.
         *
         * <p>An outline that draws nothing is demoted to {@link LayerType#OTHER}: the realistic view
         * would take it for the board edge and produce an empty board.
         */
        private static LayerType renderTypeOf(LayerClassification classification, GerberDocument document) {
            if (document != null && document.isComponentFile()) {
                return "Bottom".equals(document.getComponentSide()) ? LayerType.PNP_BOTTOM : LayerType.PNP_TOP;
            }
            if (classification == null) {
                return LayerType.OTHER;
            }
            if (classification.function() == LayerFunction.OUTLINE
                    && document != null && document.getObjects().isEmpty()) {
                return LayerType.OTHER;
            }
            return LayerType.of(classification.function(), classification.side());
        }

        /**
         * The user's layer type, restated as a {@link LayerClassification}.
         *
         * <p>{@link LayerType} carries no inner-layer index, so the index comes from the user's
         * choice when they made one, and otherwise from the library's own reading of the file.
         * Retyping an inner layer as anything else drops the index: {@link LayerClassification}
         * keeps a number only for inner copper.
         */
        static LayerClassification classify(LayerType layerType, Integer number,
                                            LayerClassification detected, String name) {
            Integer index = number != null ? number : (detected != null ? detected.number() : null);
            LayerClassification classification =
                    new LayerClassification("", layerType.getFunction(), layerType.getSide(), index);
            // The label has to be derived from the type the user picked, never carried over from
            // what the file used to look like — otherwise a .GTL retyped as silkscreen still reads
            // "top copper", and the label contradicts the function beside it.
            return classification.withName(label(layerType, classification.number()));
        }

        /** A human label for a layer type; the only place the viewer names one. */
        static String label(LayerType layerType, Integer number) {
            return switch (layerType) {
                case OUTLINE -> "board outline";
                case COPPER_TOP -> "top copper";
                case COPPER_BOTTOM -> "bottom copper";
                case COPPER_INNER -> number != null ? "inner copper " + number : "inner copper";
                case SOLDERMASK_TOP -> "top soldermask";
                case SOLDERMASK_BOTTOM -> "bottom soldermask";
                case SILKSCREEN_TOP -> "top silkscreen";
                case SILKSCREEN_BOTTOM -> "bottom silkscreen";
                case PASTE_TOP -> "top paste";
                case PASTE_BOTTOM -> "bottom paste";
                case DRILL -> "drill";
                case DRILL_PLATED -> "plated drill";
                case DRILL_NON_PLATED -> "non-plated drill";
                case PNP_TOP -> "top pick-and-place";
                case PNP_BOTTOM -> "bottom pick-and-place";
                case OTHER -> "other";
            };
        }

        /** Union of the profile centrelines of the outline layers — the board rectangle. */
        private static BoundingBox outlineBounds(List<MultiLayerSVGRenderer.Layer> layers,
                                                 Map<String, LayerClassification> classifications) {
            BoundingBox union = new BoundingBox();
            for (MultiLayerSVGRenderer.Layer layer : layers) {
                LayerClassification c = classifications.get(layer.getName());
                if (c != null && c.function() == LayerFunction.OUTLINE && layer.isGerber()) {
                    union.include(layer.getGerberDoc().calculatePathBoundingBox());
                }
            }
            return union.isValid() ? union : null;
        }

        private static void appendPcbInfo(StringBuilder json, List<MultiLayerSVGRenderer.Layer> layers,
                                          Map<String, LayerClassification> classifications) {
            BoundingBox outline = outlineBounds(layers, classifications);

            List<AnalyzedLayer> analyzed = new ArrayList<>();
            List<GerberDocument> topPaste = new ArrayList<>();
            List<GerberDocument> bottomPaste = new ArrayList<>();
            List<DrillDocument> drills = new ArrayList<>();
            for (MultiLayerSVGRenderer.Layer layer : layers) {
                LayerClassification c = classifications.get(layer.getName());
                if (layer.isDrill()) {
                    analyzed.add(PcbAnalyzer.measure(layer.getName(), layer.getDrillDoc(), c));
                    drills.add(layer.getDrillDoc());
                } else if (layer.isGerber()) {
                    analyzed.add(PcbAnalyzer.measure(layer.getName(), layer.getGerberDoc(), c, outline));
                    if (layer.getLayerType() == LayerType.PASTE_TOP) {
                        topPaste.add(layer.getGerberDoc());
                    } else if (layer.getLayerType() == LayerType.PASTE_BOTTOM) {
                        bottomPaste.add(layer.getGerberDoc());
                    }
                }
            }
            // Drills here are already aligned into the Gerber frame (alignDrillLayers ran before
            // rendering), so detect directly rather than re-aligning.
            ViaInPadResult viaInPad = (topPaste.isEmpty() && bottomPaste.isEmpty()) || drills.isEmpty()
                    ? null
                    : ViaInPadDetector.detect(topPaste, bottomPaste, drills);
            BoardSpecification spec = BoardSpecification.from(analyzed, viaInPad);

            json.append(",\"pcbInfo\":{");
            json.append("\"sizeX\":").append(number(spec.getSizeXMm(), 4));
            json.append(",\"sizeY\":").append(number(spec.getSizeYMm(), 4));
            json.append(",\"bounds\":");
            appendBounds(json, spec.getBounds());
            json.append(",\"copperLayers\":").append(spec.getCopperLayerCount());
            json.append(",\"solderMaskSide\":").append(escapeJson(name(spec.getSolderMaskSide())));
            json.append(",\"silkscreenSide\":").append(escapeJson(name(spec.getSilkscreenSide())));
            json.append(",\"stencilSide\":").append(escapeJson(name(spec.getStencilSide())));
            json.append(",\"minTrackUm\":").append(number(spec.getMinTrackWidthUm(), 3));
            json.append(",\"minDrillMm\":").append(number(spec.getMinDrillDiameterMm(), 4));
            json.append(",\"hasCopper\":").append(spec.hasCopper());
            json.append(",\"hasDrill\":").append(spec.hasDrill());
            json.append(",\"hasOutline\":").append(spec.hasOutline());

            // Via in pad: a drilled hole inside an SMD pad. null means the set had no paste or no
            // drill to judge from. hasViaInPad is the geometric fact; requiresFilledAndCappedVias
            // is the process verdict (IPC-4761 Type VII), which a via field under a thermal pad
            // does not trip — the per-pad evidence for that call is in viaInPadPads.
            json.append(",\"hasViaInPad\":").append(spec.hasViaInPad());
            json.append(",\"viaInPadCount\":").append(spec.getViaInPadCount());
            json.append(",\"viaInPadSide\":").append(escapeJson(name(spec.getViaInPadSide())));
            json.append(",\"requiresFilledAndCappedVias\":").append(spec.requiresFilledAndCappedVias());
            json.append(",\"viaInPadPads\":[");
            List<ViaInPadGroup> groups = spec.getViaInPadGroups();
            for (int i = 0; i < groups.size(); i++) {
                ViaInPadGroup g = groups.get(i);
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"x\":").append(number(g.getPadCenterX(), 4));
                json.append(",\"y\":").append(number(g.getPadCenterY(), 4));
                json.append(",\"side\":").append(escapeJson(g.isTop() ? "TOP" : "BOTTOM"));
                json.append(",\"padShape\":").append(escapeJson(g.getPadShape()));
                json.append(",\"padAreaMm2\":").append(number(g.getPadAreaMm2(), 4));
                json.append(",\"viaCount\":").append(g.getViaCount());
                json.append(",\"viaDiameterMm\":").append(number(g.getViaDiameterMm(), 4));
                // Infinite when a hole has no measurable diameter — not a JSON number, so null.
                double ratio = g.getPadToViaAreaRatio();
                json.append(",\"padToViaAreaRatio\":")
                        .append(Double.isFinite(ratio) ? number(ratio, 2) : "null");
                json.append(",\"thermal\":").append(g.isLikelyThermal());
                json.append(",\"requiresFilledAndCapped\":").append(g.requiresFilledAndCapped());
                json.append('}');
            }
            json.append(']');

            // Gerber X2 file attributes: what the CAD tool told us about the job itself.
            json.append(",\"generationSoftware\":").append(escapeJson(
                    firstAttribute(layers, GerberDocument::getGenerationSoftware)));
            json.append(",\"projectId\":").append(escapeJson(firstAttribute(layers,
                    d -> d.getProjectId().isEmpty() ? null : d.getProjectId().get(0))));
            json.append(",\"creationDate\":").append(escapeJson(
                    firstAttribute(layers, GerberDocument::getCreationDate)));
            json.append(",\"part\":").append(escapeJson(firstAttribute(layers, GerberDocument::getPart)));

            json.append(",\"layers\":[");
            boolean first = true;
            for (AnalyzedLayer layer : analyzed) {
                if (!first) json.append(",");
                first = false;
                json.append("{\"file\":").append(escapeJson(layer.getFileName()));
                json.append(",\"name\":").append(escapeJson(
                        layer.getClassification() == null ? null : layer.getClassification().name()));
                json.append(",\"function\":").append(escapeJson(layer.getFunction().name()));
                json.append(",\"side\":").append(escapeJson(layer.getSide().name()));
                json.append(",\"number\":").append(layer.getLayerNumber());
                json.append(",\"bounds\":");
                appendBounds(json, layer.getBounds());
                json.append(",\"minTrackUm\":").append(number(layer.getMinTrackWidthUm(), 3));
                json.append(",\"minDrillMm\":").append(number(layer.getMinDrillDiameterMm(), 4));
                json.append(",\"hasGeometry\":").append(layer.getHasGeometry());
                json.append("}");
            }
            json.append("]}");
        }

        /** The first non-null value of {@code attribute} across the Gerber layers, or null. */
        private static String firstAttribute(List<MultiLayerSVGRenderer.Layer> layers,
                                             java.util.function.Function<GerberDocument, String> attribute) {
            for (MultiLayerSVGRenderer.Layer layer : layers) {
                if (!layer.isGerber()) continue;
                String value = attribute.apply(layer.getGerberDoc());
                if (value != null && !value.isBlank()) return value;
            }
            return null;
        }

        private static void appendBounds(StringBuilder json, BoundingBox bounds) {
            if (bounds == null || !bounds.isValid()) {
                json.append("null");
                return;
            }
            json.append("{\"minX\":").append(number(bounds.getMinX(), 4));
            json.append(",\"minY\":").append(number(bounds.getMinY(), 4));
            json.append(",\"maxX\":").append(number(bounds.getMaxX(), 4));
            json.append(",\"maxY\":").append(number(bounds.getMaxY(), 4));
            json.append(",\"width\":").append(number(bounds.getWidth(), 4));
            json.append(",\"height\":").append(number(bounds.getHeight(), 4));
            json.append("}");
        }

        private static String number(Double value, int decimals) {
            return value == null ? "null" : String.format(Locale.US, "%." + decimals + "f", value);
        }

        private static String name(Enum<?> value) {
            return value == null ? null : value.name();
        }

        private static class LayerMeta {
            final String name, id, color, type, layerType;
            final Integer layerNumber;
            LayerMeta(String name, String id, String color, String type, String layerType, Integer layerNumber) {
                this.name = name; this.id = id; this.color = color; this.type = type;
                this.layerType = layerType; this.layerNumber = layerNumber;
            }
        }

        private static class FileWarnings {
            final String file;
            final List<String> messages;
            FileWarnings(String file, List<String> messages) {
                this.file = file; this.messages = messages;
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
                byte[] body = exchange.getRequestBody().readAllBytes();
                byte[] png = renderThumbnailPng(body, q.get("side"),
                        parseIntOrDefault(q.get("width"), 400), parseIntOrDefault(q.get("height"), 0),
                        q.get("soldermask"), q.get("silkscreen"));
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

        /**
         * Render one viewer request body as a realistic-side PNG, or {@code null} when the set has
         * no outline or that side has nothing on it. Separate from {@link #handle} for the same
         * reason as {@link RenderHandler#renderToJson}: a host application serves it from its own
         * HTTP stack.
         *
         * <p>{@code side} is {@code top}/{@code bottom} (default top). {@code width} and
         * {@code height} are pixels, clamped to 4000; a zero means "derive from the other", and
         * both zero means 400 wide. {@code soldermask} and {@code silkscreen} are colour names —
         * an unknown mask is green, an absent legend is whatever the mask pairs with. All four are
         * nullable, so a caller can pass query parameters straight through.
         */
        static byte[] renderThumbnailPng(byte[] body, String side, int width, int height,
                                         String soldermask, String silkscreen) {
            MultiLayerSVGRenderer.Side renderSide = "bottom".equalsIgnoreCase(side)
                ? MultiLayerSVGRenderer.Side.BOTTOM : MultiLayerSVGRenderer.Side.TOP;
            width  = clampDim(width,  0, 4000); // 0 = auto
            height = clampDim(height, 0, 4000);
            if (width == 0 && height == 0) width = 400;

            List<MultiLayerSVGRenderer.Layer> layers = parseLayerBody(body);
            // The server is stateless: this request re-parsed the raw drill bytes, so the origin
            // correction must be re-derived here too (it is not carried over from /render).
            layers = MultiLayerSVGRenderer.alignDrillLayers(layers);

            MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer()
                .setSoldermaskColor(SoldermaskColor.fromString(soldermask));
            if (silkscreen != null && !silkscreen.isBlank()) {
                renderer.setSilkscreenColor(SilkscreenColor.fromString(silkscreen));
            }
            return renderer.renderRealisticSidePng(layers, renderSide, width, height);
        }
    }


    /**
     * Returns the board outline extruded into a STEP (ISO 10303-21) solid — the board as a
     * mechanical part, for an enclosure designer. Accepts the same request body as
     * {@link RenderHandler}. Query params: {@code thickness=<mm>} (default
     * {@value com.deltaproto.deltagerber.renderer.step.StepExporter#DEFAULT_THICKNESS_MM}),
     * {@code name=<board name>}, which becomes the part name in CAD and the download's filename,
     * {@code drills=false} to leave the set's drilled holes (and the mouse bites they take out of
     * the board edge) out of the solid, and {@code labels=false} to leave the words {@code TOP}
     * and {@code BOTTOM} off the two faces.
     */
    static class StepHandler implements HttpHandler {
        private static final Logger log = LoggerFactory.getLogger(StepHandler.class);

        /** Thickness bounds. Wide enough for anything fabricable — 0.1 mm flex to a 10 mm slab. */
        private static final double MIN_THICKNESS_MM = 0.05;
        private static final double MAX_THICKNESS_MM = 20.0;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            try {
                Map<String, String> q = parseQuery(exchange.getRequestURI().getRawQuery());
                double thickness = parseThickness(q.get("thickness"));
                if (thickness <= 0) {
                    sendResponse(exchange, 400, "application/json",
                        "{\"error\":\"thickness must be a number between " + MIN_THICKNESS_MM
                        + " and " + MAX_THICKNESS_MM + " mm\"}");
                    return;
                }
                String name = boardName(q.get("name"));
                boolean drills = !"false".equalsIgnoreCase(q.get("drills"));
                boolean labels = !"false".equalsIgnoreCase(q.get("labels"));
                byte[] body = exchange.getRequestBody().readAllBytes();

                String step = exportStep(body, thickness, name, drills, labels);
                if (step == null) {
                    sendResponse(exchange, 422, "application/json",
                        "{\"error\":\"no board outline: the set has no profile layer and no copper "
                        + "to derive the board edge from\"}");
                    return;
                }
                byte[] bytes = step.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "model/step");
                exchange.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + name + ".step\"");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } catch (Exception e) {
                log.error("STEP export failed", e);
                sendResponse(exchange, 500, "application/json",
                    "{\"error\":" + escapeJson(e.getMessage()) + "}");
            }
        }

        /**
         * Export one viewer request body as a STEP solid, or {@code null} when the set has no
         * board outline to extrude. Separate from {@link #handle} for the same reason as
         * {@link RenderHandler#renderToJson}: a host application serves it from its own HTTP stack.
         *
         * <p>{@code thicknessMm} is the finished board thickness — the one number no Gerber file
         * carries. A host that has analysed the set can pass
         * {@link BoardSpecification#getBoardThicknessMm()} when the board declared one, and
         * {@link StepExporter#DEFAULT_THICKNESS_MM} otherwise. {@code includeDrillHoles} puts
         * everything the set drills into the solid, mouse bites on the board edge included, and
         * {@code labelSides} writes {@code TOP} and {@code BOTTOM} across the two faces.
         */
        static String exportStep(byte[] body, double thicknessMm, String productName,
                                 boolean includeDrillHoles, boolean labelSides) {
            List<MultiLayerSVGRenderer.Layer> layers = parseLayerBody(body);
            if (layers.isEmpty()) return null;
            try {
                return new StepExporter()
                    .setThicknessMm(thicknessMm)
                    .setProductName(productName)
                    .setIncludeDrillHoles(includeDrillHoles)
                    .setLabelSides(labelSides)
                    .export(layers);
            } catch (IllegalArgumentException e) {
                // No resolvable board edge — the caller gets 422, not a 500.
                log.info("No STEP export for this set: {}", e.getMessage());
                return null;
            }
        }

        /** The requested thickness in mm, or a non-positive value when the request is bad. */
        private static double parseThickness(String raw) {
            if (raw == null || raw.isBlank()) return StepExporter.DEFAULT_THICKNESS_MM;
            double mm;
            try {
                mm = Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
            if (!Double.isFinite(mm) || mm < MIN_THICKNESS_MM || mm > MAX_THICKNESS_MM) return -1;
            return mm;
        }

        private static String boardName(String raw) {
            if (raw == null || raw.isBlank()) return "board";
            // Query values arrive raw, so a name with a space or an accent is percent-encoded.
            String decoded = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
            String cleaned = decoded.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
            return cleaned.isBlank() ? "board" : cleaned.substring(0, Math.min(64, cleaned.length()));
        }
    }

    // --- Public API for host applications ------------------------------------------------
    //
    // deltaproto.com serves this viewer from its own Spring endpoint so it can track and rate-limit
    // the traffic, and runs the library inside a sandboxed child JVM so an OOM on a hostile upload
    // cannot take the site down. Both entry points below take the viewer's own request body, so the
    // host never has to reimplement — and drift from — the flow the standalone server runs.

    /** @see RenderHandler#renderToJson(byte[]) */
    public static String renderToJson(byte[] body) {
        return RenderHandler.renderToJson(body);
    }

    /** @see ThumbnailHandler#renderThumbnailPng(byte[], String, int, int, String, String) */
    public static byte[] renderThumbnailPng(byte[] body, String side, int width, int height,
                                            String soldermask, String silkscreen) {
        return ThumbnailHandler.renderThumbnailPng(body, side, width, height, soldermask, silkscreen);
    }

    /**
     * As {@link #exportStep(byte[], double, String, boolean, boolean)}, with the drilled holes in
     * and both faces labelled — what the viewer's STEP button asks for.
     */
    public static String exportStep(byte[] body, double thicknessMm, String productName) {
        return StepHandler.exportStep(body, thicknessMm, productName, true, true);
    }

    /** @see StepHandler#exportStep(byte[], double, String, boolean, boolean) */
    public static String exportStep(byte[] body, double thicknessMm, String productName,
                                    boolean includeDrillHoles, boolean labelSides) {
        return StepHandler.exportStep(body, thicknessMm, productName, includeDrillHoles, labelSides);
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
                GerberDocument gerberDoc = null;
                if ("drill".equals(fileType)) {
                    layer = new MultiLayerSVGRenderer.Layer(name, drillParser.parse(content));
                } else if ("gerber".equals(fileType)) {
                    gerberDoc = gerberParser.parse(content);
                    layer = new MultiLayerSVGRenderer.Layer(name, gerberDoc);
                }
                // A thumbnail can be asked for before any render has resolved the types.
                LayerType layerType = RenderHandler.resolveLayerType(
                        layerTypeStr, fileType, LayerClassifier.classify(name, content), gerberDoc);
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
        LAYER_COLORS.put("pnp", "#cc44cc");
    }

    public static String getLayerColor(String filename) {
        String lower = filename.toLowerCase();
        for (Map.Entry<String, String> entry : LAYER_COLORS.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        return "#aaaaaa";
    }

    public static String renderRealisticSide(List<MultiLayerSVGRenderer.Layer> allLayers, boolean topSide) {
        return renderRealisticSide(allLayers, topSide, null, null);
    }

    /**
     * As {@link #renderRealisticSide(List, boolean)}, with the board's finish colors for this
     * side. A {@code null} soldermask or silkscreen leaves the renderer's default in place —
     * green mask with the white legend it pairs with. Use {@link SoldermaskColor#NONE} /
     * {@link SilkscreenColor#NONE} to render a board ordered without that finish.
     */
    public static String renderRealisticSide(List<MultiLayerSVGRenderer.Layer> allLayers, boolean topSide,
                                             SoldermaskColor soldermask, SilkscreenColor silkscreen) {
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
            // A realistic view needs a board outline. Prefer a real profile (OUTLINE) layer,
            // but MultiLayerSVGRenderer.renderRealistic can also derive the edge from copper,
            // so a copper-bearing side qualifies too. Mirror the PNG path's gate
            // (MultiLayerSVGRenderer.filterForSide) so the tabs/PNG light up in the same cases.
            LayerType copperType = topSide ? LayerType.COPPER_TOP : LayerType.COPPER_BOTTOM;
            boolean hasOutline = sideLayers.stream().anyMatch(l -> l.getLayerType() == LayerType.OUTLINE);
            boolean hasCopper = sideLayers.stream().anyMatch(l -> l.getLayerType() == copperType);
            if ((!hasOutline && !hasCopper) || sideLayers.size() < 2) return null;

            MultiLayerSVGRenderer renderer = new MultiLayerSVGRenderer();
            if (soldermask != null) renderer.setSoldermaskColor(soldermask);
            if (silkscreen != null) renderer.setSilkscreenColor(silkscreen);
            return renderer.renderRealistic(sideLayers);
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

        int port = 9380;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        GerberViewerServer server = new GerberViewerServer(port);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
