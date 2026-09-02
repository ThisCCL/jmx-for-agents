package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.MultiProperty;
import org.apache.jmeter.testelement.property.PropertyIterator;
import org.apache.jmeter.testelement.property.StringProperty;

final class Todo6CodecFixtures {
    static final String TEST_STRINGS = "Asserion.test_strings";
    static final String COLLECTION_CLASS =
            "org.apache.jmeter.testelement.property.CollectionProperty";
    static final String STRING_CLASS =
            "org.apache.jmeter.testelement.property.StringProperty";
    static final String CODEC_REASON = "Property write codec is not implemented";

    private static final String JMETER_HOME_PROPERTY = "j4a.test.jmeterHome";
    private static final String DEFAULT_JMETER_HOME =
            io.github.thisccl.j4a.TestJMeterRuntime.home().toString();

    private Todo6CodecFixtures() {
    }

    static ResponseAssertion responseAssertion(String... values) {
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setProperty(TestElement.TEST_CLASS, ResponseAssertion.class.getName());
        assertion.setProperty(
                TestElement.GUI_CLASS, "org.apache.jmeter.assertions.gui.AssertionGui");
        assertion.setName("Todo 6 Response Assertion");
        assertion.setEnabled(true);
        for (String value : values) {
            assertion.addTestString(value);
        }
        return assertion;
    }

    static List<JMeterProperty> items(JMeterProperty property) {
        if (!(property instanceof MultiProperty)) {
            throw new AssertionError("Expected MultiProperty but got " + property.getClass().getName());
        }
        List<JMeterProperty> items = new ArrayList<JMeterProperty>();
        PropertyIterator iterator = ((MultiProperty) property).iterator();
        while (iterator.hasNext()) {
            items.add(iterator.next());
        }
        return items;
    }

    static ResponseAssertion responseAssertion(JmxTestPlan plan) {
        return plan.depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Response Assertion"));
    }

    static SaveServiceJmxLoader loader() {
        return new SaveServiceJmxLoader(jmeterHome());
    }

    static Path fixture() {
        try {
            return Paths.get(Todo6CodecFixtures.class
                    .getResource("/property-graph-conformance/response-assertion.jmx")
                    .toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Unable to resolve Response Assertion fixture", exception);
        }
    }

    static RuntimeContext runtimeContext() {
        Map<String, String> libraries = new LinkedHashMap<String, String>();
        libraries.put("lib/ApacheJMeter_core.jar", "todo6-runtime-fixture");
        return new RuntimeContext("todo6-worker", new RuntimeFingerprint(
                jmeterHome().toString(), "5.6.3", libraries));
    }

    private static Path jmeterHome() {
        return Paths.get(System.getProperty(JMETER_HOME_PROPERTY, DEFAULT_JMETER_HOME))
                .toAbsolutePath()
                .normalize();
    }
}

final class Todo6RawStringProperty extends StringProperty {
    private static final long serialVersionUID = 1L;

    Todo6RawStringProperty(String name, String value) {
        super(name, value);
    }
}
