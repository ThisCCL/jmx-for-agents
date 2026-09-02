package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalJMeterWorkerEmptyHttpReadReuseTest {
    private static final String HTTP_REF = "jmx_330976848c8e";
    private static final String HTTP_ARGUMENT =
            "org.apache.jmeter.protocol.http.util.HTTPArgument";

    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
    private final LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
            Duration.ofSeconds(20), Duration.ofSeconds(90), Duration.ofSeconds(90));

    @BeforeAll
    void prepareRuntime() throws Exception {
        fixtures.ensure();
    }

    @Test
    void emptyHttpFocusedReadIsAnExactReusableHttpArgumentDocument() throws Exception {
        Path source = Paths.get("src/test/resources/fixtures/simple-http.jmx");
        Map<String, Object> readProperty = focusedHttpArguments(source);

        assertThat(readProperty.get("property"))
                .isEqualTo(Collections.singletonList("HTTPsampler.Arguments"));
        assertThat(readProperty.get("type")).isEqualTo("rows");
        assertExactEmptyHttpValue(value(readProperty));

        Path patch = fixtures.root().resolve("empty-http-read-reuse.yml");
        Files.write(patch, patch(readProperty).getBytes(StandardCharsets.UTF_8));
        Path output = fixtures.root().resolve("empty-http-read-reuse.jmx");
        LocalJMeterWorkerResult applied = client.execute(LocalJMeterWorkerRequest.applyPatch(
                source, fixtures.localHome(), patch, output));

        assertThat(applied.response().success()).as(applied.response().toJsonLine()).isTrue();
        assertThat(client.execute(LocalJMeterWorkerRequest.validate(output, fixtures.localHome()))
                .response().success()).isTrue();
        Map<String, Object> reloaded = focusedHttpArguments(output);
        assertThat(reloaded).isEqualTo(readProperty);

        Path appendPatch = fixtures.root().resolve("empty-http-read-reuse-append.yml");
        Files.write(appendPatch, appendPatch().getBytes(StandardCharsets.UTF_8));
        Path appended = fixtures.root().resolve("empty-http-read-reuse-appended.jmx");
        LocalJMeterWorkerResult appendedResult = client.execute(LocalJMeterWorkerRequest.applyPatch(
                output, fixtures.localHome(), appendPatch, appended));
        assertThat(appendedResult.response().success())
                .as(appendedResult.response().toJsonLine()).isTrue();
        assertThat(client.execute(LocalJMeterWorkerRequest.validate(appended, fixtures.localHome()))
                .response().success()).isTrue();
        assertThat(new String(Files.readAllBytes(appended), StandardCharsets.UTF_8))
                .contains("elementType=\"HTTPArgument\"");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> focusedHttpArguments(Path source) throws Exception {
        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.renderReadData(
                source, fixtures.localHome(), "0", HTTP_REF, "ALL", "false"));
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> document = new Yaml().load(result.response().payload());
        Map<String, Object> focus = (Map<String, Object>) document.get("focus");
        for (Map<String, Object> property : (List<Map<String, Object>>) focus.get("properties")) {
            if (Collections.singletonList("HTTPsampler.Arguments").equals(property.get("property"))) {
                return property;
            }
        }
        throw new AssertionError("focused read omitted HTTPsampler.Arguments");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(Map<String, Object> property) {
        return (Map<String, Object>) property.get("value");
    }

    @SuppressWarnings("unchecked")
    private static void assertExactEmptyHttpValue(Map<String, Object> value) {
        assertThat(value.get("row_type")).isEqualTo(HTTP_ARGUMENT);
        List<Map<String, Object>> descriptors =
                (List<Map<String, Object>>) value.get("row_properties");
        assertThat(descriptors).extracting(descriptor -> descriptor.get("name"))
                .containsExactly(
                        "Argument.name",
                        "Argument.value",
                        "HTTPArgument.always_encode",
                        "HTTPArgument.content_type",
                        "Argument.metadata",
                        "HTTPArgument.use_equals");
        assertThat(descriptors).extracting(descriptor -> descriptor.get("type"))
                .containsExactly("string", "string", "boolean", "string", "string", "boolean");
        assertThat((List<?>) value.get("rows")).isEmpty();
    }

    private static String patch(Map<String, Object> property) {
        Map<String, Object> set = new LinkedHashMap<String, Object>();
        set.put("ref", HTTP_REF);
        set.put("properties", Collections.singletonList(property));
        Map<String, Object> change = Collections.<String, Object>singletonMap("set", set);
        return new Yaml().dump(Collections.singletonMap(
                "changes", Collections.singletonList(change)));
    }

    private static String appendPatch() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("Argument.name", "proof");
        row.put("Argument.value", "physical");
        row.put("HTTPArgument.always_encode", Boolean.TRUE);
        row.put("HTTPArgument.content_type", "text/plain");
        row.put("Argument.metadata", "=");
        row.put("HTTPArgument.use_equals", Boolean.TRUE);
        Map<String, Object> append = new LinkedHashMap<String, Object>();
        append.put("ref", HTTP_REF);
        append.put("property", Collections.singletonList("HTTPsampler.Arguments"));
        append.put("row", row);
        return new Yaml().dump(Collections.singletonMap("changes", Arrays.asList(
                Collections.<String, Object>singletonMap("append", append))));
    }
}
