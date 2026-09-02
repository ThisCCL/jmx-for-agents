package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class J4aMcpReadOnlyToolsTest {
    @Test
    void defaultAndNoneReadRemainPropertyFreeWhileAllKeepsProperties() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        String file = McpJson.write(fixtures.root().resolve("basic.jmx").toString());
        String home = McpJson.write(fixtures.localHome().toString());
        ServerResult result = runServer(
                call(1, "read", "\"file\":" + file + ",\"jmeter_home\":" + home),
                call(2, "read", "\"file\":" + file + ",\"jmeter_home\":" + home
                        + ",\"properties\":\"none\""),
                call(3, "read", "\"file\":" + file + ",\"jmeter_home\":" + home
                        + ",\"properties\":\"all\""));

        assertThat(result.exitCode).isZero();
        assertThat(result.stderr).isEmpty();
        List<Object> messages = messages(result.stdout);
        Map<String, Object> defaultRead = toolResult(messages.get(0));
        Map<String, Object> noneRead = toolResult(messages.get(1));
        Map<String, Object> allRead = toolResult(messages.get(2));

        assertThat(defaultRead).containsEntry("isError", Boolean.FALSE);
        assertThat(noneRead).containsEntry("isError", Boolean.FALSE);
        assertThat(structuredData(defaultRead)).isEqualTo(structuredData(noneRead));
        assertThat(yamlContent(defaultRead)).isEqualTo(yamlContent(noneRead));
        assertThat(references(yamlContent(defaultRead))).isNotEmpty();
        assertThat(propertyDocuments(yamlContent(defaultRead))).isEmpty();
        assertThat(propertyDocuments(yamlContent(noneRead))).isEmpty();
        assertThat(propertyDocuments(yamlContent(allRead))).isNotEmpty();
    }

    @Test
    void readSchemaAndRuntimeAcceptWritableWhileRejectingInvalidModesBeforeExecution() throws Exception {
        Map<String, Object> readTool = McpTools.list().stream()
                .map(J4aMcpReadOnlyToolsTest::mapping)
                .filter(tool -> "read".equals(tool.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("read MCP tool is missing"));
        Map<String, Object> schema = mapping(readTool.get("inputSchema"));
        Map<String, Object> schemaProperties = mapping(schema.get("properties"));
        assertThat(list(mapping(schemaProperties.get("properties")).get("enum")))
                .containsExactly("none", "key", "all", "writable");

        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        String file = McpJson.write(fixtures.root().resolve("basic.jmx").toString());
        String home = McpJson.write(fixtures.localHome().toString());
        ServerResult result = runServer(
                call(1, "read", "\"file\":" + file + ",\"jmeter_home\":" + home
                        + ",\"properties\":\"all\""),
                call(2, "read", "\"file\":" + file + ",\"jmeter_home\":" + home
                        + ",\"properties\":\"writable\""),
                call(3, "read", "\"file\":\"missing.jmx\",\"properties\":\"bogus\""));

        assertThat(result.exitCode).isZero();
        List<Object> messages = messages(result.stdout);
        Map<String, Object> allRead = toolResult(messages.get(0));
        Map<String, Object> writableRead = toolResult(messages.get(1));
        Map<String, Object> invalidRead = toolResult(messages.get(2));
        assertThat(allRead).containsEntry("isError", Boolean.FALSE);
        assertThat(writableRead).containsEntry("isError", Boolean.FALSE);
        assertThat(toolText(writableRead).getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(toolText(allRead).getBytes(StandardCharsets.UTF_8).length);
        assertThat(propertyDocuments(yamlContent(writableRead)))
                .containsExactlyElementsOf(propertyDocuments(yamlContent(allRead)));
        assertThat(invalidRead).containsEntry("isError", Boolean.TRUE);
        assertThat(String.valueOf(mapping(invalidRead.get("structuredContent")).get("diagnostics")))
                .contains("properties", "none, key, all, or writable")
                .doesNotContain("missing.jmx");
    }

    @Test
    void readAndComponentsExposeNativeScalarArraysAndRejectRemovedAddressMode() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        String file = McpJson.write(fixtures.unrepresentableAddressJmx().toString());
        String home = McpJson.write(fixtures.localHome().toString());
        String component = McpJson.write(
                DefaultLocalProfileQaFixtures.UNREPRESENTABLE_ADDRESS_PLUGIN_CLASS + "Gui");
        ServerResult result = runServer(
                call(201, "read", "\"file\":" + file + ",\"jmeter_home\":" + home
                        + ",\"properties\":\"all\""),
                call(202, "read", "\"file\":" + file + ",\"jmeter_home\":" + home
                        + ",\"properties\":\"all\",\"propertyAddress\":\"segments\""),
                call(203, "components", "\"component\":" + component + ",\"jmeter_home\":" + home),
                call(204, "components", "\"component\":" + component + ",\"jmeter_home\":" + home
                        + ",\"propertyAddress\":\"segments\""));

        assertThat(result.exitCode).isZero();
        assertThat(result.stderr).isEmpty();
        List<Object> responses = messages(result.stdout);
        Map<String, Object> read = toolResult(responses.get(0));
        Map<String, Object> rejectedRead = toolResult(responses.get(1));
        Map<String, Object> components = toolResult(responses.get(2));
        Map<String, Object> rejectedComponents = toolResult(responses.get(3));

        assertThat(read).containsEntry("isError", Boolean.FALSE);
        assertThat(components).containsEntry("isError", Boolean.FALSE);
        assertThat(propertyDocuments(yamlContent(read)))
                .anySatisfy(property -> assertThat(property.get("property"))
                        .isEqualTo(java.util.Collections.singletonList("can't")));
        assertThat(list(mapping(yamlContent(components)).get("properties")))
                .anySatisfy(property -> assertThat(mapping(property).get("property"))
                        .isEqualTo(java.util.Collections.singletonList("can't")));
        assertRemovedAddressMode(rejectedRead);
        assertRemovedAddressMode(rejectedComponents);
    }

    @Test
    void readKeepsOpaqueRefsStableAcrossDepthAndUnknownFocusFailure() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        String file = McpJson.write(fixtures.root().resolve("basic.jmx").toString());
        String home = McpJson.write(fixtures.localHome().toString());
        ServerResult result = runServer(
                call(101, "read", "\"file\":" + file + ",\"jmeter_home\":" + home + ",\"depth\":0"),
                call(102, "read", "\"file\":" + file + ",\"jmeter_home\":" + home + ",\"depth\":5"),
                call(103, "read", "\"file\":" + file + ",\"jmeter_home\":" + home
                        + ",\"ref\":\"AAAAAAAAAAAAAAAA\""),
                call(104, "read", "\"file\":" + file + ",\"jmeter_home\":" + home + ",\"depth\":0"));

        assertThat(result.exitCode).isZero();
        assertThat(result.stderr).isEmpty();
        List<Object> messages = messages(result.stdout);
        assertThat(messages).hasSize(4);
        Map<String, Object> shallow = toolResult(messages.get(0));
        Map<String, Object> deep = toolResult(messages.get(1));
        Map<String, Object> unknown = toolResult(messages.get(2));
        Map<String, Object> repeated = toolResult(messages.get(3));

        assertThat(shallow).containsEntry("isError", Boolean.FALSE);
        assertThat(deep).containsEntry("isError", Boolean.FALSE);
        assertThat(repeated).containsEntry("isError", Boolean.FALSE);
        List<String> shallowRefs = references(yamlContent(shallow));
        List<String> deepRefs = references(yamlContent(deep));
        assertThat(shallowRefs).hasSize(1);
        assertThat(deepRefs.size()).isGreaterThan(shallowRefs.size());
        assertThat(deepRefs.subList(0, shallowRefs.size())).containsExactlyElementsOf(shallowRefs);
        assertThat(new LinkedHashSet<String>(deepRefs)).hasSameSizeAs(deepRefs);
        assertThat(deepRefs).allMatch(reference -> reference.matches("[A-Za-z0-9_-]{16}"));
        assertThat(references(yamlContent(repeated))).containsExactlyElementsOf(shallowRefs);

        assertThat(unknown).containsEntry("isError", Boolean.TRUE);
        assertThat(list(mapping(unknown.get("structuredContent")).get("diagnostics")))
                .singleElement()
                .satisfies(diagnostic -> assertThat(mapping(diagnostic))
                        .containsEntry("code", "MCP_REF_NOT_FOUND")
                        .containsEntry("category", "usage")
                        .hasEntrySatisfying("suggestedNextAction", action -> assertThat(String.valueOf(action))
                                .contains("same file", "same JMeter home", "fresh ref")));
        assertThat(result.stdout.toLowerCase(Locale.ROOT))
                .doesNotContain("fingerprint", "snapshot", "generation");
    }

    @Test
    void validateComponentsAndCategoriesReturnStructuredSuccess() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        String home = McpJson.write(fixtures.localHome().toString());
        Path input = fixtures.root().resolve("basic.jmx");
        ServerResult result = runServer(
                call(1, "validate", "\"file\":" + McpJson.write(input.toString()) + ",\"jmeter_home\":" + home),
                call(2, "components", "\"category\":\"menu_generative_controller\",\"jmeter_home\":" + home),
                call(3, "categories", "\"jmeter_home\":" + home));

        assertThat(result.exitCode).isZero();
        assertThat(result.stderr).isEmpty();
        List<Object> messages = messages(result.stdout);
        assertThat(messages).hasSize(3);
        for (Object message : messages) {
            Map<String, Object> toolResult = mapping(mapping(message).get("result"));
            assertThat(toolResult).containsEntry("isError", Boolean.FALSE);
            assertThat(mapping(toolResult.get("structuredContent"))).containsEntry("exitStatus", 0);
        }
    }

    @Test
    void catalogToolsKeepYamlAndStructuredDataInExactFriendlyParity() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        String home = McpJson.write(fixtures.localHome().toString());
        ServerResult result = runServer(
                call(1, "categories", "\"jmeter_home\":" + home),
                call(2, "components", "\"category\":\"sampler\",\"jmeter_home\":" + home),
                call(3, "components", "\"category\":\"menu_generative_controller\",\"jmeter_home\":" + home),
                call(4, "components", "\"category\":\"not-a-category\",\"jmeter_home\":" + home));

        assertThat(result.exitCode).isZero();
        assertThat(result.stderr).isEmpty();
        List<Object> messages = messages(result.stdout);
        assertThat(messages).hasSize(4);

        Map<String, Object> categories = toolResult(messages.get(0));
        Map<String, Object> publicComponents = toolResult(messages.get(1));
        Map<String, Object> aliasComponents = toolResult(messages.get(2));
        for (Map<String, Object> success : java.util.Arrays.asList(
                categories, publicComponents, aliasComponents)) {
            assertThat(success).containsEntry("isError", Boolean.FALSE);
            assertThat(structuredData(success)).isEqualTo(yamlContent(success));
        }
        assertThat(yamlContent(aliasComponents)).isEqualTo(yamlContent(publicComponents));
        assertThat(toolText(aliasComponents)).doesNotContain("menu_generative_controller");

        Map<String, Object> unknown = toolResult(messages.get(3));
        assertThat(unknown).containsEntry("isError", Boolean.TRUE);
        Map<String, Object> unknownStructured = mapping(unknown.get("structuredContent"));
        assertThat(list(unknownStructured.get("diagnostics"))).singleElement().satisfies(item ->
                assertThat(mapping(item))
                        .containsEntry("code", "USAGE_ERROR")
                        .containsEntry("category", "usage")
                        .containsEntry("suggestedNextAction",
                                "Call the categories MCP tool to list valid category ids, then call the components MCP tool "
                                        + "with the selected category field."));
        assertThat(String.valueOf(unknownStructured.get("recoveryGuidance")))
                .contains("categories MCP tool", "components MCP tool", "category field")
                .doesNotContainIgnoringCase("categories ls", "CLI", "fallback", "retry", "replay");
        assertThat(toolText(unknown))
                .contains("categories MCP tool", "components MCP tool", "category field")
                .doesNotContainIgnoringCase("categories ls", "CLI", "fallback", "retry", "replay");
    }

    @Test
    void componentsOrdinaryDetailsAndDiagnosticsKeepExactCliProjectionParity() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        String home = McpJson.write(fixtures.localHome().toString());
        String component = McpJson.write(
                "io.github.thisccl.j4a.synthetic.ExtOnlySamplerGui");
        ServerResult result = runServer(
                call(21, "components", "\"component\":" + component + ",\"jmeter_home\":" + home),
                call(22, "components", "\"kind\":" + component
                        + ",\"details\":true,\"jmeter_home\":" + home),
                call(23, "components", "\"component\":" + component
                        + ",\"diagnostics\":true,\"jmeter_home\":" + home));

        assertThat(result.exitCode).isZero();
        assertThat(result.stderr).isEmpty();
        List<Object> responses = messages(result.stdout);
        Map<String, Object> ordinary = toolResult(responses.get(0));
        Map<String, Object> details = toolResult(responses.get(1));
        Map<String, Object> diagnostics = toolResult(responses.get(2));

        for (Map<String, Object> response : java.util.Arrays.asList(ordinary, details, diagnostics)) {
            assertThat(response).containsEntry("isError", Boolean.FALSE);
            assertThat(structuredData(response)).isEqualTo(yamlContent(response));
        }
        assertThat(structuredData(details)).isEqualTo(structuredData(ordinary));
        Map<String, Object> ordinaryDocument = mapping(structuredData(ordinary));
        for (Object value : list(ordinaryDocument.get("properties"))) {
            assertThat(mapping(value).keySet()).isSubsetOf(
                    "property", "type", "default", "value_shape", "row_type",
                    "row_properties", "value_template");
        }
        assertThat(toolText(ordinary)).doesNotContain(
                "key:", "writable:", "reason:", "ownership:", "representation_source:",
                "required_property_class:", "required_value_class:");
        assertThat(toolText(diagnostics)).contains(
                "key:", "writable:", "ownership:", "representation_source:");
    }

    @Test
    void emptyCatalogPayloadPreservesExactStructuredData() {
        final Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
        data.put("categories", java.util.Collections.singletonList(new java.util.LinkedHashMap<String, Object>()));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<Object>) data.get("categories")).get(0);
        row.put("category", "timer");
        row.put("label", "Timers");
        row.put("component_count", Integer.valueOf(0));
        row.put("components", java.util.Collections.emptyList());
        io.github.thisccl.j4a.cli.CommandResult result = new io.github.thisccl.j4a.cli.CommandResult() {
            @Override public int exitCode() { return 0; }
            @Override public String textOutput() {
                return "categories:\n- category: timer\n  label: Timers\n  component_count: 0\n  components: []\n";
            }
            @Override public Map<String, Object> structuredData() { return data; }
            @Override public List<io.github.thisccl.j4a.cli.CommandDiagnostic> diagnostics() {
                return java.util.Collections.emptyList();
            }
            @Override public String recoveryGuidance() { return null; }
        };

        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(result);

        assertThat(adapted).containsEntry("isError", Boolean.FALSE);
        assertThat(structuredData(adapted)).isEqualTo(yamlContent(adapted));
        assertThat(mapping(list(mapping(structuredData(adapted)).get("categories")).get(0)))
                .containsEntry("components", java.util.Collections.emptyList());
    }

    @Test
    void internalProbeToolNameIsRejected() {
        ServerResult result = runServer(call(404, "j4a_probe_addability", ""));

        Map<String, Object> message = mapping(messages(result.stdout).get(0));
        assertThat(mapping(message.get("error"))).containsEntry("code", -32601);
    }

    private static String call(int id, String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":{" + arguments + "}}}";
    }

    private static ServerResult runServer(String... requests) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        String input = String.join("\n", requests) + "\n";
        int exitCode = J4aMcpServer.run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true), new PrintStream(stderr, true), new String[0]);
        return new ServerResult(exitCode, new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    private static List<Object> messages(String stdout) {
        List<Object> messages = new ArrayList<Object>();
        for (String line : stdout.split("\\R")) {
            if (!line.trim().isEmpty()) messages.add(new Yaml().load(line));
        }
        return messages;
    }

    private static Map<String, Object> toolResult(Object message) {
        return mapping(mapping(message).get("result"));
    }

    private static Object structuredData(Map<String, Object> toolResult) {
        return mapping(toolResult.get("structuredContent")).get("data");
    }

    private static Object yamlContent(Map<String, Object> toolResult) {
        return new Yaml().load(toolText(toolResult));
    }

    private static String toolText(Map<String, Object> toolResult) {
        return String.valueOf(mapping(list(toolResult.get("content")).get(0)).get("text"));
    }

    private static void assertRemovedAddressMode(Map<String, Object> result) {
        assertThat(result).containsEntry("isError", Boolean.TRUE);
        Map<String, Object> structured = mapping(result.get("structuredContent"));
        assertThat(list(structured.get("diagnostics"))).singleElement().satisfies(diagnostic ->
                assertThat(mapping(diagnostic))
                        .containsEntry("code", "USAGE_ERROR")
                        .hasEntrySatisfying("message", message -> assertThat(String.valueOf(message))
                                .contains("unexpected argument", "propertyAddress")));
        assertThat(structured).doesNotContainKey("recovery");
    }

    private static List<String> references(Object value) {
        List<String> references = new ArrayList<String>();
        collectReferences(value, references);
        return references;
    }

    private static List<Map<String, Object>> propertyDocuments(Object value) {
        List<Map<String, Object>> properties = new ArrayList<Map<String, Object>>();
        collectPropertyDocuments(value, properties);
        return properties;
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

    private static void collectPropertyDocuments(Object value, List<Map<String, Object>> properties) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if ("properties".equals(entry.getKey()) && entry.getValue() instanceof Iterable<?>) {
                    for (Object property : (Iterable<?>) entry.getValue()) {
                        properties.add(mapping(property));
                    }
                }
                collectPropertyDocuments(entry.getValue(), properties);
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectPropertyDocuments(item, properties);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
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
    }
}
