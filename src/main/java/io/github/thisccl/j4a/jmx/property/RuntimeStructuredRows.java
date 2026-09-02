package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;

final class RuntimeStructuredRows {
    private RuntimeStructuredRows() {
    }

    static Optional<StructuredRowShape> observe(
            JMeterProperty outerProperty, RuntimeContext runtimeContext) {
        return observe(null, outerProperty, runtimeContext);
    }

    static Optional<StructuredRowShape> observe(
            TestElement owner, JMeterProperty outerProperty, RuntimeContext runtimeContext) {
        Object outerValue = outerProperty.getObjectValue();
        if (outerValue instanceof TestElement) {
            return observeWrapped(owner, outerProperty, (TestElement) outerValue, runtimeContext);
        }
        if (outerProperty instanceof MultiProperty && !(outerProperty instanceof MapProperty)) {
            return observeDirect(owner, (MultiProperty) outerProperty, runtimeContext);
        }
        return Optional.empty();
    }

    private static Optional<StructuredRowShape> observeWrapped(
            TestElement owner,
            JMeterProperty outerProperty,
            TestElement outer,
            RuntimeContext runtimeContext) {
        List<Storage> storages = storages(outer);
        List<Storage> populated = new ArrayList<Storage>();
        for (Storage storage : storages) {
            if (!storage.rows.isEmpty()) {
                populated.add(storage);
            }
        }
        if (populated.size() > 1 || (populated.isEmpty() && storages.size() != 1)) {
            return Optional.empty();
        }
        Storage storage = populated.isEmpty() ? storages.get(0) : populated.get(0);
        RowEvidence evidence = storage.rows.isEmpty()
                ? emptyEvidence(outer, (MultiProperty) storage.property).orElse(null)
                : populatedEvidence(storage).orElse(null);
        if (evidence == null) {
            return Optional.empty();
        }
        Optional<StructuredRowReconstruction> reconstruction =
                RuntimeRowMetadataProof.reconstruction(outer, evidence.rowClass);
        if (!reconstruction.isPresent()) {
            return Optional.empty();
        }
        RuntimeRowObservation observation = new RuntimeRowObservation(
                outerProperty.getClass().getName(),
                outer.getClass().getName(),
                storage.property.getClass().getName(),
                evidence.wrapperClass,
                evidence.rowClass.getName(),
                evidence.fields,
                storage.rows.isEmpty(),
                reconstruction.get());
        return Optional.of(new StructuredRowShape(
                observation,
                runtimeContext,
                new StructuredRowValue(evidence.rowClass.getName(), evidence.fields, evidence.values),
                outerProperty,
                owner));
    }

    private static Optional<StructuredRowShape> observeDirect(
            TestElement owner, MultiProperty outerProperty, RuntimeContext runtimeContext) {
        List<JMeterProperty> rows = rows(outerProperty);
        if (rows.isEmpty()) {
            return owner == null
                    ? Optional.<StructuredRowShape>empty()
                    : observeEmptyDirect(owner, outerProperty, runtimeContext);
        }
        RowEvidence evidence = populatedEvidence(new Storage(outerProperty, rows)).orElse(null);
        if (evidence == null) {
            return Optional.empty();
        }
        Optional<StructuredRowReconstruction> reconstruction =
                RuntimeRowMetadataProof.directReconstruction(evidence.rowClass, rows.get(0));
        if (!reconstruction.isPresent()) {
            return Optional.empty();
        }
        RuntimeRowObservation observation = new RuntimeRowObservation(
                outerProperty.getClass().getName(),
                outerProperty.getObjectValue().getClass().getName(),
                outerProperty.getClass().getName(),
                evidence.wrapperClass,
                evidence.rowClass.getName(),
                evidence.fields,
                false,
                reconstruction.get());
        return Optional.of(new StructuredRowShape(
                observation,
                runtimeContext,
                new StructuredRowValue(evidence.rowClass.getName(), evidence.fields, evidence.values),
                outerProperty,
                owner));
    }

    private static Optional<StructuredRowShape> observeEmptyDirect(
            TestElement owner, MultiProperty outerProperty, RuntimeContext runtimeContext) {
        Optional<RuntimeEmptyDirectRowProof.Evidence> proof =
                RuntimeEmptyDirectRowProof.observe(owner, outerProperty);
        if (!proof.isPresent()) {
            return Optional.empty();
        }
        RuntimeEmptyDirectRowProof.Evidence evidence = proof.get();
        RuntimeRowObservation observation = new RuntimeRowObservation(
                outerProperty.getClass().getName(),
                outerProperty.getObjectValue().getClass().getName(),
                outerProperty.getClass().getName(),
                evidence.wrapperClass(),
                evidence.rowClass().getName(),
                evidence.fields(),
                true,
                evidence.reconstruction());
        return Optional.of(new StructuredRowShape(
                observation,
                runtimeContext,
                new StructuredRowValue(
                        evidence.rowClass().getName(), evidence.fields(),
                        Collections.<Map<String, Object>>emptyList()),
                outerProperty,
                owner));
    }

    private static List<Storage> storages(TestElement outer) {
        ArrayList<Storage> result = new ArrayList<Storage>();
        PropertyIterator iterator = outer.propertyIterator();
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            if (property instanceof MultiProperty) {
                result.add(new Storage(property, rows((MultiProperty) property)));
            }
        }
        return result;
    }

    private static List<JMeterProperty> rows(MultiProperty property) {
        ArrayList<JMeterProperty> rows = new ArrayList<JMeterProperty>();
        PropertyIterator children = property.iterator();
        while (children.hasNext()) {
            rows.add(children.next());
        }
        return rows;
    }

    private static Optional<RowEvidence> populatedEvidence(Storage storage) {
        Class<?> rowClass = null;
        String wrapperClass = null;
        List<StructuredRowField> fields = null;
        ArrayList<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (JMeterProperty wrapper : storage.rows) {
            Object object = wrapper.getObjectValue();
            if (!(object instanceof TestElement)) {
                return Optional.empty();
            }
            TestElement row = (TestElement) object;
            List<StructuredRowField> rowFields = observedFields(row);
            if (rowFields.isEmpty()
                    || (rowClass != null && rowClass != row.getClass())
                    || (wrapperClass != null && !wrapperClass.equals(wrapper.getClass().getName()))
                    || (fields != null && !sameFields(fields, rowFields))) {
                return Optional.empty();
            }
            rowClass = row.getClass();
            wrapperClass = wrapper.getClass().getName();
            fields = rowFields;
            values.add(observedValues(row, fields));
        }
        List<StructuredRowField> pristine = RuntimePristineRowDefaults.prove(rowClass, fields)
                .orElse(null);
        return Optional.of(new RowEvidence(
                rowClass, wrapperClass,
                pristine == null ? fields : pristine,
                values));
    }

    private static Optional<RowEvidence> emptyEvidence(
            TestElement outer, MultiProperty observedStorage) {
        Optional<RuntimeRowMetadataProof.EmptyRowEvidence> evidence =
                RuntimeRowMetadataProof.emptyEvidence(outer, observedStorage);
        return evidence.isPresent()
                ? Optional.of(new RowEvidence(
                        evidence.get().rowClass(), null, evidence.get().fields(),
                        Collections.<Map<String, Object>>emptyList()))
                : Optional.<RowEvidence>empty();
    }

    private static List<StructuredRowField> observedFields(TestElement row) {
        ArrayList<StructuredRowField> fields = new ArrayList<StructuredRowField>();
        PropertyIterator iterator = row.propertyIterator();
        while (iterator.hasNext()) {
            JMeterProperty property = iterator.next();
            RecursiveValue value = RuntimePropertyValueDiscovery.read(property);
            if (!value.type().isScalar()) {
                return Collections.emptyList();
            }
            fields.add(new StructuredRowField(
                    property.getName(), value.type(), property.getClass().getName()));
        }
        return fields;
    }

    private static Map<String, Object> observedValues(
            TestElement row, List<StructuredRowField> fields) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        for (StructuredRowField field : fields) {
            values.put(field.name(), RuntimePropertyValueDiscovery.read(
                    row.getProperty(field.name())).scalarValue());
        }
        return values;
    }

    private static boolean sameFields(
            List<StructuredRowField> left, List<StructuredRowField> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            StructuredRowField first = left.get(index);
            StructuredRowField second = right.get(index);
            if (!first.name().equals(second.name()) || first.type() != second.type()
                    || !first.propertyClass().equals(second.propertyClass())) return false;
        }
        return true;
    }

    private static final class Storage {
        private final JMeterProperty property;
        private final List<JMeterProperty> rows;

        private Storage(JMeterProperty property, List<JMeterProperty> rows) {
            this.property = property;
            this.rows = rows;
        }
    }

    private static final class RowEvidence {
        private final Class<?> rowClass;
        private final String wrapperClass;
        private final List<StructuredRowField> fields;
        private final List<Map<String, Object>> values;

        private RowEvidence(Class<?> rowClass, String wrapperClass,
                List<StructuredRowField> fields, List<Map<String, Object>> values) {
            this.rowClass = rowClass;
            this.wrapperClass = wrapperClass;
            this.fields = fields;
            this.values = values;
        }
    }
}
