package nl.bytesoflife.gerber;

import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for comparing SVG documents structurally.
 * Supports comparison of:
 * - Element counts (paths, circles, rects, uses, etc.)
 * - Bounding box (from viewBox)
 * - Aperture IDs (from defs)
 * - Path data with tolerance
 */
public class SvgComparer {

    private static final double DEFAULT_TOLERANCE = 1e-4;
    private final double tolerance;

    public SvgComparer() {
        this(DEFAULT_TOLERANCE);
    }

    public SvgComparer(double tolerance) {
        this.tolerance = tolerance;
    }

    /**
     * Parse an SVG string and extract structural data.
     */
    public SvgData parse(String svg) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(svg)));

        SvgData data = new SvgData();

        Element root = doc.getDocumentElement();
        if (!"svg".equals(root.getTagName())) {
            throw new IllegalArgumentException("Root element is not svg");
        }

        // Extract viewBox
        String viewBox = root.getAttribute("viewBox");
        if (viewBox != null && !viewBox.isEmpty()) {
            data.viewBox = parseViewBox(viewBox);
        }

        // Count elements
        data.pathCount = doc.getElementsByTagName("path").getLength();
        data.circleCount = doc.getElementsByTagName("circle").getLength();
        data.rectCount = doc.getElementsByTagName("rect").getLength();
        data.useCount = doc.getElementsByTagName("use").getLength();
        data.lineCount = doc.getElementsByTagName("line").getLength();
        data.polygonCount = doc.getElementsByTagName("polygon").getLength();
        data.ellipseCount = doc.getElementsByTagName("ellipse").getLength();
        data.gCount = doc.getElementsByTagName("g").getLength();

        // Extract aperture IDs from defs
        NodeList defs = doc.getElementsByTagName("defs");
        if (defs.getLength() > 0) {
            extractApertureIds((Element) defs.item(0), data.apertureIds);
        }

        // Extract all path data
        NodeList paths = doc.getElementsByTagName("path");
        for (int i = 0; i < paths.getLength(); i++) {
            Element path = (Element) paths.item(i);
            String d = path.getAttribute("d");
            if (d != null && !d.isEmpty()) {
                String id = path.getAttribute("id");
                if (id == null || id.isEmpty()) {
                    id = "path_" + i;
                }
                data.pathData.put(id, d);
                data.parsedPaths.put(id, parsePathData(d));
            }
        }

        // Extract circle data
        NodeList circles = doc.getElementsByTagName("circle");
        for (int i = 0; i < circles.getLength(); i++) {
            Element circle = (Element) circles.item(i);
            CircleData cd = new CircleData();
            cd.cx = parseDouble(circle.getAttribute("cx"), 0);
            cd.cy = parseDouble(circle.getAttribute("cy"), 0);
            cd.r = parseDouble(circle.getAttribute("r"), 0);
            String id = circle.getAttribute("id");
            if (id == null || id.isEmpty()) {
                id = "circle_" + i;
            }
            data.circles.put(id, cd);
        }

        // Extract rect data
        NodeList rects = doc.getElementsByTagName("rect");
        for (int i = 0; i < rects.getLength(); i++) {
            Element rect = (Element) rects.item(i);
            RectData rd = new RectData();
            rd.x = parseDouble(rect.getAttribute("x"), 0);
            rd.y = parseDouble(rect.getAttribute("y"), 0);
            rd.width = parseDouble(rect.getAttribute("width"), 0);
            rd.height = parseDouble(rect.getAttribute("height"), 0);
            String id = rect.getAttribute("id");
            if (id == null || id.isEmpty()) {
                id = "rect_" + i;
            }
            data.rects.put(id, rd);
        }

        // Extract use references
        NodeList uses = doc.getElementsByTagName("use");
        for (int i = 0; i < uses.getLength(); i++) {
            Element use = (Element) uses.item(i);
            UseData ud = new UseData();
            ud.href = use.getAttribute("href");
            if (ud.href.isEmpty()) {
                ud.href = use.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            }
            ud.x = parseDouble(use.getAttribute("x"), 0);
            ud.y = parseDouble(use.getAttribute("y"), 0);
            data.uses.add(ud);
        }

        return data;
    }

    /**
     * Compare two SVG documents and return a comparison result.
     */
    public ComparisonResult compare(String svg1, String svg2) throws Exception {
        SvgData data1 = parse(svg1);
        SvgData data2 = parse(svg2);
        return compare(data1, data2);
    }

    /**
     * Compare two parsed SVG data structures.
     */
    public ComparisonResult compare(SvgData data1, SvgData data2) {
        ComparisonResult result = new ComparisonResult();

        // Compare element counts
        compareElementCounts(data1, data2, result);

        // Compare viewBox/bounding box
        compareViewBox(data1, data2, result);

        // Compare aperture IDs
        compareApertureIds(data1, data2, result);

        // Compare path data
        comparePathData(data1, data2, result);

        // Compare circles
        compareCircles(data1, data2, result);

        // Compare rects
        compareRects(data1, data2, result);

        // Compare uses
        compareUses(data1, data2, result);

        return result;
    }

    private void compareElementCounts(SvgData data1, SvgData data2, ComparisonResult result) {
        if (data1.pathCount != data2.pathCount) {
            result.addDifference("Element count", "path",
                String.valueOf(data1.pathCount), String.valueOf(data2.pathCount));
        }
        if (data1.circleCount != data2.circleCount) {
            result.addDifference("Element count", "circle",
                String.valueOf(data1.circleCount), String.valueOf(data2.circleCount));
        }
        if (data1.rectCount != data2.rectCount) {
            result.addDifference("Element count", "rect",
                String.valueOf(data1.rectCount), String.valueOf(data2.rectCount));
        }
        if (data1.useCount != data2.useCount) {
            result.addDifference("Element count", "use",
                String.valueOf(data1.useCount), String.valueOf(data2.useCount));
        }
        if (data1.lineCount != data2.lineCount) {
            result.addDifference("Element count", "line",
                String.valueOf(data1.lineCount), String.valueOf(data2.lineCount));
        }
        if (data1.polygonCount != data2.polygonCount) {
            result.addDifference("Element count", "polygon",
                String.valueOf(data1.polygonCount), String.valueOf(data2.polygonCount));
        }
    }

    private void compareViewBox(SvgData data1, SvgData data2, ComparisonResult result) {
        if (data1.viewBox == null && data2.viewBox == null) {
            return;
        }
        if (data1.viewBox == null || data2.viewBox == null) {
            result.addDifference("ViewBox", "presence",
                data1.viewBox != null ? "present" : "absent",
                data2.viewBox != null ? "present" : "absent");
            return;
        }

        double[] vb1 = data1.viewBox;
        double[] vb2 = data2.viewBox;

        if (!approximatelyEqual(vb1[0], vb2[0]) || !approximatelyEqual(vb1[1], vb2[1])) {
            result.addDifference("ViewBox", "position",
                String.format("(%.6f, %.6f)", vb1[0], vb1[1]),
                String.format("(%.6f, %.6f)", vb2[0], vb2[1]));
        }
        if (!approximatelyEqual(vb1[2], vb2[2]) || !approximatelyEqual(vb1[3], vb2[3])) {
            result.addDifference("ViewBox", "size",
                String.format("%.6f x %.6f", vb1[2], vb1[3]),
                String.format("%.6f x %.6f", vb2[2], vb2[3]));
        }
    }

    private void compareApertureIds(SvgData data1, SvgData data2, ComparisonResult result) {
        Set<String> only1 = new HashSet<>(data1.apertureIds);
        only1.removeAll(data2.apertureIds);

        Set<String> only2 = new HashSet<>(data2.apertureIds);
        only2.removeAll(data1.apertureIds);

        if (!only1.isEmpty()) {
            result.addDifference("Aperture IDs", "only in first", only1.toString(), "");
        }
        if (!only2.isEmpty()) {
            result.addDifference("Aperture IDs", "only in second", "", only2.toString());
        }
    }

    private void comparePathData(SvgData data1, SvgData data2, ComparisonResult result) {
        // Compare paths by ID
        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(data1.parsedPaths.keySet());
        allPaths.addAll(data2.parsedPaths.keySet());

        for (String pathId : allPaths) {
            List<PathCommand> path1 = data1.parsedPaths.get(pathId);
            List<PathCommand> path2 = data2.parsedPaths.get(pathId);

            if (path1 == null) {
                result.addDifference("Path", pathId, "missing", "present");
                continue;
            }
            if (path2 == null) {
                result.addDifference("Path", pathId, "present", "missing");
                continue;
            }

            // Compare path commands
            if (path1.size() != path2.size()) {
                result.addDifference("Path commands", pathId,
                    path1.size() + " commands", path2.size() + " commands");
            } else {
                for (int i = 0; i < path1.size(); i++) {
                    PathCommand cmd1 = path1.get(i);
                    PathCommand cmd2 = path2.get(i);

                    if (cmd1.command != cmd2.command) {
                        result.addDifference("Path command type", pathId + "[" + i + "]",
                            String.valueOf(cmd1.command), String.valueOf(cmd2.command));
                    } else if (!commandsEqual(cmd1, cmd2)) {
                        result.addDifference("Path command values", pathId + "[" + i + "]",
                            cmd1.toString(), cmd2.toString());
                    }
                }
            }
        }
    }

    private void compareCircles(SvgData data1, SvgData data2, ComparisonResult result) {
        // For unnamed circles, compare by position
        List<CircleData> circles1 = new ArrayList<>(data1.circles.values());
        List<CircleData> circles2 = new ArrayList<>(data2.circles.values());

        // Sort by position for comparison
        circles1.sort((a, b) -> {
            int cmp = Double.compare(a.cx, b.cx);
            return cmp != 0 ? cmp : Double.compare(a.cy, b.cy);
        });
        circles2.sort((a, b) -> {
            int cmp = Double.compare(a.cx, b.cx);
            return cmp != 0 ? cmp : Double.compare(a.cy, b.cy);
        });

        int minSize = Math.min(circles1.size(), circles2.size());
        for (int i = 0; i < minSize; i++) {
            CircleData c1 = circles1.get(i);
            CircleData c2 = circles2.get(i);

            if (!approximatelyEqual(c1.cx, c2.cx) || !approximatelyEqual(c1.cy, c2.cy)) {
                result.addDifference("Circle center", "circle[" + i + "]",
                    String.format("(%.6f, %.6f)", c1.cx, c1.cy),
                    String.format("(%.6f, %.6f)", c2.cx, c2.cy));
            }
            if (!approximatelyEqual(c1.r, c2.r)) {
                result.addDifference("Circle radius", "circle[" + i + "]",
                    String.format("%.6f", c1.r), String.format("%.6f", c2.r));
            }
        }
    }

    private void compareRects(SvgData data1, SvgData data2, ComparisonResult result) {
        List<RectData> rects1 = new ArrayList<>(data1.rects.values());
        List<RectData> rects2 = new ArrayList<>(data2.rects.values());

        rects1.sort((a, b) -> {
            int cmp = Double.compare(a.x, b.x);
            return cmp != 0 ? cmp : Double.compare(a.y, b.y);
        });
        rects2.sort((a, b) -> {
            int cmp = Double.compare(a.x, b.x);
            return cmp != 0 ? cmp : Double.compare(a.y, b.y);
        });

        int minSize = Math.min(rects1.size(), rects2.size());
        for (int i = 0; i < minSize; i++) {
            RectData r1 = rects1.get(i);
            RectData r2 = rects2.get(i);

            if (!approximatelyEqual(r1.x, r2.x) || !approximatelyEqual(r1.y, r2.y)) {
                result.addDifference("Rect position", "rect[" + i + "]",
                    String.format("(%.6f, %.6f)", r1.x, r1.y),
                    String.format("(%.6f, %.6f)", r2.x, r2.y));
            }
            if (!approximatelyEqual(r1.width, r2.width) || !approximatelyEqual(r1.height, r2.height)) {
                result.addDifference("Rect size", "rect[" + i + "]",
                    String.format("%.6f x %.6f", r1.width, r1.height),
                    String.format("%.6f x %.6f", r2.width, r2.height));
            }
        }
    }

    private void compareUses(SvgData data1, SvgData data2, ComparisonResult result) {
        // Sort uses by href and position
        List<UseData> uses1 = new ArrayList<>(data1.uses);
        List<UseData> uses2 = new ArrayList<>(data2.uses);

        uses1.sort((a, b) -> {
            int cmp = a.href.compareTo(b.href);
            if (cmp != 0) return cmp;
            cmp = Double.compare(a.x, b.x);
            return cmp != 0 ? cmp : Double.compare(a.y, b.y);
        });
        uses2.sort((a, b) -> {
            int cmp = a.href.compareTo(b.href);
            if (cmp != 0) return cmp;
            cmp = Double.compare(a.x, b.x);
            return cmp != 0 ? cmp : Double.compare(a.y, b.y);
        });

        int minSize = Math.min(uses1.size(), uses2.size());
        for (int i = 0; i < minSize; i++) {
            UseData u1 = uses1.get(i);
            UseData u2 = uses2.get(i);

            if (!u1.href.equals(u2.href)) {
                result.addDifference("Use href", "use[" + i + "]", u1.href, u2.href);
            }
            if (!approximatelyEqual(u1.x, u2.x) || !approximatelyEqual(u1.y, u2.y)) {
                result.addDifference("Use position", "use[" + i + "]",
                    String.format("(%.6f, %.6f)", u1.x, u1.y),
                    String.format("(%.6f, %.6f)", u2.x, u2.y));
            }
        }
    }

    private boolean commandsEqual(PathCommand cmd1, PathCommand cmd2) {
        if (cmd1.args.length != cmd2.args.length) {
            return false;
        }
        for (int i = 0; i < cmd1.args.length; i++) {
            if (!approximatelyEqual(cmd1.args[i], cmd2.args[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean approximatelyEqual(double a, double b) {
        return Math.abs(a - b) <= tolerance;
    }

    private double parseDouble(String s, double defaultValue) {
        if (s == null || s.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double[] parseViewBox(String viewBox) {
        String[] parts = viewBox.trim().split("\\s+");
        if (parts.length == 4) {
            return new double[] {
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]),
                Double.parseDouble(parts[3])
            };
        }
        return null;
    }

    private void extractApertureIds(Element defs, Set<String> apertureIds) {
        NodeList children = defs.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                String id = ((Element) child).getAttribute("id");
                if (id != null && !id.isEmpty()) {
                    apertureIds.add(id);
                }
            }
        }
    }

    /**
     * Parse SVG path data into a list of commands.
     */
    public List<PathCommand> parsePathData(String d) {
        List<PathCommand> commands = new ArrayList<>();
        if (d == null || d.isEmpty()) {
            return commands;
        }

        // Pattern to match path commands
        Pattern cmdPattern = Pattern.compile("([MmLlHhVvCcSsQqTtAaZz])\\s*([^MmLlHhVvCcSsQqTtAaZz]*)");
        Matcher matcher = cmdPattern.matcher(d);

        while (matcher.find()) {
            char cmd = matcher.group(1).charAt(0);
            String argsStr = matcher.group(2).trim();
            double[] args = parseNumbers(argsStr);
            commands.add(new PathCommand(cmd, args));
        }

        return commands;
    }

    private double[] parseNumbers(String s) {
        if (s == null || s.isEmpty()) {
            return new double[0];
        }
        // Split on whitespace, commas, or between number and sign
        String[] parts = s.split("[\\s,]+|(?<=[0-9])(?=-)");
        List<Double> numbers = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                try {
                    numbers.add(Double.parseDouble(part));
                } catch (NumberFormatException e) {
                    // Skip invalid numbers
                }
            }
        }
        return numbers.stream().mapToDouble(Double::doubleValue).toArray();
    }

    // ============================================================
    // Data Classes
    // ============================================================

    public static class SvgData {
        public double[] viewBox;
        public int pathCount;
        public int circleCount;
        public int rectCount;
        public int useCount;
        public int lineCount;
        public int polygonCount;
        public int ellipseCount;
        public int gCount;
        public Set<String> apertureIds = new HashSet<>();
        public Map<String, String> pathData = new HashMap<>();
        public Map<String, List<PathCommand>> parsedPaths = new HashMap<>();
        public Map<String, CircleData> circles = new HashMap<>();
        public Map<String, RectData> rects = new HashMap<>();
        public List<UseData> uses = new ArrayList<>();

        public int getTotalElements() {
            return pathCount + circleCount + rectCount + useCount + lineCount + polygonCount;
        }
    }

    public static class PathCommand {
        public char command;
        public double[] args;

        public PathCommand(char command, double[] args) {
            this.command = command;
            this.args = args;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(command);
            for (double arg : args) {
                sb.append(" ").append(String.format("%.6f", arg));
            }
            return sb.toString();
        }
    }

    public static class CircleData {
        public double cx, cy, r;
    }

    public static class RectData {
        public double x, y, width, height;
    }

    public static class UseData {
        public String href;
        public double x, y;
    }

    public static class ComparisonResult {
        public List<Difference> differences = new ArrayList<>();

        public void addDifference(String category, String item, String value1, String value2) {
            differences.add(new Difference(category, item, value1, value2));
        }

        public boolean isMatch() {
            return differences.isEmpty();
        }

        public String getSummary() {
            if (isMatch()) {
                return "SVGs match";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(differences.size()).append(" difference(s) found:\n");
            for (Difference diff : differences) {
                sb.append("  - ").append(diff).append("\n");
            }
            return sb.toString();
        }
    }

    public static class Difference {
        public String category;
        public String item;
        public String value1;
        public String value2;

        public Difference(String category, String item, String value1, String value2) {
            this.category = category;
            this.item = item;
            this.value1 = value1;
            this.value2 = value2;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s: '%s' vs '%s'", category, item, value1, value2);
        }
    }
}
