package com.deltaproto.deltagerber.model.gerber;

import com.deltaproto.deltagerber.model.gerber.aperture.Aperture;
import com.deltaproto.deltagerber.model.gerber.aperture.macro.MacroTemplate;
import com.deltaproto.deltagerber.model.gerber.attribute.FileAttribute;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a parsed Gerber document.
 */
public class GerberDocument {

    private String fileName;
    private CoordinateFormat coordinateFormat;
    private Unit unit = Unit.MM;

    private final Map<String, FileAttribute> fileAttributes = new HashMap<>();
    private final Map<Integer, Aperture> apertures = new HashMap<>();
    private final Map<String, MacroTemplate> macroTemplates = new HashMap<>();
    private final List<GraphicsObject> objects = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private BoundingBox boundingBox;

    public GerberDocument() {
    }

    /**
     * Calculate the bounding box of all graphics objects.
     */
    public BoundingBox calculateBoundingBox() {
        boundingBox = new BoundingBox();
        for (GraphicsObject obj : objects) {
            boundingBox.include(obj.getBoundingBox());
        }
        return boundingBox;
    }

    /**
     * Get the file function from .FileFunction attribute.
     */
    public String getFileFunction() {
        FileAttribute attr = fileAttributes.get(".FileFunction");
        if (attr == null) attr = fileAttributes.get("FileFunction");
        return attr != null ? attr.getFirstValue() : null;
    }

    /**
     * Get generation software info from .GenerationSoftware attribute.
     */
    public String getGenerationSoftware() {
        FileAttribute attr = fileAttributes.get(".GenerationSoftware");
        if (attr == null) attr = fileAttributes.get("GenerationSoftware");
        if (attr != null && attr.getValues().size() >= 2) {
            return attr.getValue(0) + " " + attr.getValue(1);
        }
        return null;
    }

    public double getWidth() {
        if (boundingBox == null) calculateBoundingBox();
        return boundingBox.getWidth();
    }

    public double getHeight() {
        if (boundingBox == null) calculateBoundingBox();
        return boundingBox.getHeight();
    }

    public double getWidthMm() {
        return unit.toMm(getWidth());
    }

    public double getHeightMm() {
        return unit.toMm(getHeight());
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public void addObject(GraphicsObject object) {
        objects.add(object);
    }

    public void addAperture(Aperture aperture) {
        apertures.put(aperture.getDCode(), aperture);
    }

    public Aperture getAperture(int dCode) {
        return apertures.get(dCode);
    }

    public void addFileAttribute(FileAttribute attribute) {
        fileAttributes.put(attribute.getName(), attribute);
    }

    public void addMacroTemplate(MacroTemplate template) {
        macroTemplates.put(template.getName(), template);
    }

    public MacroTemplate getMacroTemplate(String name) {
        return macroTemplates.get(name);
    }

    public Map<String, MacroTemplate> getMacroTemplates() {
        return macroTemplates;
    }

    // Getters and setters

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public CoordinateFormat getCoordinateFormat() {
        return coordinateFormat;
    }

    public void setCoordinateFormat(CoordinateFormat coordinateFormat) {
        this.coordinateFormat = coordinateFormat;
    }

    public Unit getUnit() {
        return unit;
    }

    public void setUnit(Unit unit) {
        this.unit = unit;
    }

    public Map<String, FileAttribute> getFileAttributes() {
        return fileAttributes;
    }

    public Map<Integer, Aperture> getApertures() {
        return apertures;
    }

    public List<GraphicsObject> getObjects() {
        return objects;
    }

    public BoundingBox getBoundingBox() {
        if (boundingBox == null) calculateBoundingBox();
        return boundingBox;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return String.format("GerberDocument[%s, %d apertures, %d objects, %s]",
            fileName != null ? fileName : "unnamed",
            apertures.size(), objects.size(), unit);
    }
}
