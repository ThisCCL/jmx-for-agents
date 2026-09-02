package io.github.thisccl.j4a.jmx.property;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;

final class StructuredRowField {
    private final String name;
    private final GraphType type;
    private final String propertyClass;
    private final boolean required;
    private final boolean defaultPresent;
    private final Object defaultValue;

    StructuredRowField(String name, GraphType type, String propertyClass) {
        this(name, type, propertyClass, true, false, null);
    }

    StructuredRowField(
            String name,
            GraphType type,
            String propertyClass,
            boolean required,
            boolean defaultPresent,
            Object defaultValue) {
        this.name = requireText(name, "row field name");
        this.type = Objects.requireNonNull(type, "row field type is required");
        this.propertyClass = requireText(propertyClass, "row field property class");
        if (!type.isScalar()) {
            throw new IllegalArgumentException("row field type must be scalar");
        }
        if (required == defaultPresent) {
            throw new IllegalArgumentException(
                    "row field default must be present exactly when the field is not required");
        }
        if (defaultPresent && !matches(type, defaultValue)) {
            throw new IllegalArgumentException("row field default does not match type "
                    + type.wireName());
        }
        this.required = required;
        this.defaultPresent = defaultPresent;
        this.defaultValue = defaultValue;
    }

    String name() {
        return name;
    }

    GraphType type() {
        return type;
    }

    String propertyClass() {
        return propertyClass;
    }

    boolean required() {
        return required;
    }

    boolean defaultPresent() {
        return defaultPresent;
    }

    Object defaultValue() {
        return defaultValue;
    }

    boolean accepts(Object value) {
        return matches(type, value);
    }

    Map<String, Object> descriptor() {
        LinkedHashMap<String, Object> descriptor = new LinkedHashMap<String, Object>();
        descriptor.put("name", name);
        descriptor.put("type", type.wireName());
        descriptor.put("required", Boolean.valueOf(required));
        if (defaultPresent) {
            descriptor.put("default", defaultValue);
        }
        return descriptor;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof StructuredRowField)) {
            return false;
        }
        StructuredRowField field = (StructuredRowField) other;
        return name.equals(field.name)
                && type == field.type
                && propertyClass.equals(field.propertyClass)
                && required == field.required
                && defaultPresent == field.defaultPresent
                && Objects.equals(defaultValue, field.defaultValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, propertyClass, required, defaultPresent, defaultValue);
    }

    private static boolean matches(GraphType type, Object value) {
        switch (type) {
            case STRING:
                return value instanceof String;
            case BOOLEAN:
                return value instanceof Boolean;
            case INT:
                return value instanceof Integer;
            case LONG:
                return value instanceof Long;
            case FLOAT:
                return value instanceof Float && Float.isFinite(((Float) value).floatValue());
            case DOUBLE:
                return value instanceof Double && Double.isFinite(((Double) value).doubleValue());
            case NULL:
                return value == null;
            default:
                return false;
        }
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
