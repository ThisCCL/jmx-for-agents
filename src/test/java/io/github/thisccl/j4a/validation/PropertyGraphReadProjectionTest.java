package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.property.DefaultJMeterPropertyGraph;
import io.github.thisccl.j4a.jmx.property.GraphSnapshot;
import io.github.thisccl.j4a.jmx.property.PropertyGraphDocumentMapper;
import io.github.thisccl.j4a.path.PropertyPath;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PropertyGraphReadProjectionTest {
    private static final List<Object> TEST_STRINGS = Collections.<Object>singletonList(
            "Asserion.test_strings");
    private static final String COLLECTION_PROPERTY =
            "org.apache.jmeter.testelement.property.CollectionProperty";
    private static final String STRING_PROPERTY =
            "org.apache.jmeter.testelement.property.StringProperty";

    @Test
    void focusedReadEmitsOneReusableRecursiveDocumentAndOmitsSystemIdentity() throws Exception {
        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.renderReadData(
                        fixture(), jmeterHome(), "1", "jmx_99fb165049e0", "ALL", "false"));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> output = mapping(new Yaml().load(result.response().payload()));
        Map<String, Object> focus = mapping(output.get("focus"));
        Map<String, Object> property = property(focus, TEST_STRINGS);

        assertThat(property).containsOnlyKeys("property", "type", "value")
                .containsEntry("property", TEST_STRINGS)
                .containsEntry("type", "collection");
        Map<String, Object> value = mapping(property.get("value"));
        assertThat(value).containsEntry("presence", "present")
                .containsEntry("property_class", COLLECTION_PROPERTY);
        List<Object> items = list(value.get("items"));
        assertThat(items).hasSize(2);
        assertScalar(items.get(0), "login failed");
        assertScalar(items.get(1), "access denied");
        assertThat(new PropertyGraphDocumentMapper().normalize(property)).isEqualTo(property);
        assertThat(result.response().payload()).doesNotContain(
                "TestElement.gui_class", "TestElement.test_class", "type: unsupported");
    }

    @Test
    void writableFocusedReadKeepsTheOrderedGraphWritableSubsetWithoutChangingDocuments() throws Exception {
        LocalJMeterWorkerClient worker = new LocalJMeterWorkerClient();
        LocalJMeterWorkerResult all = worker.execute(LocalJMeterWorkerRequest.renderReadData(
                fixture(), jmeterHome(), "1", "jmx_99fb165049e0", "ALL", "false"));
        LocalJMeterWorkerResult writable = worker.execute(LocalJMeterWorkerRequest.renderReadData(
                fixture(), jmeterHome(), "1", "jmx_99fb165049e0", "WRITABLE", "false"));

        assertThat(all.response().success()).as(all.response().toJsonLine()).isTrue();
        assertThat(writable.response().success()).as(writable.response().toJsonLine()).isTrue();
        List<Map<String, Object>> allProperties = properties(all.response().payload());
        List<Map<String, Object>> writableProperties = properties(writable.response().payload());
        GraphSnapshot snapshot = new DefaultJMeterPropertyGraph().inspect(
                responseAssertion(), LocalPropertyGraphRuntimeContext.selected(jmeterHome()));
        List<Map<String, Object>> expected = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> property : allProperties) {
            Object address = property.get("property");
            if (Collections.singletonList("TestElement.name").equals(address)
                    || snapshot.resolve(io.github.thisccl.j4a.path.PropertyAddress.decode(address))
                            .capability().writable()) {
                expected.add(property);
            }
        }

        assertThat(snapshot.nodes()).anyMatch(node -> !node.capability().writable());
        assertThat(allProperties).anySatisfy(property -> assertThat(property).containsEntry(
                "property", TEST_STRINGS).containsEntry("type", "collection"));
        assertThat(allProperties).anySatisfy(property -> assertThat(property).containsEntry(
                "property", Collections.<Object>singletonList("Assertion.custom_message"))
                .containsEntry("type", "string"));
        assertThat(writableProperties).containsExactlyElementsOf(expected);
        assertThat(writableProperties).containsExactlyElementsOf(allProperties);
        assertThat(writable.response().payload().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(all.response().payload().getBytes(StandardCharsets.UTF_8).length);
    }

    private static void assertScalar(Object item, String expected) {
        assertThat(mapping(item)).containsEntry("type", "string")
                .containsEntry("presence", "present")
                .containsEntry("property_class", STRING_PROPERTY)
                .containsEntry("value", expected);
    }

    private static Path fixture() {
        try {
            return Paths.get(PropertyGraphReadProjectionTest.class.getResource(
                    "/property-graph-conformance/response-assertion.jmx").toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("fixture URI is invalid", exception);
        }
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }

    private static ResponseAssertion responseAssertion() {
        ResponseAssertion assertion = new ResponseAssertion();
        assertion.setName("Sanitized Response Assertion");
        assertion.addTestString("login failed");
        assertion.addTestString("access denied");
        assertion.setProperty(new StringProperty("Assertion.custom_message", ""));
        assertion.setProperty(new StringProperty("Assertion.test_field", "Assertion.response_data"));
        assertion.setProperty(new BooleanProperty("Assertion.assume_success", false));
        assertion.setProperty(new IntegerProperty("Assertion.test_type", 2));
        assertion.setProperty(TestElement.GUI_CLASS, "AssertionGui");
        assertion.setProperty(TestElement.TEST_CLASS, ResponseAssertion.class.getName());
        return assertion;
    }

    private static List<Map<String, Object>> properties(String payload) {
        Map<String, Object> output = mapping(new Yaml().load(payload));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object property : list(mapping(output.get("focus")).get("properties"))) {
            result.add(mapping(property));
        }
        return result;
    }

    private static Map<String, Object> property(Map<String, Object> focus, List<Object> path) {
        for (Object value : list(focus.get("properties"))) {
            Map<String, Object> candidate = mapping(value);
            if (path.equals(candidate.get("property"))) {
                return candidate;
            }
        }
        throw new AssertionError("missing read property: " + path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
