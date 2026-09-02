package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;

final class StructuredRowMaterializer {
    private StructuredRowMaterializer() {
    }

    static JMeterProperty materialize(StructuredRowShape shape, StructuredRowValue requested) {
        List<TestElement> rows = buildRows(shape, requested);
        JMeterProperty property = shape.reconstruction().replace(
                shape.observedProperty(), rows);
        requireReobserved(shape, projected(shape, requested, rows), property);
        return property;
    }

    private static List<TestElement> buildRows(
            StructuredRowShape shape, StructuredRowValue requested) {
        ArrayList<TestElement> rows = new ArrayList<TestElement>(requested.rows().size());
        for (Map<String, Object> values : requested.rows()) {
            TestElement row = newRow(shape);
            java.util.LinkedHashSet<String> claimed = new java.util.LinkedHashSet<String>();
            for (RuntimeStructuredScalarSetter setter : shape.scalarSetters()) {
                if (values.containsKey(setter.canonicalProperty())) {
                    setter.write(row, values.get(setter.canonicalProperty()));
                    claimed.addAll(setter.footprint());
                }
            }
            for (StructuredRowField field : shape.fields()) {
                if (claimed.contains(field.name()) || !values.containsKey(field.name())) continue;
                RecursiveValue value = field.type() == GraphType.NULL
                        ? RecursiveValue.presentNull(field.propertyClass())
                        : RecursiveValue.scalar(
                                field.type(), field.propertyClass(), values.get(field.name()));
                JMeterProperty property = new ScalarPropertyCodec()
                        .materialize(field.name(), value).get();
                row.setProperty(property);
            }
            rows.add(row);
        }
        return rows;
    }

    private static StructuredRowValue projected(
            StructuredRowShape shape,
            StructuredRowValue requested,
            List<TestElement> rows) {
        ArrayList<Map<String, Object>> values = new ArrayList<Map<String, Object>>(rows.size());
        for (TestElement row : rows) {
            java.util.LinkedHashMap<String, Object> projected =
                    new java.util.LinkedHashMap<String, Object>();
            for (StructuredRowField field : shape.fields()) {
                JMeterProperty property = row.getPropertyOrNull(field.name());
                if (property != null) {
                    projected.put(field.name(),
                            RuntimePropertyValueDiscovery.read(property).scalarValue());
                }
            }
            values.add(projected);
        }
        return new StructuredRowValue(requested.rowType(), shape.fields(), values);
    }

    private static TestElement newRow(StructuredRowShape shape) {
        try {
            return (TestElement) shape.reconstruction().rowConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw error("observed row constructor failed", exception);
        }
    }

    private static void requireReobserved(
            StructuredRowShape expected, StructuredRowValue requested, JMeterProperty property) {
        Optional<StructuredRowShape> observed = RuntimeStructuredRows.observe(
                property, expected.runtimeContext());
        if (!observed.isPresent() && expected.owner() != null) {
            TestElement owner = cloneOwner(expected.owner());
            owner.setProperty(property);
            observed = RuntimeStructuredRows.observe(
                    owner, owner.getPropertyOrNull(property.getName()), expected.runtimeContext());
        }
        boolean exactShape = observed.isPresent()
                && sameReconstruction(expected, observed.get())
                && requested.rowType().equals(observed.get().observedValue().rowType());
        if (!exactShape && requested.rows().isEmpty() && expected.owner() != null) {
            Optional<StructuredRowShape> probe = observeExactEmptyShape(expected);
            exactShape = probe.isPresent()
                    && sameCorrelatedShape(expected, probe.get())
                    && requested.rowType().equals(probe.get().observedValue().rowType());
        }
        if (!observed.isPresent()
                || !requested.rows().equals(observed.get().observedValue().rows())
                || !exactShape) {
            throw error("reconstructed rows did not preserve the runtime-proven projection", null);
        }
    }

    private static boolean sameCorrelatedShape(
            StructuredRowShape expected, StructuredRowShape actual) {
        return expected.rowClass().equals(actual.rowClass())
                && sameFields(expected.fields(), actual.fields())
                && expected.reconstruction().layout() == actual.reconstruction().layout()
                && expected.runtimeFingerprint().equals(actual.runtimeFingerprint());
    }

    private static Optional<StructuredRowShape> observeExactEmptyShape(StructuredRowShape expected) {
        TestElement owner = cloneOwner(expected.owner());
        JMeterProperty probe = owner.getPropertyOrNull(expected.observedProperty().getName());
        if (probe == null) return Optional.empty();
        return RuntimeStructuredRows.observe(
                owner, probe, expected.runtimeContext());
    }

    private static TestElement cloneOwner(TestElement owner) {
        Object clone = owner.clone();
        if (clone == owner || clone == null || clone.getClass() != owner.getClass()) {
            throw error("runtime row owner cannot be cloned safely", null);
        }
        return (TestElement) clone;
    }

    private static boolean sameReconstruction(
            StructuredRowShape expected, StructuredRowShape actual) {
        return expected.outerPropertyClass().equals(actual.outerPropertyClass())
                && expected.outerValueClass().equals(actual.outerValueClass())
                && expected.storagePropertyClass().equals(actual.storagePropertyClass())
                && (!expected.rowWrapperClass().isPresent()
                        || expected.rowWrapperClass().equals(actual.rowWrapperClass()))
                && expected.rowClass().equals(actual.rowClass())
                && sameFields(expected.fields(), actual.fields())
                && expected.reconstruction().layout() == actual.reconstruction().layout()
                && expected.reconstruction().listMutator()
                        .equals(actual.reconstruction().listMutator())
                && expected.runtimeFingerprint().equals(actual.runtimeFingerprint());
    }

    private static boolean sameFields(
            List<StructuredRowField> expected, List<StructuredRowField> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            StructuredRowField left = expected.get(index);
            StructuredRowField right = actual.get(index);
            if (!left.name().equals(right.name()) || left.type() != right.type()
                    || !left.propertyClass().equals(right.propertyClass())) {
                return false;
            }
        }
        return true;
    }

    private static StructuredRowValueException error(String message, Throwable cause) {
        StructuredRowValueException failure = new StructuredRowValueException(message);
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }
}
