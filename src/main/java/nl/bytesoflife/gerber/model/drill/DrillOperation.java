package nl.bytesoflife.gerber.model.drill;

import nl.bytesoflife.gerber.model.gerber.BoundingBox;

/**
 * Base class for drill operations (hits and slots).
 */
public abstract class DrillOperation {

    protected Tool tool;

    protected DrillOperation(Tool tool) {
        this.tool = tool;
    }

    public Tool getTool() {
        return tool;
    }

    /**
     * Get the bounding box of this operation.
     */
    public abstract BoundingBox getBoundingBox();

    /**
     * Generate SVG for this operation.
     */
    public abstract String toSvg();
}
