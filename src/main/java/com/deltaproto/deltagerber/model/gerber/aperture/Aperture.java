package com.deltaproto.deltagerber.model.gerber.aperture;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for all aperture types.
 */
public abstract class Aperture {

    private final int dCode;

    // X2/X3 aperture attributes (.AperFunction, .DrillTolerance, …) in scope when this aperture
    // was defined (TA dictionary snapshot). Lazily allocated; null until the first attribute.
    private Map<String, List<String>> attributes;

    protected Aperture(int dCode) {
        this.dCode = dCode;
    }

    public int getDCode() {
        return dCode;
    }

    /**
     * Attach the aperture attributes in scope at definition time. The map is copied; an empty or
     * null argument leaves this aperture without attributes.
     */
    public void setAttributes(Map<String, List<String>> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return;
        }
        attributes = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : attrs.entrySet()) {
            attributes.put(e.getKey(), List.copyOf(e.getValue()));
        }
    }

    /** Aperture attributes by name (e.g. {@code .AperFunction}); empty if none. */
    public Map<String, List<String>> getAttributes() {
        return attributes == null ? Collections.emptyMap() : Collections.unmodifiableMap(attributes);
    }

    /** Values of a single aperture attribute, or {@code null} if absent. */
    public List<String> getAttribute(String name) {
        return attributes == null ? null : attributes.get(name);
    }

    /**
     * Typed view of the {@code .AperFunction} attribute (the aperture's role on the board), or
     * {@code null} if the aperture carries no {@code .AperFunction}.
     */
    public ApertureFunction getFunction() {
        List<String> v = getAttribute(".AperFunction");
        return (v == null || v.isEmpty()) ? null : ApertureFunction.fromValue(v.get(0));
    }

    /**
     * The {@code .DrillTolerance} plus/minus tolerance of a drilled hole as {@code [plus, minus]}
     * in millimetres (normalized from the file unit at parse time), or {@code null} if the
     * attribute is absent or malformed.
     */
    public double[] getDrillTolerance() {
        List<String> v = getAttribute(".DrillTolerance");
        if (v == null || v.size() < 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(v.get(0).trim()), Double.parseDouble(v.get(1).trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The {@code .FlashText} attribute values (text-image metadata), or an empty list. */
    public List<String> getFlashText() {
        List<String> v = getAttribute(".FlashText");
        return v == null ? Collections.emptyList() : v;
    }

    /**
     * Get the template code for this aperture type.
     * C=Circle, R=Rectangle, O=Obround, P=Polygon, or macro name.
     */
    public abstract String getTemplateCode();

    /**
     * Get the bounding box of this aperture centered at origin.
     */
    public abstract BoundingBox getBoundingBox();

    /**
     * Generate SVG definition for this aperture with default options (exact mode).
     * Returns an SVG element string that can be placed in a &lt;defs&gt; section.
     */
    public String toSvgDef(String id) {
        return toSvgDef(id, SvgOptions.exact());
    }

    /**
     * Generate SVG definition for this aperture with specified options.
     * @param id the SVG element id
     * @param options output options (exact or polygonized)
     * @return SVG element string for the defs section
     */
    public abstract String toSvgDef(String id, SvgOptions options);
}
