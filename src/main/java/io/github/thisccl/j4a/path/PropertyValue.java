package io.github.thisccl.j4a.path;

import java.util.Objects;

public final class PropertyValue {
    private final PropertyValueType type;
    private final Object value;
    private final String text;

    public PropertyValue(PropertyValueType type, Object value, String text) {
        if (type == null) {
            throw new IllegalArgumentException("property value type is required");
        }
        if (text == null) {
            throw new IllegalArgumentException("property value text is required");
        }
        this.type = type;
        this.value = value;
        this.text = text;
    }

    public PropertyValueType type() {
        return type;
    }

    public Object value() {
        return value;
    }

    public String text() {
        return text;
    }

    public static PropertyValue string(String value) {
        return new PropertyValue(PropertyValueType.STRING, value, value);
    }

    public static PropertyValue bool(boolean value) {
        return new PropertyValue(PropertyValueType.BOOLEAN, value, Boolean.toString(value));
    }

    public static PropertyValue integer(int value) {
        return new PropertyValue(PropertyValueType.INT, value, Integer.toString(value));
    }

    public static PropertyValue longValue(long value) {
        return new PropertyValue(PropertyValueType.LONG, value, Long.toString(value));
    }

    public static PropertyValue doubleValue(double value, String text) {
        return new PropertyValue(PropertyValueType.DOUBLE, value, text);
    }

    public static PropertyValue raw(String value) {
        return new PropertyValue(PropertyValueType.RAW, value, value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PropertyValue)) {
            return false;
        }
        PropertyValue that = (PropertyValue) other;
        return type == that.type
                && Objects.equals(value, that.value)
                && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value, text);
    }

    @Override
    public String toString() {
        return "PropertyValue[type=" + type + ", value=" + value + ", text=" + text + "]";
    }
}
