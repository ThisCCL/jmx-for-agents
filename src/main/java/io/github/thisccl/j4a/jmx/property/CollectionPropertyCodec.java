package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;

public final class CollectionPropertyCodec {
    private final ScalarPropertyCodec scalarCodec = new ScalarPropertyCodec();

    public RecursiveValue read(MultiProperty property) {
        MultiProperty required = requireCollection(property);
        RecursiveValue value = RuntimePropertyValueDiscovery.read(required);
        requireCollectionValue(value);
        return value;
    }

    public Optional<JMeterProperty> materialize(String name, RecursiveValue value) {
        String requiredName = requireName(name);
        RecursiveValue requiredValue = Objects.requireNonNull(value, "recursive value is required");
        requireCollectionValue(requiredValue);
        if (requiredValue.presence() == GraphPresence.ABSENT) {
            return Optional.empty();
        }
        requireConcreteCollectionClass(requiredValue);
        List<JMeterProperty> items = buildItems(requiredValue.items(), Collections.<JMeterProperty>emptyList());
        CollectionProperty property = new CollectionProperty(
                requiredName, Collections.<JMeterProperty>emptyList());
        addItems(property, items);
        return Optional.<JMeterProperty>of(property);
    }

    public Optional<JMeterProperty> materialize(
            MultiProperty observed, RecursiveValue value) {
        MultiProperty requiredObserved = requireCollection(observed);
        RecursiveValue requiredValue = Objects.requireNonNull(value, "recursive value is required");
        requireCollectionValue(requiredValue);
        if (!requiredObserved.getClass().getName().equals(requiredValue.propertyClass())) {
            throw error("$.property_class", "property class does not match observed collection class");
        }
        if (requiredValue.presence() == GraphPresence.ABSENT) {
            return Optional.empty();
        }
        List<JMeterProperty> items = buildItems(requiredValue.items(), items(requiredObserved));
        JMeterProperty cloned = requiredObserved.clone();
        if (cloned == requiredObserved
                || !cloned.getClass().equals(requiredObserved.getClass())
                || !(cloned instanceof MultiProperty)) {
            throw error("$.property_class", "observed collection class cannot be cloned safely");
        }
        MultiProperty property = (MultiProperty) cloned;
        property.clear();
        addItems(property, items);
        return Optional.of(cloned);
    }

    private List<JMeterProperty> buildItems(
            List<RecursiveValue> requested, List<JMeterProperty> observed) {
        List<JMeterProperty> items = new ArrayList<JMeterProperty>(requested.size());
        for (int index = 0; index < requested.size(); index++) {
            RecursiveValue value = requested.get(index);
            JMeterProperty template = index < observed.size() ? observed.get(index) : null;
            items.add(materializeItem(index, value, template));
        }
        return items;
    }

    private JMeterProperty materializeItem(
            int index, RecursiveValue value, JMeterProperty observed) {
        String path = "$.items[" + index + "]";
        if (value.presence() == GraphPresence.ABSENT) {
            throw error(path + ".presence", "collection items must be present");
        }
        if (value.type() == GraphType.COLLECTION) {
            if (observed instanceof MultiProperty
                    && !(observed instanceof MapProperty)
                    && observed.getClass().getName().equals(value.propertyClass())) {
                return materialize((MultiProperty) observed, value).get();
            }
            return materialize(Integer.toString(index), value).get();
        }
        if (value.type() == GraphType.ELEMENT) {
            if (!(observed instanceof TestElementProperty)
                    || !observed.getClass().getName().equals(value.propertyClass())) {
                throw error(path + ".property_class",
                        "element collection items require an observed runtime row class");
            }
            return new TestElementPropertyCodec().materialize(
                    (TestElementProperty) observed, value).get();
        }
        if (value.type().isScalar()) {
            if (observed != null
                    && observed.getClass().getName().equals(value.propertyClass())) {
                return scalarCodec.materialize(observed, value).get();
            }
            return scalarCodec.materialize(itemName(value, index), value).get();
        }
        throw error(path + ".type", "collection item codec is not implemented for "
                + value.type().wireName());
    }

    private static String itemName(RecursiveValue value, int index) {
        Object scalar = value.scalarValue();
        return scalar == null ? Integer.toString(index) : Integer.toString(scalar.hashCode());
    }

    private static MultiProperty requireCollection(MultiProperty property) {
        MultiProperty required = Objects.requireNonNull(property, "collection property is required");
        if (required instanceof MapProperty) {
            throw error("$.type", "map property is not an ordered collection");
        }
        return required;
    }

    private static void requireCollectionValue(RecursiveValue value) {
        if (value.type() != GraphType.COLLECTION) {
            throw error("$.type", "collection codec requires a collection value");
        }
    }

    private static void requireConcreteCollectionClass(RecursiveValue value) {
        if (!CollectionProperty.class.getName().equals(value.propertyClass())) {
            throw error("$.property_class", "collection materialization requires CollectionProperty");
        }
    }

    private static List<JMeterProperty> items(MultiProperty property) {
        List<JMeterProperty> items = new ArrayList<JMeterProperty>();
        PropertyIterator iterator = property.iterator();
        while (iterator.hasNext()) {
            items.add(iterator.next());
        }
        return items;
    }

    private static void addItems(MultiProperty property, List<JMeterProperty> items) {
        for (JMeterProperty item : items) {
            property.addProperty(item);
        }
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
