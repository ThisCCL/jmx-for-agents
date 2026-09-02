package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;

public final class RuntimeStructuredRowWriter {
    public Optional<PropertyWrite> prepare(
            TestElement target,
            PropertyPath path,
            Object submittedValue,
            RuntimeContext runtimeContext) {
        List<PropertyPathSegment> segments = path.segments();
        if (segments.size() != 1
                || segments.get(0).kind() != PropertyPathSegment.Kind.PROPERTY) {
            return Optional.empty();
        }
        JMeterProperty observed = target.getPropertyOrNull(segments.get(0).name());
        if (observed == null) {
            return Optional.empty();
        }
        Optional<StructuredRowShape> shape = RuntimeStructuredRows.observe(
                target, observed, runtimeContext);
        if (!shape.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(PropertyWrite.fromObserved(
                path, shape.get().materialize(submittedValue)));
    }

    public Optional<PropertyWrite> prepare(
            TestElement target,
            PropertyPath path,
            Object submittedValue,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowEvidence correlatedEvidence) {
        if (correlatedEvidence == null) {
            return prepare(target, path, submittedValue, runtimeContext);
        }
        Optional<Map<String, Object>> existing = completeValue(target, path, runtimeContext);
        if (submittedValue instanceof Map
                && existing.isPresent()
                && !rows(existing.get()).isEmpty()
                && existing.get().get("row_type").equals(
                        ((Map<?, ?>) submittedValue).get("row_type"))
                && !correlatedEvidence.rowType().equals(existing.get().get("row_type"))) {
            return prepare(target, path, submittedValue, runtimeContext);
        }
        return correlatedEvidence.prepare(target, path, submittedValue, runtimeContext);
    }

    public Optional<PropertyWrite> prepareAppend(
            TestElement target,
            PropertyPath path,
            Map<String, Object> row,
            RuntimeContext runtimeContext) {
        Optional<Map<String, Object>> value = completeValue(target, path, runtimeContext);
        if (!value.isPresent()) {
            return Optional.empty();
        }
        rows(value.get()).add(copy(row));
        return prepare(target, path, value.get(), runtimeContext);
    }

    public Optional<PropertyWrite> prepareAppend(
            TestElement target,
            PropertyPath path,
            Map<String, Object> row,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowEvidence correlatedEvidence) {
        if (correlatedEvidence == null) {
            return prepareAppend(target, path, row, runtimeContext);
        }
        Map<String, Object> value = existingRowsOrEvidence(
                target, path, runtimeContext, correlatedEvidence);
        rows(value).add(copy(row));
        return correlatedEvidence.prepare(target, path, value, runtimeContext);
    }

    public Optional<PropertyWrite> prepareInsert(
            TestElement target,
            PropertyPath path,
            int index,
            Map<String, Object> row,
            RuntimeContext runtimeContext) {
        Optional<Map<String, Object>> value = completeValue(target, path, runtimeContext);
        if (!value.isPresent()) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = rows(value.get());
        if (index < 0 || index > rows.size()) {
            throw new IllegalArgumentException(
                    "Insert index " + index + " must be from 0 to " + rows.size()
                            + " for property " + path);
        }
        rows.add(index, copy(row));
        return prepare(target, path, value.get(), runtimeContext);
    }

    public Optional<PropertyWrite> prepareInsert(
            TestElement target,
            PropertyPath path,
            int index,
            Map<String, Object> row,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowEvidence correlatedEvidence) {
        if (correlatedEvidence == null) {
            return prepareInsert(target, path, index, row, runtimeContext);
        }
        Map<String, Object> value = existingRowsOrEvidence(
                target, path, runtimeContext, correlatedEvidence);
        List<Map<String, Object>> rows = rows(value);
        if (index < 0 || index > rows.size()) {
            throw new IllegalArgumentException(
                    "Insert index " + index + " must be from 0 to " + rows.size()
                            + " for property " + path);
        }
        rows.add(index, copy(row));
        return correlatedEvidence.prepare(target, path, value, runtimeContext);
    }

    private Map<String, Object> existingRowsOrEvidence(
            TestElement target,
            PropertyPath path,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowEvidence correlatedEvidence) {
        Optional<Map<String, Object>> existing = completeValue(target, path, runtimeContext);
        return existing.isPresent() && !rows(existing.get()).isEmpty()
                ? existing.get() : correlatedEvidence.documentValue();
    }

    public Optional<PropertyWrite> prepareRemove(
            TestElement target,
            PropertyPath path,
            int index,
            RuntimeContext runtimeContext) {
        Optional<Map<String, Object>> value = completeValue(target, path, runtimeContext);
        if (!value.isPresent()) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = rows(value.get());
        if (index < 0 || index >= rows.size()) {
            throw new IllegalArgumentException(
                    "Remove index " + index + " must address an existing row from 0 to "
                            + (rows.size() - 1) + " for property " + path);
        }
        rows.remove(index);
        return prepare(target, path, value.get(), runtimeContext);
    }

    private Optional<Map<String, Object>> completeValue(
            TestElement target, PropertyPath path, RuntimeContext runtimeContext) {
        List<PropertyPathSegment> segments = path.segments();
        if (segments.size() != 1
                || segments.get(0).kind() != PropertyPathSegment.Kind.PROPERTY) {
            return Optional.empty();
        }
        JMeterProperty observed = target.getPropertyOrNull(segments.get(0).name());
        if (observed == null) {
            return Optional.empty();
        }
        Optional<RuntimeStructuredRowDocument.Projection> projection =
                RuntimeStructuredRowDocument.observe(target, observed, runtimeContext);
        if (!projection.isPresent()) {
            return Optional.empty();
        }
        Map<String, Object> value = new LinkedHashMap<String, Object>(projection.get().value());
        value.put("rows", new ArrayList<Map<String, Object>>(rows(projection.get().value())));
        return Optional.of(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> value) {
        return (List<Map<String, Object>>) value.get("rows");
    }

    private static Map<String, Object> copy(Map<String, Object> row) {
        return new LinkedHashMap<String, Object>(row);
    }
}
