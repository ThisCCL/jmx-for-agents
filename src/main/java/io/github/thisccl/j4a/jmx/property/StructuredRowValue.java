package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class StructuredRowValue {
    private final String rowType;
    private final List<StructuredRowField> rowProperties;
    private final List<Map<String, Object>> rows;

    StructuredRowValue(
            String rowType,
            List<StructuredRowField> rowProperties,
            List<Map<String, Object>> rows) {
        this.rowType = Objects.requireNonNull(rowType, "row type is required");
        this.rowProperties = Collections.unmodifiableList(
                new ArrayList<StructuredRowField>(rowProperties));
        ArrayList<Map<String, Object>> copy = new ArrayList<Map<String, Object>>(rows.size());
        for (Map<String, Object> row : rows) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<String, Object>(row)));
        }
        this.rows = Collections.unmodifiableList(copy);
    }

    String rowType() {
        return rowType;
    }

    List<StructuredRowField> rowProperties() {
        return rowProperties;
    }

    List<Map<String, Object>> rows() {
        return rows;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StructuredRowValue)) {
            return false;
        }
        StructuredRowValue that = (StructuredRowValue) other;
        return rowType.equals(that.rowType)
                && rowProperties.equals(that.rowProperties)
                && rows.equals(that.rows);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rowType, rowProperties, rows);
    }
}
