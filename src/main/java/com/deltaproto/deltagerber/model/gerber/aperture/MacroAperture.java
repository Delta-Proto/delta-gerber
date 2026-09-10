package com.deltaproto.deltagerber.model.gerber.aperture;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroPrimitive;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroTemplate;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

import java.awt.Shape;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An aperture instantiated from a macro template.
 */
public class MacroAperture extends Aperture {

    private final MacroTemplate template;
    private final List<Double> parameters;
    private final Map<Integer, Double> evaluatedVariables;
    private final double unitFactor;

    public MacroAperture(int dCode, MacroTemplate template, List<Double> parameters) {
        this(dCode, template, parameters, 1.0);
    }

    public MacroAperture(int dCode, MacroTemplate template, List<Double> parameters, double unitFactor) {
        super(dCode);
        this.template = template;
        this.parameters = new ArrayList<>(parameters);
        this.evaluatedVariables = template.evaluateVariables(parameters);
        this.unitFactor = unitFactor;
    }

    public MacroTemplate getTemplate() {
        return template;
    }

    public List<Double> getParameters() {
        return parameters;
    }

    @Override
    public String getTemplateCode() {
        return template.getName();
    }

    @Override
    public BoundingBox getBoundingBox() {
        BoundingBox bbox = new BoundingBox();
        for (MacroPrimitive primitive : template.getPrimitives()) {
            BoundingBox primBounds = primitive.getBoundingBox(evaluatedVariables, unitFactor);
            bbox.extend(primBounds);
        }
        return bbox;
    }

    /**
     * The area this aperture covers, as a Java2D shape in millimetres, centred on the flash point
     * with Y up — the macro's primitives combined in the order the template declares them, an
     * exposed one adding and an unexposed one clearing.
     *
     * <p>A macro is the one aperture whose outline cannot be written down as a few numbers, so
     * everything that measures rather than draws — is this point inside the pad, how far is it from
     * the pad's edge — has to build it. Empty when the macro exposes nothing.
     */
    public Shape getShape() {
        Area area = new Area();
        for (MacroPrimitive primitive : template.getPrimitives()) {
            Shape shape = primitive.toShape(evaluatedVariables, unitFactor);
            if (shape == null) {
                continue;
            }
            if (primitive.isExposed(evaluatedVariables)) {
                area.add(new Area(shape));
            } else {
                area.subtract(new Area(shape));
            }
        }
        return area;
    }

    @Override
    public String toSvgDef(String id, SvgOptions options) {
        StringBuilder svg = new StringBuilder();
        svg.append(String.format("<g id=\"%s\">", id));

        for (MacroPrimitive primitive : template.getPrimitives()) {
            String primSvg = primitive.toSvg(evaluatedVariables, options, unitFactor);
            if (primSvg != null && !primSvg.isEmpty()) {
                // Primitives render fill="currentColor" (the sentinel used for defs).
                // In SVG, currentColor reads the CSS `color` property — not `fill` — so
                // fill="white" on a <use> element does NOT cascade into the shapes.
                // Removing the fill attribute lets shapes inherit fill from the <use>,
                // which is the correct behaviour for both normal rendering and mask contexts
                // (sm-mask uses fill="black", cf-mask uses fill="white").
                svg.append(primSvg.replace(" fill=\"currentColor\"", ""));
            }
        }

        svg.append("</g>");
        return svg.toString();
    }
}
