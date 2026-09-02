package io.github.thisccl.j4a.jmx.property;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestElementSchema;
import org.apache.jmeter.testelement.schema.BooleanPropertyDescriptor;
import org.apache.jmeter.testelement.schema.DoublePropertyDescriptor;
import org.apache.jmeter.testelement.schema.FloatPropertyDescriptor;
import org.apache.jmeter.testelement.schema.IntegerPropertyDescriptor;
import org.apache.jmeter.testelement.schema.LongPropertyDescriptor;
import org.apache.jmeter.testelement.schema.PropertyDescriptor;
import org.apache.jmeter.testelement.schema.StringPropertyDescriptor;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;

final class RuntimePristineRowDefaults {
    private RuntimePristineRowDefaults() {
    }

    static Optional<List<StructuredRowField>> prove(Class<?> rowClass) {
        if (!TestElement.class.isAssignableFrom(rowClass)
                || rowClass.isInterface() || Modifier.isAbstract(rowClass.getModifiers())) {
            return Optional.empty();
        }
        try {
            Constructor<?> constructor = rowClass.getConstructor();
            return prove((TestElement) constructor.newInstance());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return Optional.empty();
        }
    }

    static Optional<List<StructuredRowField>> prove(TestElement pristine) {
        try {
            List<StructuredRowField> fields = optionalFields(pristine);
            if (fields.isEmpty()) {
                return Optional.empty();
            }
            materializeDefaults(pristine, fields);
            JMeterProperty envelope = new TestElementProperty("runtime.pristine.row", pristine);
            Object loaded = OpaqueEnvelope.load(OpaqueEnvelope.save(envelope));
            Object loadedValue = loaded instanceof JMeterProperty
                    ? ((JMeterProperty) loaded).getObjectValue() : null;
            return loadedValue != null && loadedValue.getClass() == pristine.getClass()
                    && preserves((TestElement) loadedValue, fields)
                    ? Optional.of(fields) : Optional.<List<StructuredRowField>>empty();
        } catch (IOException | RuntimeException | LinkageError exception) {
            return Optional.empty();
        }
    }

    static Optional<List<StructuredRowField>> prove(
            Class<?> rowClass, List<StructuredRowField> observed) {
        List<StructuredRowField> pristine = prove(rowClass).orElse(null);
        if (pristine == null || pristine.size() != observed.size()) {
            return Optional.empty();
        }
        ArrayList<StructuredRowField> ordered =
                new ArrayList<StructuredRowField>(observed.size());
        for (StructuredRowField field : observed) {
            StructuredRowField matched = matching(field, pristine);
            if (matched == null) {
                return Optional.empty();
            }
            ordered.add(new StructuredRowField(
                    field.name(), field.type(), field.propertyClass(),
                    false, true, matched.defaultValue()));
        }
        return Optional.<List<StructuredRowField>>of(ordered);
    }

    private static List<StructuredRowField> optionalFields(TestElement row) {
        ArrayList<StructuredRowField> fields = new ArrayList<StructuredRowField>();
        for (Map.Entry<String, PropertyDescriptor<?, ?>> entry : descriptors(row).entrySet()) {
            JMeterProperty persisted = row.getPropertyOrNull(entry.getKey());
            RecursiveValue persistedValue = persisted == null
                    ? null : RuntimePropertyValueDiscovery.read(persisted);
            GraphType type = persistedValue == null
                    ? type(entry.getValue()) : persistedValue.type();
            Object value = persistedValue == null
                    ? defaultValue(entry.getValue(), row) : persistedValue.scalarValue();
            if (type == null || !matches(type, value)) {
                return Collections.emptyList();
            }
            String propertyClass = persisted == null
                    ? propertyClass(type) : persisted.getClass().getName();
            fields.add(new StructuredRowField(
                    entry.getKey(), type, propertyClass, false, true, value));
        }
        return fields;
    }

    private static Map<String, PropertyDescriptor<?, ?>> descriptors(TestElement row) {
        java.util.LinkedHashMap<String, PropertyDescriptor<?, ?>> descriptors =
                new java.util.LinkedHashMap<String, PropertyDescriptor<?, ?>>();
        Map<String, PropertyDescriptor<?, ?>> base = TestElementSchema.INSTANCE.getProperties();
        for (Map.Entry<String, PropertyDescriptor<?, ?>> entry
                : row.getSchema().getProperties().entrySet()) {
            if (!base.containsKey(entry.getKey())) {
                descriptors.put(entry.getKey(), entry.getValue());
            }
        }
        return descriptors;
    }

    private static Object defaultValue(PropertyDescriptor<?, ?> descriptor, TestElement row) {
        if (descriptor instanceof StringPropertyDescriptor) {
            return ((StringPropertyDescriptor<?>) descriptor).get(row);
        }
        if (descriptor instanceof BooleanPropertyDescriptor) {
            return Boolean.valueOf(((BooleanPropertyDescriptor<?>) descriptor).get(row));
        }
        if (descriptor instanceof IntegerPropertyDescriptor) {
            return Integer.valueOf(((IntegerPropertyDescriptor<?>) descriptor).get(row));
        }
        if (descriptor instanceof LongPropertyDescriptor) {
            return Long.valueOf(((LongPropertyDescriptor<?>) descriptor).get(row));
        }
        if (descriptor instanceof FloatPropertyDescriptor) {
            return Float.valueOf(((FloatPropertyDescriptor<?>) descriptor).get(row));
        }
        if (descriptor instanceof DoublePropertyDescriptor) {
            return Double.valueOf(((DoublePropertyDescriptor<?>) descriptor).get(row));
        }
        return null;
    }

    private static void materializeDefaults(
            TestElement row, List<StructuredRowField> fields) {
        ScalarPropertyCodec codec = new ScalarPropertyCodec();
        for (StructuredRowField field : fields) {
            RecursiveValue value = field.type() == GraphType.NULL
                    ? RecursiveValue.presentNull(field.propertyClass())
                    : RecursiveValue.scalar(
                            field.type(), field.propertyClass(), field.defaultValue());
            row.setProperty(codec.materialize(field.name(), value).get());
        }
    }

    private static boolean preserves(
            TestElement loaded, List<StructuredRowField> fields) {
        for (StructuredRowField field : fields) {
            JMeterProperty property = loaded.getPropertyOrNull(field.name());
            if (property == null || !property.getClass().getName().equals(field.propertyClass())) {
                return false;
            }
            RecursiveValue value = RuntimePropertyValueDiscovery.read(property);
            if (value.type() != field.type()
                    || !java.util.Objects.equals(value.scalarValue(), field.defaultValue())) {
                return false;
            }
        }
        return true;
    }

    private static StructuredRowField matching(
            StructuredRowField observed, List<StructuredRowField> pristine) {
        StructuredRowField matched = null;
        for (StructuredRowField candidate : pristine) {
            if (candidate.name().equals(observed.name())
                    && candidate.type() == observed.type()
                    && candidate.propertyClass().equals(observed.propertyClass())) {
                if (matched != null) {
                    return null;
                }
                matched = candidate;
            }
        }
        return matched;
    }

    private static GraphType type(PropertyDescriptor<?, ?> descriptor) {
        if (descriptor instanceof StringPropertyDescriptor) return GraphType.STRING;
        if (descriptor instanceof BooleanPropertyDescriptor) return GraphType.BOOLEAN;
        if (descriptor instanceof IntegerPropertyDescriptor) return GraphType.INT;
        if (descriptor instanceof LongPropertyDescriptor) return GraphType.LONG;
        if (descriptor instanceof FloatPropertyDescriptor) return GraphType.FLOAT;
        if (descriptor instanceof DoublePropertyDescriptor) return GraphType.DOUBLE;
        return null;
    }

    private static String propertyClass(GraphType type) {
        switch (type) {
            case STRING: return StringProperty.class.getName();
            case BOOLEAN: return BooleanProperty.class.getName();
            case INT: return IntegerProperty.class.getName();
            case LONG: return LongProperty.class.getName();
            case FLOAT: return FloatProperty.class.getName();
            case DOUBLE: return DoubleProperty.class.getName();
            default: throw new IllegalArgumentException("unsupported row scalar type: " + type);
        }
    }

    private static boolean matches(GraphType type, Object value) {
        switch (type) {
            case NULL: return value == null;
            case STRING: return value instanceof String;
            case BOOLEAN: return value instanceof Boolean;
            case INT: return value instanceof Integer;
            case LONG: return value instanceof Long;
            case FLOAT: return value instanceof Float && Float.isFinite(((Float) value).floatValue());
            case DOUBLE: return value instanceof Double && Double.isFinite(((Double) value).doubleValue());
            default: return false;
        }
    }
}
