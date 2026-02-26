package com.deltaproto.deltagerber.model.gerber.aperture;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroPrimitive;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroTemplate;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

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

    @Override
    public String toSvgDef(String id, SvgOptions options) {
        StringBuilder svg = new StringBuilder();
        svg.append(String.format("<g id=\"%s\">", id));

        for (MacroPrimitive primitive : template.getPrimitives()) {
            String primSvg = primitive.toSvg(evaluatedVariables, options, unitFactor);
            if (primSvg != null && !primSvg.isEmpty()) {
                svg.append(primSvg);
            }
        }

        svg.append("</g>");
        return svg.toString();
    }
}
