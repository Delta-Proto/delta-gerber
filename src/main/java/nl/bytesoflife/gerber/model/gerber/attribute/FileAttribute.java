package nl.bytesoflife.gerber.model.gerber.attribute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * File attribute (TF command) from Gerber X2/X3.
 */
public class FileAttribute {

    private final String name;
    private final List<String> values;

    public FileAttribute(String name, List<String> values) {
        this.name = name;
        this.values = new ArrayList<>(values);
    }

    public FileAttribute(String name, String... values) {
        this.name = name;
        this.values = new ArrayList<>();
        Collections.addAll(this.values, values);
    }

    public String getName() {
        return name;
    }

    public List<String> getValues() {
        return Collections.unmodifiableList(values);
    }

    public String getValue(int index) {
        return index < values.size() ? values.get(index) : null;
    }

    public String getFirstValue() {
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public String toString() {
        return String.format("FileAttribute[%s=%s]", name, values);
    }
}
