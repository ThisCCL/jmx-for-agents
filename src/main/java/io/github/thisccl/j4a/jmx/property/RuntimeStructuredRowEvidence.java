package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;

public final class RuntimeStructuredRowEvidence {
    private final String property;
    private final StructuredRowShape shape;

    private RuntimeStructuredRowEvidence(String property, StructuredRowShape shape) {
        this.property = property;
        this.shape = shape;
    }

    public static Optional<RuntimeStructuredRowEvidence> observe(
            TestElement owner, String property, RuntimeContext runtimeContext) {
        JMeterProperty observed = owner.getPropertyOrNull(property);
        if (observed == null) {
            return Optional.empty();
        }
        Optional<StructuredRowShape> shape = RuntimeStructuredRows.observe(owner, observed, runtimeContext);
        return shape.isPresent()
                ? Optional.of(new RuntimeStructuredRowEvidence(property, shape.get()))
                : Optional.<RuntimeStructuredRowEvidence>empty();
    }

    public String property() {
        return property;
    }

    public String rowType() {
        return shape.observedValue().rowType();
    }

    public List<String> rowProperties() {
        ArrayList<String> fields = new ArrayList<String>();
        for (StructuredRowField field : shape.fields()) {
            fields.add(field.name());
        }
        return Collections.unmodifiableList(fields);
    }

    public String reconstructionShape() {
        return shape.reconstruction().layout() == StructuredRowReconstruction.Layout.DIRECT
                ? "direct" : "wrapped";
    }

    public boolean containsScalar(Object sentinel) {
        for (Map<String, Object> row : shape.observedValue().rows()) {
            if (row.containsValue(sentinel)) {
                return true;
            }
        }
        return false;
    }

    public RuntimeStructuredRowEvidence withScalarSetter(
            String canonicalProperty,
            String setterName,
            Class<?> inputType,
            List<String> footprint) {
        return new RuntimeStructuredRowEvidence(
                property, shape.withScalarSetter(
                        canonicalProperty, setterName, inputType, footprint));
    }

    public Optional<RuntimeStructuredRowDocument.Projection> refineEmptyDocument(
            TestElement target, JMeterProperty property, RuntimeContext runtimeContext) {
        if (!this.property.equals(property.getName())
                || !shape.runtimeContext().equals(runtimeContext)) {
            return Optional.empty();
        }
        Optional<StructuredRowShape> targetShape = RuntimeStructuredRows.observe(
                target, property, runtimeContext);
        if (!targetShape.isPresent()
                || !targetShape.get().observedValue().rows().isEmpty()
                || !compatibleTarget(targetShape.get())) {
            return Optional.empty();
        }
        return Optional.of(RuntimeStructuredRowDocument.emptyProjection(shape));
    }

    Map<String, Object> documentValue() {
        LinkedHashMap<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("row_type", rowType());
        ArrayList<Map<String, Object>> descriptors = new ArrayList<Map<String, Object>>();
        for (StructuredRowField field : shape.fields()) {
            descriptors.add(field.descriptor());
        }
        value.put("row_properties", descriptors);
        value.put("rows", new ArrayList<Map<String, Object>>());
        return value;
    }

    Optional<PropertyWrite> prepare(
            TestElement target,
            PropertyPath path,
            Object submittedValue,
            RuntimeContext runtimeContext) {
        List<PropertyPathSegment> segments = path.segments();
        if (segments.size() != 1
                || segments.get(0).kind() != PropertyPathSegment.Kind.PROPERTY
                || !property.equals(segments.get(0).name())
                || !shape.runtimeContext().equals(runtimeContext)) {
            return Optional.empty();
        }
        JMeterProperty targetProperty = target.getPropertyOrNull(property);
        if (targetProperty == null) {
            return Optional.empty();
        }
        Optional<StructuredRowShape> targetShape = RuntimeStructuredRows.observe(
                target, targetProperty, runtimeContext);
        if (!targetShape.isPresent() || !compatibleTarget(targetShape.get())) {
            return Optional.empty();
        }
        StructuredRowShape writeShape = targetShape.get().sameRowClass(shape)
                ? targetShape.get().withSetterEvidence(shape) : shape;
        return Optional.of(PropertyWrite.fromObserved(
                path, writeShape.materialize(submittedValue)));
    }

    private boolean compatibleTarget(StructuredRowShape candidate) {
        return shape.outerPropertyClass().equals(candidate.outerPropertyClass())
                && shape.outerValueClass().equals(candidate.outerValueClass())
                && shape.storagePropertyClass().equals(candidate.storagePropertyClass())
                && shape.reconstruction().layout() == candidate.reconstruction().layout()
                && shape.runtimeFingerprint().equals(candidate.runtimeFingerprint());
    }
}
