package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;

public final class TestElementPropertyCodec {
    public RecursiveValue read(TestElementProperty property) {
        RecursiveValue value = RuntimePropertyValueDiscovery.read(
                Objects.requireNonNull(property, "element property is required"));
        requireElement(value, "$");
        return value;
    }

    public Optional<JMeterProperty> materialize(
            TestElementProperty observed, RecursiveValue value) {
        TestElementProperty required = Objects.requireNonNull(
                observed, "observed element property is required");
        return materializeAt(new Target(required.getName(), required, "$"), value);
    }

    static Optional<JMeterProperty> materializeAt(Target target, RecursiveValue value) {
        RecursiveValue required = Objects.requireNonNull(value, "recursive value is required");
        requireElement(required, target.path);
        if (!target.observed.getClass().getName().equals(required.propertyClass())) {
            throw error(target.path + ".property_class",
                    "property class does not match observed element property class");
        }
        TestElement source = target.observed.getElement();
        if (required.presence() == GraphPresence.ABSENT) {
            return Optional.empty();
        }
        if (!source.getClass().getName().equals(required.elementClass())) {
            throw error(target.path + ".element_class",
                    "element class does not match observed element class");
        }
        List<RequestedProperty> requests = validateProperties(
                required.properties(), source, target.path);
        List<JMeterProperty> children = new ArrayList<JMeterProperty>(requests.size());
        for (RequestedProperty request : requests) {
            Optional<JMeterProperty> child = Todo7RecursivePropertyCodec.materialize(
                    new Todo7RecursivePropertyCodec.Request(
                            new Todo7RecursivePropertyCodec.Binding(
                                    request.observed.getName(), request.observed),
                            request.value, request.path + ".value"));
            if (child.isPresent()) {
                children.add(child.get());
            }
        }
        Object clonedElement = source.clone();
        if (clonedElement == source
                || !clonedElement.getClass().equals(source.getClass())
                || !(clonedElement instanceof TestElement)) {
            throw error(target.path + ".element_class",
                    "observed element class cannot be cloned safely");
        }
        TestElement resultElement = (TestElement) clonedElement;
        resultElement.clear();
        PropertyIterator sourceProperties = source.propertyIterator();
        int childIndex = 0;
        while (sourceProperties.hasNext()) {
            JMeterProperty property = sourceProperties.next();
            if (isIdentity(property.getName())) {
                resultElement.setProperty(cloneIdentityProperty(property, target.path));
            } else {
                resultElement.setProperty(children.get(childIndex));
                childIndex++;
            }
        }

        JMeterProperty clonedProperty = target.observed.clone();
        if (clonedProperty == target.observed
                || !clonedProperty.getClass().equals(target.observed.getClass())
                || !(clonedProperty instanceof TestElementProperty)) {
            throw error(target.path + ".property_class",
                    "observed element property class cannot be cloned safely");
        }
        TestElementProperty result = (TestElementProperty) clonedProperty;
        result.setElement(resultElement);
        result.setName(target.name);
        return Optional.of(clonedProperty);
    }

    private static List<RequestedProperty> validateProperties(
            List<PropertyWrite> writes, TestElement observed, String path) {
        List<RequestedProperty> requests = new ArrayList<RequestedProperty>(writes.size());
        Map<String, RequestedProperty> requestsByName =
                new LinkedHashMap<String, RequestedProperty>();
        Set<String> names = new HashSet<String>();
        List<PropertyPathSegment> sharedPrefix = null;
        for (int index = 0; index < writes.size(); index++) {
            PropertyWrite write = writes.get(index);
            String writePath = path + ".properties[" + index + "]";
            List<PropertyPathSegment> segments = write.property().segments();
            if (segments.isEmpty() || !allPropertySegments(segments)) {
                throw error(writePath + ".property",
                        "element property path requires one property segment");
            }
            List<PropertyPathSegment> prefix =
                    new ArrayList<PropertyPathSegment>(segments.subList(0, segments.size() - 1));
            if (sharedPrefix == null) {
                sharedPrefix = prefix;
            } else if (!sharedPrefix.equals(prefix)) {
                throw error(writePath + ".property",
                        "element property paths require one shared ancestor prefix");
            }
            String propertyName = segments.get(segments.size() - 1).name();
            if (TestElement.GUI_CLASS.equals(propertyName)
                    || TestElement.TEST_CLASS.equals(propertyName)) {
                throw error(writePath + ".property", "system identity property is read-only");
            }
            if (!names.add(propertyName)) {
                throw error(writePath + ".property",
                        "duplicate element property '" + propertyName + "'");
            }
            JMeterProperty template = observed.getPropertyOrNull(propertyName);
            if (template == null) {
                throw error(writePath + ".property",
                        "property is not declared by the observed element");
            }
            Todo7RecursivePropertyCodec.validate(template, write.value(), writePath + ".value");
            requestsByName.put(propertyName,
                    new RequestedProperty(template, write.value(), writePath));
        }
        PropertyIterator observedProperties = observed.propertyIterator();
        while (observedProperties.hasNext()) {
            JMeterProperty property = observedProperties.next();
            if (isIdentity(property.getName())) {
                continue;
            }
            RequestedProperty request = requestsByName.remove(property.getName());
            if (request == null) {
                throw error(path + ".properties",
                        "complete element document requires property '"
                                + property.getName() + "'");
            }
            requests.add(request);
        }
        return requests;
    }

    private static boolean allPropertySegments(List<PropertyPathSegment> segments) {
        for (PropertyPathSegment segment : segments) {
            if (segment.kind() != PropertyPathSegment.Kind.PROPERTY) {
                return false;
            }
        }
        return true;
    }

    private static JMeterProperty cloneIdentityProperty(
            JMeterProperty property, String path) {
        JMeterProperty copy = property.clone();
        if (copy == property || !copy.getClass().equals(property.getClass())) {
            throw error(path + ".properties", "system identity cannot be cloned safely");
        }
        return copy;
    }

    private static boolean isIdentity(String propertyName) {
        return TestElement.GUI_CLASS.equals(propertyName)
                || TestElement.TEST_CLASS.equals(propertyName);
    }

    private static void requireElement(RecursiveValue value, String path) {
        if (value.type() != GraphType.ELEMENT) {
            throw error(path + ".type", "element codec requires an element value");
        }
    }

    private static PropertyGraphRepresentationException error(String path, String reason) {
        return MapPropertyCodec.error(path, reason);
    }

    static final class Target {
        private final String name;
        private final TestElementProperty observed;
        private final String path;

        Target(String name, TestElementProperty observed, String path) {
            this.name = name;
            this.observed = observed;
            this.path = path;
        }
    }

    private static final class RequestedProperty {
        private final JMeterProperty observed;
        private final RecursiveValue value;
        private final String path;

        RequestedProperty(JMeterProperty observed, RecursiveValue value, String path) {
            this.observed = observed;
            this.value = value;
            this.path = path;
        }
    }
}
