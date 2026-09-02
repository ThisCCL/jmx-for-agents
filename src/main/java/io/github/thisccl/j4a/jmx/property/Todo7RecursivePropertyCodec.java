package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;

final class Todo7RecursivePropertyCodec {
    private Todo7RecursivePropertyCodec() {
    }

    static void validate(JMeterProperty observed, RecursiveValue value, String path) {
        if (!observed.getClass().getName().equals(value.propertyClass())) {
            throw MapPropertyCodec.error(
                    path + ".property_class", "property class does not match observed property");
        }
        if (value.presence() == GraphPresence.ABSENT) {
            return;
        }
        switch (value.type()) {
            case MAP:
                requireInstance(observed, MapProperty.class, path);
                MapPropertyCodec.materializeAt(
                        new MapPropertyCodec.Target(
                                observed.getName(), (MapProperty) observed, path), value);
                return;
            case ELEMENT:
                requireInstance(observed, TestElementProperty.class, path);
                TestElementPropertyCodec.materializeAt(
                        new TestElementPropertyCodec.Target(
                                observed.getName(), (TestElementProperty) observed, path), value);
                return;
            case COLLECTION:
                requireCollection(observed, path);
                validateCollection((MultiProperty) observed, value, path);
                return;
            default:
                if (value.type().isScalar()) {
                    new ScalarPropertyCodec().materialize(observed, value);
                    return;
                }
                throw MapPropertyCodec.error(
                        path + ".type", "recursive codec does not support " + value.type().wireName());
        }
    }

    static Optional<JMeterProperty> materialize(Request request) {
        if (request.binding.observed != null) {
            validate(request.binding.observed, request.value, request.path);
        }
        if (request.value.presence() == GraphPresence.ABSENT) {
            return Optional.empty();
        }
        switch (request.value.type()) {
            case MAP:
                return MapPropertyCodec.materializeAt(
                        new MapPropertyCodec.Target(request.binding.name,
                                request.binding.observed == null
                                        ? null : (MapProperty) request.binding.observed,
                                request.path), request.value);
            case ELEMENT:
                if (!(request.binding.observed instanceof TestElementProperty)) {
                    throw MapPropertyCodec.error(request.path + ".element_class",
                            "element materialization requires an observed declaration");
                }
                return TestElementPropertyCodec.materializeAt(
                        new TestElementPropertyCodec.Target(request.binding.name,
                                (TestElementProperty) request.binding.observed, request.path),
                        request.value);
            case COLLECTION:
                return materializeCollection(request);
            default:
                if (request.value.type().isScalar()) {
                    ScalarPropertyCodec codec = new ScalarPropertyCodec();
                    return request.binding.observed == null
                            ? codec.materialize(request.binding.name, request.value)
                            : codec.materialize(request.binding.observed, request.value);
                }
                throw MapPropertyCodec.error(
                        request.path + ".type", "recursive codec does not support "
                                + request.value.type().wireName());
        }
    }

    private static Optional<JMeterProperty> materializeCollection(Request request) {
        MultiProperty source = request.binding.observed == null
                ? null : requireCollection(request.binding.observed, request.path);
        if (source == null
                && !CollectionProperty.class.getName().equals(request.value.propertyClass())) {
            throw MapPropertyCodec.error(request.path + ".property_class",
                    "collection materialization requires CollectionProperty");
        }
        List<JMeterProperty> templates = source == null
                ? Collections.<JMeterProperty>emptyList() : children(source);
        List<JMeterProperty> items = new ArrayList<JMeterProperty>(request.value.items().size());
        for (int index = 0; index < request.value.items().size(); index++) {
            RecursiveValue item = request.value.items().get(index);
            JMeterProperty template = index < templates.size() ? templates.get(index) : null;
            Optional<JMeterProperty> child = materialize(
                    new Request(new Binding(Integer.toString(index), template), item,
                            request.path + ".items[" + index + "]"));
            if (!child.isPresent()) {
                throw MapPropertyCodec.error(request.path + ".items[" + index + "]",
                        "collection items must be present");
            }
            items.add(child.get());
        }
        MultiProperty result;
        if (source == null) {
            result = new CollectionProperty(request.binding.name, Collections.emptyList());
        } else {
            JMeterProperty cloned = source.clone();
            if (cloned == source
                    || !cloned.getClass().equals(source.getClass())
                    || !(cloned instanceof MultiProperty)) {
                throw MapPropertyCodec.error(request.path + ".property_class",
                        "observed collection class cannot be cloned safely");
            }
            result = (MultiProperty) cloned;
            result.clear();
        }
        for (JMeterProperty item : items) {
            result.addProperty(item);
        }
        return Optional.of((JMeterProperty) result);
    }

    private static void validateCollection(
            MultiProperty observed, RecursiveValue value, String path) {
        List<JMeterProperty> templates = children(observed);
        for (int index = 0; index < value.items().size(); index++) {
            if (index >= templates.size()) {
                continue;
            }
            validate(templates.get(index), value.items().get(index),
                    path + ".items[" + index + "]");
        }
    }

    private static MultiProperty requireCollection(JMeterProperty property, String path) {
        if (!(property instanceof MultiProperty)
                || property instanceof MapProperty
                || property instanceof TestElementProperty) {
            throw MapPropertyCodec.error(path + ".type",
                    "observed property is not an ordered collection");
        }
        return (MultiProperty) property;
    }

    private static void requireInstance(
            JMeterProperty value, Class<?> expected, String path) {
        if (!expected.isInstance(value)) {
            String family = expected == MapProperty.class ? "map" : "element";
            throw MapPropertyCodec.error(path + ".type",
                    "observed property is not a " + family + " property");
        }
    }

    private static List<JMeterProperty> children(MultiProperty property) {
        List<JMeterProperty> children = new ArrayList<JMeterProperty>();
        PropertyIterator iterator = property.iterator();
        while (iterator.hasNext()) {
            children.add(iterator.next());
        }
        return children;
    }

    static final class Request {
        private final Binding binding;
        private final RecursiveValue value;
        private final String path;

        Request(Binding binding, RecursiveValue value, String path) {
            this.binding = binding;
            this.value = value;
            this.path = path;
        }
    }

    static final class Binding {
        private final String name;
        private final JMeterProperty observed;

        Binding(String name, JMeterProperty observed) {
            this.name = name;
            this.observed = observed;
        }
    }
}
