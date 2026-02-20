package com.deltaproto.deltagerber;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Utility to split a multi-layer reference SVG into individual layer files.
 * Each layer file contains the defs section (apertures) and the layer content.
 */
public class SvgLayerSplitter {

    /**
     * Split a reference SVG into individual layer files.
     *
     * @param inputSvg Path to the input SVG file
     * @param outputDir Directory to write individual layer SVGs
     * @return Map of layer ID to output file path
     */
    public static Map<String, Path> split(Path inputSvg, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(inputSvg.toFile());

        // Find the defs element
        Element defsElement = null;
        NodeList defsList = doc.getElementsByTagName("defs");
        if (defsList.getLength() > 0) {
            defsElement = (Element) defsList.item(0);
        }

        // Find viewBox from root or nested g element
        String viewBox = null;
        Element root = doc.getDocumentElement();
        if (root.hasAttribute("viewBox")) {
            viewBox = root.getAttribute("viewBox");
        } else {
            // Try to find viewBox in nested g element
            NodeList groups = doc.getElementsByTagName("g");
            for (int i = 0; i < groups.getLength(); i++) {
                Element g = (Element) groups.item(i);
                if (g.hasAttribute("viewBox")) {
                    viewBox = g.getAttribute("viewBox");
                    break;
                }
            }
        }

        // Default viewBox if not found
        if (viewBox == null) {
            viewBox = "0 0 10 10";
        }

        // Find all layer groups
        Map<String, Path> result = new HashMap<>();
        NodeList groups = doc.getElementsByTagName("g");

        for (int i = 0; i < groups.getLength(); i++) {
            Element g = (Element) groups.item(i);
            String className = g.getAttribute("class");
            String id = g.getAttribute("id");

            if ("layer".equals(className) && id != null && !id.isEmpty()) {
                Path outputFile = outputDir.resolve(sanitizeFileName(id) + ".svg");
                writeLayerSvg(g, defsElement, viewBox, outputFile);
                result.put(id, outputFile);
                System.out.println("Wrote layer: " + id + " -> " + outputFile);
            }
        }

        return result;
    }

    /**
     * Write a single layer as a standalone SVG file.
     */
    private static void writeLayerSvg(Element layerElement, Element defsElement, String viewBox, Path outputFile) throws Exception {
        // Create new document
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document newDoc = builder.newDocument();

        // Create SVG root element
        Element svgRoot = newDoc.createElement("svg");
        svgRoot.setAttribute("xmlns", "http://www.w3.org/2000/svg");
        svgRoot.setAttribute("viewBox", viewBox);
        newDoc.appendChild(svgRoot);

        // Copy defs if present
        if (defsElement != null) {
            Node importedDefs = newDoc.importNode(defsElement, true);
            svgRoot.appendChild(importedDefs);
        }

        // Copy layer content
        Node importedLayer = newDoc.importNode(layerElement, true);
        svgRoot.appendChild(importedLayer);

        // Write to file
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        DOMSource source = new DOMSource(newDoc);
        StreamResult result = new StreamResult(outputFile.toFile());
        transformer.transform(source, result);
    }

    /**
     * Sanitize file name (replace problematic characters).
     */
    private static String sanitizeFileName(String name) {
        return name.replace(" ", "_").replace("/", "_").replace("\\", "_");
    }

    /**
     * Main entry point for command-line usage.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: SvgLayerSplitter <input.svg> <output-dir>");
            return;
        }

        Path inputSvg = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);

        Map<String, Path> layers = split(inputSvg, outputDir);
        System.out.println("Split " + layers.size() + " layers");
    }
}
