package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StructuredRowValueNormalizer {
    private StructuredRowValueNormalizer() {
    }

    static StructuredRowValue normalize(StructuredRowShape shape, Object input) {
        Object rowsSource = input;
        if (input instanceof Map<?, ?>) {
            Map<?, ?> wrapper = (Map<?, ?>) input;
            for (Object key : wrapper.keySet()) {
                if (!(key instanceof String)
                        || !Arrays.asList("row_type", "row_properties", "rows").contains(key)) {
                    throw error("rows value has unknown field '" + key + "'");
                }
            }
            if (wrapper.containsKey("row_type")) {
                Object assertedType = wrapper.get("row_type");
                if (!(assertedType instanceof String)) {
                    throw error("rows field 'row_type' must be a string");
                }
                if (!shape.rowClass().equals(assertedType)) {
                    throw error("rows field 'row_type' does not match observed row type '"
                            + shape.rowClass() + "'");
                }
            }
            if (wrapper.containsKey("row_properties")) {
                assertRowProperties(shape, wrapper.get("row_properties"));
            }
            if (!wrapper.containsKey("rows")) {
                throw error("rows value requires field 'rows'");
            }
            rowsSource = wrapper.get("rows");
        }
        if (!(rowsSource instanceof List<?>)) {
            throw error("rows value must be a list or an object containing 'rows'");
        }
        List<?> rows = (List<?>) rowsSource;
        ArrayList<Map<String, Object>> normalized =
                new ArrayList<Map<String, Object>>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            normalized.add(row(shape, rows.get(index), index));
        }
        return new StructuredRowValue(shape.rowClass(), shape.fields(), normalized);
    }

    private static Map<String, Object> row(
            StructuredRowShape shape, Object input, int index) {
        if (!(input instanceof Map<?, ?>)) {
            throw error("rows[" + index + "] must be an object");
        }
        Map<?, ?> source = (Map<?, ?>) input;
        for (RuntimeStructuredScalarSetter setter : shape.scalarSetters()) {
            setter.rejectConflict(source, shape.scalarSetters(), index);
        }
        for (Object key : source.keySet()) {
            if (!(key instanceof String) || field(shape, (String) key) == null) {
                throw error("rows[" + index + "] has unknown field '" + key + "'");
            }
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<String, Object>();
        for (StructuredRowField field : shape.fields()) {
            if (!source.containsKey(field.name())) {
                if (derivedFromSubmittedSetter(shape, source, field.name())) {
                    continue;
                }
                if (field.defaultPresent()) {
                    normalized.put(field.name(), field.defaultValue());
                    continue;
                }
                throw error("rows[" + index + "] requires field '" + field.name() + "'");
            }
            Object value = source.get(field.name());
            if (!field.accepts(value)) {
                throw error("rows[" + index + "]." + field.name() + " must be "
                        + field.type().wireName() + " but was " + kind(value));
            }
            normalized.put(field.name(), value);
        }
        return normalized;
    }

    private static boolean derivedFromSubmittedSetter(
            StructuredRowShape shape, Map<?, ?> submitted, String property) {
        for (RuntimeStructuredScalarSetter setter : shape.scalarSetters()) {
            if (setter.derivedFrom(submitted, property)) return true;
        }
        return false;
    }

    private static void assertRowProperties(StructuredRowShape shape, Object input) {
        if (!(input instanceof List<?>)) {
            throw error("rows field 'row_properties' must be a list");
        }
        List<?> asserted = (List<?>) input;
        if (asserted.size() != shape.fields().size()) {
            throw error("rows field 'row_properties' does not match observed row properties");
        }
        for (int index = 0; index < asserted.size(); index++) {
            if (!shape.fields().get(index).descriptor().equals(asserted.get(index))) {
                throw error("rows field 'row_properties' does not match observed row properties");
            }
        }
    }

    private static StructuredRowField field(StructuredRowShape shape, String name) {
        for (StructuredRowField field : shape.fields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private static String kind(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static StructuredRowValueException error(String message) {
        return new StructuredRowValueException(message);
    }
}
