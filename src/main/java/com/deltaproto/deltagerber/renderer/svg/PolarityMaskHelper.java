package com.deltaproto.deltagerber.renderer.svg;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Polarity;
import com.deltaproto.deltagerber.model.gerber.operation.GraphicsObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared helper for rendering Gerber polarity using SVG masks.
 *
 * <p>The Gerber rendering model is a painter's model: dark objects paint, clear objects erase
 * from the entire accumulated image. A clear can only erase what lies under it, though, so a
 * dark object is masked by exactly the clear objects drawn <em>after</em> it whose bounding box
 * overlaps its own — and most dark objects are touched by none. Dark objects are therefore
 * grouped by the set of clears that can reach them: the untouched ones are emitted bare, and
 * each distinct set becomes one flat {@code <g mask>} whose mask holds just those clears.
 *
 * <p>This replaces one nested mask per clear run. Nesting was correct but priced every mask at
 * the whole image: a renderer rasterises each masked group and its mask into an offscreen buffer
 * the size of the group's extent, and with the nesting every group spanned the board. An Altium
 * legend with 211 polarity flips (inverted text boxes, then pad knockouts) took 15 s to
 * rasterise and repainted at 4 frames a second in a browser; scoped, the same layer costs the
 * bare-paths price. Ordering among dark objects is free to change because a layer is one colour
 * under one group opacity, so where two dark objects overlap the result is the same either way.
 */
class PolarityMaskHelper {

    static class PolarityGroup {
        final Polarity polarity;
        final List<GraphicsObject> objects;

        PolarityGroup(Polarity polarity, List<GraphicsObject> objects) {
            this.polarity = polarity;
            this.objects = objects;
        }
    }

    /**
     * Groups consecutive objects with the same polarity into PolarityGroups.
     */
    static List<PolarityGroup> groupByPolarity(List<GraphicsObject> objects) {
        List<PolarityGroup> groups = new ArrayList<>();
        if (objects.isEmpty()) {
            return groups;
        }

        Polarity current = objects.get(0).getPolarity();
        List<GraphicsObject> currentObjects = new ArrayList<>();

        for (GraphicsObject obj : objects) {
            if (obj.getPolarity() != current) {
                groups.add(new PolarityGroup(current, currentObjects));
                currentObjects = new ArrayList<>();
                current = obj.getPolarity();
            }
            currentObjects.add(obj);
        }
        if (!currentObjects.isEmpty()) {
            groups.add(new PolarityGroup(current, currentObjects));
        }
        return groups;
    }

    /** One masked group: the dark objects, and the clear objects whose mask they share. */
    private static final class MaskedSet {
        final List<Integer> runs;
        final List<GraphicsObject> clears = new ArrayList<>();
        final List<GraphicsObject> darks = new ArrayList<>();
        final BoundingBox extent = new BoundingBox();
        boolean unbounded;

        MaskedSet(List<Integer> runs) {
            this.runs = runs;
        }
    }

    /** Dark objects nothing erases, and the masked sets, in first-seen order. */
    private static final class Plan {
        final List<GraphicsObject> bare = new ArrayList<>();
        final List<MaskedSet> sets = new ArrayList<>();
    }

    private static final class ClearRef {
        final GraphicsObject object;
        final int sequence;   // position in file order
        final int run;        // ordinal of the clear run (polarity group) it belongs to
        final double minX, minY, maxX, maxY;

        ClearRef(GraphicsObject object, int sequence, int run) {
            this.object = object;
            this.sequence = sequence;
            this.run = run;
            BoundingBox b = object.getBoundingBox();
            boolean valid = b != null && b.isValid();
            minX = valid ? b.getMinX() : Double.NEGATIVE_INFINITY;
            minY = valid ? b.getMinY() : Double.NEGATIVE_INFINITY;
            maxX = valid ? b.getMaxX() : Double.POSITIVE_INFINITY;
            maxY = valid ? b.getMaxY() : Double.POSITIVE_INFINITY;
        }
    }

    /**
     * Decide which clears can reach which dark objects. The plan depends only on the groups, so
     * {@link #generateMaskDefs} and {@link #renderWithMasks} compute the same one and agree on
     * mask numbering.
     */
    private static Plan plan(List<PolarityGroup> groups) {
        Plan plan = new Plan();
        List<ClearRef> clears = new ArrayList<>();
        List<List<ClearRef>> runs = new ArrayList<>();
        int sequence = 0;
        for (PolarityGroup group : groups) {
            List<ClearRef> run = group.polarity == Polarity.CLEAR ? new ArrayList<>() : null;
            for (GraphicsObject obj : group.objects) {
                if (run != null) {
                    ClearRef ref = new ClearRef(obj, sequence, runs.size());
                    clears.add(ref);
                    run.add(ref);
                }
                sequence++;
            }
            if (run != null) runs.add(run);
        }
        if (clears.isEmpty()) {
            for (PolarityGroup group : groups) {
                plan.bare.addAll(group.objects);
            }
            return plan;
        }

        // Uniform grid over the clears, so each dark object only tests its neighbourhood.
        double gMinX = Double.POSITIVE_INFINITY, gMinY = Double.POSITIVE_INFINITY;
        double gMaxX = Double.NEGATIVE_INFINITY, gMaxY = Double.NEGATIVE_INFINITY;
        for (ClearRef c : clears) {
            if (Double.isInfinite(c.minX)) continue;
            gMinX = Math.min(gMinX, c.minX); gMinY = Math.min(gMinY, c.minY);
            gMaxX = Math.max(gMaxX, c.maxX); gMaxY = Math.max(gMaxY, c.maxY);
        }
        boolean gridded = gMinX < gMaxX && gMinY < gMaxY;
        int cells = gridded ? Math.max(1, Math.min(64, (int) Math.sqrt(clears.size()))) : 1;
        double cellW = gridded ? (gMaxX - gMinX) / cells : 1;
        double cellH = gridded ? (gMaxY - gMinY) / cells : 1;
        @SuppressWarnings("unchecked")
        List<ClearRef>[] grid = new List[cells * cells];
        List<ClearRef> unbounded = new ArrayList<>();
        for (ClearRef c : clears) {
            if (!gridded || Double.isInfinite(c.minX)) {
                unbounded.add(c);
                continue;
            }
            int x0 = cell(c.minX, gMinX, cellW, cells), x1 = cell(c.maxX, gMinX, cellW, cells);
            int y0 = cell(c.minY, gMinY, cellH, cells), y1 = cell(c.maxY, gMinY, cellH, cells);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int i = y * cells + x;
                    if (grid[i] == null) grid[i] = new ArrayList<>();
                    grid[i].add(c);
                }
            }
        }

        Map<List<Integer>, MaskedSet> sets = new LinkedHashMap<>();
        sequence = 0;
        for (PolarityGroup group : groups) {
            for (GraphicsObject obj : group.objects) {
                int seq = sequence++;
                if (group.polarity != Polarity.DARK) continue;
                BoundingBox b = obj.getBoundingBox();
                List<ClearRef> hits = new ArrayList<>();
                for (ClearRef c : unbounded) {
                    if (c.sequence > seq) hits.add(c);
                }
                if (b == null || !b.isValid()) {
                    for (ClearRef c : clears) {
                        if (c.sequence > seq && !Double.isInfinite(c.minX)) hits.add(c);
                    }
                } else if (gridded && b.getMaxX() >= gMinX && b.getMinX() <= gMaxX
                        && b.getMaxY() >= gMinY && b.getMinY() <= gMaxY) {
                    int x0 = cell(b.getMinX(), gMinX, cellW, cells), x1 = cell(b.getMaxX(), gMinX, cellW, cells);
                    int y0 = cell(b.getMinY(), gMinY, cellH, cells), y1 = cell(b.getMaxY(), gMinY, cellH, cells);
                    for (int y = y0; y <= y1; y++) {
                        for (int x = x0; x <= x1; x++) {
                            List<ClearRef> cell = grid[y * cells + x];
                            if (cell == null) continue;
                            for (ClearRef c : cell) {
                                if (c.sequence > seq && c.minX <= b.getMaxX() && b.getMinX() <= c.maxX
                                        && c.minY <= b.getMaxY() && b.getMinY() <= c.maxY
                                        && !hits.contains(c)) {
                                    hits.add(c);
                                }
                            }
                        }
                    }
                }
                if (hits.isEmpty()) {
                    plan.bare.add(obj);
                    continue;
                }
                // Key by clear *run*, not clear object: a run of 1 400 pad knockouts then
                // masks the strokes it touches with one mask instead of 1 400 near-identical
                // ones, and every object of a run is after every dark object keyed on it.
                List<Integer> key = new ArrayList<>();
                for (ClearRef c : hits) {
                    if (!key.contains(c.run)) key.add(c.run);
                }
                key.sort(null);
                MaskedSet set = sets.get(key);
                if (set == null) {
                    set = new MaskedSet(key);
                    sets.put(key, set);
                }
                set.darks.add(obj);
                if (b == null || !b.isValid()) set.unbounded = true;
                else set.extent.include(b);
            }
        }
        // A set's mask holds the clears of its runs that can reach its dark objects; the rest
        // of the run would only erase where the set has nothing.
        for (MaskedSet set : sets.values()) {
            for (int runIndex : set.runs) {
                for (ClearRef c : runs.get(runIndex)) {
                    if (set.unbounded || Double.isInfinite(c.minX)
                            || (c.minX <= set.extent.getMaxX() && set.extent.getMinX() <= c.maxX
                                && c.minY <= set.extent.getMaxY() && set.extent.getMinY() <= c.maxY)) {
                        set.clears.add(c.object);
                    }
                }
            }
        }
        plan.sets.addAll(sets.values());
        return plan;
    }

    private static int cell(double v, double origin, double size, int cells) {
        int i = (int) ((v - origin) / size);
        return Math.max(0, Math.min(cells - 1, i));
    }

    /**
     * Generates SVG mask definitions, one per distinct set of clears that reach a dark object.
     *
     * @param svg        target builder
     * @param groups     polarity groups from {@link #groupByPolarity}
     * @param maskPrefix ID prefix for mask elements (e.g., "cm" or "L0_cm")
     * @param maskRect   the white background rect for the mask (covers the viewbox)
     * @param maskOptions SvgOptions with dark/clear colors set to "black" for mask rendering
     */
    static void generateMaskDefs(StringBuilder svg, List<PolarityGroup> groups,
                                  String maskPrefix, String maskRect, SvgOptions maskOptions) {
        Plan plan = plan(groups);
        // Every mask states its region in user space. Left to the default, the region is the
        // masked group's bounding box in objectBoundingBox units — which excludes stroke width,
        // so a group holding one horizontal stroke has a zero-height region and the stroke
        // vanishes; and the ±10 % margin that makes it is empty for a 100 mm trace that is
        // 0.4 mm wide anyway. The region is the set's inked extent plus a margin, or the whole
        // mask rect for a set with an object whose extent is unknown; the mask's buffer is the
        // size of this region, which is what keeps a scoped mask cheap.
        double[] whole = rectBounds(maskRect);
        int maskId = 0;
        for (MaskedSet set : plan.sets) {
            String region = "";
            if (!set.unbounded && set.extent.isValid()) {
                region = String.format(Locale.US,
                        " maskUnits=\"userSpaceOnUse\" x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\"",
                        set.extent.getMinX() - MASK_REGION_MARGIN, set.extent.getMinY() - MASK_REGION_MARGIN,
                        set.extent.getWidth() + 2 * MASK_REGION_MARGIN, set.extent.getHeight() + 2 * MASK_REGION_MARGIN);
            } else if (whole != null) {
                region = String.format(Locale.US,
                        " maskUnits=\"userSpaceOnUse\" x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\"",
                        whole[0], whole[1], whole[2], whole[3]);
            }
            svg.append(String.format("  <mask id=\"%s%d\"%s>\n", maskPrefix, maskId++, region));
            svg.append("    ").append(maskRect).append("\n");
            for (GraphicsObject obj : set.clears) {
                String objSvg = obj.toSvg(maskOptions);
                if (objSvg != null && !objSvg.isEmpty()) {
                    svg.append("    ").append(objSvg).append("\n");
                }
            }
            svg.append("  </mask>\n");
        }
    }

    /**
     * Renders the dark objects: the ones no clear reaches bare, then one flat masked group per
     * set of clears, numbered as {@link #generateMaskDefs} numbered the masks.
     *
     * @param svg        target builder
     * @param groups     polarity groups from {@link #groupByPolarity}
     * @param maskPrefix ID prefix matching the one used in {@link #generateMaskDefs}
     * @param options    SvgOptions for rendering dark objects
     */
    static void renderWithMasks(StringBuilder svg, List<PolarityGroup> groups,
                                 String maskPrefix, SvgOptions options) {
        if (groups.isEmpty()) {
            return;
        }
        Plan plan = plan(groups);
        renderObjects(svg, plan.bare, options, "  ");
        int maskId = 0;
        for (MaskedSet set : plan.sets) {
            svg.append(String.format("  <g mask=\"url(#%s%d)\">\n", maskPrefix, maskId++));
            renderObjects(svg, set.darks, options, "    ");
            svg.append("  </g>\n");
        }
    }

    /** How far a mask's region reaches beyond the inked extent of the objects it masks, mm. */
    private static final double MASK_REGION_MARGIN = 1.0;

    private static final java.util.regex.Pattern RECT_BOUNDS = java.util.regex.Pattern.compile(
            "x=\"([-\\d.]+)\" y=\"([-\\d.]+)\" width=\"([-\\d.]+)\" height=\"([-\\d.]+)\"");

    /** The x, y, width, height of a rect made by {@link #createMaskRect}, or null. */
    private static double[] rectBounds(String maskRect) {
        java.util.regex.Matcher m = RECT_BOUNDS.matcher(maskRect);
        if (!m.find()) return null;
        try {
            return new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)),
                    Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Creates the mask base rect string (white rect covering the viewbox + margin).
     */
    static String createMaskRect(double minX, double minY, double width, double height, double extraMargin) {
        return String.format(Locale.US,
            "<rect x=\"%.6f\" y=\"%.6f\" width=\"%.6f\" height=\"%.6f\" fill=\"white\"/>",
            minX - extraMargin, minY - extraMargin,
            width + 2 * extraMargin, height + 2 * extraMargin);
    }

    private static void renderObjects(StringBuilder svg, List<GraphicsObject> objects,
                                      SvgOptions options, String indent) {
        for (GraphicsObject obj : objects) {
            String objSvg = obj.toSvg(options);
            if (objSvg != null && !objSvg.isEmpty()) {
                svg.append(indent).append(objSvg).append("\n");
            }
        }
    }
}
