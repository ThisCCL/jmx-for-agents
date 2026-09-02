package io.github.thisccl.j4a.jmx.property;

import java.util.Objects;

public final class TypedScalarMapKey {
    private final GraphType type;
    private final Object value;

    public TypedScalarMapKey(GraphType type, Object value) {
        this.type = Objects.requireNonNull(type, "map key type is required");
        if (!type.isMapKeyScalar()) {
            throw new IllegalArgumentException("map key type must be a non-null scalar");
        }
        this.value = requireScalarValue(type, value);
    }

    public GraphType type() {
        return type;
    }

    public Object value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TypedScalarMapKey)) {
            return false;
        }
        TypedScalarMapKey that = (TypedScalarMapKey) other;
        return type == that.type && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    static Object requireScalarValue(GraphType type, Object value) {
        Objects.requireNonNull(value, "scalar value is required");
        switch (type) {
            case STRING:
                return requireInstance(value, String.class, type);
            case BOOLEAN:
                return requireInstance(value, Boolean.class, type);
            case INT:
                return requireInstance(value, Integer.class, type);
            case LONG:
                return requireInstance(value, Long.class, type);
            case FLOAT:
                Float floatValue = requireInstance(value, Float.class, type);
                if (!Float.isFinite(floatValue.floatValue())) {
                    throw new IllegalArgumentException("value for type 'float' must be finite");
                }
                return floatValue;
            case DOUBLE:
                Double doubleValue = requireInstance(value, Double.class, type);
                if (!Double.isFinite(doubleValue.doubleValue())) {
                    throw new IllegalArgumentException("value for type 'double' must be finite");
                }
                return doubleValue;
            default:
                throw new IllegalArgumentException("type is not a non-null scalar: " + type.wireName());
        }
    }

    private static <T> T requireInstance(Object value, Class<T> expected, GraphType type) {
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException(
                    "value for type '" + type.wireName() + "' must be " + expected.getSimpleName());
        }
        return expected.cast(value);
    }
}
