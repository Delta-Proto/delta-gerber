package nl.bytesoflife.gerber.model.gerber.attribute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aperture attribute (TA command) from Gerber X2/X3.
 */
public class ApertureAttribute {

    private final String name;
    private final List<String> values;

    public ApertureAttribute(String name, List<String> values) {
        this.name = name;
        this.values = new ArrayList<>(values);
    }

    public String getName() {
        return name;
    }

    public List<String> getValues() {
        return Collections.unmodifiableList(values);
    }

    public String getFirstValue() {
        return values.isEmpty() ? null : values.get(0);
    }

    @Override
    public String toString() {
        return String.format("ApertureAttribute[%s=%s]", name, values);
    }
}
