package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

// allow: SIZE_OK — indivisible public tools/list schema and description contract suite.
class J4aMcpToolSurfaceTest {
    private static final List<String> PUBLIC_TOOL_NAMES = Arrays.asList(
            "read", "validate", "components", "categories", "apply", "init", "set");

    @Test
    void toolsListExposesOnlyPublicSubcommandsWithCliOptionSchemas() {
        List<Map<String, Object>> tools = toolsList();

        assertThat(tools.stream().map(tool -> String.valueOf(tool.get("name"))).collect(Collectors.toList()))
                .containsExactlyElementsOf(PUBLIC_TOOL_NAMES);

        assertSchema(tools, "read",
                Arrays.asList(),
                Arrays.asList("file", "path", "jmeter_home", "depth", "ref",
                        "properties", "includeDisabledDetails"),
                true);
        assertSchema(tools, "validate",
                Arrays.asList(),
                Arrays.asList("file", "path", "jmeter_home"),
                true);
        assertSchema(tools, "components",
                Arrays.asList(),
                Arrays.asList("jmeter_home", "category", "component", "kind", "componentToken",
                        "details", "diagnostics", "limit", "maxBytes", "cursor"),
                false);
        assertSchema(tools, "categories",
                Arrays.asList(),
                Arrays.asList("jmeter_home"),
                false);
    }

    @Test
    void packagedToolsListFitsTheAgentContextBudget() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("tools", new ArrayList<Object>(toolsList()));

        assertThat(McpJson.write(result).length()).isLessThanOrEqualTo(40_000);
    }

    @Test
    void applySchemaKeepsYamlSourcesAndAddsNoStructuredMutationInputs() {
        Map<String, Object> schema = schemaOf(toolsByName().get("apply"));
        Map<String, Object> properties = propertiesOf(toolsByName().get("apply"));

        assertThat(properties.keySet()).contains("patchYaml", "patchFile")
                .doesNotContain("changes", "pathSegments", "segments");
        assertAllOfRequiresExactlyOne(schema, "apply", "patchFile", "patchYaml");
    }

    @Test
    void unifiedPropertySchemaPublishesAddressesRowsAndExactTypes() {
        Map<String, Object> set = toolsByName().get("set");
        Map<String, Object> definitions = mapping(schemaOf(set).get("$defs"));
        Map<String, Object> collection = mapping(definitions.get("collectionValue"));
        Map<String, Object> setProperties = propertiesOf(set);

        assertThat(requiredOf(collection)).containsExactly("presence", "property_class");
        assertThat(mapping(collection.get("properties")).keySet())
                .containsExactly("presence", "property_class", "items");
        assertThat(definitions.keySet()).contains("rowsValue", "scalarAddress", "propertyDocument")
                .doesNotContain("headersValue", "argumentsValue", "cookiesValue", "http_filesValue");
        assertThat(list(mapping(setProperties.get("type")).get("enum"))).containsExactly(
                "string", "boolean", "int", "long", "float", "double", "null", "raw",
                "collection", "map", "element", "rows", "opaque");
        assertThat(mapping(setProperties.get("property")).get("$ref"))
                .isEqualTo("#/$defs/scalarAddress");
        Map<String, Object> address = mapping(definitions.get("scalarAddress"));
        assertThat(address).containsEntry("type", "array").containsEntry("minItems", Integer.valueOf(1));
        assertThat(address).doesNotContainKey("oneOf");
        List<Object> addressMembers = list(mapping(address.get("items")).get("oneOf"));
        assertThat(addressMembers).hasSize(2);
        assertThat(addressMembers).anySatisfy(member ->
                assertThat(mapping(member)).containsEntry("type", "string"));
        assertThat(addressMembers).anySatisfy(member ->
                assertThat(mapping(member))
                        .containsEntry("type", "integer")
                        .containsEntry("minimum", Integer.valueOf(0))
                        .containsEntry("maximum", Integer.valueOf(Integer.MAX_VALUE)));
        assertThat(list(mapping(definitions.get("rowsValue")).get("oneOf"))).hasSize(2);
    }

    @Test
    void inputSchemasEncodeRepresentableOpenSpecConstraints() {
        Map<String, Map<String, Object>> tools = toolsByName();

        for (String toolName : Arrays.asList("read", "validate", "apply", "set")) {
            assertAllOfRequiresExactlyOne(schemaOf(tools.get(toolName)), toolName, "file", "path");
        }

        assertAllOfRequiresExactlyOne(schemaOf(tools.get("apply")), "apply", "patchFile", "patchYaml");
        assertApplyAllowsDryRunOrDestination(schemaOf(tools.get("apply")));
        assertApplyOutRejectsBlankValues(schemaOf(tools.get("apply")));
        Map<String, Object> components = schemaOf(tools.get("components"));
        assertAllOfForbidsTogether(components, "component", "kind");
        assertAllOfForbidsTogether(components, "category", "component");
        assertAllOfForbidsTogether(components, "category", "kind");
        assertAllOfForbidsTogether(components, "category", "diagnostics");
        assertMarkerRequiresExactlyOneSelector(components, "details");
        assertMarkerRequiresExactlyOneSelector(components, "diagnostics");
        assertThat(requiredOf(schemaOf(tools.get("set"))))
                .as("set must require targeted edit inputs")
                .contains("locator", "property", "value");
        assertAllOfRequiresExactlyOne(schemaOf(tools.get("set")), "set destination", "out", "override");
        assertThat(requiredOf(schemaOf(tools.get("init")))).contains("out");
    }

    @Test
    void inputSchemasPublishReadBoundsEnumsAndTrueOnlyMarkers() {
        Map<String, Map<String, Object>> tools = toolsByName();
        Map<String, Object> readProperties = propertiesOf(tools.get("read"));

        assertThat(mapping(readProperties.get("depth")))
                .containsEntry("minimum", Integer.valueOf(0))
                .containsEntry("default", Integer.valueOf(1));
        assertThat(list(mapping(readProperties.get("properties")).get("enum")))
                .containsExactly("none", "key", "all", "writable");
        assertThat(readProperties).doesNotContainKey("propertyAddress");

        assertTrueMarker(readProperties, "includeDisabledDetails");
        Map<String, Object> componentProperties = propertiesOf(tools.get("components"));
        assertTrueMarker(componentProperties, "details");
        assertTrueMarker(componentProperties, "diagnostics");
        assertThat(mapping(componentProperties.get("limit")))
                .containsEntry("minimum", Integer.valueOf(1))
                .containsEntry("maximum", Integer.valueOf(50))
                .containsEntry("default", Integer.valueOf(20));
        assertTrueMarker(propertiesOf(tools.get("apply")), "dryRun");
        assertTrueMarker(propertiesOf(tools.get("apply")), "override");
        assertTrueMarker(propertiesOf(tools.get("apply")), "forceOut");
        assertTrueMarker(propertiesOf(tools.get("init")), "forceOut");
        assertTrueMarker(propertiesOf(tools.get("set")), "override");
        assertTrueMarker(propertiesOf(tools.get("set")), "forceOut");
    }

    @Test
    void componentsDiagnosticsRuntimeValidationMatchesAdvertisedModes() {
        assertThat(McpToolArgumentValidator.validate("components", arguments(
                "component", "example.Component", "diagnostics", Boolean.TRUE))).isNull();
        assertThat(McpToolArgumentValidator.validate("components", arguments(
                "diagnostics", Boolean.TRUE))).contains("diagnostics", "component", "kind");
        assertThat(McpToolArgumentValidator.validate("components", arguments(
                "component", "example.Component", "diagnostics", Boolean.FALSE)))
                .contains("diagnostics", "true");
        assertThat(McpToolArgumentValidator.validate("components", arguments(
                "category", "sampler", "component", "example.Component",
                "diagnostics", Boolean.TRUE)))
                .contains("category", "diagnostics", "exclusive");
    }

    @Test
    void scalarPropertyAddressRuntimeAcceptsOnlyStringsAndBoundedIntegers() {
        assertThat(McpToolArgumentValidator.validate("set", arguments(
                "file", "source.jmx", "locator", "ref", "property",
                Arrays.<Object>asList("ThreadGroup.main_controller", Integer.valueOf(0), "", Integer.MAX_VALUE),
                "value", "1", "type", "string", "out", "target.jmx"))).isNull();
        assertThat(McpToolArgumentValidator.validate("set", arguments(
                "file", "source.jmx", "locator", "ref", "property", "HTTPsampler\\.Arguments",
                "value", "1", "type", "string", "out", "target.jmx")))
                .contains("non-empty scalar array");
        assertThat(McpToolArgumentValidator.validate("set", arguments(
                "file", "source.jmx", "locator", "ref", "property",
                Collections.emptyList(),
                "value", "1", "type", "string", "out", "target.jmx")))
                .contains("at least one segment");
        for (Object invalid : Arrays.<Object>asList(
                arguments("property", "a"), Boolean.TRUE, null, Integer.valueOf(-1),
                Long.valueOf((long) Integer.MAX_VALUE + 1L), Double.valueOf(1.5d))) {
            assertThat(McpToolArgumentValidator.validate("set", arguments(
                    "file", "source.jmx", "locator", "ref", "property", Arrays.asList(invalid),
                    "value", "1", "type", "string", "out", "target.jmx")))
                    .as("invalid scalar address member %s", invalid)
                    .contains("string or integer", "0", String.valueOf(Integer.MAX_VALUE));
        }
        assertThat(McpToolArgumentValidator.validate("read", arguments(
                "file", "source.jmx", "propertyAddress", "segments")))
                .contains("unexpected argument", "propertyAddress");
        assertThat(McpToolArgumentValidator.validate("components", arguments(
                "propertyAddress", "canonical")))
                .contains("unexpected argument", "propertyAddress");
    }

    @Test
    void sourceResourcesAndLiveToolsListUseByteIdenticalDescriptions() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Map<String, Map<String, Object>> tools = toolsByName();

        for (String toolName : PUBLIC_TOOL_NAMES) {
            String resource = "io/github/thisccl/j4a/mcp/tool-descriptions/" + toolName + ".txt";
            byte[] source = Files.readAllBytes(Paths.get(
                    "src/main/resources/io/github/thisccl/j4a/mcp/tool-descriptions/" + toolName + ".txt"));
            String sourceDescription = new String(source, StandardCharsets.UTF_8).trim();
            String liveDescription = String.valueOf(tools.get(toolName).get("description"));

            assertThat(resourceBytes(classLoader, resource)).isEqualTo(source);
            assertThat(liveDescription).startsWith(sourceDescription);
        }
    }

    @Test
    void descriptionsPublishOnlyToolApplicableArrayAddressGuidance() throws Exception {
        Map<String, Object> guidance = canonicalGuidance();
        Map<String, Object> recovery = mapping(guidance.get("recovery_templates"));
        String generic = String.valueOf(guidance.get("generic_collection_example"));
        String semantic = String.valueOf(guidance.get("semantic_row_example"));
        String invalidShape = String.valueOf(recovery.get("invalid_collection_shape"));
        String samePath = String.valueOf(recovery.get("same_path_write"));
        String existingTarget = String.valueOf(recovery.get("existing_target"));
        String staleRef = String.valueOf(recovery.get("stale_mcp_ref"));
        Map<String, Map<String, Object>> tools = toolsByName();

        assertThat(semantic).contains("row_type", "row_properties", "required", "default", "rows");
        assertThat(String.valueOf(tools.get("apply").get("description"))).contains(
                "append", "insert", "remove", "declaration order");
        assertThat(String.valueOf(tools.get("init").get("description"))).contains("forceOut:true");
        assertThat(String.valueOf(tools.get("components").get("description"))).contains(
                "runtime-proven or runtime-metadata-unavailable",
                "successful empty evidence is proven",
                "only after worker replacement",
                "do not open windows",
                "execute samplers/load");

        assertGuidance(tools, "read",
                Arrays.asList("none|key|all|writable", "ref_scope", "source-bound",
                        "future read", "no automatic retry or mutation replay", staleRef),
                Arrays.asList("runtime_metadata_status", generic, semantic, invalidShape,
                        samePath, existingTarget));
        assertGuidance(tools, "components",
                Arrays.asList("runtime_metadata_status", "metadata-source availability",
                        "not GUI completeness"),
                Arrays.asList("none|key|all|writable", "ref_scope", generic, semantic,
                        invalidShape, samePath, existingTarget, staleRef));
        for (String toolName : Arrays.asList("apply", "set")) {
            assertGuidance(tools, toolName,
                    Arrays.asList("ref_scope", "future read", "no automatic retry or mutation replay",
                            generic, semantic, invalidShape, samePath, existingTarget, staleRef),
                    Arrays.asList("none|key|all|writable", "runtime_metadata_status"));
        }
        for (String toolName : Arrays.asList("validate", "categories", "init")) {
            assertGuidance(tools, toolName,
                    Collections.<String>emptyList(),
                    Arrays.asList("none|key|all|writable", "runtime_metadata_status", generic,
                            semantic, invalidShape, samePath, existingTarget, staleRef));
        }

        for (Map.Entry<String, Map<String, Object>> entry : tools.entrySet()) {
            String description = String.valueOf(entry.getValue().get("description"));
            assertThat(description.getBytes(StandardCharsets.UTF_8).length)
                    .as("%s description UTF-8 bytes", entry.getKey())
                    .isLessThan(4096);
            assertThat(description).doesNotContain(
                    "type: arguments", "type: headers", "http_files", "authorizations",
                    "dns_servers", "dns_hosts", "Registered collection types");
            assertThat(description).doesNotContain(
                    "propertyAddress canonical", "canonical address", "typed segment array",
                    "BindingGroup", "ObjectTableModel", "setAccessible", "reflection handle");
        }
        for (String toolName : Arrays.asList("read", "components", "apply", "set")) {
            assertThat(String.valueOf(tools.get(toolName).get("description")))
                    .containsIgnoringCase("native")
                    .containsIgnoringCase("array");
        }
    }

    @Test
    void schemaDescriptionsKeepWritableMetadataAndInvocationAwareRecoveryPrecise() {
        Map<String, Map<String, Object>> tools = toolsByName();
        Map<String, Object> read = propertiesOf(tools.get("read"));
        Map<String, Object> components = propertiesOf(tools.get("components"));
        Map<String, Object> set = propertiesOf(tools.get("set"));

        assertThat(mapping(read.get("properties")).get("description").toString())
                .contains("none|key|all|writable", "writable graph-capability subset");
        assertThat(mapping(read.get("ref")).get("description").toString())
                .contains("ref_scope.source", "structuredContent.recovery", "future read");
        assertThat(mapping(components.get("diagnostics")).get("description").toString())
                .contains("runtime_metadata_status", "metadata-source availability", "not GUI completeness");
        assertThat(mapping(set.get("locator")).get("description").toString())
                .contains("structuredContent.recovery", "original file/path spelling");
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
        Map<String, Object> schema = schemaOf(tool);
        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsEntry("additionalProperties", Boolean.FALSE);
        assertThat(list(schema.get("required"))).containsExactlyElementsOf(required);
        assertThat(mapping(schema.get("properties")).keySet()).containsExactlyElementsOf(properties);
        if (fileOrPathRequired) {
            assertAllOfRequiresExactlyOne(schema, toolName, "file", "path");
        } else {
            assertThat(schema).doesNotContainKey("anyOf");
        }
    }

    private static void assertGuidance(
            Map<String, Map<String, Object>> tools,
            String toolName,
            List<String> included,
            List<String> excluded) {
        String description = String.valueOf(tools.get(toolName).get("description"));
        if (!included.isEmpty()) {
            assertThat(description).as("%s applicable guidance", toolName)
                    .contains(included.toArray(new String[0]));
        }
        if (!excluded.isEmpty()) {
            assertThat(description).as("%s inapplicable guidance", toolName)
                    .doesNotContain(excluded.toArray(new String[0]));
        }
    }

    private static Map<String, Object> arguments(Object... entries) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            arguments.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return arguments;
    }

    private static Map<String, Map<String, Object>> toolsByName() {
        Map<String, Map<String, Object>> toolsByName = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> tool : toolsList()) {
            toolsByName.put(String.valueOf(tool.get("name")), tool);
        }
        return toolsByName;
    }

    private static List<Map<String, Object>> toolsList() {
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"j4a-tool-surface-test\",\"version\":\"0.0.0\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"shutdown\",\"params\":{}}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(3);
        return list(mapping(messages.get(1).get("result")).get("tools")).stream()
                .map(J4aMcpToolSurfaceTest::mapping)
                .collect(Collectors.toList());
    }

    private static void assertAllOfRequiresExactlyOne(
            Map<String, Object> schema,
            String schemaName,
            String firstProperty,
            String secondProperty) {
        assertThat(list(schema.get("allOf")))
                .as("%s allOf groups", schemaName)
                .anySatisfy(group -> {
                    List<Object> alternatives = list(mapping(group).get("oneOf"));
                    assertThat(alternatives).hasSize(2);
                    assertThat(alternatives).anySatisfy(candidate -> assertThat(requiredOf(mapping(candidate)))
                            .containsExactly(firstProperty));
                    assertThat(alternatives).anySatisfy(candidate -> assertThat(requiredOf(mapping(candidate)))
                            .containsExactly(secondProperty));
                });
    }

    private static void assertApplyAllowsDryRunOrDestination(Map<String, Object> schema) {
        assertThat(list(schema.get("allOf")))
                .as("apply allOf groups")
                .anySatisfy(group -> {
                    Map<String, Object> groupedSchema = mapping(group);
                    assertThat(list(groupedSchema.get("oneOf")))
                            .as("apply DRY_RUN/IN_PLACE/COPY oneOf")
                            .hasSize(3)
                            .anySatisfy(candidate -> {
                                Map<String, Object> option = mapping(candidate);
                                assertThat(requiredOf(option)).containsExactly("dryRun");
                                assertThat(mapping(mapping(option.get("properties")).get("dryRun")))
                                        .containsEntry("const", Boolean.TRUE);
                                assertForbiddenProperties(option, "override", "forceOut");
                            })
                            .anySatisfy(candidate -> {
                                Map<String, Object> option = mapping(candidate);
                                assertThat(requiredOf(option)).containsExactly("out");
                                assertForbiddenProperties(option, "dryRun", "override");
                            })
                            .anySatisfy(candidate -> {
                                Map<String, Object> option = mapping(candidate);
                                assertThat(requiredOf(option)).containsExactly("override");
                                assertForbiddenProperties(option, "dryRun", "out", "forceOut");
                            });
                });
    }

    private static void assertApplyOutRejectsBlankValues(Map<String, Object> schema) {
        assertThat(mapping(mapping(schema.get("properties")).get("out")))
                .containsEntry("type", "string")
                .containsEntry("minLength", Integer.valueOf(1))
                .containsEntry("pattern", ".*\\S.*");
    }

    private static void assertAllOfForbidsTogether(
            Map<String, Object> schema, String firstProperty, String secondProperty) {
        assertThat(list(schema.get("allOf"))).anySatisfy(clause ->
                assertThat(requiredOf(mapping(mapping(clause).get("not"))))
                        .containsExactly(firstProperty, secondProperty));
    }

    private static void assertMarkerRequiresExactlyOneSelector(Map<String, Object> schema, String marker) {
        assertThat(list(schema.get("allOf"))).anySatisfy(clause -> {
            Map<String, Object> condition = mapping(clause);
            assertThat(requiredOf(mapping(condition.get("if")))).containsExactly(marker);
            List<Object> alternatives = list(mapping(condition.get("then")).get("oneOf"));
            assertThat(alternatives).anySatisfy(candidate ->
                    assertThat(requiredOf(mapping(candidate))).containsExactly("component"));
            assertThat(alternatives).anySatisfy(candidate ->
                    assertThat(requiredOf(mapping(candidate))).containsExactly("kind"));
        });
    }

    private static void assertForbiddenProperties(Map<String, Object> option, String... propertyNames) {
        assertThat(list(option.get("allOf"))).hasSize(propertyNames.length);
        for (String propertyName : propertyNames) {
            assertThat(list(option.get("allOf")))
                    .anySatisfy(clause -> assertThat(requiredOf(mapping(mapping(clause).get("not"))))
                            .containsExactly(propertyName));
        }
    }

    private static void assertTrueMarker(Map<String, Object> properties, String propertyName) {
        assertThat(mapping(properties.get(propertyName))).containsEntry("const", Boolean.TRUE);
    }

    private static Map<String, Object> schemaOf(Map<String, Object> tool) {
        return mapping(tool.get("inputSchema"));
    }

    private static Map<String, Object> propertiesOf(Map<String, Object> tool) {
        return mapping(schemaOf(tool).get("properties"));
    }

    private static List<Object> requiredOf(Map<String, Object> schema) {
        Object required = schema.get("required");
        if (required == null) {
            return Collections.emptyList();
        }
        return list(required);
    }

    private static byte[] resourceBytes(ClassLoader classLoader, String resource) throws Exception {
        InputStream input = classLoader.getResourceAsStream(resource);
        assertThat(input).as(resource).isNotNull();
        try (InputStream resourceInput = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = resourceInput.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> canonicalGuidance() throws Exception {
        Path path = Paths.get(
                "src/main/resources/io/github/thisccl/j4a/guidance/agent-guidance.json");
        assertThat(path).exists();
        Object parsed = new Yaml().load(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        assertThat(parsed).isInstanceOf(Map.class);
        return (Map<String, Object>) parsed;
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
}
