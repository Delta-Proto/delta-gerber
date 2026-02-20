package com.deltaproto.deltagerber.model.drill;

/**
 * Represents a drill tool definition.
 */
public class Tool {

    private final int number;
    private final double diameter;
    private String feedRate;
    private String spindleSpeed;
    private String maxRetractRate;

    public Tool(int number, double diameter) {
        this.number = number;
        this.diameter = diameter;
    }

    public int getNumber() {
        return number;
    }

    public double getDiameter() {
        return diameter;
    }

    public String getFeedRate() {
        return feedRate;
    }

    public void setFeedRate(String feedRate) {
        this.feedRate = feedRate;
    }

    public String getSpindleSpeed() {
        return spindleSpeed;
    }

    public void setSpindleSpeed(String spindleSpeed) {
        this.spindleSpeed = spindleSpeed;
    }

    public String getMaxRetractRate() {
        return maxRetractRate;
    }

    public void setMaxRetractRate(String maxRetractRate) {
        this.maxRetractRate = maxRetractRate;
    }

    /**
     * Generate SVG definition for this tool (a circle).
     */
    public String toSvgDef(String id) {
        return String.format("<circle id=\"%s\" r=\"%.6f\"/>", id, diameter / 2);
    }

    @Override
    public String toString() {
        return String.format("Tool[T%d, %.4fmm]", number, diameter);
    }
}
