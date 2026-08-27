package com.deltaproto.deltagerber.model.gerber;

import com.deltaproto.deltagerber.classify.GenerationSoftware;
import com.deltaproto.deltagerber.classify.LayerClassification;
import com.deltaproto.deltagerber.classify.LayerClassifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed Gerber job file ({@code .gbrjob}) — the JSON manifest that describes a whole board:
 * who made it, how big it is, how many layers it has, and what each file in the set is for.
 *
 * <p>Its {@code FilesAttributes} carry a {@code FileFunction} per file, which makes it the one
 * fully reliable way to classify a set: no filename guessing, no per-file header scanning. When a
 * set ships a job file, trust it.
 */
public class GerberJobDocument {

    private GenerationSoftware generationSoftware;
    private String creationDate;
    private String projectName;
    private String projectGuid;
    private String projectRevision;
    private Double sizeXMm;
    private Double sizeYMm;
    private Integer layerCount;
    private Double boardThicknessMm;
    private final List<JobFile> files = new ArrayList<>();
    private final List<StackupEntry> materialStackup = new ArrayList<>();

    /** One entry of the job file's {@code FilesAttributes} array. */
    public static class JobFile {

        private final String path;
        private final String fileFunction;
        private final String filePolarity;

        public JobFile(String path, String fileFunction, String filePolarity) {
            this.path = path;
            this.fileFunction = fileFunction;
            this.filePolarity = filePolarity;
        }

        /** Filename relative to the job file, e.g. {@code "board-F_Cu.gbr"}. */
        public String getPath() {
            return path;
        }

        /** Raw {@code .FileFunction} value, e.g. {@code "Copper,L1,Top"}. */
        public String getFileFunction() {
            return fileFunction;
        }

        /** {@code "Positive"} or {@code "Negative"}, or null when unstated. */
        public String getFilePolarity() {
            return filePolarity;
        }

        /** {@link #getFileFunction()} resolved to a layer role, or null when unrecognised. */
        public LayerClassification getClassification() {
            return LayerClassifier.fromFileFunction(fileFunction);
        }

        @Override
        public String toString() {
            return String.format("JobFile[%s, %s]", path, fileFunction);
        }
    }

    /**
     * What one entry of the job file's {@code MaterialStackup} is made of, as the file spells it.
     *
     * <p>Deliberately the file's own vocabulary, not ours: {@code Legend} rather than silkscreen,
     * {@code Dielectric} without saying whether that is core or prepreg. Mapping it onto something
     * a caller can reason about is {@link com.deltaproto.deltagerber.spec.StackFunction}'s job.
     */
    public enum StackupType {

        COPPER,
        DIELECTRIC,
        SOLDERMASK,
        SOLDERPASTE,
        LEGEND,
        FINISH,
        /** A type the specification allows but we do not model — carried through by its raw name. */
        OTHER;

        /**
         * The type a {@code "Type"} value names, or {@link #OTHER} when it names something else.
         * Case, spaces and hyphens are ignored, so {@code "Solder Mask"} and {@code "SolderMask"}
         * are the same type — tools differ on the spelling.
         */
        public static StackupType of(String type) {
            if (type == null) {
                return OTHER;
            }
            String key = type.replace(" ", "").replace("-", "").replace("_", "").toUpperCase();
            return switch (key) {
                case "COPPER" -> COPPER;
                case "DIELECTRIC" -> DIELECTRIC;
                case "SOLDERMASK" -> SOLDERMASK;
                case "SOLDERPASTE", "PASTE" -> SOLDERPASTE;
                case "LEGEND", "SILKSCREEN" -> LEGEND;
                case "FINISH", "SURFACEFINISH" -> FINISH;
                default -> OTHER;
            };
        }
    }

    /**
     * One entry of the job file's {@code MaterialStackup} array — one physical layer of the board,
     * read exactly as the file states it.
     *
     * @param type      the entry's {@code Type}, resolved; never null
     * @param rawType   the {@code Type} string as written, so an unmodelled type is not lost
     * @param thicknessMm the entry's {@code Thickness}; null when the file omits it. The job file's
     *                  unit is always mm
     * @param material  the {@code Material}, e.g. {@code "FR4"}; null when the file omits it
     * @param name      the {@code Name}, e.g. {@code "F.Cu"} or {@code "Top Solder Mask"}
     * @param notes     the {@code Notes}
     */
    public record StackupEntry(StackupType type, String rawType, Double thicknessMm, String material,
                               String name, String notes) {
    }

    /** The tool that wrote the job file, from {@code Header.GenerationSoftware}; may be null. */
    public GenerationSoftware getGenerationSoftware() {
        return generationSoftware;
    }

    public void setGenerationSoftware(GenerationSoftware generationSoftware) {
        this.generationSoftware = generationSoftware;
    }

    /** Vendor of the generating tool, e.g. "KiCad"; null when undeclared. */
    public String getVendor() {
        return generationSoftware == null ? null : generationSoftware.vendor();
    }

    /** ISO 8601 timestamp from {@code Header.CreationDate}; null when undeclared. */
    public String getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(String creationDate) {
        this.creationDate = creationDate;
    }

    /** {@code GeneralSpecs.ProjectId.Name}. */
    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectGuid() {
        return projectGuid;
    }

    public void setProjectGuid(String projectGuid) {
        this.projectGuid = projectGuid;
    }

    public String getProjectRevision() {
        return projectRevision;
    }

    public void setProjectRevision(String projectRevision) {
        this.projectRevision = projectRevision;
    }

    /** Board width in mm, as declared. The job file's unit is always mm. */
    public Double getSizeXMm() {
        return sizeXMm;
    }

    public void setSizeXMm(Double sizeXMm) {
        this.sizeXMm = sizeXMm;
    }

    /** Board height in mm, as declared. */
    public Double getSizeYMm() {
        return sizeYMm;
    }

    public void setSizeYMm(Double sizeYMm) {
        this.sizeYMm = sizeYMm;
    }

    /** Number of copper layers, from {@code GeneralSpecs.LayerNumber}. */
    public Integer getLayerCount() {
        return layerCount;
    }

    public void setLayerCount(Integer layerCount) {
        this.layerCount = layerCount;
    }

    public Double getBoardThicknessMm() {
        return boardThicknessMm;
    }

    public void setBoardThicknessMm(Double boardThicknessMm) {
        this.boardThicknessMm = boardThicknessMm;
    }

    public void addStackupEntry(StackupEntry entry) {
        materialStackup.add(entry);
    }

    /**
     * The board's physical build-up from {@code MaterialStackup}, in declaration order — which the
     * specification defines as top of the board first, down to the bottom. Empty when the file
     * declares no stack-up — KiCad writes one from version 6 on (with thicknesses from version 8),
     * EAGLE/Fusion writes none.
     *
     * <p>This is the only place a Gerber set ever states the material between two copper layers.
     * Everything else in a set describes artwork on a layer.
     */
    public List<StackupEntry> getMaterialStackup() {
        return Collections.unmodifiableList(materialStackup);
    }

    public void addFile(JobFile file) {
        files.add(file);
    }

    /** Every file the job declares, in declaration order. */
    public List<JobFile> getFiles() {
        return Collections.unmodifiableList(files);
    }

    @Override
    public String toString() {
        return String.format("GerberJobDocument[%s, %s x %s mm, %s layers, %d files]",
                projectName, sizeXMm, sizeYMm, layerCount, files.size());
    }
}
