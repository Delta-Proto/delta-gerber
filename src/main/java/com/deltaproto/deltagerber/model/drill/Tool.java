package com.deltaproto.deltagerber.model.drill;

import java.util.Locale;

/**
 * Represents a drill tool definition.
 */
public class Tool {

    private final int number;
    private final double diameter;
    private String feedRate;
    private String spindleSpeed;
    private String maxRetractRate;
    private Boolean plated;

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
     * Whether the holes this tool drills are plated: {@code TRUE} for a plated through-hole,
     * {@code FALSE} for a non-plated one, and {@code null} when the file did not say.
     *
     * <p>Excellon has no field for this — it is stated in a {@code ;TYPE=PLATED} /
     * {@code ;TYPE=NON_PLATED} comment, and a single file routinely switches partway down its tool
     * table (Altium writes its non-plated tools last). So plating belongs to the tool, not to the
     * file, and a set that splits the two into separate files says the same thing a coarser way.
     *
     * <p>It is the difference between a hole that needs an annular ring and one that does not: a
     * non-plated mounting hole has no barrel to connect to a pad, so it is exempt from the check
     * rather than failing it.
     */
    public Boolean getPlated() {
        return plated;
    }

    /** See {@link #getPlated()}; {@code null} means the file did not state it. */
    public void setPlated(Boolean plated) {
        this.plated = plated;
    }

    /**
     * Generate SVG definition for this tool (a circle).
     */
    public String toSvgDef(String id) {
        return String.format(Locale.US, "<circle id=\"%s\" r=\"%.6f\"/>", id, diameter / 2);
    }

    @Override
    public String toString() {
        return String.format("Tool[T%d, %.4fmm]", number, diameter);
    }
}
