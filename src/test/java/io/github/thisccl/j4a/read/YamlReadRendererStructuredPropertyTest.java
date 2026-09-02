package io.github.thisccl.j4a.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class YamlReadRendererStructuredPropertyTest {
    @Test
    void publicPropertyAddressesAreScalarArrays() {
        TestElement sampler = new AbstractTestElement() { };
        sampler.setName("request");
        sampler.setProperty(new StringProperty("literal.property", "value"));
        sampler.setProperty(new StringProperty("", "empty-key"));
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);
        YamlReadRenderer renderer = new YamlReadRenderer();

        String yaml = renderer.render(new JmxTestPlan(tree), new ReadOptions(
                false, false, 1, null, ReadOptions.PropertyMode.ALL));

        Map<String, Object> root = mapping(mapping(new Yaml().load(yaml)).get("root"));
        List<Object> properties = list(root.get("properties"));
        assertThat(properties).allSatisfy(property -> assertScalarAddress(mapping(property).get("property")));
        assertThat(properties).extracting(property -> mapping(property).get("property"))
                .contains(
                        java.util.Collections.singletonList("literal.property"),
                        java.util.Collections.singletonList(""));
    }

    @Test
    void readUsesActualClassWhenNoSelectedRuntimeRegistryIsSupplied() {
        HTTPSamplerProxy sampler = new HTTPSamplerProxy();
        sampler.setName("request");
        sampler.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);

        String yaml = new YamlReadRenderer().render(new JmxTestPlan(tree), new ReadOptions(
                false, false, 1, null, ReadOptions.PropertyMode.NONE));

        assertThat(mapping(mapping(new Yaml().load(yaml)).get("root")))
                .containsEntry("component",
                        "org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy");
    }
    @Test
    void propertiesAllRendersArgumentsAsOneStructuredRecord() {
        TestElement sampler = new AbstractTestElement() { };
        sampler.setName("HTTP Request");
        sampler.setProperty(TestElement.GUI_CLASS,
                "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        Arguments arguments = new Arguments();
        arguments.addArgument(new Argument("branch_no", "1001", "="));
        arguments.addArgument(new Argument("client_id", "demo", "=", "client identifier"));
        sampler.setProperty(new TestElementProperty("HTTPsampler.Arguments", arguments));
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);

        String yaml = new YamlReadRenderer().render(new JmxTestPlan(tree), new ReadOptions(
                false,
                false,
                1,
                null,
                ReadOptions.PropertyMode.ALL));

        Map<String, Object> root = mapping(new Yaml().load(yaml)).containsKey("root")
                ? mapping(mapping(new Yaml().load(yaml)).get("root"))
                : mapping(new Yaml().load(yaml));
        List<Object> properties = list(root.get("properties"));
        Map<String, Object> argumentsProperty = properties.stream()
                .map(YamlReadRendererStructuredPropertyTest::mapping)
                .filter(property -> java.util.Collections.singletonList("HTTPsampler.Arguments")
                        .equals(property.get("property")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing arguments property in:\n" + yaml));
        assertThat(argumentsProperty).containsEntry("type", "element");
        assertEveryPropertyAddressIsScalar(argumentsProperty);
        assertThat(yaml).contains("branch_no", "client_id");
        assertThat(yaml).doesNotContain("Arguments\\.arguments[0]");
    }

    @Test
    void propertiesAllPreservesHttpArgumentTypeAndPrimitiveRowProperties() {
        TestElement sampler = new AbstractTestElement() { };
        sampler.setName("HTTP Request");
        Arguments arguments = new Arguments();
        HTTPArgument row = new HTTPArgument("q", "abc", false, "text/plain");
        row.setProperty(new BooleanProperty("HTTPArgument.always_encode", true));
        row.setProperty(new BooleanProperty("HTTPArgument.use_equals", false));
        row.setProperty(new StringProperty("HTTPArgument.content_type", "text/plain"));
        row.setProperty(new StringProperty("qa.custom", "kept"));
        arguments.addArgument(row);
        assertThat(arguments.getArgument(0)).isExactlyInstanceOf(HTTPArgument.class);
        sampler.setProperty(new TestElementProperty("HTTPsampler.Arguments", arguments));
        ListedHashTree tree = new ListedHashTree();
        tree.add(sampler);

        String yaml = new YamlReadRenderer().render(new JmxTestPlan(tree), new ReadOptions(
                false, false, 1, null, ReadOptions.PropertyMode.ALL));

        Map<String, Object> root = mapping(mapping(new Yaml().load(yaml)).get("root"));
        Map<String, Object> property = list(root.get("properties")).stream()
                .map(YamlReadRendererStructuredPropertyTest::mapping)
                .filter(candidate -> java.util.Collections.singletonList("HTTPsampler.Arguments")
                        .equals(candidate.get("property")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing arguments property in:\n" + yaml));
        assertThat(property).containsEntry("type", "rows");
        Map<String, Object> value = mapping(property.get("value"));
        assertThat(value).containsOnlyKeys("row_type", "row_properties", "rows");
        assertThat(value).containsEntry("row_type", HTTPArgument.class.getName());
        List<Object> rowProperties = list(value.get("row_properties"));
        assertThat(rowProperties)
                .extracting(descriptor -> mapping(descriptor).get("name"))
                .containsExactly(
                        "HTTPArgument.always_encode", "Argument.name", "Argument.value",
                        "Argument.metadata", "HTTPArgument.use_equals", "HTTPArgument.content_type",
                        "qa.custom");
        assertThat(rowProperties).allSatisfy(descriptor -> assertThat(mapping(descriptor))
                .containsOnlyKeys("name", "type", "required"));
        assertThat(rowProperties).extracting(descriptor -> mapping(descriptor).get("type"))
                .containsExactly("boolean", "string", "string", "string", "boolean", "string", "string");
        assertThat(rowProperties).extracting(descriptor -> mapping(descriptor).get("required"))
                .containsExactly(true, true, true, true, true, true, true);
        List<Object> rows = list(value.get("rows"));
        assertThat(rows).hasSize(1);
        assertThat(mapping(rows.get(0))).containsOnlyKeys(
                "HTTPArgument.always_encode", "Argument.name", "Argument.value",
                "Argument.metadata", "HTTPArgument.use_equals", "HTTPArgument.content_type",
                "qa.custom").containsEntry("HTTPArgument.always_encode", true)
                .containsEntry("Argument.name", "q")
                .containsEntry("Argument.value", "abc")
                .containsEntry("Argument.metadata", "=")
                .containsEntry("HTTPArgument.use_equals", false)
                .containsEntry("HTTPArgument.content_type", "text/plain")
                .containsEntry("qa.custom", "kept");
        assertEveryPropertyAddressIsScalar(property);
        assertThat(yaml).contains(
                "HTTPArgument.always_encode", "HTTPArgument.use_equals",
                "HTTPArgument.content_type", "qa.custom");
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

    private static void assertScalarAddress(Object value) {
        List<Object> address = list(value);
        assertThat(address).isNotEmpty();
        assertThat(address).allSatisfy(segment -> assertThat(segment)
                .isInstanceOfAny(String.class, Integer.class));
    }

    private static void assertEveryPropertyAddressIsScalar(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = mapping(value);
            if (map.containsKey("property") && map.containsKey("type") && map.containsKey("value")) {
                assertScalarAddress(map.get("property"));
            }
            for (Object child : map.values()) {
                assertEveryPropertyAddressIsScalar(child);
            }
        } else if (value instanceof List<?>) {
            for (Object child : (List<?>) value) {
                assertEveryPropertyAddressIsScalar(child);
            }
        }
    }
}
