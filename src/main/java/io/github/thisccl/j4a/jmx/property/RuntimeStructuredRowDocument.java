package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;

public final class RuntimeStructuredRowDocument {
    private RuntimeStructuredRowDocument() {
    }

    public static Optional<Projection> observe(
            JMeterProperty property, RuntimeContext runtimeContext) {
        return observe(null, property, runtimeContext);
    }

    public static Optional<Projection> observe(
            TestElement owner, JMeterProperty property, RuntimeContext runtimeContext) {
        Optional<StructuredRowShape> shape = RuntimeStructuredRows.observe(
                owner, property, runtimeContext);
        if (!shape.isPresent()) return Optional.empty();
        return Optional.of(project(shape.get()));
    }

    static Projection project(StructuredRowShape shape) {
        StructuredRowValue observed = shape.observedValue();
        LinkedHashMap<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("row_type", observed.rowType());
        ArrayList<Map<String, Object>> rowProperties = new ArrayList<Map<String, Object>>();
        for (StructuredRowField field : observed.rowProperties()) {
            rowProperties.add(field.descriptor());
        }
        value.put("row_properties", rowProperties);
        value.put("rows", observed.rows());
        ArrayList<String> fields = new ArrayList<String>();
        for (StructuredRowField field : shape.fields()) fields.add(field.name());
        return new Projection(observed.rowType(), fields, value);
    }

    static Projection emptyProjection(StructuredRowShape shape) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("row_type", shape.observedValue().rowType());
        ArrayList<Map<String, Object>> descriptors = new ArrayList<Map<String, Object>>();
        ArrayList<String> fields = new ArrayList<String>();
        for (StructuredRowField field : shape.fields()) {
            descriptors.add(field.descriptor());
            fields.add(field.name());
        }
        value.put("row_properties", descriptors);
        value.put("rows", new ArrayList<Map<String, Object>>());
        return new Projection(shape.observedValue().rowType(), fields, value);
    }

    public static final class Projection {
        private final String rowType;
        private final List<String> fields;
        private final Map<String, Object> value;

        private Projection(String rowType, List<String> fields, Map<String, Object> value) {
            this.rowType = rowType;
            this.fields = Collections.unmodifiableList(new ArrayList<String>(fields));
            this.value = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(value));
        }

        public String rowType() { return rowType; }

        public List<String> fields() { return fields; }

        public Map<String, Object> value() { return value; }
    }
}
