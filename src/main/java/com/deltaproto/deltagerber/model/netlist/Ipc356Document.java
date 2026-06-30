package com.deltaproto.deltagerber.model.netlist;

import com.deltaproto.deltagerber.model.gerber.BoundingBox;
import com.deltaproto.deltagerber.model.gerber.Unit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A parsed IPC-D-356A bare-board electrical-test netlist.
 *
 * <p>Mirrors {@code GerberDocument} / {@code DrillDocument}: it holds the parsed records, the
 * collected comments, a de-duplicated list of non-fatal parse warnings, and a lazily-computed
 * {@link BoundingBox}. All geometry is normalized to millimetres at parse time (from the file's
 * native 0.0001&nbsp;inch {@code CUST} or 0.001&nbsp;mm {@code SI} grid), so {@link #getUnit()}
 * always returns {@link Unit#MM} and the coordinates share one space with
 * {@code GerberDocument}/{@code DrillDocument} geometry.
 */
public class Ipc356Document {

    private String fileName;
    private final Unit unit = Unit.MM;

    // Header parameters.
    private String job;
    private String version;          // P VER (e.g. "IPC-D-356A")
    private String unitsDeclaration; // raw P UNITS value, e.g. "CUST 0" or "SI"
    private final Map<String, String> parameters = new LinkedHashMap<>();

    /** Long-net-name aliases (alias token → full net name), from {@code P NNAME} / Allegro comments. */
    private final Map<String, String> netNameAliases = new LinkedHashMap<>();
    /** Image section names encountered (e.g. "PRIMARY", "PANEL", "2"). */
    private final List<String> images = new ArrayList<>();

    private final List<TestRecord> testRecords = new ArrayList<>();
    private final List<Conductor> conductors = new ArrayList<>();
    private final List<Adjacency> adjacencies = new ArrayList<>();
    private final List<Outline> outlines = new ArrayList<>();
    private final List<String> comments = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private BoundingBox boundingBox;

    public Ipc356Document() {
    }

    public BoundingBox calculateBoundingBox() {
        boundingBox = new BoundingBox();
        for (TestRecord r : testRecords) boundingBox.include(r.getBoundingBox());
        for (Conductor c : conductors) boundingBox.include(c.getBoundingBox());
        for (Outline o : outlines) boundingBox.include(o.getBoundingBox());
        return boundingBox;
    }

    public BoundingBox getBoundingBox() {
        if (boundingBox == null) calculateBoundingBox();
        return boundingBox;
    }

    // ---- mutation (used by the parser) ----

    public void addTestRecord(TestRecord r) { testRecords.add(r); }
    public void addConductor(Conductor c) { conductors.add(c); }
    public void addAdjacency(Adjacency a) { adjacencies.add(a); }
    public void addOutline(Outline o) { outlines.add(o); }
    public void addComment(String c) { comments.add(c); }
    public void addImage(String image) { if (image != null && !images.contains(image)) images.add(image); }

    public void putParameter(String key, String value) { parameters.put(key, value); }

    /** Register a long-net-name alias. The first definition for an alias wins. */
    public void addNetNameAlias(String alias, String fullName) {
        if (alias != null && !alias.isEmpty() && fullName != null && !fullName.isEmpty()) {
            netNameAliases.putIfAbsent(alias, fullName);
        }
    }

    /** Resolve a raw net field through the alias table; returns the input unchanged if not aliased. */
    public String resolveNet(String rawNet) {
        if (rawNet == null) return null;
        return netNameAliases.getOrDefault(rawNet, rawNet);
    }

    /**
     * Record a non-fatal parsing anomaly (missing {@code P UNITS}, an unknown op code, a truncated
     * record, …). Duplicates are collapsed so a recurring problem reports once.
     */
    public void addWarning(String warning) {
        if (warning != null && !warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    // ---- accessors ----

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    /** Always {@link Unit#MM} — IPC-356 coordinates are normalized to mm at parse time. */
    public Unit getUnit() { return unit; }

    public String getJob() { return job; }
    public void setJob(String job) { this.job = job; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getUnitsDeclaration() { return unitsDeclaration; }
    public void setUnitsDeclaration(String unitsDeclaration) { this.unitsDeclaration = unitsDeclaration; }

    public Map<String, String> getParameters() { return parameters; }
    public Map<String, String> getNetNameAliases() { return netNameAliases; }
    public List<String> getImages() { return images; }

    public List<TestRecord> getTestRecords() { return testRecords; }
    public List<Conductor> getConductors() { return conductors; }
    public List<Adjacency> getAdjacencies() { return adjacencies; }
    public List<Outline> getOutlines() { return outlines; }
    public List<String> getComments() { return comments; }
    public List<String> getWarnings() { return warnings; }

    /** Distinct resolved net names across all test records (excludes {@code N/C} / unnamed). */
    public Set<String> getNets() {
        Set<String> nets = new LinkedHashSet<>();
        for (TestRecord r : testRecords) {
            if (r.isConnected() && r.getNetName() != null && !r.getNetName().isEmpty()) {
                nets.add(r.getNetName());
            }
        }
        return nets;
    }

    @Override
    public String toString() {
        return String.format("Ipc356Document[%s, %d test records, %d conductors, %d adjacencies, %d outlines]",
            fileName != null ? fileName : "unnamed",
            testRecords.size(), conductors.size(), adjacencies.size(), outlines.size());
    }
}
