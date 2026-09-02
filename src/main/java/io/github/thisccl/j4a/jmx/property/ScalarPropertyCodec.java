package io.github.thisccl.j4a.jmx.property;

import java.util.Objects;
import java.util.Optional;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.StringProperty;

public final class ScalarPropertyCodec {
    public RecursiveValue read(JMeterProperty property) {
        Objects.requireNonNull(property, "property is required");
        RecursiveValue value = RuntimePropertyValueDiscovery.read(property);
        requireScalar(value);
        return value;
    }

    public Optional<JMeterProperty> materialize(String name, RecursiveValue value) {
        String requiredName = requireName(name);
        RecursiveValue requiredValue = Objects.requireNonNull(value, "recursive value is required");
        requireScalar(requiredValue);
        if (requiredValue.presence() == GraphPresence.ABSENT) {
            return Optional.empty();
        }
        return Optional.of(create(requiredName, requiredValue));
    }

    public Optional<JMeterProperty> materialize(
            JMeterProperty observed, RecursiveValue value) {
        JMeterProperty requiredObserved = Objects.requireNonNull(observed, "observed property is required");
        RecursiveValue requiredValue = Objects.requireNonNull(value, "recursive value is required");
        requireScalar(read(requiredObserved));
        requireObservedClass(requiredObserved, requiredValue);
        if (requiredValue.presence() == GraphPresence.ABSENT) {
            return Optional.empty();
        }
        if (isBuiltInClass(requiredObserved.getClass().getName())) {
            return materialize(requiredObserved.getName(), requiredValue);
        }
        JMeterProperty copy = requiredObserved.clone();
        if (copy == requiredObserved || !copy.getClass().equals(requiredObserved.getClass())) {
            throw error("$.property_class", "observed scalar class cannot be cloned safely");
        }
        if (requiredValue.type() == GraphType.STRING) {
            copy.setObjectValue(requiredValue.scalarValue());
        } else {
            throw error("$.property_class", "unsupported observed scalar property class: "
                    + requiredValue.propertyClass());
        }
        return Optional.of(copy);
    }

    private static JMeterProperty create(String name, RecursiveValue value) {
        switch (value.type()) {
            case STRING:
                requireClass(value, StringProperty.class, "string requires StringProperty");
                return new StringProperty(name, (String) value.scalarValue());
            case BOOLEAN:
                requireClass(value, BooleanProperty.class, "boolean requires BooleanProperty");
                return new BooleanProperty(name, ((Boolean) value.scalarValue()).booleanValue());
            case INT:
                requireClass(value, IntegerProperty.class, "int requires IntegerProperty");
                return new IntegerProperty(name, ((Integer) value.scalarValue()).intValue());
            case LONG:
                requireClass(value, LongProperty.class, "long requires LongProperty");
                return new LongProperty(name, ((Long) value.scalarValue()).longValue());
            case FLOAT:
                requireClass(value, FloatProperty.class, "float requires FloatProperty");
                return new FloatProperty(name, ((Float) value.scalarValue()).floatValue());
            case DOUBLE:
                requireClass(value, DoubleProperty.class, "double requires DoubleProperty");
                return new DoubleProperty(name, ((Double) value.scalarValue()).doubleValue());
            case NULL:
                requireClass(value, NullProperty.class, "null requires NullProperty");
                return new NullProperty(name);
            default:
                throw error("$.type", "scalar codec requires a scalar value");
        }
    }

    private static void requireScalar(RecursiveValue value) {
        if (!value.type().isScalar()) {
            throw error("$.type", "scalar codec requires a scalar value");
        }
    }

    private static void requireClass(
            RecursiveValue value, Class<? extends JMeterProperty> expected, String reason) {
        if (!expected.getName().equals(value.propertyClass())) {
            throw error("$.property_class", reason);
        }
    }

    private static void requireObservedClass(
            JMeterProperty observed, RecursiveValue value) {
        if (!observed.getClass().getName().equals(value.propertyClass())) {
            throw error("$.property_class", "property class does not match observed scalar class");
        }
    }

    private static boolean isBuiltInClass(String propertyClass) {
        return StringProperty.class.getName().equals(propertyClass)
                || BooleanProperty.class.getName().equals(propertyClass)
                || IntegerProperty.class.getName().equals(propertyClass)
                || LongProperty.class.getName().equals(propertyClass)
                || FloatProperty.class.getName().equals(propertyClass)
                || DoubleProperty.class.getName().equals(propertyClass)
                || NullProperty.class.getName().equals(propertyClass);
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "property name is required");
        if (name.isEmpty()) {
            throw error("$.property", "property name is required");
        }
        return name;
    }

    private static PropertyGraphRepresentationException error(String path, String reason) {
        return new PropertyGraphRepresentationException(
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE, path, reason);
    }
}
