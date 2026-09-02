package io.github.thisccl.j4a.jmx.property;

import java.util.Objects;
import java.util.Optional;

public final class RuntimeClassConstraint {
    private final String propertyClass;
    private final String valueClass;

    public RuntimeClassConstraint(String propertyClass, String valueClass) {
        this.propertyClass = requireText(propertyClass, "property class");
        this.valueClass = valueClass == null ? null : requireText(valueClass, "value class");
    }

    public static RuntimeClassConstraint propertyClass(String propertyClass) {
        return new RuntimeClassConstraint(propertyClass, null);
    }

    public String propertyClass() {
        return propertyClass;
    }

    public Optional<String> valueClass() {
        return Optional.ofNullable(valueClass);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RuntimeClassConstraint)) {
            return false;
        }
        RuntimeClassConstraint that = (RuntimeClassConstraint) other;
        return propertyClass.equals(that.propertyClass) && Objects.equals(valueClass, that.valueClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyClass, valueClass);
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
