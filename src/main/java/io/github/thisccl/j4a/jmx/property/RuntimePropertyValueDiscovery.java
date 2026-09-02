package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;

final class RuntimePropertyValueDiscovery {
    private RuntimePropertyValueDiscovery() {
    }

    static RecursiveValue read(JMeterProperty property) {
        return read(property, null, new IdentityHashMap<TestElement, Boolean>());
    }

    static RecursiveValue read(JMeterProperty property, RuntimeContext runtimeContext) {
        return read(property, runtimeContext, new IdentityHashMap<TestElement, Boolean>());
    }

    private static RecursiveValue read(
            JMeterProperty property,
            RuntimeContext runtimeContext,
            IdentityHashMap<TestElement, Boolean> elementStack) {
        String propertyClass = property.getClass().getName();
        if (property instanceof NullProperty) {
            return RecursiveValue.presentNull(propertyClass);
        }
        if (property instanceof BooleanProperty) {
            return RecursiveValue.scalar(
                    GraphType.BOOLEAN, propertyClass, Boolean.valueOf(property.getBooleanValue()));
        }
        if (property instanceof IntegerProperty) {
            return RecursiveValue.scalar(
                    GraphType.INT, propertyClass, Integer.valueOf(property.getIntValue()));
        }
        if (property instanceof LongProperty) {
            return RecursiveValue.scalar(
                    GraphType.LONG, propertyClass, Long.valueOf(property.getLongValue()));
        }
        if (property instanceof FloatProperty) {
            return RecursiveValue.scalar(
                    GraphType.FLOAT, propertyClass, Float.valueOf(property.getFloatValue()));
        }
        if (property instanceof DoubleProperty) {
            return RecursiveValue.scalar(
                    GraphType.DOUBLE, propertyClass, Double.valueOf(property.getDoubleValue()));
        }
        if (property instanceof StringProperty) {
            return RecursiveValue.scalar(GraphType.STRING, propertyClass, property.getStringValue());
        }
        if (property instanceof TestElementProperty
                && property.getObjectValue() instanceof TestElement) {
            return element(propertyClass, (TestElement) property.getObjectValue(),
                    runtimeContext, elementStack);
        }
        if (property instanceof MapProperty) {
            return map(propertyClass, (MapProperty) property, runtimeContext, elementStack);
        }
        if (property instanceof MultiProperty) {
            return collection(propertyClass, (MultiProperty) property, runtimeContext, elementStack);
        }
        if (runtimeContext != null) {
            try {
                return RecursiveValue.opaque(
                        propertyClass, new OpaquePropertyCodec(runtimeContext).read(property));
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "opaque property could not be read by SaveService", exception);
            }
        }
        String value = property.getStringValue();
        return value == null
                ? RecursiveValue.presentNull(propertyClass)
                : RecursiveValue.scalar(GraphType.STRING, propertyClass, value);
    }

    private static RecursiveValue collection(
            String propertyClass,
            MultiProperty property,
            RuntimeContext runtimeContext,
            IdentityHashMap<TestElement, Boolean> elementStack) {
        List<RecursiveValue> items = new ArrayList<RecursiveValue>();
        PropertyIterator iterator = property.iterator();
        while (iterator.hasNext()) {
            items.add(read(iterator.next(), runtimeContext, elementStack));
        }
        return RecursiveValue.collection(propertyClass, items);
    }

    private static RecursiveValue map(
            String propertyClass,
            MapProperty property,
            RuntimeContext runtimeContext,
            IdentityHashMap<TestElement, Boolean> elementStack) {
        List<RecursiveValue.MapEntry> entries = new ArrayList<RecursiveValue.MapEntry>();
        PropertyIterator iterator = property.iterator();
        while (iterator.hasNext()) {
            JMeterProperty value = iterator.next();
            entries.add(new RecursiveValue.MapEntry(
                    new TypedScalarMapKey(GraphType.STRING, value.getName()),
                    read(value, runtimeContext, elementStack)));
        }
        return RecursiveValue.map(propertyClass, entries);
    }

    private static RecursiveValue element(
            String propertyClass,
            TestElement element,
            RuntimeContext runtimeContext,
            IdentityHashMap<TestElement, Boolean> elementStack) {
        List<PropertyWrite> properties = new ArrayList<PropertyWrite>();
        if (elementStack.put(element, Boolean.TRUE) == null) {
            try {
                PropertyIterator iterator = element.propertyIterator();
                while (iterator.hasNext()) {
                    JMeterProperty property = iterator.next();
                    RecursiveValue value = read(property, runtimeContext, elementStack);
                    properties.add(new PropertyWrite(
                            new PropertyPath(Collections.singletonList(
                                    PropertyPathSegment.property(property.getName()))),
                            value.type(),
                            value));
                }
            } finally {
                elementStack.remove(element);
            }
        }
        return RecursiveValue.element(propertyClass, element.getClass().getName(), properties);
    }
}
