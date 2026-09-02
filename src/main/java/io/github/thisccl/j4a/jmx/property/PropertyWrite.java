package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import java.util.Objects;
import java.util.Optional;
import org.apache.jmeter.testelement.property.JMeterProperty;

public final class PropertyWrite {
    private final PropertyPath property;
    private final GraphType type;
    private final RecursiveValue value;
    private final JMeterProperty preparedProperty;

    public PropertyWrite(PropertyPath property, GraphType type, RecursiveValue value) {
        this(property, type, value, null);
    }

    private PropertyWrite(
            PropertyPath property,
            GraphType type,
            RecursiveValue value,
            JMeterProperty preparedProperty) {
        this.property = Objects.requireNonNull(property, "property path is required");
        this.type = Objects.requireNonNull(type, "graph type is required");
        this.value = Objects.requireNonNull(value, "recursive value is required");
        if (type != value.type()) {
            throw new IllegalArgumentException("property type must match recursive value type");
        }
        this.preparedProperty = preparedProperty;
    }

    public static PropertyWrite fromObserved(
            PropertyPath property, JMeterProperty observedProperty) {
        RecursiveValue observed = RuntimePropertyValueDiscovery.read(
                Objects.requireNonNull(observedProperty, "observed property is required"));
        return new PropertyWrite(property, observed.type(), observed, observedProperty);
    }

    public PropertyPath property() {
        return property;
    }

    public GraphType type() {
        return type;
    }

    public RecursiveValue value() {
        return value;
    }

    Optional<JMeterProperty> preparedProperty() {
        return preparedProperty == null
                ? Optional.<JMeterProperty>empty()
                : Optional.of(preparedProperty);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PropertyWrite)) {
            return false;
        }
        PropertyWrite that = (PropertyWrite) other;
        return property.segments().equals(that.property.segments())
                && type == that.type
                && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(property.segments(), type, value);
    }
}
