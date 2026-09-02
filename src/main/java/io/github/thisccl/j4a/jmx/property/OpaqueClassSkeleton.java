package io.github.thisccl.j4a.jmx.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.ObjectProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.TestElementProperty;

final class OpaqueClassSkeleton {
    private final List<String> recursiveClasses;
    private final Set<String> concreteClasses;

    private OpaqueClassSkeleton(List<String> recursiveClasses) {
        this.recursiveClasses = Collections.unmodifiableList(
                new ArrayList<String>(recursiveClasses));
        this.concreteClasses = Collections.unmodifiableSet(
                new LinkedHashSet<String>(recursiveClasses));
    }

    static OpaqueClassSkeleton capture(JMeterProperty root) {
        Capture capture = new Capture();
        capture.property(root);
        return new OpaqueClassSkeleton(capture.classes);
    }

    void requireContains(OpaqueClassSkeleton submitted) {
        TreeSet<String> expansion = new TreeSet<String>(submitted.concreteClasses);
        expansion.removeAll(concreteClasses);
        if (!expansion.isEmpty()) {
            throw new IllegalArgumentException(
                    "opaque payload expands serialized classes: " + expansion);
        }
    }

    List<String> recursiveClasses() {
        return recursiveClasses;
    }

    private static final class Capture {
        private final List<String> classes = new ArrayList<String>();
        private final IdentityHashMap<Object, Boolean> seen =
                new IdentityHashMap<Object, Boolean>();

        private void property(JMeterProperty property) {
            if (seen.put(property, Boolean.TRUE) != null) {
                return;
            }
            classes.add(property.getClass().getName());
            if (property instanceof MultiProperty) {
                PropertyIterator children = ((MultiProperty) property).iterator();
                while (children.hasNext()) {
                    property(children.next());
                }
            }
            Object value = property.getObjectValue();
            if (property instanceof TestElementProperty && value instanceof TestElement) {
                element((TestElement) value);
            } else if (property instanceof ObjectProperty) {
                objectValue(value);
            }
        }

        private void objectValue(Object value) {
            if (value == null) {
                return;
            }
            if (value instanceof JMeterProperty) {
                property((JMeterProperty) value);
                return;
            }
            if (value instanceof TestElement) {
                element((TestElement) value);
                return;
            }
            if (seen.put(value, Boolean.TRUE) != null) {
                return;
            }
            classes.add(value.getClass().getName());
            if (value instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    objectValue(entry.getKey());
                    objectValue(entry.getValue());
                }
            } else if (value instanceof Iterable) {
                for (Object item : (Iterable<?>) value) {
                    objectValue(item);
                }
            } else if (value instanceof Object[]) {
                for (Object item : (Object[]) value) {
                    objectValue(item);
                }
            }
        }

        private void element(TestElement element) {
            if (seen.put(element, Boolean.TRUE) != null) {
                return;
            }
            classes.add(element.getClass().getName());
            PropertyIterator properties = element.propertyIterator();
            while (properties.hasNext()) {
                property(properties.next());
            }
        }
    }
}
