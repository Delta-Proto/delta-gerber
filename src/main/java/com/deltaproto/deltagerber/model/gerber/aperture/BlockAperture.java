package com.deltaproto.deltagerber.model.gerber.aperture;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;
import com.deltaproto.deltagerber.renderer.svg.SvgOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A block aperture (Gerber spec §4.11): an aperture whose "shape" is a stored sequence of graphics
 * objects, each with its own polarity. Defined by {@code %ABDnn*%} … {@code %AB*%} and added to the
 * image by flashing ({@code D03}), which places the whole block at the flash point.
 *
 * <p>delta-gerber expands block flashes at parse time — the contained objects are copied (translated
 * to the flash point, with the active {@code LM}/{@code LR}/{@code LS} transform and {@code LP}
 * polarity applied) straight into the document's object list, so they render through the normal
 * pipeline. The objects stored here are in block-local coordinates (block origin at {@code 0,0}).
 */
public class BlockAperture extends Aperture {

    private final List<GraphicsObject> objects = new ArrayList<>();

    public BlockAperture(int dCode) {
        super(dCode);
    }

    public void add(GraphicsObject object) {
        objects.add(object);
    }

    /** The graphics objects that make up this block, in block-local coordinates. */
    public List<GraphicsObject> getObjects() {
        return Collections.unmodifiableList(objects);
    }

    @Override
    public String getTemplateCode() {
        return "AB";
    }

    @Override
    public BoundingBox getBoundingBox() {
        BoundingBox bbox = new BoundingBox();
        for (GraphicsObject obj : objects) {
            bbox.include(obj.getBoundingBox());
        }
        return bbox;
    }

    @Override
    public String toSvgDef(String id, SvgOptions options) {
        // Block flashes are expanded at parse time, so this definition is normally unreferenced.
        // It is still emitted as a faithful group (its objects rendered in block-local coordinates)
        // so a block remains usable through the generic aperture/<use> path if ever needed.
        StringBuilder svg = new StringBuilder();
        svg.append(String.format("<g id=\"%s\">", id));
        for (GraphicsObject obj : objects) {
            String objSvg = obj.toSvg(options);
            if (objSvg != null && !objSvg.isEmpty()) {
                svg.append(objSvg);
            }
        }
        svg.append("</g>");
        return svg.toString();
    }
}
