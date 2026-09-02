package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalJMeterWorkerArgumentCharacterizationTest {
    private static final String HTTP_ARGUMENT = "org.apache.jmeter.protocol.http.util.HTTPArgument";
    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
    private final LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
            Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

    @Test
    void runtimeMenuComponentDiscoversHttpArgumentsWithoutProfileVocabulary() throws Exception {
        fixtures.ensure();
        CanonicalStructuredMenuFixture.install(fixtures.localHome());
        LocalJMeterWorkerResult result = client.execute(
                LocalJMeterWorkerRequest.componentDetails(
                        fixtures.localHome(), CanonicalStructuredMenuFixture.HTTP_COMPONENT, true));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> component = yamlMap(result.response().payload());
        assertThat(component).containsEntry("component", CanonicalStructuredMenuFixture.HTTP_COMPONENT);
        assertThat(property(component, "HTTPsampler.Arguments")).containsEntry(
                "property", Collections.singletonList("HTTPsampler.Arguments"));
        assertThat(result.response().payload()).doesNotContain("profile:");

        LocalJMeterWorkerResult runtime = client.execute(LocalJMeterWorkerRequest.componentDetails(
                fixtures.localHome(), "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui", true));
        assertThat(runtime.response().success()).as(runtime.response().toJsonLine()).isTrue();
        Map<String, Object> runtimeProperty = property(yamlMap(runtime.response().payload()), "HTTPsampler.Arguments");
        assertThat(runtimeProperty).containsEntry("type", "rows").containsEntry("row_type", HTTP_ARGUMENT);
        assertThat(runtimeProperty.get("row_properties")).isEqualTo(httpRowPropertyNames());
    }

    @Test
    void httpArgumentFixtureLoadsRowsWithHttpRowProperties() throws IOException {
        String fixture = fixtureText(fixtures.httpArgumentBackedJmx());

        LocalJMeterWorkerResult result = client.execute(
                LocalJMeterWorkerRequest.validate(fixtures.httpArgumentBackedJmx(), fixtures.localHome()));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(fixture).contains(
                "elementType=\"org.apache.jmeter.protocol.http.util.HTTPArgument\"",
                "HTTPArgument.always_encode",
                "HTTPArgument.use_equals",
                "HTTPArgument.content_type");
    }

    @Test
    void kpArgumentFixtureLoadsRowsWithEncryptedPropertyFromLocalHome() throws IOException {
        String fixture = fixtureText(fixtures.kpArgumentBackedJmx());

        LocalJMeterWorkerResult result = client.execute(
                LocalJMeterWorkerRequest.validate(fixtures.kpArgumentBackedJmx(), fixtures.localHome()));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(fixture).contains(
                "elementType=\"" + DefaultLocalProfileQaFixtures.KP_ARGUMENT_CLASS + "\"",
                "kingdomArgument.encrypted");
    }

    @Test
    void readbackPreservesHttpArgumentRowTypeAndRowProperties() throws IOException {
        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.renderReadData(
                fixtures.httpArgumentBackedJmx(), fixtures.localHome(), "5", null, "ALL", "false"));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> property = property(yamlMap(result.response().payload()), "HTTPsampler.Arguments");
        assertHttpRows(property);
        assertThat(result.response().payload()).doesNotContain("element_type:");
    }

    @Test
    void readbackPreservesDivergentRawElementTypeAlongsideRuntimeRowType() throws IOException {
        fixtures.ensure();
        java.nio.file.Path divergent = fixtures.root().resolve("http-argument-alias-element-type.jmx");
        String source = fixtureText(fixtures.httpArgumentBackedJmx()).replace(
                "elementType=\"org.apache.jmeter.protocol.http.util.HTTPArgument\"",
                "elementType=\"HTTPArgument\"");
        Files.write(divergent, source.getBytes(StandardCharsets.UTF_8));
        Files.write(
                fixtures.localHome().resolve("bin").resolve("saveservice.properties"),
                "\nHTTPArgument=org.apache.jmeter.protocol.http.util.HTTPArgument\n"
                        .getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        assertThat(source).contains("elementType=\"HTTPArgument\"");

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.renderReadData(
                divergent, fixtures.localHome(), "5", null, "ALL", "false"));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> property = property(yamlMap(result.response().payload()), "HTTPsampler.Arguments");
        assertHttpRows(property);
        assertThat(result.response().payload()).doesNotContain("element_type:");
    }

    @Test
    void readbackPreservesKpArgumentRowTypeAndEncryptedProperty() throws IOException {
        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.renderReadData(
                fixtures.kpArgumentBackedJmx(), fixtures.localHome(), "5", null, "ALL", "false"));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> property = property(yamlMap(result.response().payload()), "kingdomSampler.arguments");
        assertThat(property).containsEntry("type", "rows");
        Map<String, Object> value = mapping(property.get("value"));
        assertThat(value).containsOnlyKeys("row_type", "row_properties", "rows");
        assertThat(value.get("row_type")).isEqualTo(DefaultLocalProfileQaFixtures.KP_ARGUMENT_CLASS);
        assertThat(value.get("row_properties")).isEqualTo(kpRowProperties());
        assertThat(value.get("rows")).isEqualTo(Collections.singletonList(kpRow()));
    }

    private static void assertHttpRows(Map<String, Object> property) {
        assertThat(property).containsEntry("type", "rows");
        Map<String, Object> value = mapping(property.get("value"));
        assertThat(value).containsOnlyKeys("row_type", "row_properties", "rows");
        assertThat(value.get("row_type")).isEqualTo(HTTP_ARGUMENT);
        assertThat(value.get("row_properties")).isEqualTo(httpRowProperties());
        assertThat(value.get("rows")).isEqualTo(Collections.singletonList(httpRow()));
    }

    private static List<Map<String, Object>> httpRowProperties() {
        return Arrays.asList(
                descriptor("HTTPArgument.always_encode", "boolean"),
                descriptor("HTTPArgument.use_equals", "boolean"),
                descriptor("HTTPArgument.content_type", "string"),
                descriptor("Argument.name", "string"),
                descriptor("Argument.value", "string"),
                descriptor("Argument.metadata", "string"));
    }

    private static List<String> httpRowPropertyNames() {
        return Arrays.asList(
                "Argument.name", "Argument.value", "HTTPArgument.always_encode",
                "HTTPArgument.content_type", "Argument.metadata", "HTTPArgument.use_equals");
    }

    private static List<Map<String, Object>> kpRowProperties() {
        return Arrays.asList(
                descriptor("kingdomArgument.encrypted", "boolean"),
                descriptor("Argument.name", "string"),
                descriptor("Argument.value", "string"),
                descriptor("Argument.metadata", "string"));
    }

    private static Map<String, Object> httpRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("HTTPArgument.always_encode", true);
        row.put("HTTPArgument.use_equals", true);
        row.put("HTTPArgument.content_type", "text/plain");
        row.put("Argument.name", "q");
        row.put("Argument.value", "abc");
        row.put("Argument.metadata", "=");
        return row;
    }

    private static Map<String, Object> kpRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kingdomArgument.encrypted", true);
        row.put("Argument.name", "branch_no");
        row.put("Argument.value", "1001");
        row.put("Argument.metadata", "=");
        return row;
    }

    private static Map<String, Object> descriptor(String name, String type) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", name);
        descriptor.put("type", type);
        descriptor.put("required", true);
        return descriptor;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlMap(String payload) {
        Map<String, Object> document = mapping(new Yaml().load(payload));
        return document.containsKey("root") ? mapping(document.get("root")) : document;
    }

    private static Map<String, Object> property(Map<String, Object> document, String address) {
        Object properties = document.get("properties");
        if (properties instanceof List) {
            for (Map<String, Object> candidate : list(properties)) {
                if (Collections.singletonList(address).equals(candidate.get("property"))) {
                    return candidate;
                }
            }
        }
        Object children = document.get("children");
        if (children instanceof List) {
            for (Map<String, Object> child : list(children)) {
                try {
                    return property(child, address);
                } catch (AssertionError ignored) {
                }
            }
        }
        throw new AssertionError("Missing property " + address + " in " + document);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    private static String fixtureText(java.nio.file.Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
