package com.deltaproto.deltagerber.parser;

import com.deltaproto.deltagerber.model.ipc2581.Ipc2581StackupDocument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the stack-up out of an IPC-2581 file ({@code .cvg}, {@code .xml}).
 *
 * <p>Only the stack-up: an IPC-2581 file describes the whole product, and this parser walks it as a
 * stream and stops at {@code </Stackup>}, which sits within the first few percent of the document.
 * That matters — these files run to hundreds of megabytes, and the stack-up is a few kilobytes of
 * it.
 *
 * <p>Three sections are read on the way there:
 *
 * <pre>{@code
 * <CadHeader units="INCH">                                        <-- the unit everything is in
 *   <Spec name="Dielectric 3">
 *     <General type="MATERIAL"><Property text="Core-027"/></General>   <-- the material
 *   </Spec>
 * <Layer name="Top Layer" layerFunction="SIGNAL" side="TOP"/>     <-- what each layer is for
 * <Stackup overallThickness="0.061292">                           <-- the finished thickness
 *   <StackupGroup>
 *     <StackupLayer layerOrGroupRef="Top Layer" thickness="0.001378" sequence="4"/>
 * }</pre>
 *
 * <p>Thicknesses are converted to millimetres here, from the file's declared units, so nothing
 * downstream has to know what the file was written in. The conversion runs in decimal, because an
 * imperial stack-up quotes exact thousandths of an inch and they have to stay exact.
 */
public class Ipc2581StackupParser {

    private static final Logger log = LoggerFactory.getLogger(Ipc2581StackupParser.class);

    private static final BigDecimal MM_PER_INCH = new BigDecimal("25.4");
    private static final BigDecimal MM_PER_MICRON = new BigDecimal("0.001");

    /** How much of the file to sniff for the root element before giving up on it. */
    private static final int SNIFF_LENGTH = 4096;

    /**
     * Parse the stack-up.
     *
     * @return the stack-up, or null when {@code content} is not an IPC-2581 file or states no
     *         stack-up. Content that is IPC-2581 but malformed also yields null, with a logged
     *         warning: a caller should fall back to what the rest of the set says, not fail.
     */
    public Ipc2581StackupDocument parse(String content) {
        if (!looksLikeIpc2581(content)) {
            return null;
        }
        try {
            Ipc2581StackupDocument document = read(content);
            return document.getLayers().isEmpty() && document.getBoardThicknessMm() == null
                    ? null
                    : document;
        } catch (XMLStreamException | RuntimeException e) {
            log.warn("Not a readable IPC-2581 stack-up: {}", e.toString());
            return null;
        }
    }

    /**
     * Whether this content is an IPC-2581 file at all — the root element names the standard, and it
     * is within the first few characters. Cheap enough to run on every file in a set.
     */
    public static boolean looksLikeIpc2581(String content) {
        if (content == null) {
            return false;
        }
        String head = content.substring(0, Math.min(content.length(), SNIFF_LENGTH));
        return head.contains("<IPC-2581") || head.contains("webstds.ipc.org/2581");
    }

    // ------------------------------------------------------------------------

    private Ipc2581StackupDocument read(String content) throws XMLStreamException {
        Ipc2581StackupDocument document = new Ipc2581StackupDocument();
        Map<String, String> materials = new HashMap<>();      // Spec name -> material
        Map<String, String[]> functions = new HashMap<>();    // Layer name -> {layerFunction, side}

        XMLStreamReader xml = factory().createXMLStreamReader(new StringReader(content));
        BigDecimal toMm = MM_PER_INCH;                        // until CadHeader says otherwise
        boolean unitsDeclared = false;
        String specName = null;
        boolean inMaterialSpec = false;
        boolean inStackup = false;
        boolean stackupDone = false;
        int groupDepth = 0;
        String layerRef = null;
        String specRef = null;
        String thickness = null;
        int sequence = 0;

        try {
            while (xml.hasNext() && !stackupDone) {
                if (xml.next() == XMLStreamConstants.END_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "Spec" -> {
                            specName = null;
                            inMaterialSpec = false;
                        }
                        case "General" -> inMaterialSpec = false;
                        case "StackupLayer" -> {
                            addLayer(document, materials, functions, layerRef, specRef, thickness,
                                    sequence, toMm);
                            layerRef = null;
                            specRef = null;
                            thickness = null;
                        }
                        // Only the first group is read: a rigid-flex file states one group per
                        // zone, and concatenating them would count the shared layers twice.
                        case "StackupGroup" -> groupDepth++;
                        case "Stackup" -> {
                            inStackup = false;
                            stackupDone = true;
                        }
                        default -> { }
                    }
                    continue;
                }
                if (xml.getEventType() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }

                switch (xml.getLocalName()) {
                    case "IPC-2581" -> document.setRevision(attribute(xml, "revision"));
                    case "CadHeader" -> {
                        String units = attribute(xml, "units");
                        if (units != null) {
                            toMm = scale(units);
                            unitsDeclared = true;
                        }
                    }
                    case "Spec" -> specName = attribute(xml, "name");
                    case "General" -> inMaterialSpec = "MATERIAL".equalsIgnoreCase(attribute(xml, "type"));
                    case "Property" -> {
                        String text = attribute(xml, "text");
                        if (inMaterialSpec && specName != null && text != null && !text.isBlank()) {
                            materials.putIfAbsent(specName, text);
                        }
                    }
                    case "Layer" -> {
                        String name = attribute(xml, "name");
                        if (name != null) {
                            functions.put(name, new String[] {
                                    attribute(xml, "layerFunction"), attribute(xml, "side") });
                        }
                    }
                    case "Stackup" -> {
                        inStackup = true;
                        document.setStackupName(attribute(xml, "name"));
                        document.setBoardThicknessMm(millimetres(attribute(xml, "overallThickness"), toMm));
                    }
                    case "StackupLayer" -> {
                        if (inStackup && groupDepth == 0) {
                            layerRef = attribute(xml, "layerOrGroupRef");
                            thickness = attribute(xml, "thickness");
                            sequence = integer(attribute(xml, "sequence"), document.getLayers().size() + 1);
                        }
                    }
                    case "SpecRef" -> specRef = attribute(xml, "id");
                    default -> { }
                }
            }
        } finally {
            xml.close();
        }

        if (!unitsDeclared && !document.getLayers().isEmpty()) {
            log.warn("IPC-2581 file declares no units on CadHeader; read as inches, "
                    + "which is what every file in our corpus uses");
        }
        return document;
    }

    private static void addLayer(Ipc2581StackupDocument document, Map<String, String> materials,
                                 Map<String, String[]> functions, String layerRef, String specRef,
                                 String thickness, int sequence, BigDecimal toMm) {
        if (layerRef == null) {
            return;
        }
        String[] layer = functions.getOrDefault(layerRef, new String[2]);
        String material = materials.get(specRef != null ? specRef : layerRef);
        document.addLayer(new Ipc2581StackupDocument.StackupLayer(
                layerRef,
                Ipc2581StackupDocument.Function.of(layer[0]),
                layer[0],
                layer[1],
                millimetres(thickness, toMm),
                material,
                sequence));
    }

    /**
     * A stated thickness in millimetres. A zero thickness means "no thickness of its own" — every
     * documentation layer in a stack-up group carries one — and reads back as null rather than as
     * a measurement of zero.
     */
    private static Double millimetres(String value, BigDecimal toMm) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal mm = new BigDecimal(value.trim()).multiply(toMm);
            return mm.signum() == 0 ? null : mm.doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal scale(String units) {
        return switch (units.trim().toUpperCase()) {
            case "MILLIMETER", "MILLIMETRE", "MM" -> BigDecimal.ONE;
            case "MICRON", "MICROMETER", "UM" -> MM_PER_MICRON;
            default -> MM_PER_INCH;
        };
    }

    private static int integer(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String attribute(XMLStreamReader xml, String name) {
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            if (xml.getAttributeLocalName(i).equals(name)) {
                return xml.getAttributeValue(i);
            }
        }
        return null;
    }

    /** A reader that resolves no entities and loads no DTD — this is a customer's file. */
    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory;
    }
}
