package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.yaml.snakeyaml.Yaml;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalJMeterWorkerOverlayTest {
    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();

    @Test
    void workerRejectsScalarOverlayForArgumentsPropertyBeforeWritingTarget() throws IOException {
        Path localHome = fixtures.localHome();
        Path patch = fixtures.root().resolve("scalar-arguments-overlay.yml");
        Files.write(patch, ("changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      properties:\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          value: not-structured\n"
                + "          type: string\n").getBytes(StandardCharsets.UTF_8));
        Path target = fixtures.root().resolve("preserve-target.jmx");
        Files.write(target, "existing-target".getBytes(StandardCharsets.UTF_8));
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                fixtures.root().resolve("basic.jmx"), localHome, patch, target));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().message()).contains(
                "HTTPsampler.Arguments",
                "expected type 'element'",
                "received type 'string'");
        assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)).isEqualTo("existing-target");
    }

    @Test
    void workerAcceptsStructuredArgumentsOverlayForLocalPluginAdd() throws Exception {
        Path localHome = fixtures.localHome();
        Path patch = fixtures.root().resolve("structured-arguments-overlay.yml");
        Files.write(patch, ("changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          value: Structured Args Sampler\n"
                + "          type: string\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: org.apache.jmeter.protocol.http.util.HTTPArgument\n"
                + "            rows:\n"
                + "              - Argument.name: branch_no\n"
                + "                Argument.value: \"1001\"\n"
                + "                Argument.metadata: \"=\"\n"
                + "                HTTPArgument.always_encode: true\n"
                + "                HTTPArgument.use_equals: false\n"
                + "                HTTPArgument.content_type: text/plain\n"
                + "              - Argument.name: client_id\n"
                + "                Argument.value: demo\n"
                + "                Argument.metadata: \"=\"\n"
                + "                HTTPArgument.always_encode: false\n"
                + "                HTTPArgument.use_equals: true\n"
                + "                HTTPArgument.content_type: application/json\n").getBytes(StandardCharsets.UTF_8));
        Path target = fixtures.root().resolve("structured-target.jmx");
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                fixtures.root().resolve("basic.jmx"), localHome, patch, target));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)).contains(
                "Structured Args Sampler",
                "elementProp name=\"HTTPsampler.Arguments\"");
        assertReadbackHttpRows(target, localHome);
    }

    @Test
    void workerRejectsScalarSetOverlayForArgumentsPropertyBeforeWritingTarget() throws IOException {
        Path localHome = fixtures.localHome();
        Path addPatch = fixtures.root().resolve("structured-arguments-set-source.yml");
        Files.write(addPatch, ("changes:\n"
                + "  - add:\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          value: Set Source Sampler\n"
                + "          type: string\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: org.apache.jmeter.protocol.http.util.HTTPArgument\n"
                + "            rows:\n"
                + "              - Argument.name: branch_no\n"
                + "                Argument.value: \"1001\"\n"
                + "                Argument.metadata: \"=\"\n"
                + "                HTTPArgument.always_encode: false\n"
                + "                HTTPArgument.use_equals: true\n"
                + "                HTTPArgument.content_type: application/json\n").getBytes(StandardCharsets.UTF_8));
        Path source = fixtures.root().resolve("set-source.jmx");
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));
        LocalJMeterWorkerResult addResult = client.execute(LocalJMeterWorkerRequest.applyPatch(
                fixtures.root().resolve("basic.jmx"), localHome, addPatch, source));
        assertThat(addResult.response().success()).as(addResult.response().toJsonLine()).isTrue();
        String ref = refFor(source, localHome, "Set Source Sampler");
        Path setPatch = fixtures.root().resolve("scalar-arguments-set.yml");
        Files.write(setPatch, ("changes:\n"
                + "  - set:\n"
                + "      ref: " + ref + "\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n"
                + "      properties:\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          value: not-structured\n"
                + "          type: string\n").getBytes(StandardCharsets.UTF_8));
        Path target = fixtures.root().resolve("set-preserve-target.jmx");
        Files.write(target, "existing-set-target".getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                source, localHome, setPatch, target));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().message()).contains(
                "HTTPsampler.Arguments",
                "expected type 'element'",
                "received type 'string'");
        assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8)).isEqualTo("existing-set-target");
    }

    private static String refFor(Path jmx, Path localHome, String name) throws IOException {
        LocalJMeterWorkerResult result = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60))
                .execute(LocalJMeterWorkerRequest.renderReadData(
                        jmx, localHome, "5", null, "NONE", "false"));
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        Map<String, Object> document = mapping(new Yaml().load(result.response().payload()));
        Map<String, Object> root = document.containsKey("root") ? mapping(document.get("root")) : document;
        return findRef(root, name).orElseThrow(() -> new AssertionError("Missing ref for " + name));
    }

    private static void assertReadbackHttpRows(Path target, Path localHome) throws IOException {
        LocalJMeterWorkerResult readback = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60))
                .execute(LocalJMeterWorkerRequest.renderReadData(
                        target, localHome, "5", null, "ALL", "false"));
        assertThat(readback.response().success()).as(readback.response().toJsonLine()).isTrue();
        assertThat(readback.response().payload()).contains(
                "row_type: org.apache.jmeter.protocol.http.util.HTTPArgument",
                "Argument.name: branch_no",
                "Argument.value: '1001'",
                "Argument.metadata: =",
                "HTTPArgument.always_encode: true",
                "HTTPArgument.use_equals: false",
                "HTTPArgument.content_type: text/plain",
                "Argument.name: client_id",
                "Argument.value: demo",
                "HTTPArgument.always_encode: false",
                "HTTPArgument.use_equals: true",
                "HTTPArgument.content_type: application/json");
    }

    private static java.util.Optional<String> findRef(Map<String, Object> node, String name) {
        if (name.equals(node.get("name"))) {
            return java.util.Optional.of((String) node.get("ref"));
        }
        Object children = node.get("children");
        if (!(children instanceof List<?>)) {
            return java.util.Optional.empty();
        }
        for (Object child : (List<?>) children) {
            java.util.Optional<String> ref = findRef(mapping(child), name);
            if (ref.isPresent()) {
                return ref;
            }
        }
        return java.util.Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }
}
