package com.deltaproto.deltagerber.model.gerber.operation;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for graphics objects produced by Gerber operations.
 */
public abstract class GraphicsObject {

    protected Polarity polarity = Polarity.DARK;

    // X2/X3 object attributes (.N net, .C component, .P pin, …) in scope when this object was
    // created (TO dictionary snapshot). Lazily allocated; null until the first attribute.
    private Map<String, List<String>> attributes;

    public Polarity getPolarity() {
        return polarity;
    }

    public void setPolarity(Polarity polarity) {
        this.polarity = polarity;
    }

    /**
     * Attach the object attributes in scope at creation time. The map is copied; an empty or null
     * argument leaves this object without attributes.
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

    /** Object attributes by name (e.g. {@code .N}, {@code .C}, {@code .P}); empty if none. */
    public Map<String, List<String>> getAttributes() {
        return attributes == null ? Collections.emptyMap() : Collections.unmodifiableMap(attributes);
    }

    /** Values of a single object attribute, or {@code null} if absent. */
    public List<String> getAttribute(String name) {
        return attributes == null ? null : attributes.get(name);
    }

    /** Net name from the {@code .N} object attribute, or {@code null}. */
    public String getNet() {
        return firstValue(".N");
    }

    /** Component reference designator from the {@code .C} object attribute, or {@code null}. */
    public String getComponentRef() {
        return firstValue(".C");
    }

    /**
     * Pin number from the {@code .P} object attribute (whose values are {@code <refdes>,<pin>}),
     * or {@code null} if absent.
     */
    public String getPinNumber() {
        List<String> v = getAttribute(".P");
        return (v != null && v.size() >= 2) ? v.get(1) : null;
    }

    /** Component value from {@code .CVal} (e.g. "100nF"), or null. */
    public String getComponentValue() {
        return firstValue(".CVal");
    }

    /** Component footprint from {@code .CFtp}, or null. */
    public String getComponentFootprint() {
        return firstValue(".CFtp");
    }

    /** Component mount type from {@code .CMnt} ("TH" | "SMD" | "Pressfit" | "Fiducial" | "Other"), or null. */
    public String getComponentMountType() {
        return firstValue(".CMnt");
    }

    /** Component manufacturer from {@code .CMfr}, or null. */
    public String getComponentManufacturer() {
        return firstValue(".CMfr");
    }

    /** Component manufacturer part number from {@code .CMPN}, or null. */
    public String getComponentPartNumber() {
        return firstValue(".CMPN");
    }

    /** Component package name from {@code .CPgN}, or null. */
    public String getComponentPackageName() {
        return firstValue(".CPgN");
    }

    /** Component library name from {@code .CLbN}, or null. */
    public String getComponentLibraryName() {
        return firstValue(".CLbN");
    }

    /** Component rotation in degrees counterclockwise from {@code .CRot}, or null if absent/malformed. */
    public Double getComponentRotation() {
        return doubleValue(".CRot");
    }

    /**
     * Component height in millimetres from {@code .CHgt} (normalized from the file unit at parse
     * time), or null if absent/malformed.
     */
    public Double getComponentHeight() {
        return doubleValue(".CHgt");
    }

    private String firstValue(String name) {
        List<String> v = getAttribute(name);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private Double doubleValue(String name) {
        String s = firstValue(name);
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Copy polarity and attributes from this object onto {@code other}. Used by
     * {@link #translate} / {@link #transform} so derived copies keep their metadata.
     */
    protected void copyMetaTo(GraphicsObject other) {
        other.polarity = this.polarity;
        if (this.attributes != null) {
            other.attributes = new LinkedHashMap<>(this.attributes);
        }
    }

    /**
     * Get the bounding box of this graphics object.
     */
    public abstract BoundingBox getBoundingBox();

    /**
     * Generate SVG representation with default (exact) options.
     */
    public String toSvg() {
        return toSvg(SvgOptions.exact());
    }

    /**
     * Generate SVG representation with specified options.
     */
    public abstract String toSvg(SvgOptions options);

    /**
     * Create a translated copy of this object.
     */
    public abstract GraphicsObject translate(double offsetX, double offsetY);

    /**
     * Create a copy of this object with an affine placement transform applied (translate, mirror,
     * rotate, uniform scale). Used to expand block-aperture flashes; polarity and attributes are
     * preserved on the copy.
     */
    public abstract GraphicsObject transform(GraphicsTransform t);
}
