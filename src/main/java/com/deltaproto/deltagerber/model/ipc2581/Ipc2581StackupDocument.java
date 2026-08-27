package com.deltaproto.deltagerber.model.ipc2581;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The stack-up of an IPC-2581 file — the one section of that standard this library reads.
 *
 * <p>IPC-2581 describes a whole product (netlist, artwork, assembly, test), and reading all of it
 * is a different project. What it has that no Gerber set does is a <em>measured</em> stack-up: every
 * layer in order, with its thickness and tolerance, its material, and its function stated rather
 * than guessed. That is worth reading on its own.
 *
 * <p>Thicknesses are normalized to millimetres at parse time, from the file's {@code CadHeader}
 * units, so this document shares the library's one unit like every other parsed document.
 */
public class Ipc2581StackupDocument {

    private String revision;
    private String stackupName;
    private Double boardThicknessMm;
    private final List<StackupLayer> layers = new ArrayList<>();

    /**
     * What a layer of an IPC-2581 stack-up is for, from its {@code layerFunction} attribute.
     *
     * <p>The file's own vocabulary, kept as the file states it. Note in particular that the
     * standard separates {@link #DIELCORE} from {@link #DIELPREG} — and that CAD tools do not
     * always mean it: the Altium exports in our corpus label every dielectric {@code DIELCORE},
     * including the ones whose material is plainly a prepreg. Which is why
     * {@code spec.StackFunction} keeps one dielectric and does not repeat the claim.
     */
    public enum Function {

        /** Copper: {@code CONDUCTOR}, {@code SIGNAL}, {@code PLANE}. */
        CONDUCTOR(true),
        /** Dielectric: {@code DIELCORE}, {@code DIELPREG}, {@code DIELADHV}. */
        DIELECTRIC(true),
        SOLDERMASK(true),
        SOLDERPASTE(true),
        SILKSCREEN(true),
        /** A drill, rout, document, assembly or courtyard layer — no z-position of its own. */
        NON_PHYSICAL(false),
        /** A function this library does not model. */
        OTHER(false);

        private final boolean physical;

        Function(boolean physical) {
            this.physical = physical;
        }

        /**
         * Whether this layer is a material the board is built from, as opposed to a drawing or a
         * drill program. An IPC-2581 stack-up group lists both: documentation layers appear in it
         * with a thickness of zero.
         */
        public boolean isPhysical() {
            return physical;
        }

        /** The function a {@code layerFunction} value names; {@link #OTHER} when unrecognised. */
        public static Function of(String layerFunction) {
            if (layerFunction == null) {
                return OTHER;
            }
            return switch (layerFunction.trim().toUpperCase()) {
                case "CONDUCTOR", "SIGNAL", "PLANE", "POWER", "GROUND", "MIXED" -> CONDUCTOR;
                case "DIELCORE", "DIELPREG", "DIELADHV", "DIELECTRIC" -> DIELECTRIC;
                case "SOLDERMASK", "SOLDERRESIST", "COATINGNONCOND" -> SOLDERMASK;
                case "SOLDERPASTE", "PASTEMASK" -> SOLDERPASTE;
                case "SILKSCREEN", "LEGEND" -> SILKSCREEN;
                case "DRILL", "ROUT", "DOCUMENT", "ASSEMBLY", "COMPONENT", "COURTYARD",
                     "BOARDOUTLINE", "PROBE" -> NON_PHYSICAL;
                default -> OTHER;
            };
        }
    }

    /**
     * One {@code StackupLayer}, with what the file's {@code Layer} and {@code Spec} entries say
     * about it.
     *
     * @param name        the layer name, e.g. {@code "Top Layer"} or {@code "Dielectric 3"}
     * @param function    what it is for; never null
     * @param rawFunction the {@code layerFunction} as written, so {@code DIELCORE} against
     *                    {@code DIELPREG} is still available to a caller that wants it
     * @param side        {@code TOP}, {@code BOTTOM}, {@code INTERNAL} or {@code NONE} as written
     * @param thicknessMm thickness in millimetres, or null when the file states none
     * @param material    the material named by the layer's {@code Spec}, e.g. {@code "PP-001"}
     * @param sequence    the stack position the file gives it, 1 at the top of the board
     */
    public record StackupLayer(String name, Function function, String rawFunction, String side,
                               Double thicknessMm, String material, int sequence) {
    }

    /** The {@code revision} of the IPC-2581 file, e.g. {@code "B"} or {@code "C"}. */
    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    /** The {@code Stackup} element's name; null when unnamed. */
    public String getStackupName() {
        return stackupName;
    }

    public void setStackupName(String stackupName) {
        this.stackupName = stackupName;
    }

    /**
     * The stack-up's {@code overallThickness} in millimetres, or null when the file states none.
     * This is the finished board thickness, as designed.
     */
    public Double getBoardThicknessMm() {
        return boardThicknessMm;
    }

    public void setBoardThicknessMm(Double boardThicknessMm) {
        this.boardThicknessMm = boardThicknessMm;
    }

    public void addLayer(StackupLayer layer) {
        layers.add(layer);
    }

    /** Every layer of the stack-up in sequence order — top of the board first. */
    public List<StackupLayer> getLayers() {
        return Collections.unmodifiableList(layers);
    }

    @Override
    public String toString() {
        return String.format("Ipc2581StackupDocument[rev %s, %d layers, %s mm]",
                revision, layers.size(), boardThicknessMm);
    }
}
