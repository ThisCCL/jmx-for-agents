package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;

final class StructuredRowShape {
    private final String outerPropertyClass;
    private final String outerValueClass;
    private final String storagePropertyClass;
    private final String rowWrapperClass;
    private final String rowClass;
    private final List<StructuredRowField> fields;
    private final boolean emptyEvidence;
    private final StructuredRowReconstruction reconstruction;
    private final RuntimeContext runtimeContext;
    private final StructuredRowValue observedValue;
    private final JMeterProperty observedProperty;
    private final TestElement owner;
    private final List<RuntimeStructuredScalarSetter> scalarSetters;

    StructuredRowShape(
            RuntimeRowObservation observation,
            RuntimeContext runtimeContext,
            StructuredRowValue observedValue,
            JMeterProperty observedProperty) {
        this(observation, runtimeContext, observedValue, observedProperty, null);
    }

    StructuredRowShape(
            RuntimeRowObservation observation,
            RuntimeContext runtimeContext,
            StructuredRowValue observedValue,
            JMeterProperty observedProperty,
            TestElement owner) {
        RuntimeRowObservation required = Objects.requireNonNull(
                observation, "row observation is required");
        this.outerPropertyClass = required.outerPropertyClass();
        this.outerValueClass = required.outerValueClass();
        this.storagePropertyClass = required.storagePropertyClass();
        this.rowWrapperClass = required.rowWrapperClass().orElse(null);
        this.rowClass = required.rowClass();
        this.fields = Collections.unmodifiableList(
                new ArrayList<StructuredRowField>(required.fields()));
        this.emptyEvidence = required.emptyEvidence();
        this.reconstruction = required.reconstruction();
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtime context is required");
        this.observedValue = Objects.requireNonNull(observedValue, "observed rows are required");
        this.observedProperty = Objects.requireNonNull(
                observedProperty, "observed property is required");
        this.owner = owner;
        this.scalarSetters = Collections.emptyList();
    }

    private StructuredRowShape(
            StructuredRowShape source,
            List<StructuredRowField> fields,
            List<RuntimeStructuredScalarSetter> scalarSetters) {
        this.outerPropertyClass = source.outerPropertyClass;
        this.outerValueClass = source.outerValueClass;
        this.storagePropertyClass = source.storagePropertyClass;
        this.rowWrapperClass = source.rowWrapperClass;
        this.rowClass = source.rowClass;
        this.fields = Collections.unmodifiableList(new ArrayList<StructuredRowField>(fields));
        this.emptyEvidence = source.emptyEvidence;
        this.reconstruction = source.reconstruction;
        this.runtimeContext = source.runtimeContext;
        this.observedValue = source.observedValue;
        this.observedProperty = source.observedProperty;
        this.owner = source.owner;
        this.scalarSetters = Collections.unmodifiableList(
                new ArrayList<RuntimeStructuredScalarSetter>(scalarSetters));
    }

    String outerPropertyClass() {
        return outerPropertyClass;
    }

    String outerValueClass() {
        return outerValueClass;
    }

    String storagePropertyClass() {
        return storagePropertyClass;
    }

    Optional<String> rowWrapperClass() {
        return Optional.ofNullable(rowWrapperClass);
    }

    String rowClass() {
        return rowClass;
    }

    List<StructuredRowField> fields() {
        return fields;
    }

    boolean emptyEvidence() {
        return emptyEvidence;
    }

    StructuredRowReconstruction reconstruction() {
        return reconstruction;
    }

    RuntimeFingerprint runtimeFingerprint() {
        return runtimeContext.fingerprint();
    }

    StructuredRowValue observedValue() {
        return observedValue;
    }

    StructuredRowValue normalize(Object input) {
        return StructuredRowValueNormalizer.normalize(this, input);
    }

    JMeterProperty materialize(Object input) {
        return StructuredRowMaterializer.materialize(this, normalize(input));
    }

    RuntimeContext runtimeContext() {
        return runtimeContext;
    }

    JMeterProperty observedProperty() {
        return observedProperty;
    }

    TestElement owner() {
        return owner;
    }

    StructuredRowShape withScalarSetter(
            String canonicalProperty,
            String setterName,
            Class<?> inputType,
            List<String> footprint) {
        requireField(canonicalProperty);
        for (String property : footprint) requireField(property);
        if (footprint.size() < 2) return this;
        for (int index = 1; index < footprint.size(); index++) {
            if (runtimeSchemaOwns(footprint.get(index))) return this;
        }
        RuntimeStructuredScalarSetter setter = RuntimeStructuredScalarSetter.prove(
                reconstruction.rowConstructor().getDeclaringClass(), canonicalProperty,
                setterName, inputType, footprint);
        if (!setter.declaredBy(reconstruction.rowConstructor().getDeclaringClass())) return this;
        ArrayList<RuntimeStructuredScalarSetter> setters =
                new ArrayList<RuntimeStructuredScalarSetter>(scalarSetters);
        setters.add(setter);
        return new StructuredRowShape(this, fields, setters);
    }

    List<RuntimeStructuredScalarSetter> scalarSetters() {
        return scalarSetters;
    }

    boolean sameRowClass(StructuredRowShape other) {
        return rowClass.equals(other.rowClass);
    }

    StructuredRowShape withSetterEvidence(StructuredRowShape evidence) {
        if (evidence.scalarSetters.isEmpty()) return this;
        ArrayList<StructuredRowField> merged = new ArrayList<StructuredRowField>(fields);
        for (RuntimeStructuredScalarSetter setter : evidence.scalarSetters) {
            int insertion = fieldIndex(merged, setter.canonicalProperty()) + 1;
            for (String property : setter.footprint()) {
                if (fieldIndex(merged, property) >= 0) continue;
                StructuredRowField field = evidence.field(property);
                if (field == null) {
                    throw new IllegalArgumentException(
                            "runtime scalar setter footprint names unknown field '" + property + "'");
                }
                merged.add(insertion++, field);
            }
        }
        return new StructuredRowShape(this, merged, evidence.scalarSetters);
    }

    private StructuredRowField field(String property) {
        for (StructuredRowField field : fields) {
            if (field.name().equals(property)) return field;
        }
        return null;
    }

    private static int fieldIndex(List<StructuredRowField> fields, String property) {
        for (int index = 0; index < fields.size(); index++) {
            if (fields.get(index).name().equals(property)) return index;
        }
        return -1;
    }

    private void requireField(String property) {
        for (StructuredRowField field : fields) {
            if (field.name().equals(property)) return;
        }
        throw new IllegalArgumentException(
                "runtime scalar setter footprint names unknown field '" + property + "'");
    }

    private boolean runtimeSchemaOwns(String property) {
        try {
            TestElement row = (TestElement) reconstruction.rowConstructor().newInstance();
            return row.getSchema().getProperties().containsKey(property);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            return true;
        }
    }
}
