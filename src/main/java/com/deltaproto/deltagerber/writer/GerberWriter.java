package com.deltaproto.deltagerber.writer;

import com.deltaproto.deltagerber.model.gerber.GerberDocument;
import com.deltaproto.deltagerber.model.gerber.ImagePolarity;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.Unit;
import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.BlockAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.CircleAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.MacroAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.ObroundAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.PolygonAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.RectangleAperture;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroPrimitive;
import com.deltaproto.deltagerber.model.gerber.attribute.FileAttribute;
import com.deltaproto.deltagerber.model.gerber.operation.Arc;
import com.deltaproto.deltagerber.model.gerber.operation.Contour;
import com.deltaproto.deltagerber.model.gerber.operation.Draw;
import com.deltaproto.deltagerber.model.gerber.operation.Flash;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.model.gerber.operation.Region;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Writes a {@link GerberDocument} as RS-274X, with the X2 attributes the document carries.
 *
 * <p>The output is always millimetres in format 4.6, absolute, leading zeros omitted
 * ({@code %FSLAX46Y46*%}, {@code %MOMM*%}) — the unit every parsed document is already in, at a
 * nanometre grid that holds any coordinate the parser produced. A document parsed and written back
 * inks the same image; it is not the same text: comments, step-and-repeat (which the parser has
 * already expanded) and the file's own number format do not survive.
 *
 * <p><b>Apertures.</b> Each aperture is written once, under its own D-code where that is free and
 * at least 10, otherwise under the next free one; apertures only an object refers to (a document
 * built by hand need not call {@code addAperture}) are written too. A macro aperture is written as
 * a template of its own with every expression evaluated — in mm, whatever unit the macro was
 * stated in — so no parameters are needed. A draw carrying a stroke scale gets an aperture of the
 * scaled size. A block aperture is written as {@code %AB%} with its contents.
 *
 * <p><b>State.</b> Polarity ({@code %LP%}), the flash transformation ({@code %LM%}, {@code %LR%},
 * {@code %LS%}) and the object attributes ({@code %TO%}/{@code %TD%}) are emitted only when they
 * change. The transformation is reset before any draw, arc or region, because a reader is entitled
 * to apply it to those as well, which the model never does.
 */
public final class GerberWriter {

    private static final double NM_PER_MM = 1_000_000.0;
    private static final String APER_FUNCTION = ".AperFunction";

    private final StringBuilder out = new StringBuilder();
    private final Map<Aperture, Integer> codes = new IdentityHashMap<>();
    private final Set<Integer> usedCodes = new HashSet<>();
    private final Map<Aperture, Map<Long, Aperture>> scaled = new IdentityHashMap<>();
    private final Set<String> macroNames = new HashSet<>();
    private final Set<BlockAperture> writtenBlocks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    // Graphics state as last written; null means "unknown, write it next time".
    private Polarity polarity;
    private Integer currentCode;
    private String interpolation;
    private Long x;
    private Long y;
    // A fresh file starts untransformed; inside and after a block nothing is assumed.
    private String mirror = "N";
    private Double rotation = 0.0;
    private Double scale = 1.0;
    private Map<String, List<String>> apertureAttrs = new LinkedHashMap<>();
    private Map<String, List<String>> objectAttrs = new LinkedHashMap<>();

    private GerberWriter() {
    }

    /**
     * The document as RS-274X text.
     *
     * @throws IllegalArgumentException      when the document is not in millimetres
     * @throws UnsupportedOperationException for a stroke with a scaled macro or block aperture,
     *                                       which has no aperture of its own to scale
     */
    public static String write(GerberDocument doc) {
        Objects.requireNonNull(doc, "doc");
        if (doc.getUnit() != Unit.MM) {
            throw new IllegalArgumentException("GerberWriter writes documents in mm; this one is " + doc.getUnit());
        }
        GerberWriter w = new GerberWriter();
        w.writeDocument(doc);
        return w.out.toString();
    }

    private void writeDocument(GerberDocument doc) {
        line("G04 Written by delta-gerber*");
        for (FileAttribute attr : new TreeMap<>(doc.getFileAttributes()).values()) {
            line("%TF" + attribute(attr.getName(), attr.getValues()) + "*%");
        }
        line("%FSLAX46Y46*%");
        line("%MOMM*%");
        if (doc.getImagePolarity() == ImagePolarity.NEGATIVE) {
            line("%IPNEG*%");
        }

        // Reserve the codes the document itself uses before assigning anything new.
        for (Aperture a : new TreeMap<>(doc.getApertures()).values()) {
            reserve(a);
        }
        collect(doc.getObjects());
        for (Aperture a : new TreeMap<>(doc.getApertures()).values()) {
            define(a);
        }
        defineUsedBy(doc.getObjects());
        setApertureAttributes(Map.of());

        line("G75*");
        for (GraphicsObject object : doc.getObjects()) {
            writeObject(object);
        }
        setObjectAttributes(Map.of());
        line("M02*");
    }

    // ------------------------------------------------------------------------------------------
    // Aperture dictionary
    // ------------------------------------------------------------------------------------------

    private void reserve(Aperture a) {
        if (!codes.containsKey(a) && a.getDCode() >= 10 && usedCodes.add(a.getDCode())) {
            codes.put(a, a.getDCode());
        }
    }

    /** Reserve the codes of every aperture the objects use, recursing into blocks. */
    private void collect(List<GraphicsObject> objects) {
        for (GraphicsObject o : objects) {
            Aperture a = apertureOf(o);
            if (a != null) {
                reserve(a);
                if (a instanceof BlockAperture block) {
                    collect(block.getObjects());
                }
            }
        }
    }

    private int codeOf(Aperture a) {
        Integer code = codes.get(a);
        if (code == null) {
            int next = 10;
            while (usedCodes.contains(next)) {
                next++;
            }
            usedCodes.add(next);
            codes.put(a, next);
            code = next;
        }
        return code;
    }

    /** Define every aperture the objects use that is not yet defined; blocks after their contents. */
    private void defineUsedBy(List<GraphicsObject> objects) {
        for (GraphicsObject o : objects) {
            Aperture a = apertureOf(o);
            if (a == null) {
                continue;
            }
            if (a instanceof BlockAperture block) {
                defineUsedBy(block.getObjects());
            }
            define(a);
            double s = strokeScale(o);
            if (s != 1.0) {
                define(scaledAperture(a, s));
            }
        }
    }

    private final Set<Aperture> defined = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private void define(Aperture a) {
        if (defined.contains(a)) {
            return;
        }
        if (a instanceof BlockAperture block) {
            defineUsedBy(block.getObjects());
        }
        defined.add(a);
        int code = codeOf(a);
        setApertureAttributes(a.getAttributes());
        if (a instanceof CircleAperture c) {
            line("%ADD" + code + "C," + num(c.getDiameter()) + hole(c.getHoleDiameter()) + "*%");
        } else if (a instanceof RectangleAperture r) {
            line("%ADD" + code + "R," + num(r.getWidth()) + "X" + num(r.getHeight())
                    + hole(r.getHoleDiameter()) + "*%");
        } else if (a instanceof ObroundAperture o) {
            line("%ADD" + code + "O," + num(o.getWidth()) + "X" + num(o.getHeight())
                    + hole(o.getHoleDiameter()) + "*%");
        } else if (a instanceof PolygonAperture p) {
                String params = num(p.getOuterDiameter()) + "X" + p.getNumVertices();
                if (p.getRotation() != 0 || p.getHoleDiameter() > 0) {
                    params += "X" + num(p.getRotation());
                }
                line("%ADD" + code + "P," + params + hole(p.getHoleDiameter()) + "*%");
        } else if (a instanceof MacroAperture m) {
                String name = macroName(m.getTemplateCode(), code);
                StringBuilder body = new StringBuilder("%AM").append(name).append("*\n");
                Map<Integer, Double> variables = m.getTemplate().evaluateVariables(m.getParameters());
                for (MacroPrimitive primitive : m.getTemplate().getPrimitives()) {
                    body.append(primitive.toGerber(variables, m.getUnitFactor())).append("*\n");
                }
                body.append("%");
                line(body.toString());
                line("%ADD" + code + name + "*%");
        } else if (a instanceof BlockAperture b) {
            writeBlock(b, code);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot write aperture " + a.getClass().getSimpleName() + " (D" + a.getDCode() + ")");
        }
    }

    private void writeBlock(BlockAperture block, int code) {
        if (!writtenBlocks.add(block)) {
            return;
        }
        line("%ABD" + code + "*%");
        forgetState();
        for (GraphicsObject o : block.getObjects()) {
            writeObject(o);
        }
        setObjectAttributes(Map.of());
        line("%AB*%");
        forgetState();
    }

    private String macroName(String templateName, int code) {
        String base = templateName == null ? "MACRO" : templateName.replaceAll("[^A-Za-z0-9_.$]", "_");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0)) && base.charAt(0) != '_'
                && base.charAt(0) != '.' && base.charAt(0) != '$') {
            base = "M" + base;
        }
        // A template this writer named already carries its code; do not grow it on every round trip.
        String name = base.matches(".*_D\\d+") ? base : base + "_D" + code;
        while (!macroNames.add(name)) {
            name = name + "_";
        }
        return name;
    }

    private static String hole(double holeDiameter) {
        return holeDiameter > 0 ? "X" + num(holeDiameter) : "";
    }

    /** The aperture a stroke with a stroke scale is really drawn with. */
    private Aperture scaledAperture(Aperture a, double s) {
        long key = Double.doubleToLongBits(s);
        return scaled.computeIfAbsent(a, k -> new LinkedHashMap<>()).computeIfAbsent(key, k -> {
            Aperture copy;
            if (a instanceof CircleAperture c) {
                copy = new CircleAperture(0, c.getDiameter() * s, c.getHoleDiameter() * s);
            } else if (a instanceof RectangleAperture r) {
                copy = new RectangleAperture(0, r.getWidth() * s, r.getHeight() * s, r.getHoleDiameter() * s);
            } else if (a instanceof ObroundAperture o) {
                copy = new ObroundAperture(0, o.getWidth() * s, o.getHeight() * s, o.getHoleDiameter() * s);
            } else if (a instanceof PolygonAperture p) {
                copy = new PolygonAperture(0, p.getOuterDiameter() * s, p.getNumVertices(),
                        p.getRotation(), p.getHoleDiameter() * s);
            } else {
                throw new UnsupportedOperationException(
                        "Cannot write a stroke with a scaled " + a.getClass().getSimpleName());
            }
            copy.setAttributes(a.getAttributes());
            return copy;
        });
    }

    // ------------------------------------------------------------------------------------------
    // Objects
    // ------------------------------------------------------------------------------------------

    private void writeObject(GraphicsObject o) {
        setPolarity(o.getPolarity());
        if (o instanceof Flash f) {
                setObjectAttributes(o.getAttributes());
                setTransform(f.isMirrorX(), f.isMirrorY(), f.getRotation(), f.getScale());
                select(f.getAperture());
                line(xy(f.getX(), f.getY()) + "D03*");
                moveTo(f.getX(), f.getY());
        } else if (o instanceof Draw d) {
                setObjectAttributes(o.getAttributes());
                identityTransform();
                select(strokeAperture(d.getAperture(), d.getStrokeScale()));
                startAt(d.getStartX(), d.getStartY());
                interpolate("G01");
                line(xy(d.getEndX(), d.getEndY()) + "D01*");
                moveTo(d.getEndX(), d.getEndY());
        } else if (o instanceof Arc a) {
                setObjectAttributes(o.getAttributes());
                identityTransform();
                select(strokeAperture(a.getAperture(), a.getStrokeScale()));
                startAt(a.getStartX(), a.getStartY());
                interpolate(a.isClockwise() ? "G02" : "G03");
                line(xy(a.getEndX(), a.getEndY())
                        + ij(a.getCenterX() - a.getStartX(), a.getCenterY() - a.getStartY()) + "D01*");
                moveTo(a.getEndX(), a.getEndY());
        } else if (o instanceof Region r) {
            writeRegion(r);
        } else {
            throw new UnsupportedOperationException(
                    "Cannot write graphics object " + o.getClass().getSimpleName());
        }
    }

    private void writeRegion(Region r) {
        if (r.getContours().isEmpty()) {
            return;
        }
        // A region takes the aperture attributes in force (.AperFunction) the way an aperture does.
        setApertureAttributes(r.getApertureAttributes());
        setObjectAttributes(r.getAttributes());
        identityTransform();
        line("G36*");
        for (Contour c : r.getContours()) {
            line(xy(c.getStartX(), c.getStartY()) + "D02*");
            moveTo(c.getStartX(), c.getStartY());
            for (Contour.ContourSegment s : c.getSegments()) {
                if (s.isArc()) {
                    interpolate(s.isClockwise() ? "G02" : "G03");
                    line(xy(s.getX(), s.getY()) + ij(s.getCenterX() - nm(x), s.getCenterY() - nm(y)) + "D01*");
                } else {
                    interpolate("G01");
                    line(xy(s.getX(), s.getY()) + "D01*");
                }
                moveTo(s.getX(), s.getY());
            }
            // A contour must end where it starts; the model closes it implicitly.
            if (x != round(c.getStartX()) || y != round(c.getStartY())) {
                interpolate("G01");
                line(xy(c.getStartX(), c.getStartY()) + "D01*");
                moveTo(c.getStartX(), c.getStartY());
            }
        }
        line("G37*");
        setApertureAttributes(Map.of());
    }

    private Aperture strokeAperture(Aperture a, double strokeScale) {
        return strokeScale == 1.0 ? a : scaledAperture(a, strokeScale);
    }

    private static Aperture apertureOf(GraphicsObject o) {
        if (o instanceof Flash f) {
            return f.getAperture();
        } else if (o instanceof Draw d) {
            return d.getAperture();
        } else if (o instanceof Arc a) {
            return a.getAperture();
        }
        return null;
    }

    private static double strokeScale(GraphicsObject o) {
        if (o instanceof Draw d) {
            return d.getStrokeScale();
        } else if (o instanceof Arc a) {
            return a.getStrokeScale();
        }
        return 1.0;
    }

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    private void forgetState() {
        polarity = null;
        currentCode = null;
        interpolation = null;
        x = null;
        y = null;
        mirror = null;
        rotation = null;
        scale = null;
    }

    private void setPolarity(Polarity p) {
        if (p != polarity) {
            line(p == Polarity.CLEAR ? "%LPC*%" : "%LPD*%");
            polarity = p;
        }
    }

    private void select(Aperture a) {
        if (!defined.contains(a)) {
            throw new IllegalStateException("aperture used before it was defined: D" + a.getDCode());
        }
        int code = codes.get(a);
        if (!Objects.equals(currentCode, code)) {
            line("D" + code + "*");
            currentCode = code;
        }
    }

    private void interpolate(String mode) {
        if (!mode.equals(interpolation)) {
            line(mode + "*");
            interpolation = mode;
        }
    }

    private void startAt(double sx, double sy) {
        if (x == null || y == null || x != round(sx) || y != round(sy)) {
            line(xy(sx, sy) + "D02*");
            moveTo(sx, sy);
        }
    }

    private void moveTo(double nx, double ny) {
        x = round(nx);
        y = round(ny);
    }

    private void identityTransform() {
        setTransform(false, false, 0, 1.0);
    }

    private void setTransform(boolean mx, boolean my, double rot, double s) {
        String m = mx && my ? "XY" : mx ? "X" : my ? "Y" : "N";
        if (!m.equals(mirror)) {
            line("%LM" + m + "*%");
            mirror = m;
        }
        if (rotation == null || rotation != rot) {
            line("%LR" + num(rot) + "*%");
            rotation = rot;
        }
        if (scale == null || scale != s) {
            line("%LS" + num(s) + "*%");
            scale = s;
        }
    }

    private void setApertureAttributes(Map<String, List<String>> wanted) {
        apertureAttrs = setAttributes("%TA", apertureAttrs, wanted);
    }

    private void setObjectAttributes(Map<String, List<String>> wanted) {
        objectAttrs = setAttributes("%TO", objectAttrs, wanted);
    }

    private Map<String, List<String>> setAttributes(String command, Map<String, List<String>> current,
                                                    Map<String, List<String>> wanted) {
        Map<String, List<String>> target = wanted == null ? Map.of() : wanted;
        if (current.equals(target)) {
            return current;
        }
        for (String name : current.keySet()) {
            if (!target.containsKey(name)) {
                line("%TD" + name + "*%");
            }
        }
        for (Map.Entry<String, List<String>> e : target.entrySet()) {
            if (!e.getValue().equals(current.get(e.getKey()))) {
                line(command + attribute(e.getKey(), e.getValue()) + "*%");
            }
        }
        return new LinkedHashMap<>(target);
    }

    // ------------------------------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------------------------------

    private static String attribute(String name, List<String> values) {
        StringBuilder sb = new StringBuilder(name);
        for (String v : values) {
            sb.append(',').append(escape(v));
        }
        return sb.toString();
    }

    /** {@code *} and {@code %} end a command; Gerber escapes them as {@code \\uXXXX}. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == '*' || c == '%' || c == '\n' || c == '\r') {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String xy(double px, double py) {
        return "X" + round(px) + "Y" + round(py);
    }

    private static String ij(double i, double j) {
        return "I" + round(i) + "J" + round(j);
    }

    private static long round(double mm) {
        if (!Double.isFinite(mm)) {
            throw new IllegalArgumentException("non-finite coordinate " + mm);
        }
        return Math.round(mm * NM_PER_MM);
    }

    private static double nm(Long value) {
        return value / NM_PER_MM;
    }

    static String num(double value) {
        return MacroPrimitive.num(value);
    }

    private void line(String text) {
        out.append(text).append('\n');
    }
}
