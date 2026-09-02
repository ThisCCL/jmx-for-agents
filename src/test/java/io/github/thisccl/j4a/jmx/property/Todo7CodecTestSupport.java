package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.config.ConfigTestElement;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MapProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.property.TestElementProperty;

final class Todo7CodecTestSupport {
    static final String MAP_PROPERTY =
            "org.apache.jmeter.testelement.property.MapProperty";
    static final String STRING_PROPERTY =
            "org.apache.jmeter.testelement.property.StringProperty";
    static final String INTEGER_PROPERTY =
            "org.apache.jmeter.testelement.property.IntegerProperty";
    static final String ELEMENT_PROPERTY =
            "org.apache.jmeter.testelement.property.TestElementProperty";
    static final String ELEMENT_CLASS = ConfigTestElement.class.getName();
    static final String GUI_CLASS = "todo7.fixture.NestedGui";

    private Todo7CodecTestSupport() {
    }

    static MapProperty mapFixture() {
        LinkedHashMap<String, JMeterProperty> entries =
                new LinkedHashMap<String, JMeterProperty>();
        entries.put("a", new StringProperty("a", "one"));
        entries.put("b", elementFixture("b"));
        return new MapProperty("qa.map", entries);
    }

    static TestElementProperty elementFixture(String propertyName) {
        ConfigTestElement element = new ConfigTestElement();
        element.setProperty(TestElement.TEST_CLASS, ELEMENT_CLASS);
        element.setProperty(TestElement.GUI_CLASS, GUI_CLASS);
        element.setName("Todo 7 nested");
        element.setEnabled(true);
        element.setProperty(new IntegerProperty("qa.count", 7));
        return new TestElementProperty(propertyName, element);
    }

    static ConfigTestElement outer(JMeterProperty property) {
        ConfigTestElement outer = new ConfigTestElement();
        outer.setProperty(TestElement.TEST_CLASS, ELEMENT_CLASS);
        outer.setProperty(TestElement.GUI_CLASS, "todo7.fixture.OuterGui");
        outer.setName("Todo 7 outer");
        outer.setEnabled(true);
        outer.setProperty(property);
        return outer;
    }

    static GraphSnapshot inspect(TestElement element) {
        Map<String, String> libraries = new LinkedHashMap<String, String>();
        libraries.put("lib/ApacheJMeter_core.jar", "todo7-runtime-fixture");
        RuntimeContext context = new RuntimeContext("todo7-worker", new RuntimeFingerprint(
                jmeterHome().toString(), "5.6.3", libraries));
        return new DefaultJMeterPropertyGraph().inspect(element, context);
    }

    static SavedElement saveRoundTrip(Object value) {
        initializeSaveService();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            SaveService.saveElement(value, output);
            byte[] xml = output.toByteArray();
            Object loaded = SaveService.loadElement(new ByteArrayInputStream(xml));
            return new SavedElement(xml, loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to round-trip Todo 7 fixture", exception);
        }
    }

    static String propertyProjection(JMeterProperty property) {
        StringBuilder projection = new StringBuilder();
        appendProperty(projection, property, "");
        return projection.toString();
    }

    static String elementProjection(TestElement element) {
        StringBuilder projection = new StringBuilder();
        projection.append(element.getClass().getName()).append('\n');
        PropertyIterator iterator = element.propertyIterator();
        while (iterator.hasNext()) {
            appendProperty(projection, iterator.next(), "  ");
        }
        return projection.toString();
    }

    static List<String> mapKeys(MapProperty property) {
        List<String> keys = new ArrayList<String>();
        PropertyIterator iterator = property.iterator();
        while (iterator.hasNext()) {
            keys.add(iterator.next().getName());
        }
        return keys;
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                hex.append(String.format("%02x", Integer.valueOf(current & 0xff)));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void appendProperty(
            StringBuilder projection, JMeterProperty property, String indent) {
        projection.append(indent)
                .append(property.getClass().getName()).append('|')
                .append(property.getName()).append('|');
        Object value = property.getObjectValue();
        if (value instanceof TestElement) {
            projection.append(value.getClass().getName()).append('\n');
            PropertyIterator iterator = ((TestElement) value).propertyIterator();
            while (iterator.hasNext()) {
                appendProperty(projection, iterator.next(), indent + "  ");
            }
        } else if (property instanceof MapProperty) {
            projection.append("map").append('\n');
            PropertyIterator iterator = ((MapProperty) property).iterator();
            while (iterator.hasNext()) {
                appendProperty(projection, iterator.next(), indent + "  ");
            }
        } else {
            projection.append(property.getStringValue()).append('\n');
        }
    }

    private static synchronized void initializeSaveService() {
        new SaveServiceJmxLoader(jmeterHome());
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }

    static final class SavedElement {
        private final byte[] xml;
        private final Object loaded;

        private SavedElement(byte[] xml, Object loaded) {
            this.xml = xml.clone();
            this.loaded = loaded;
        }

        String xmlSha256() {
            return sha256(xml);
        }

        String xml() {
            return new String(xml, StandardCharsets.UTF_8);
        }

        Object loaded() {
            return loaded;
        }
    }
}
