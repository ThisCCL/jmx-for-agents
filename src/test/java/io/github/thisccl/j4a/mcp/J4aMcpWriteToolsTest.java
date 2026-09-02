package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;
import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;

class J4aMcpWriteToolsTest {
    private static final byte[] WRITE_MODE_SENTINEL =
            "mcp-write-mode-output-sentinel".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void toolsListIncludesWriteSchemasWithCliSafetyOptions() {
        List<Map<String, Object>> tools = toolsList();

        assertSchema(tools, "apply",
                Arrays.asList(),
                Arrays.asList("file", "path", "patchFile", "patchYaml", "dryRun", "out", "override",
                        "forceOut", "jmeter_home"),
                true);
        assertApplyWriteModeSchema(tools);
        assertSchema(tools, "init",
                Arrays.asList("out"),
                Arrays.asList("out", "forceOut", "jmeter_home", "name",
                        "threadGroupName"),
                false);
        assertSchema(tools, "set",
                Arrays.asList("locator", "property", "value"),
                Arrays.asList("file", "path", "locator", "property", "value", "type", "out", "override",
                        "forceOut", "jmeter_home"),
                true);
    }

    @Test
    void initSetAndApplyExecuteThroughMcpAndReturnStructuredResults() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        String home = jsonString(fixtures.localHome().toString());
        Path initialized = tempDir.resolve("initialized.jmx");
        Path setOutput = tempDir.resolve("set-output.jmx");
        Path applyOutput = tempDir.resolve("apply-output.jmx");
        Path dryRunOutput = tempDir.resolve("apply-dry-run-output.jmx");

        ServerResult initResult = runServer(
                call(1, "init", "\"out\":" + jsonString(initialized.toString())
                        + ",\"forceOut\":true,\"jmeter_home\":" + home + ",\"name\":\"MCP Test Plan\","
                        + "\"threadGroupName\":\"MCP Users\""));
        SessionSetResult setResult = runSessionSet(
                fixture("simple-http.jmx"), setOutput, fixtures.localHome(),
                "HTTPSampler\\.domain", jsonString("MCP Renamed Plan"), null);
        ServerResult applyResult = runServer(
                call(3, "apply", "\"file\":" + jsonString(setOutput.toString())
                        + ",\"patchYaml\":" + jsonString("changes: []\n")
                        + ",\"out\":" + jsonString(applyOutput.toString())
                        + ",\"forceOut\":true,\"jmeter_home\":" + home),
                call(4, "apply", "\"file\":" + jsonString(setOutput.toString())
                        + ",\"patchYaml\":" + jsonString("changes: []\n")
                        + ",\"dryRun\":true,\"out\":" + jsonString(dryRunOutput.toString())
                        + ",\"jmeter_home\":" + home));

        assertThat(initResult.exitCode()).isZero();
        assertThat(initResult.stderr()).isEmpty();
        assertThat(setResult.exitCode).isZero();
        assertThat(setResult.stderr).isEmpty();
        assertThat(applyResult.exitCode()).isZero();
        assertThat(applyResult.stderr()).isEmpty();
        assertThat(initialized).isRegularFile();
        assertThat(setOutput).isRegularFile();
        assertThat(applyOutput).isRegularFile();
        assertThat(dryRunOutput).doesNotExist();

        assertInitSuccess(parseJsonLines(initResult.stdout()).get(0), initialized, "MCP Test Plan", "MCP Users");
        assertSetSuccess(setResult.message, setOutput, setResult.reference, "HTTPSampler\\.domain");
        List<Map<String, Object>> applyMessages = parseJsonLines(applyResult.stdout());
        assertApplySuccess(applyMessages.get(0), applyOutput, Boolean.FALSE, applyOutput.toString());
        assertApplySuccess(applyMessages.get(1), dryRunOutput, Boolean.TRUE, null);
        assertThat(new String(Files.readAllBytes(setOutput), StandardCharsets.UTF_8))
                .contains("MCP Renamed Plan");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contradictoryApplyWriteModes")
    void applyRejectsContradictoryWriteModesWithoutMutatingRequestedTargets(
            String name, boolean existingOutput, String argumentTemplate) throws IOException {
        Path input = tempDir.resolve(name + "-input.jmx");
        Path output = tempDir.resolve(name + "-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] originalInput = Files.readAllBytes(input);
        if (existingOutput) {
            Files.write(output, WRITE_MODE_SENTINEL);
        }
        String modeArguments = argumentTemplate
                .replace("$INPUT", jsonString(input.toString()))
                .replace("$OUT", jsonString(output.toString()));

        ServerResult result = runServer(call(30, "apply",
                "\"file\":" + jsonString(input.toString())
                        + ",\"patchYaml\":" + jsonString("changes: []\n")
                        + ",\"jmeter_home\":" + jsonString(DefaultLocalProfileQaFixtures.cached().localHome().toString())
                        + modeArguments));

        assertThat(result.exitCode()).as(name).isZero();
        assertThat(result.stderr()).as(name).isEmpty();
        Map<String, Object> toolResult = mapping(parseJsonLines(result.stdout()).get(0).get("result"));
        assertThat(toolResult).as(name).containsEntry("isError", Boolean.TRUE);
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        assertThat(structured).as(name).containsEntry("exitStatus", 2);
        assertThat(list(structured.get("diagnostics"))).as(name).singleElement()
                .satisfies(diagnostic -> assertThat(mapping(diagnostic))
                        .containsEntry("code", "USAGE_ERROR")
                        .containsEntry("category", "usage"));
        assertThat(Files.readAllBytes(input)).as(name + " input").containsExactly(originalInput);
        if (existingOutput) {
            assertThat(Files.readAllBytes(output)).as(name + " output").containsExactly(WRITE_MODE_SENTINEL);
        } else {
            assertThat(output).as(name + " output").doesNotExist();
        }
    }

    private static Stream<Arguments> contradictoryApplyWriteModes() {
        return Stream.of(
                Arguments.of("dry-run-with-override", false, ",\"dryRun\":true,\"override\":true"),
                Arguments.of("dry-run-with-force-out", false, ",\"dryRun\":true,\"forceOut\":true"),
                Arguments.of("dry-run-with-out-and-force-out", true,
                        ",\"dryRun\":true,\"out\":$OUT,\"forceOut\":true"),
                Arguments.of("out-with-override", false, ",\"out\":$OUT,\"override\":true"),
                Arguments.of("override-with-force-out", false, ",\"override\":true,\"forceOut\":true"),
                Arguments.of("blank-out", false, ",\"out\":\"\""),
                Arguments.of("force-out-without-out", false, ",\"forceOut\":true"),
                Arguments.of("out-same-as-input", false, ",\"out\":$INPUT,\"forceOut\":true"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPatchSources")
    void applyRejectsInvalidPatchSourceCardinalityBeforeWritingAndRecovers(
            String name, String patchArguments) throws IOException {
        Path input = tempDir.resolve(name + "-input.jmx");
        Path output = tempDir.resolve(name + "-output.jmx");
        Path patch = tempDir.resolve(name + "-patch.yaml");
        Files.copy(fixture("simple-http.jmx"), input);
        StringBuilder sixOperationPatch = new StringBuilder("changes:\n");
        for (int index = 1; index <= 6; index++) {
            sixOperationPatch.append("  - op: set\n")
                    .append("    ref: jmx_330976848c8e\n")
                    .append("    property: [HTTPSampler.domain]\n")
                    .append("    value: patch-file-").append(index).append(".example\n");
        }
        Files.write(patch, sixOperationPatch.toString().getBytes(StandardCharsets.UTF_8));
        byte[] originalInput = Files.readAllBytes(input);

        ServerResult result = runServer(
                call(40, "apply", "\"file\":" + jsonString(input.toString())
                        + patchArguments.replace("$PATCH", jsonString(patch.toString()))
                        + ",\"out\":" + jsonString(output.toString()) + ",\"jmeter_home\":"
                        + jsonString(DefaultLocalProfileQaFixtures.cached().localHome().toString())),
                call(41, "read", "\"file\":" + jsonString(input.toString()) + ",\"jmeter_home\":"
                        + jsonString(DefaultLocalProfileQaFixtures.cached().localHome().toString())));

        assertThat(result.exitCode()).as(name).isZero();
        assertThat(result.stderr()).as(name).isEmpty();
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        Map<String, Object> rejected = mapping(messages.get(0).get("result"));
        assertThat(rejected).as(name).containsEntry("isError", Boolean.TRUE);
        Map<String, Object> structured = mapping(rejected.get("structuredContent"));
        assertThat(structured).as(name).containsEntry("exitStatus", 2).containsKey("recoveryGuidance");
        assertThat(mapping(messages.get(1).get("result"))).containsEntry("isError", Boolean.FALSE);
        assertThat(Files.readAllBytes(input)).containsExactly(originalInput);
        assertThat(output).doesNotExist();
    }

    private static Stream<Arguments> invalidPatchSources() {
        return Stream.of(
                Arguments.of("missing-patch-source", ""),
                Arguments.of("both-patch-sources", ",\"patchFile\":$PATCH,\"patchYaml\":\"changes: []\\n\""));
    }

    @Test
    void invalidSetDoesNotOverwriteExistingOutput() throws IOException {
        Path existing = tempDir.resolve("existing.jmx");
        Files.write(existing, "preserve-existing".getBytes(StandardCharsets.UTF_8));

        ServerResult result = runServer(call(10, "set",
                "\"file\":" + jsonString(fixture("simple-http.jmx").toString())
                        + ",\"locator\":\"jmx_missing\",\"property\":[\"HTTPSampler.domain\"],"
                        + "\"value\":\"changed.example\",\"out\":" + jsonString(existing.toString())
                        + ",\"forceOut\":true,\"jmeter_home\":"
                        + jsonString(DefaultLocalProfileQaFixtures.cached().localHome().toString())));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(new String(Files.readAllBytes(existing), StandardCharsets.UTF_8)).isEqualTo("preserve-existing");
        Map<String, Object> toolResult = mapping(parseJsonLines(result.stdout()).get(0).get("result"));
        assertThat(toolResult).containsEntry("isError", Boolean.TRUE);
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        assertThat(structured).containsEntry("exitStatus", 3);
        assertThat(list(structured.get("diagnostics"))).singleElement()
                .satisfies(diagnostic -> assertThat(mapping(diagnostic))
                        .containsEntry("code", "MCP_REF_NOT_FOUND")
                        .containsEntry("category", "usage"));
    }

    @Test
    void invalidTemplateUsesGenericUsageCodeWhileMissingSessionRefRetainsStableCode() throws IOException {
        Path input = tempDir.resolve("invalid-template-input.jmx");
        Path output = tempDir.resolve("invalid-template-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        String home = jsonString(DefaultLocalProfileQaFixtures.cached().localHome().toString());
        String invalidTemplate = "changes:\n"
                + "  - set:\n"
                + "      ref: jmx_missing\n"
                + "      properties:\n"
                + "        - property: [Asserion.test_strings]\n"
                + "          type: collection\n"
                + "          value:\n"
                + "            property_class: org.apache.jmeter.testelement.property.CollectionProperty\n"
                + "            items: []\n";

        ServerResult result = runServer(
                call(50, "apply", "\"file\":" + jsonString(input.toString())
                        + ",\"patchYaml\":" + jsonString(invalidTemplate)
                        + ",\"dryRun\":true,\"jmeter_home\":" + home),
                call(51, "set", "\"file\":" + jsonString(input.toString())
                        + ",\"locator\":\"jmx_missing\",\"property\":[\"HTTPSampler.domain\"],"
                        + "\"value\":\"unchanged.example\",\"out\":" + jsonString(output.toString())
                        + ",\"forceOut\":true,\"jmeter_home\":" + home));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        Map<String, Object> invalidTemplateResult = mapping(messages.get(0).get("result"));
        assertThat(list(mapping(invalidTemplateResult.get("structuredContent")).get("diagnostics")))
                .singleElement().satisfies(diagnostic -> assertThat(mapping(diagnostic))
                        .containsEntry("code", "USAGE_ERROR")
                        .containsEntry("category", "usage"));
        Map<String, Object> missingRefResult = mapping(messages.get(1).get("result"));
        assertThat(list(mapping(missingRefResult.get("structuredContent")).get("diagnostics")))
                .singleElement().satisfies(diagnostic -> assertThat(mapping(diagnostic))
                        .containsEntry("code", "MCP_REF_NOT_FOUND")
                        .containsEntry("category", "usage"));
        assertThat(output).doesNotExist();
    }

    @ParameterizedTest(name = "local missing {0}")
    @MethodSource("missingLocalLocatorRoles")
    void localApplyMissingLocatorReturnsSemanticLocatorErrorWithoutMutation(String role, String operation) throws IOException {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Path output = tempDir.resolve("mcp-local-missing-ref.jmx");
        byte[] sentinel = "mcp-local-missing-ref-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(output, sentinel);

        ServerResult result = runServer(call(12, "apply",
                "\"file\":" + jsonString(fixtures.root().resolve("basic.jmx").toString())
                        + ",\"patchYaml\":" + jsonString(
                                "changes:\n  - " + operation.replace("\n", "\n    ") + "\n")
                        + ",\"out\":" + jsonString(output.toString())
                        + ",\"forceOut\":true,\"jmeter_home\":"
                        + jsonString(fixtures.localHome().toString())));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        Map<String, Object> toolResult = mapping(parseJsonLines(result.stdout()).get(0).get("result"));
        assertThat(toolResult).containsEntry("isError", Boolean.TRUE);
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        assertThat(structured).containsEntry("exitStatus", 3);
        assertThat(list(structured.get("diagnostics"))).singleElement()
                .satisfies(diagnostic -> assertThat(mapping(diagnostic))
                        .containsEntry("code", "MCP_REF_NOT_FOUND")
                        .containsEntry("category", "usage"));
        assertThat(structured.get("recoveryGuidance").toString())
                .containsIgnoringCase("same file")
                .containsIgnoringCase("same JMeter home")
                .containsIgnoringCase("fresh ref");
        assertThat(Files.readAllBytes(output)).containsExactly(sentinel);
    }

    private static Stream<Arguments> missingLocalLocatorRoles() {
        return Stream.of(
                Arguments.of("set ref", "set:\n  ref: jmx_absent\n  properties:\n    - property: [TestElement.name]\n      value: changed\n      type: string"),
                Arguments.of("delete ref", "delete:\n  ref: jmx_absent\n  component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"),
                Arguments.of("move ref", "move:\n  ref: jmx_absent\n  parent: jmx_19871e6efa95\n  position: last"),
                Arguments.of("move parent", "move:\n  ref: jmx_330976848c8e\n  parent: jmx_absent\n  position: last"),
                Arguments.of("move before", "move:\n  ref: jmx_330976848c8e\n  parent: jmx_19871e6efa95\n  before: jmx_absent"),
                Arguments.of("move after", "move:\n  ref: jmx_330976848c8e\n  parent: jmx_19871e6efa95\n  after: jmx_absent"),
                Arguments.of("add parent", "add:\n  parent: jmx_absent\n  component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n  position: last"),
                Arguments.of("add before", "add:\n  parent: jmx_19871e6efa95\n  component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n  before: jmx_absent"),
                Arguments.of("add after", "add:\n  parent: jmx_19871e6efa95\n  component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n  after: jmx_absent"));
    }

    @Test
    void structuredSetWithConflictingScalarTypeIsRejectedBeforeMutation() throws IOException {
        Path existing = tempDir.resolve("conflicting-type.jmx");
        byte[] original = "preserve-conflicting-type-output".getBytes(StandardCharsets.UTF_8);
        Files.write(existing, original);

        ServerResult result = runServer(call(11, "set",
                "\"file\":" + jsonString(fixture("simple-http.jmx").toString())
                        + ",\"locator\":\"jmx_330976848c8e\",\"property\":[\"HTTPsampler.Arguments\"],"
                        + "\"value\":{\"rows\":[{\"name\":\"q\",\"value\":\"abc\"}]},\"type\":\"string\","
                        + "\"out\":" + jsonString(existing.toString())
                        + ",\"forceOut\":true,\"jmeter_home\":"
                        + jsonString(DefaultLocalProfileQaFixtures.cached().localHome().toString())));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(Files.readAllBytes(existing)).containsExactly(original);
        Map<String, Object> toolResult = mapping(parseJsonLines(result.stdout()).get(0).get("result"));
        assertThat(toolResult).containsEntry("isError", Boolean.TRUE);
        assertThat(mapping(toolResult.get("structuredContent")))
                .containsEntry("exitStatus", 2);
        assertThat(mapping(list(toolResult.get("content")).get(0)).get("text").toString())
                .containsIgnoringCase("type")
                .containsIgnoringCase("arguments");
    }

    @Test
    void structuredSetAndReadUseTheSharedLocalArgumentService() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Path output = tempDir.resolve("mcp-http-arguments.jmx");
        String structuredValue = "{\"row_type\":\"org.apache.jmeter.protocol.http.util.HTTPArgument\","
                + "\"rows\":[{\"HTTPArgument.always_encode\":true,\"HTTPArgument.use_equals\":true,"
                + "\"HTTPArgument.content_type\":\"text/plain\",\"Argument.name\":\"phase6\","
                + "\"Argument.value\":\"mcp\",\"Argument.metadata\":\"=\"}]}";

        SessionSetResult setResult = runSessionSet(
                fixtures.root().resolve("basic.jmx"), output, fixtures.localHome(),
                "HTTPsampler\\.Arguments", structuredValue, "rows");
        ServerResult readResultServer = runServer(
                call(21, "read", "\"file\":" + jsonString(output.toString())
                        + ",\"jmeter_home\":" + jsonString(fixtures.localHome().toString())
                        + ",\"depth\":5,\"properties\":\"all\""));

        assertThat(setResult.exitCode).isZero();
        assertThat(setResult.stderr).isEmpty();
        assertThat(readResultServer.exitCode()).isZero();
        assertSetSuccess(setResult.message, output, setResult.reference, "HTTPsampler\\.Arguments");
        Map<String, Object> readResult = mapping(parseJsonLines(readResultServer.stdout()).get(0).get("result"));
        assertThat(readResult).containsEntry("isError", Boolean.FALSE);
        assertThat(mapping(list(readResult.get("content")).get(0)).get("text").toString())
                .contains("row_type: org.apache.jmeter.protocol.http.util.HTTPArgument")
                .contains("name: phase6")
                .contains("value: mcp")
                .contains("metadata: =")
                .contains("HTTPArgument.always_encode: true")
                .contains("HTTPArgument.use_equals: true")
                .contains("HTTPArgument.content_type: text/plain")
                .contains("type: rows");
    }

    private static Map<String, Object> assertToolSuccess(Map<String, Object> message, String command, Path output) {
        Map<String, Object> toolResult = mapping(message.get("result"));
        assertThat(toolResult).containsEntry("isError", Boolean.FALSE);
        assertThat(list(toolResult.get("content"))).singleElement()
                .satisfies(content -> assertThat(mapping(content).get("text").toString()).isNotBlank());
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        assertThat(structured).containsEntry("exitStatus", 0);
        assertThat(mapping(structured.get("data")))
                .containsEntry("command", command)
                .containsEntry("output", output.toString());
        assertThat(list(structured.get("diagnostics"))).isEmpty();
        return mapping(structured.get("data"));
    }

    private static void assertInitSuccess(
            Map<String, Object> message, Path output, String testPlanName, String threadGroupName) {
        Map<String, Object> data = assertToolSuccess(message, "init", output);
        assertThat(mapping(data.get("createdPlan")))
                .containsEntry("testPlanName", testPlanName)
                .containsEntry("threadGroupName", threadGroupName);
        Map<String, Object> toolResult = mapping(message.get("result"));
        String rendered = mapping(list(toolResult.get("content")).get(0)).get("text").toString();
        List<String> references = componentReferences(rendered);
        assertThat(references).hasSize(2)
                .allMatch(reference -> reference.matches("[A-Za-z0-9_-]{16}"))
                .noneMatch(reference -> reference.startsWith("jmx_"));
    }

    private static void assertSetSuccess(
            Map<String, Object> message, Path output, String locator, String property) {
        Map<String, Object> data = assertToolSuccess(message, "set", output);
        assertThat(mapping(data.get("changedProperty")))
                .containsEntry("locator", locator)
                .containsEntry("property", Arrays.asList(property.replace("\\.", ".")));
    }

    private static void assertApplySuccess(
            Map<String, Object> message, Path output, Boolean dryRun, String writtenTarget) {
        Map<String, Object> data = assertToolSuccess(message, "apply", output);
        String expectedMode = dryRun.booleanValue() ? "dry-run" : "copy";
        assertThat(data)
                .containsEntry("dryRun", dryRun)
                .containsEntry("writtenTarget", writtenTarget)
                .containsEntry("writeMode", expectedMode)
                .containsEntry("appliedCount", Integer.valueOf(0))
                .containsEntry("createdRefs", java.util.Collections.emptyList())
                .containsEntry("deletedRefs", java.util.Collections.emptyList());
        Map<String, Object> toolResult = mapping(message.get("result"));
        String text = String.valueOf(mapping(list(toolResult.get("content")).get(0)).get("text"));
        assertThat(mapping(new Yaml().load(text))).isEqualTo(data);
    }

    private static List<Map<String, Object>> toolsList() {
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"j4a-write-tool-test\",\"version\":\"0.0.0\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"shutdown\",\"params\":{}}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(3);
        return list(mapping(messages.get(1).get("result")).get("tools")).stream()
                .map(J4aMcpWriteToolsTest::mapping)
                .collect(Collectors.toList());
    }

    private static void assertSchema(
            List<Map<String, Object>> tools,
            String toolName,
            List<String> required,
            List<String> properties,
            boolean fileOrPathRequired) {
        Map<String, Object> tool = tools.stream()
                .filter(candidate -> toolName.equals(candidate.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing tool: " + toolName));
        Map<String, Object> schema = mapping(tool.get("inputSchema"));
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsEntry("additionalProperties", Boolean.FALSE);
        assertThat(list(schema.get("required"))).containsExactlyElementsOf(required);
        assertThat(mapping(schema.get("properties")).keySet()).containsAll(properties);
        if (fileOrPathRequired) {
            assertThat(list(schema.get("allOf"))).anySatisfy(group -> {
                List<Object> oneOf = list(mapping(group).get("oneOf"));
                assertThat(oneOf).anySatisfy(candidate -> assertThat(mapping(candidate).get("required"))
                        .isEqualTo(Arrays.asList("file")));
                assertThat(oneOf).anySatisfy(candidate -> assertThat(mapping(candidate).get("required"))
                        .isEqualTo(Arrays.asList("path")));
            });
        } else {
            assertThat(schema).doesNotContainKey("anyOf");
        }
    }

    private static void assertApplyWriteModeSchema(List<Map<String, Object>> tools) {
        Map<String, Object> apply = tools.stream()
                .filter(candidate -> "apply".equals(candidate.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing tool: apply"));
        Map<String, Object> schema = mapping(apply.get("inputSchema"));
        assertThat(list(schema.get("allOf"))).anySatisfy(group -> {
            List<Object> modes = list(mapping(group).get("oneOf"));
            assertThat(modes).hasSize(3);
            assertThat(modes).anySatisfy(mode -> assertThat(mapping(mode).get("required"))
                    .isEqualTo(Arrays.asList("dryRun")));
            assertThat(modes).anySatisfy(mode -> assertThat(mapping(mode).get("required"))
                    .isEqualTo(Arrays.asList("override")));
            assertThat(modes).anySatisfy(mode -> assertThat(mapping(mode).get("required"))
                    .isEqualTo(Arrays.asList("out")));
        });
        Map<String, Object> properties = mapping(schema.get("properties"));
        Map<String, Object> out = mapping(properties.get("out"));
        assertThat(out).containsEntry("minLength", 1).containsKey("pattern");
        assertThat(schemaAcceptsOut(out, "")).isFalse();
        assertThat(schemaAcceptsOut(out, "   \t")).isFalse();
        assertThat(schemaAcceptsOut(out, "copy.jmx")).isTrue();
        assertThat(schemaAcceptsOut(out, " dry-run-copy.jmx ")).isTrue();
        assertThat(mapping(properties.get("dryRun")).get("description").toString()).contains("ignored");
        assertThat(mapping(properties.get("forceOut")).get("description").toString())
                .contains("Never authorizes out equal to input");
    }

    private static boolean schemaAcceptsOut(Map<String, Object> outSchema, String value) {
        int minimumLength = ((Number) outSchema.get("minLength")).intValue();
        String pattern = String.valueOf(outSchema.get("pattern"));
        return value.length() >= minimumLength && value.matches(pattern);
    }

    private static String call(int id, String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":{" + arguments + "}}}";
    }

    private static ServerResult runServer(String... lines) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        String input = String.join("\n", lines) + "\n";

        int exitCode = J4aMcpServer.run(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true),
                new PrintStream(stderr, true),
                new String[0]);

        return new ServerResult(
                exitCode,
                new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    private static SessionSetResult runSessionSet(
            Path source, Path target, Path jmeterHome,
            String property, String jsonValue, String type) throws IOException {
        PipedInputStream serverInput = new PipedInputStream();
        PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        PipedInputStream clientInput = new PipedInputStream();
        PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicInteger exitCode = new AtomicInteger(-1);
        Thread server = new Thread(() -> exitCode.set(J4aMcpServer.run(
                serverInput, new PrintStream(serverOutput, true), new PrintStream(stderr, true), new String[0])));
        server.start();
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8), true);
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8))) {
            writer.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"session-set-test\",\"version\":\"0.0.0\"}}}");
            reader.readLine();
            writer.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
            writer.println(call(2, "read", "\"file\":" + jsonString(source.toString())
                    + ",\"jmeter_home\":" + jsonString(jmeterHome.toString()) + ",\"depth\":5"));
            Map<String, Object> readMessage = mapping(new Yaml().load(reader.readLine()));
            String rendered = mapping(list(mapping(readMessage.get("result")).get("content")).get(0))
                    .get("text").toString();
            List<String> references = componentReferences(rendered);
            String reference = references.get(references.size() - 1);
            String typeArgument = type == null ? "" : ",\"type\":" + jsonString(type);
            writer.println(call(3, "set", "\"file\":" + jsonString(source.toString())
                    + ",\"locator\":" + jsonString(reference)
                    + ",\"property\":[" + jsonString(property.replace("\\.", ".")) + "]"
                    + ",\"value\":" + jsonValue + typeArgument
                    + ",\"out\":" + jsonString(target.toString())
                    + ",\"forceOut\":true,\"jmeter_home\":" + jsonString(jmeterHome.toString())));
            Map<String, Object> setMessage = mapping(new Yaml().load(reader.readLine()));
            writer.println("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"shutdown\",\"params\":{}}");
            reader.readLine();
            writer.close();
            try {
                server.join(30000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for MCP server", exception);
            }
            return new SessionSetResult(exitCode.get(),
                    new String(stderr.toByteArray(), StandardCharsets.UTF_8), reference, setMessage);
        }
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(J4aMcpWriteToolsTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Fixture URI is invalid: " + name, exception);
        }
    }

    private static String jsonString(String value) {
        return McpJson.write(value);
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseJsonLines(String stdout) {
        assertThat(stdout).as("stdout must contain only newline-delimited JSON-RPC messages").isNotBlank();
        Yaml yaml = new Yaml();
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        for (String line : stdout.split("\\R")) {
            if (line.isEmpty()) {
                continue;
            }
            Object parsed = yaml.load(line);
            assertThat(parsed).as("stdout line must parse as a JSON object: %s", line).isInstanceOf(Map.class);
            messages.add((Map<String, Object>) parsed);
        }
        return messages;
    }

    private static List<String> componentReferences(String yamlText) {
        List<String> references = new ArrayList<String>();
        collectReferences(new Yaml().load(yamlText), references);
        return references;
    }

    private static void collectReferences(Object value, List<String> references) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if ("ref".equals(entry.getKey()) && entry.getValue() instanceof String) {
                    references.add((String) entry.getValue());
                }
                collectReferences(entry.getValue(), references);
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectReferences(item, references);
            }
        }
    }

    private static final class ServerResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private ServerResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private int exitCode() {
            return exitCode;
        }

        private String stdout() {
            return stdout;
        }

        private String stderr() {
            return stderr;
        }
    }

    private static final class SessionSetResult {
        private final int exitCode;
        private final String stderr;
        private final String reference;
        private final Map<String, Object> message;

        private SessionSetResult(
                int exitCode, String stderr, String reference, Map<String, Object> message) {
            this.exitCode = exitCode;
            this.stderr = stderr;
            this.reference = reference;
            this.message = message;
        }
    }
}
