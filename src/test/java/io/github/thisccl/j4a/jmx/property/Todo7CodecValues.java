package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;

final class Todo7CodecValues {
    private Todo7CodecValues() {
    }

    static MapProperty typedMapFixture() {
        Map<String, JMeterProperty> entries = new LinkedHashMap<String, JMeterProperty>();
        entries.put("nested", Todo7CodecTestSupport.elementFixture("nested"));
        entries.put("true", new IntegerProperty("true", 7));
        entries.put("1", new StringProperty("1", "one"));
        return new MapProperty("qa.typed", entries);
    }

    static TestElementProperty elementWithMap() {
        TestElementProperty property = Todo7CodecTestSupport.elementFixture("qa.element");
        Map<String, JMeterProperty> entries = new LinkedHashMap<String, JMeterProperty>();
        entries.put("b", new IntegerProperty("b", 2));
        entries.put("a", new StringProperty("a", "old"));
        property.getElement().setProperty(new MapProperty("qa.options", entries));
        return property;
    }

    static RecursiveValue requestedOptions() {
        return RecursiveValue.map(Todo7CodecTestSupport.MAP_PROPERTY, Arrays.asList(
                entry(GraphType.STRING, "a", scalar(
                        GraphType.STRING, Todo7CodecTestSupport.STRING_PROPERTY, "new")),
                entry(GraphType.STRING, "b", scalar(
                        GraphType.INT, Todo7CodecTestSupport.INTEGER_PROPERTY, 22))));
    }

    static RecursiveValue requestedElement(int count) {
        List<PropertyWrite> writes = Arrays.asList(
                write("TestElement.name", GraphType.STRING,
                        scalar(GraphType.STRING,
                                Todo7CodecTestSupport.STRING_PROPERTY, "Todo 7 nested")),
                write("TestElement.enabled", GraphType.BOOLEAN,
                        scalar(GraphType.BOOLEAN, BooleanProperty.class.getName(), true)),
                write("qa.count", GraphType.INT,
                        scalar(GraphType.INT, Todo7CodecTestSupport.INTEGER_PROPERTY, count)));
        return RecursiveValue.element(
                Todo7CodecTestSupport.ELEMENT_PROPERTY,
                Todo7CodecTestSupport.ELEMENT_CLASS,
                writes);
    }

    static RecursiveValue.MapEntry entry(
            GraphType keyType, Object key, RecursiveValue value) {
        return new RecursiveValue.MapEntry(new TypedScalarMapKey(keyType, key), value);
    }

    static RecursiveValue scalar(GraphType type, String propertyClass, Object value) {
        return RecursiveValue.scalar(type, propertyClass, value);
    }

    static PropertyWrite write(String path, GraphType type, RecursiveValue value) {
        return new PropertyWrite(io.github.thisccl.j4a.path.TestPropertyPaths.properties(path), type, value);
    }

    static List<String> propertyNames(TestElement element) {
        List<String> names = new java.util.ArrayList<String>();
        PropertyIterator iterator = element.propertyIterator();
        while (iterator.hasNext()) {
            names.add(iterator.next().getName());
        }
        return names;
    }

    static boolean hasExactIdentity(TestElement element) {
        return Todo7CodecTestSupport.ELEMENT_CLASS.equals(element.getClass().getName())
                && Todo7CodecTestSupport.ELEMENT_CLASS.equals(
                        element.getPropertyAsString(TestElement.TEST_CLASS))
                && Todo7CodecTestSupport.GUI_CLASS.equals(
                        element.getPropertyAsString(TestElement.GUI_CLASS));
    }
}
