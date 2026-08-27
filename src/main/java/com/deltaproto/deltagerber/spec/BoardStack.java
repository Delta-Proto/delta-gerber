package com.deltaproto.deltagerber.spec;

import com.deltaproto.deltagerber.classify.LayerFunction;
import com.deltaproto.deltagerber.classify.LayerSide;
import com.deltaproto.deltagerber.model.gerber.GerberJobDocument;
import com.deltaproto.deltagerber.model.ipc2581.Ipc2581StackupDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The board's physical build-up: the layers from the top down, and how thick the finished board is.
 *
 * <p>The two travel together because they come from the same file and mean nothing apart — a total
 * read from one customer's file against layers read from another's is a bug, not a stack. Either
 * half can be missing, and both cases are real:
 *
 * <ul>
 *   <li><b>Layers and a total.</b> A Gerber job file from KiCad 8 or later, or an IPC-2581 file.
 *       The entries state their own thicknesses and add up to the declared total exactly.
 *   <li><b>Layers, no thicknesses.</b> KiCad 6 and 7 name the layers and their materials and state
 *       no thickness — except the board's, in {@code GeneralSpecs}.
 *   <li><b>A total, no layers.</b> An EAGLE/Fusion job file states the board thickness and no
 *       stack-up at all. So does an ODB++ archive, whose {@code .board_thickness} a caller can pass
 *       in through {@link #of(List, Long)}.
 *   <li><b>Neither.</b> Nearly every Gerber set, which ships no job file. The layers are then
 *       {@linkplain #estimate estimated} from the artwork and the thickness is simply not known.
 * </ul>
 *
 * @see BoardSpecification#getStack()
 * @see BoardSpecification#getBoardThicknessPm()
 */
public final class BoardStack {

    private static final BoardStack EMPTY = new BoardStack(List.of(), null);

    private final List<StackEntry> entries;
    private final Long boardThicknessPm;

    private BoardStack(List<StackEntry> entries, Long declaredThicknessPm) {
        this.entries = entries;
        this.boardThicknessPm = declaredThicknessPm != null ? declaredThicknessPm : sum(entries);
    }

    /** Nothing known: no layers, no thickness. */
    public static BoardStack empty() {
        return EMPTY;
    }

    /**
     * A stack a caller already holds — persisted rows read back, or a stack read from a format this
     * library does not parse, such as an ODB++ archive's matrix and {@code .board_thickness}.
     *
     * <p>Entries are taken top of the board first and their {@linkplain StackEntry#getOrdinal()
     * ordinals} are renumbered densely from 0. A null {@code boardThicknessPm} falls back to the
     * sum of the entries that state one.
     */
    public static BoardStack of(List<StackEntry> entries, Long boardThicknessPm) {
        List<StackEntry> ordered = ordered(entries);
        return ordered.isEmpty() && boardThicknessPm == null
                ? EMPTY
                : new BoardStack(ordered, boardThicknessPm);
    }

    /**
     * The stack a Gerber job file states.
     *
     * <p>Its {@code MaterialStackup} gives the layers, and {@code GeneralSpecs.BoardThickness} the
     * finished thickness — which is stated even by the files that carry no stack-up, and by every
     * KiCad file whose stack-up carries no thicknesses. Millimetres become picometres here.
     */
    public static BoardStack from(GerberJobDocument job) {
        if (job == null) {
            return EMPTY;
        }
        List<StackEntry> entries = new ArrayList<>(job.getMaterialStackup().size());
        for (GerberJobDocument.StackupEntry entry : job.getMaterialStackup()) {
            entries.add(StackEntry.ofMm(entries.size(), function(entry.type()), entry.name(),
                    entry.thicknessMm(), entry.material(), false));
        }
        return of(entries, StackEntry.toPicometres(job.getBoardThicknessMm()));
    }

    /**
     * The stack an IPC-2581 file states — the best of the fab formats: every layer's function, its
     * material and its own thickness, plus the finished board's.
     *
     * <p>Only the layers that are {@linkplain Ipc2581StackupDocument.Function#isPhysical() material}
     * take part. An IPC-2581 stack-up group lists the documentation layers too — assembly drawings,
     * courtyards, the drill guide — each with a thickness of zero, and none of them is part of the
     * board.
     */
    public static BoardStack from(Ipc2581StackupDocument stackup) {
        if (stackup == null) {
            return EMPTY;
        }
        List<Ipc2581StackupDocument.StackupLayer> layers = new ArrayList<>(stackup.getLayers());
        layers.sort(Comparator.comparingInt(Ipc2581StackupDocument.StackupLayer::sequence));

        List<StackEntry> entries = new ArrayList<>(layers.size());
        for (Ipc2581StackupDocument.StackupLayer layer : layers) {
            if (!layer.function().isPhysical()) {
                continue;
            }
            entries.add(StackEntry.ofMm(entries.size(), function(layer.function()), layer.name(),
                    layer.thicknessMm(), layer.material(), false));
        }
        return of(entries, StackEntry.toPicometres(stackup.getBoardThicknessMm()));
    }

    /**
     * The stack the set's own layers imply, with {@linkplain StackEntry#isEstimated() estimated}
     * set throughout and no thickness anywhere.
     *
     * <p>Only layers that {@linkplain LayerFunction#isPhysical() occupy a z-position} take part,
     * ordered the way a job file writes them: legend, paste, mask, then the copper — top, the
     * inner layers by {@link AnalyzedLayer#getLayerNumber()}, bottom — then mask, paste and legend
     * again on the way out. There are no dielectrics: nothing in a Gerber set says what is between
     * the copper, so inventing a layer with a made-up thickness would be a guess dressed as a
     * measurement.
     *
     * <p>A layer that cannot be placed is left out — a soldermask whose side is unknown has no
     * position in an ordered stack. Copper is the exception: it is kept even when its side is not
     * known, after the layers that are, because a copper layer count that silently disagreed with
     * {@link BoardSpecification#getCopperLayerCount()} would be worse than an imperfect order.
     */
    public static BoardStack estimate(List<AnalyzedLayer> layers) {
        if (layers == null || layers.isEmpty()) {
            return EMPTY;
        }
        List<StackEntry> stack = new ArrayList<>();
        add(stack, layers, LayerFunction.SILKSCREEN, LayerSide.TOP);
        add(stack, layers, LayerFunction.PASTE, LayerSide.TOP);
        add(stack, layers, LayerFunction.SOLDERMASK, LayerSide.TOP);
        addCopper(stack, layers);
        add(stack, layers, LayerFunction.SOLDERMASK, LayerSide.BOTTOM);
        add(stack, layers, LayerFunction.PASTE, LayerSide.BOTTOM);
        add(stack, layers, LayerFunction.SILKSCREEN, LayerSide.BOTTOM);
        return stack.isEmpty() ? EMPTY : new BoardStack(List.copyOf(stack), null);
    }

    /** The layers, top of the board first, with dense ordinals from 0. Never null. */
    public List<StackEntry> getEntries() {
        return entries;
    }

    /**
     * The finished board's thickness in picometres, or null when nothing states one.
     *
     * <p>The figure the files declare, where they declare one; otherwise the sum of the layers that
     * state a thickness. The two agree exactly wherever both are present — which is the whole point
     * of counting in picometres, since a stack quoted in thousandths of an inch has to add up in
     * millimetres too.
     */
    public Long getBoardThicknessPm() {
        return boardThicknessPm;
    }

    /** {@link #getBoardThicknessPm()} in millimetres, or null when nothing states one. */
    public Double getBoardThicknessMm() {
        return StackEntry.toMillimetres(boardThicknessPm);
    }

    /** Whether the layers were synthesised from the artwork rather than read from a stack-up. */
    public boolean isEstimated() {
        return !entries.isEmpty() && entries.stream().anyMatch(StackEntry::isEstimated);
    }

    /** True when neither the layers nor the thickness are known. */
    public boolean isEmpty() {
        return entries.isEmpty() && boardThicknessPm == null;
    }

    @Override
    public String toString() {
        return String.format("BoardStack[%d layers, %s mm%s]",
                entries.size(), getBoardThicknessMm(), isEstimated() ? ", estimated" : "");
    }

    // ------------------------------------------------------------------------

    private static Long sum(List<StackEntry> entries) {
        Long total = null;
        for (StackEntry entry : entries) {
            if (entry.getThicknessPm() != null) {
                total = (total == null ? 0L : total) + entry.getThicknessPm();
            }
        }
        return total;
    }

    private static List<StackEntry> ordered(List<StackEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<StackEntry> ordered = new ArrayList<>(entries.size());
        for (StackEntry entry : entries) {
            if (entry != null) {
                ordered.add(entry.withOrdinal(ordered.size()));
            }
        }
        return List.copyOf(ordered);
    }

    private static StackFunction function(GerberJobDocument.StackupType type) {
        return switch (type) {
            case COPPER -> StackFunction.COPPER;
            case SOLDERMASK -> StackFunction.SOLDERMASK;
            case SOLDERPASTE -> StackFunction.PASTE;
            case LEGEND -> StackFunction.SILKSCREEN;
            case FINISH -> StackFunction.FINISH;
            case DIELECTRIC -> StackFunction.DIELECTRIC;
            case OTHER -> StackFunction.OTHER;
        };
    }

    private static StackFunction function(Ipc2581StackupDocument.Function function) {
        return switch (function) {
            case CONDUCTOR -> StackFunction.COPPER;
            case DIELECTRIC -> StackFunction.DIELECTRIC;
            case SOLDERMASK -> StackFunction.SOLDERMASK;
            case SOLDERPASTE -> StackFunction.PASTE;
            case SILKSCREEN -> StackFunction.SILKSCREEN;
            case NON_PHYSICAL, OTHER -> StackFunction.OTHER;
        };
    }

    private static void add(List<StackEntry> stack, List<AnalyzedLayer> layers,
                            LayerFunction function, LayerSide side) {
        for (AnalyzedLayer layer : layers) {
            if (layer.getFunction() == function && layer.getSide() == side) {
                stack.add(entry(stack.size(), layer));
            }
        }
    }

    /** Top copper, then the inner layers in stack-up order, then bottom, then any copper we cannot place. */
    private static void addCopper(List<StackEntry> stack, List<AnalyzedLayer> layers) {
        add(stack, layers, LayerFunction.COPPER, LayerSide.TOP);

        List<AnalyzedLayer> inner = new ArrayList<>();
        List<AnalyzedLayer> unplaced = new ArrayList<>();
        for (AnalyzedLayer layer : layers) {
            if (!layer.getFunction().isCopper()) {
                continue;
            }
            if (layer.getSide() == LayerSide.INNER) {
                inner.add(layer);
            } else if (layer.getSide() != LayerSide.TOP && layer.getSide() != LayerSide.BOTTOM) {
                unplaced.add(layer);
            }
        }
        // An unnumbered inner layer keeps its position in the set: the file order is the only
        // ordering evidence left once the number is gone.
        inner.sort(Comparator.comparing(AnalyzedLayer::getLayerNumber,
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (AnalyzedLayer layer : inner) {
            stack.add(entry(stack.size(), layer));
        }

        add(stack, layers, LayerFunction.COPPER, LayerSide.BOTTOM);
        for (AnalyzedLayer layer : unplaced) {
            stack.add(entry(stack.size(), layer));
        }
    }

    private static StackEntry entry(int ordinal, AnalyzedLayer layer) {
        String name = layer.getClassification() == null ? null : layer.getClassification().name();
        return StackEntry.of(ordinal, function(layer.getFunction()), name, null, null, true);
    }

    private static StackFunction function(LayerFunction function) {
        return switch (function) {
            case COPPER -> StackFunction.COPPER;
            case SOLDERMASK -> StackFunction.SOLDERMASK;
            case SILKSCREEN -> StackFunction.SILKSCREEN;
            case PASTE -> StackFunction.PASTE;
            default -> StackFunction.OTHER;
        };
    }
}
