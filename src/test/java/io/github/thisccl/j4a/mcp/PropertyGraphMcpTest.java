package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.cli.CommandDiagnostic;
import io.github.thisccl.j4a.cli.CommandResult;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.config.Argument;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.IntegerProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.LongProperty;
import org.apache.jmeter.testelement.property.ObjectProperty;

class PropertyGraphMcpTest {
    private static final String ASSERTION_GUI =
            "org.apache.jmeter.assertions.gui.AssertionGui";

    private PipedOutputStream requests;
    private BufferedReader responses;
    private ByteArrayOutputStream serverErrors;
    private Thread serverThread;
    private volatile int serverExit = Integer.MIN_VALUE;
    private volatile Throwable serverFailure;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopMcpServer() throws Exception {
        if (requests == null) {
            return;
        }
        requests.close();
        serverThread.join(10_000L);
        assertThat(serverThread.isAlive()).isFalse();
        assertThat(serverFailure).isNull();
        assertThat(serverExit).isZero();
        assertThat(new String(serverErrors.toByteArray(), StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void setAdvertisesStrictRecursiveGraphDocumentsWithoutWeakeningTheToolSurface() {
        List<Map<String, Object>> tools = McpTools.list().stream()
                .map(PropertyGraphMcpTest::mapping)
                .collect(Collectors.toList());
        assertThat(tools.stream().map(tool -> tool.get("name")))
                .containsExactly("read", "validate", "components", "categories", "apply", "init", "set");

        Map<String, Object> set = tools.get(6);
        Map<String, Object> schema = mapping(set.get("inputSchema"));
        assertThat(schema.get("$defs")).as("strict recursive graph definitions").isInstanceOf(Map.class);
        Map<String, Object> definitions = mapping(schema.get("$defs"));
        assertThat(definitions.get("recursiveValue")).as("recursive graph value definition").isInstanceOf(Map.class);
        Map<String, Object> recursive = mapping(definitions.get("recursiveValue"));
        assertThat(recursive.get("oneOf")).as("recursive graph value alternatives").isInstanceOf(List.class);
        assertThat(list(recursive.get("oneOf"))).extracting(PropertyGraphMcpTest::requiredType)
                .containsExactlyInAnyOrder(
                        "string", "boolean", "int", "long", "float", "double", "null",
                        "collection", "map", "element", "opaque");
        assertThat(McpJson.write(recursive))
                .contains("\"additionalProperties\":false")
                .contains("\"$ref\":\"#/$defs/recursiveValue\"")
                .contains("property_class", "presence", "items", "entries", "element_class", "properties",
                        "format", "base_digest", "outer_property_class", "runtime_fingerprint", "payload");

        Map<String, Object> properties = mapping(schema.get("properties"));
        assertThat(definitions).containsKeys(
                "recursiveValue", "scalarAddress", "rowsValue", "propertyDocument");
        assertThat(list(mapping(properties.get("value")).get("oneOf")))
                .extracting(candidate -> mapping(candidate).get("type"))
                .containsExactly("string", "number", "boolean", "object", "array");
        assertThat(list(mapping(properties.get("type")).get("enum")))
                .contains("float", "collection", "map", "element", "opaque");
        assertThat(String.valueOf(set.get("description")))
                .containsIgnoringCase("copied from read")
                .containsIgnoringCase("collection")
                .containsIgnoringCase("rows")
                .containsIgnoringCase("opaque")
                .doesNotContain(
                        "\"type\":\"arguments\"", "\"type\":\"headers\"", "\"type\":\"cookies\"",
                        "\"type\":\"authorizations\"", "\"type\":\"dns_servers\"", "\"type\":\"dns_hosts\"");
        assertThat(schema.get("additionalProperties")).isEqualTo(Boolean.FALSE);
        assertThat(schema.get("required")).isEqualTo(Arrays.asList("locator", "property", "value"));
        assertThat(schema.get("allOf")).isInstanceOf(List.class);
    }

    @Test
    void toolSchemasAreFreshOrderedTreesAcrossListCalls() {
        List<Object> first = McpTools.list();
        List<Object> second = McpTools.list();
        assertThat(McpJson.write(first)).isEqualTo(McpJson.write(second));
        assertThat(first).isNotSameAs(second);

        Map<String, Object> firstSet = mapping(first.get(6));
        Map<String, Object> secondSet = mapping(second.get(6));
        Map<String, Object> firstSchema = mapping(firstSet.get("inputSchema"));
        Map<String, Object> secondSchema = mapping(secondSet.get("inputSchema"));
        assertThat(firstSchema).isNotSameAs(secondSchema);
        assertThat(firstSchema.keySet()).containsExactly(
                "type", "additionalProperties", "required", "allOf", "properties", "$defs");

        mapping(firstSchema.get("$defs")).put("qaMutation", Collections.<String, Object>emptyMap());
        list(firstSchema.get("allOf")).clear();
        List<Object> afterMutation = McpTools.list();
        assertThat(McpJson.write(afterMutation)).isEqualTo(McpJson.write(second));
        assertThat(McpJson.write(afterMutation)).doesNotContain("qaMutation");
    }

    @Test
    void successfulGraphYamlTextIsTheSoleSourceOfNormalizedStructuredData() {
        String yaml = "focus:\n"
                + "  ref: jmx_assertion\n"
                + "  properties:\n"
                + "    - property: [Asserion.test_strings]\n"
                + "      type: collection\n"
                + "      value:\n"
                + "        presence: present\n"
                + "        property_class: org.apache.jmeter.testelement.property.CollectionProperty\n"
                + "        items:\n"
                + "          - type: string\n"
                + "            presence: present\n"
                + "            property_class: org.apache.jmeter.testelement.property.StringProperty\n"
                + "            value: login failed\n";
        Map<String, Object> staleMetadata = new LinkedHashMap<String, Object>();
        staleMetadata.put("command", "read");
        staleMetadata.put("format", "yaml");
        staleMetadata.put("raw_groups", Arrays.asList("must-not-leak"));

        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(
                successfulResult(yaml, staleMetadata));

        assertThat(text(adapted)).isEqualTo(yaml);
        Map<String, Object> data = mapping(mapping(adapted.get("structuredContent")).get("data"));
        assertThat(data).doesNotContainKeys("command", "format", "raw_groups", "registration",
                "admission", "allowed_parent", "placement", "gui_openability");
        Map<String, Object> focus = mapping(data.get("focus"));
        assertThat(focus).containsEntry("ref", "jmx_assertion");
        Map<String, Object> property = mapping(list(focus.get("properties")).get(0));
        assertThat(property).containsEntry("property", address("Asserion.test_strings"))
                .containsEntry("type", "collection");
        Map<String, Object> value = mapping(property.get("value"));
        assertThat(value).containsEntry("presence", "present")
                .containsEntry("property_class", "org.apache.jmeter.testelement.property.CollectionProperty");
        assertThat(mapping(list(value.get("items")).get(0)))
                .containsEntry("type", "string")
                .containsEntry("value", "login failed");
    }

    @Test
    void liveReadStructuredDataKeepsFloatAndDoubleTypesDistinct() throws Exception {
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        JmxTestPlan plan = loader.load(copyFixture("scalar-mcp-source.jmx"));
        ResponseAssertion assertion = plan.depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("response assertion missing"));
        assertion.setProperty(new FloatProperty("qa.float", 1.25F));
        assertion.setProperty(new DoubleProperty("qa.double", 1.5D));
        Path input = tempDir.resolve("scalar-mcp-input.jmx");
        loader.save(plan, input);

        Map<String, Object> data = readData(call("read", arguments(
                "file", input.toString(), "ref", componentRef(input, "qa.float"),
                "properties", "all", "jmeter_home", jmeterHome().toString())));

        assertThat(property(data, "qa.float")).containsEntry("type", "float");
        assertThat(property(data, "qa.double")).containsEntry("type", "double");
    }

    @Test
    void liveJsonRpcStdioMaterializesExplicitNumericLiteralsToExactNativeTypes() throws Exception {
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        JmxTestPlan plan = loader.load(copyFixture("numeric-literal-source.jmx"));
        ResponseAssertion assertion = plan.depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("response assertion missing"));
        assertion.setProperty(new IntegerProperty("qa.int", 1));
        assertion.setProperty(new LongProperty("qa.long", 2L));
        assertion.setProperty(new FloatProperty("qa.float", 1.25F));
        assertion.setProperty(new DoubleProperty("qa.double", 1.5D));
        Path input = tempDir.resolve("numeric-literal-input.jmx");
        loader.save(plan, input);

        assertExactLiteralWrite(input, "qa.int", "int", Integer.valueOf(7));
        assertExactLiteralWrite(input, "qa.long", "long", Long.valueOf(8L));
        assertExactLiteralWrite(input, "qa.float", "float", Float.valueOf(2.5F));
        assertExactLiteralWrite(input, "qa.double", "double", Double.valueOf(3.75D));

        ResponseAssertion reloaded = loader.load(input).depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("response assertion missing"));
        assertThat(reloaded.getPropertyOrNull("qa.int")).isExactlyInstanceOf(IntegerProperty.class);
        assertThat(reloaded.getPropertyOrNull("qa.long")).isExactlyInstanceOf(LongProperty.class);
        assertThat(reloaded.getPropertyOrNull("qa.float")).isExactlyInstanceOf(FloatProperty.class);
        assertThat(reloaded.getPropertyOrNull("qa.double")).isExactlyInstanceOf(DoubleProperty.class);
        assertThat(reloaded.getPropertyOrNull("qa.int").getObjectValue()).isEqualTo(Integer.valueOf(7));
        assertThat(reloaded.getPropertyOrNull("qa.long").getObjectValue()).isEqualTo(Long.valueOf(8L));
        assertThat(reloaded.getPropertyOrNull("qa.float").getObjectValue()).isEqualTo(Float.valueOf(2.5F));
        assertThat(reloaded.getPropertyOrNull("qa.double").getObjectValue()).isEqualTo(Double.valueOf(3.75D));
    }

    @Test
    void responseAssertionOrdinaryDetailsStayCompactAndSmallerThanDiagnostics() {
        Map<String, Object> ordinary = readData(call("components", arguments(
                "component", ASSERTION_GUI,
                "jmeter_home", jmeterHome().toString())));
        Map<String, Object> diagnostics = readData(call("components", arguments(
                "component", ASSERTION_GUI, "diagnostics", Boolean.TRUE,
                "jmeter_home", jmeterHome().toString())));
        List<String> compactKeys = Arrays.asList(
                "property", "type", "default", "value_shape",
                "row_type", "row_properties");
        List<String> observedKeys = list(ordinary.get("properties")).stream()
                .map(PropertyGraphMcpTest::mapping)
                .flatMap(property -> property.keySet().stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        assertThat(observedKeys).containsExactly("default", "property", "type", "value_shape");
        for (Object candidate : list(ordinary.get("properties"))) {
            assertThat(mapping(candidate).keySet()).allMatch(compactKeys::contains);
        }
        assertThat(catalogProperty(ordinary, "Asserion.test_strings").keySet())
                .containsExactly("property", "type", "value_shape");
        assertThat(catalogProperty(ordinary, "TestElement.enabled"))
                .containsOnlyKeys("property", "type", "default")
                .containsEntry("default", Boolean.TRUE);
        for (Object candidate : list(ordinary.get("properties"))) {
            Map<String, Object> property = mapping(candidate);
            if (!address("Asserion.test_strings").equals(property.get("property"))
                    && !address("TestElement.enabled").equals(property.get("property"))) {
                assertThat(property).containsOnlyKeys("property", "type");
            }
        }
        assertThat(McpJson.write(ordinary).length())
                .isLessThan(McpJson.write(diagnostics).length());
    }

    @Test
    void bareGenericCollectionListFailsBeforeMcpMutationWithEnvelopeRecovery() throws Exception {
        Path input = copyFixture("bare-list-input.jmx");
        byte[] original = Files.readAllBytes(input);
        Path output = tempDir.resolve("bare-list-output.jmx");

        Map<String, Object> result = call("set", arguments(
                "file", input.toString(),
                "locator", componentRef(input, "Asserion.test_strings"),
                "property", address("Asserion.test_strings"), "type", "collection",
                "value", Arrays.asList("must-not-write"), "out", output.toString(),
                "jmeter_home", jmeterHome().toString()));

        assertThat(result).containsEntry("isError", Boolean.TRUE);
        assertThat(text(result)).contains("presence", "property_class", "items", "value_template",
                "components", "focused read");
        assertThat(output).doesNotExist();
        assertThat(Files.readAllBytes(input)).containsExactly(original);
    }

    @Test
    void responseAssertionRecursiveValueRoundTripsThroughMcpSetAndApply() throws Exception {
        Path input = copyFixture("mcp-round-trip-input.jmx");
        byte[] original = Files.readAllBytes(input);
        String inputRef = componentRef(input, "Asserion.test_strings");
        Map<String, Object> read = call("read", arguments(
                "file", input.toString(), "ref", inputRef, "properties", "all",
                "jmeter_home", jmeterHome().toString()));
        assertThat(read).containsEntry("isError", Boolean.FALSE);
        Map<String, Object> record = property(readData(read), "Asserion.test_strings");
        assertThat(record).containsEntry("type", "collection");
        assertThat(mapping(record.get("value"))).containsEntry("presence", "present");

        Path setOutput = tempDir.resolve("mcp-set-output.jmx");
        Map<String, Object> set = call("set", arguments(
                "file", input.toString(), "locator", inputRef,
                "property", record.get("property"), "type", record.get("type"),
                "value", record.get("value"), "out", setOutput.toString(),
                "jmeter_home", jmeterHome().toString()));
        assertThat(set).containsEntry("isError", Boolean.FALSE);
        assertThat(Files.readAllBytes(input)).containsExactly(original);
        String setOutputRef = componentRef(setOutput, "Asserion.test_strings");
        assertThat(property(readData(call("read", arguments(
                "file", setOutput.toString(), "ref", setOutputRef, "properties", "all",
                "jmeter_home", jmeterHome().toString()))), "Asserion.test_strings"))
                .isEqualTo(record);

        Path applyOutput = tempDir.resolve("mcp-apply-output.jmx");
        Map<String, Object> operation = new LinkedHashMap<String, Object>();
        operation.put("ref", setOutputRef);
        operation.put("properties", Collections.<Object>singletonList(record));
        Map<String, Object> change = new LinkedHashMap<String, Object>();
        change.put("set", operation);
        Map<String, Object> patch = new LinkedHashMap<String, Object>();
        patch.put("changes", Collections.<Object>singletonList(change));
        Map<String, Object> apply = call("apply", arguments(
                "file", setOutput.toString(), "patchYaml", new Yaml().dump(patch),
                "out", applyOutput.toString(), "jmeter_home", jmeterHome().toString()));
        assertThat(apply).containsEntry("isError", Boolean.FALSE);
        String applyOutputRef = componentRef(applyOutput, "Asserion.test_strings");
        assertThat(property(readData(call("read", arguments(
                "file", applyOutput.toString(), "ref", applyOutputRef, "properties", "all",
                "jmeter_home", jmeterHome().toString()))), "Asserion.test_strings"))
                .isEqualTo(record);

        Path rejectedOutput = tempDir.resolve("unknown-path-output.jmx");
        Map<String, Object> rejected = call("set", arguments(
                "file", input.toString(), "locator", inputRef,
                "property", address("qa.unknown"), "type", record.get("type"),
                "value", record.get("value"), "out", rejectedOutput.toString(),
                "jmeter_home", jmeterHome().toString()));
        assertThat(rejected).containsEntry("isError", Boolean.TRUE);
        assertThat(text(rejected)).containsIgnoringCase("property");
        assertThat(rejectedOutput).doesNotExist();
        assertThat(Files.readAllBytes(input)).containsExactly(original);
    }

    @Test
    void directResponseAssertionScalarLeafPersistsThroughMcpSetAndApply() throws Exception {
        Path input = copyFixture("mcp-direct-leaf-input.jmx");
        byte[] sourceBefore = Files.readAllBytes(input);
        Path setOutput = tempDir.resolve("mcp-direct-leaf-set-output.jmx");
        String inputRef = componentRef(input, "Asserion.test_strings");

        Map<String, Object> set = call("set", arguments(
                "file", input.toString(), "locator", inputRef,
                "property", address("Asserion.test_strings", Integer.valueOf(0)), "type", "string",
                "value", "changed-index", "out", setOutput.toString(),
                "jmeter_home", jmeterHome().toString()));

        assertThat(set).containsEntry("isError", Boolean.FALSE);
        assertThat(responseAssertionStrings(setOutput))
                .containsExactly("changed-index", "access denied");
        assertThat(Files.readAllBytes(input)).containsExactly(sourceBefore);

        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("property", address("Asserion.test_strings", Integer.valueOf(0)));
        record.put("type", "string");
        record.put("value", "changed-index");
        Map<String, Object> operation = new LinkedHashMap<String, Object>();
        operation.put("ref", componentRef(input, "Asserion.test_strings"));
        operation.put("properties", Collections.<Object>singletonList(record));
        Map<String, Object> change = new LinkedHashMap<String, Object>();
        change.put("set", operation);
        Map<String, Object> patch = new LinkedHashMap<String, Object>();
        patch.put("changes", Collections.<Object>singletonList(change));
        Path applyOutput = tempDir.resolve("mcp-direct-leaf-apply-output.jmx");

        Map<String, Object> apply = call("apply", arguments(
                "file", input.toString(), "patchYaml", new Yaml().dump(patch),
                "out", applyOutput.toString(), "jmeter_home", jmeterHome().toString()));

        assertThat(apply).containsEntry("isError", Boolean.FALSE);
        assertThat(responseAssertionStrings(applyOutput))
                .containsExactly("changed-index", "access denied");
        assertThat(Files.readAllBytes(input)).containsExactly(sourceBefore);
    }

    @Test
    void namedFamilySchemasAreAbsentAndUnifiedRowsHasTwoStrictShapes() {
        Map<String, Object> set = mapping(McpTools.list().get(6));
        Map<String, Object> schema = mapping(set.get("inputSchema"));
        Map<String, Object> definitions = mapping(schema.get("$defs"));
        assertThat(definitions.keySet()).doesNotContain(
                "argumentsValue", "headersValue", "http_filesValue", "authorizationsValue",
                "cookiesValue", "dns_serversValue", "dns_hostsValue");
        assertThat(McpJson.write(definitions.get("rowsValue")))
                .contains("oneOf", "row_type", "rows", "additionalProperties");
        assertThat(McpJson.write(schema)).doesNotContain(
                "argumentsValue", "headersValue", "cookiesValue");
    }

    @Test
    void semanticAdapterValueRoundTripsThroughMcpReadAndSet() throws Exception {
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        JmxTestPlan plan = loader.load(copyFixture("semantic-source.jmx"));
        HTTPSamplerProxy sampler = plan.depthFirstTestElements().stream()
                .filter(HTTPSamplerProxy.class::isInstance)
                .map(HTTPSamplerProxy.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("HTTP sampler missing"));
        Arguments rows = new Arguments();
        rows.addArgument(new Argument("branch_no", "1001", "="));
        sampler.setArguments(rows);
        Path input = tempDir.resolve("semantic-input.jmx");
        loader.save(plan, input);

        Map<String, Object> record = property(readData(call("read", arguments(
                "file", input.toString(), "ref", componentRef(input, "HTTPsampler.Arguments"), "properties", "all",
                "jmeter_home", jmeterHome().toString()))), "HTTPsampler.Arguments");
        assertThat(record).containsEntry("type", "rows");
        assertThat(McpJson.write(record.get("value"))).contains("branch_no", "1001");
        Path output = tempDir.resolve("semantic-output.jmx");

        Map<String, Object> set = call("set", arguments(
                "file", input.toString(), "locator", componentRef(input, "HTTPsampler.Arguments"),
                "property", record.get("property"), "type", record.get("type"),
                "value", record.get("value"), "out", output.toString(),
                "jmeter_home", jmeterHome().toString()));

        assertThat(set).containsEntry("isError", Boolean.FALSE);
        Map<String, Object> written = property(readData(call("read", arguments(
                "file", output.toString(), "ref", componentRef(output, "HTTPsampler.Arguments"), "properties", "all",
                "jmeter_home", jmeterHome().toString()))), "HTTPsampler.Arguments");
        assertThat(written).containsEntry("type", "rows");
        Map<String, Object> writtenValue = mapping(written.get("value"));
        assertThat(writtenValue).containsEntry("row_type", Argument.class.getName());
        assertThat(McpJson.write(writtenValue))
                .contains("branch_no", "1001")
                .doesNotContain("raw_groups", "registration", "admission");
    }

    @Test
    void nestedElementRecordRoundTripsThroughMcpSetAndApply() throws Exception {
        Path input = copyNamedFixture("builtin-nested-element.jmx", "nested-input.jmx");
        Map<String, Object> initialRead = call("read", arguments(
                "file", input.toString(), "depth", Integer.valueOf(1), "properties", "all",
                "jmeter_home", jmeterHome().toString()));
        assertThat(initialRead).as(text(initialRead)).containsEntry("isError", Boolean.FALSE);
        Map<String, Object> root = mapping(readData(initialRead).get("root"));
        String locator = String.valueOf(root.get("ref"));
        Map<String, Object> record = propertyFromNode(root, "qa.element");
        assertThat(record).containsEntry("type", "rows");
        assertThat(mapping(record.get("value"))).containsKeys("rows", "row_type");

        Path setOutput = tempDir.resolve("nested-set-output.jmx");
        assertThat(call("set", arguments(
                "file", input.toString(), "locator", locator,
                "property", record.get("property"), "type", record.get("type"),
                "value", record.get("value"), "out", setOutput.toString(),
                "jmeter_home", jmeterHome().toString()))).containsEntry("isError", Boolean.FALSE);
        assertThat(propertyFromNode(mapping(readData(call("read", arguments(
                "file", setOutput.toString(), "depth", Integer.valueOf(1), "properties", "all",
                "jmeter_home", jmeterHome().toString()))).get("root")), "qa.element"))
                .isEqualTo(record);

        Path applyOutput = tempDir.resolve("nested-apply-output.jmx");
        Map<String, Object> set = new LinkedHashMap<String, Object>();
        set.put("ref", componentRef(setOutput, "qa.element"));
        set.put("properties", Collections.<Object>singletonList(record));
        Map<String, Object> change = new LinkedHashMap<String, Object>();
        change.put("set", set);
        Map<String, Object> patch = new LinkedHashMap<String, Object>();
        patch.put("changes", Collections.<Object>singletonList(change));
        assertThat(call("apply", arguments(
                "file", setOutput.toString(), "patchYaml", new Yaml().dump(patch),
                "out", applyOutput.toString(), "jmeter_home", jmeterHome().toString())))
                .containsEntry("isError", Boolean.FALSE);
        assertThat(propertyFromNode(mapping(readData(call("read", arguments(
                "file", applyOutput.toString(), "depth", Integer.valueOf(1), "properties", "all",
                "jmeter_home", jmeterHome().toString()))).get("root")), "qa.element"))
                .isEqualTo(record);
    }

    @Test
    void boundOpaqueDocumentCrossesMcpTransportAndInvalidBindingPreservesBytes() throws Exception {
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        JmxTestPlan plan = loader.load(copyFixture("opaque-source.jmx"));
        TestPlan root = plan.depthFirstTestElements().stream()
                .filter(TestPlan.class::isInstance)
                .map(TestPlan.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("Test Plan missing"));
        root.setProperty(new ObjectProperty("qa.opaque", "opaque-value"));
        Path input = tempDir.resolve("opaque-input.jmx");
        loader.save(plan, input);
        byte[] before = Files.readAllBytes(input);

        Map<String, Object> fullRead = readData(call("read", arguments(
                "file", input.toString(), "depth", Integer.valueOf(1), "properties", "all",
                "jmeter_home", jmeterHome().toString())));
        Map<String, Object> rootDocument = mapping(fullRead.get("root"));
        String locator = String.valueOf(rootDocument.get("ref"));
        Map<String, Object> record = propertyFromNode(rootDocument, "qa.opaque");
        assertThat(record).containsEntry("type", "opaque");
        Map<String, Object> changedValue = new LinkedHashMap<String, Object>(mapping(record.get("value")));
        changedValue.put("payload", String.valueOf(changedValue.get("payload"))
                .replace("opaque-value", "opaque-edited-by-mcp"));

        Path setOutput = tempDir.resolve("opaque-set-output.jmx");
        assertThat(call("set", arguments(
                "file", input.toString(), "locator", locator,
                "property", record.get("property"), "type", record.get("type"),
                "value", changedValue, "out", setOutput.toString(),
                "jmeter_home", jmeterHome().toString()))).containsEntry("isError", Boolean.FALSE);
        Map<String, Object> setRoot = mapping(readData(call("read", arguments(
                "file", setOutput.toString(), "depth", Integer.valueOf(1), "properties", "all",
                "jmeter_home", jmeterHome().toString()))).get("root"));
        Map<String, Object> setRecord = propertyFromNode(setRoot, "qa.opaque");
        assertThat(String.valueOf(mapping(setRecord.get("value")).get("payload")))
                .contains("opaque-edited-by-mcp");
        assertThat(mapping(setRecord.get("value")).get("base_digest"))
                .isNotEqualTo(mapping(record.get("value")).get("base_digest"));

        Path applyOutput = tempDir.resolve("opaque-apply-output.jmx");
        Map<String, Object> setOperation = new LinkedHashMap<String, Object>();
        setOperation.put("ref", componentRef(setOutput, "qa.opaque"));
        setOperation.put("properties", Collections.<Object>singletonList(setRecord));
        Map<String, Object> change = new LinkedHashMap<String, Object>();
        change.put("set", setOperation);
        Map<String, Object> patch = new LinkedHashMap<String, Object>();
        patch.put("changes", Collections.<Object>singletonList(change));
        assertThat(call("apply", arguments(
                "file", setOutput.toString(), "patchYaml", new Yaml().dump(patch),
                "out", applyOutput.toString(), "jmeter_home", jmeterHome().toString())))
                .containsEntry("isError", Boolean.FALSE);
        Map<String, Object> applyRoot = mapping(readData(call("read", arguments(
                "file", applyOutput.toString(), "depth", Integer.valueOf(1), "properties", "all",
                "jmeter_home", jmeterHome().toString()))).get("root"));
        assertThat(propertyFromNode(applyRoot, "qa.opaque")).isEqualTo(setRecord);

        Map<String, Object> staleValue =
                new LinkedHashMap<String, Object>(mapping(record.get("value")));
        staleValue.put("base_digest", String.join("", Collections.nCopies(64, "0")));
        Path rejected = tempDir.resolve("stale-opaque-output.jmx");
        Map<String, Object> opaqueArguments = arguments(
                "file", input.toString(), "locator", locator,
                "property", address("qa.opaque"), "type", "opaque",
                "value", staleValue, "out", rejected.toString(),
                "jmeter_home", jmeterHome().toString());
        McpToolInvocation invocation = McpToolInvocation.from("set", opaqueArguments);
        assertThat(invocation.valid()).isTrue();
        assertThat(mapping(McpJson.parse(option(invocation.args(), "--value")))).isEqualTo(staleValue);
        Map<String, Object> stale = call("set", arguments(
                "file", input.toString(), "locator", locator,
                "property", address("qa.opaque"), "type", "opaque",
                "value", staleValue, "out", rejected.toString(),
                "jmeter_home", jmeterHome().toString()));
        assertThat(stale).containsEntry("isError", Boolean.TRUE);
        assertThat(text(stale)).containsIgnoringCase("opaque base digest is stale");
        assertThat(rejected).doesNotExist();
        assertThat(Files.readAllBytes(input)).containsExactly(before);
    }

    private static String option(String[] args, String option) {
        List<String> values = Arrays.asList(args);
        return values.get(values.indexOf(option) + 1);
    }

    private static CommandResult successfulResult(String text, Map<String, Object> structuredData) {
        return new CommandResult() {
            @Override public int exitCode() { return 0; }
            @Override public String textOutput() { return text; }
            @Override public Map<String, Object> structuredData() { return structuredData; }
            @Override public List<CommandDiagnostic> diagnostics() { return Collections.emptyList(); }
            @Override public String recoveryGuidance() { return null; }
        };
    }

    private static String text(Map<String, Object> adapted) {
        return String.valueOf(mapping(list(adapted.get("content")).get(0)).get("text"));
    }

    private Map<String, Object> call(String tool, Map<String, Object> arguments) {
        startMcpServer();
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", tool);
        params.put("arguments", arguments);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", Integer.valueOf(1));
        request.put("method", "tools/call");
        request.put("params", params);
        try {
            requests.write((McpJson.write(request) + "\n").getBytes(StandardCharsets.UTF_8));
            requests.flush();
            String responseLine = responses.readLine();
            assertThat(responseLine).as("MCP response before server termination").isNotNull();
            return mapping(mapping(McpJson.parse(responseLine)).get("result"));
        } catch (IOException exception) {
            throw new AssertionError("MCP test transport failed", exception);
        }
    }

    private void startMcpServer() {
        if (requests != null) {
            return;
        }
        try {
            PipedInputStream serverInput = new PipedInputStream();
            requests = new PipedOutputStream(serverInput);
            PipedOutputStream serverOutput = new PipedOutputStream();
            responses = new BufferedReader(new InputStreamReader(
                    new PipedInputStream(serverOutput), StandardCharsets.UTF_8));
            serverErrors = new ByteArrayOutputStream();
            PrintStream stdout = new PrintStream(serverOutput, true, StandardCharsets.UTF_8.name());
            PrintStream stderr = new PrintStream(serverErrors, true, StandardCharsets.UTF_8.name());
            serverThread = new Thread(() -> {
                try {
                    serverExit = J4aMcpServer.run(serverInput, stdout, stderr, new String[0]);
                } catch (Throwable failure) {
                    serverFailure = failure;
                } finally {
                    stdout.close();
                    stderr.close();
                }
            }, "property-graph-mcp-test-server");
            serverThread.start();
        } catch (IOException exception) {
            throw new AssertionError("MCP test transport setup failed", exception);
        }
    }

    private static Map<String, Object> readData(Map<String, Object> result) {
        return mapping(mapping(result.get("structuredContent")).get("data"));
    }

    private static Map<String, Object> property(Map<String, Object> data, String path) {
        Map<String, Object> focus = mapping(data.get("focus"));
        for (Object candidate : list(focus.get("properties"))) {
            Map<String, Object> property = mapping(candidate);
            if (address(path).equals(property.get("property"))) {
                return property;
            }
        }
        throw new AssertionError("missing property " + path);
    }

    private static Map<String, Object> catalogProperty(Map<String, Object> data, String path) {
        for (Object candidate : list(data.get("properties"))) {
            Map<String, Object> property = mapping(candidate);
            if (address(path).equals(property.get("property"))) {
                return property;
            }
        }
        throw new AssertionError("missing catalog property " + path);
    }

    private static Map<String, Object> propertyFromNode(Map<String, Object> node, String path) {
        for (Object candidate : list(node.get("properties"))) {
            Map<String, Object> property = mapping(candidate);
            if (address(path).equals(property.get("property"))) {
                return property;
            }
        }
        throw new AssertionError("missing property " + path);
    }

    private String componentRef(Path file, String propertyPath) {
        Map<String, Object> result = call("read", arguments(
                "file", file.toString(), "depth", Integer.valueOf(20), "properties", "all",
                "jmeter_home", jmeterHome().toString()));
        assertThat(result).as("read before numeric literal write").containsEntry("isError", Boolean.FALSE);
        Map<String, Object> data = readData(result);
        String ref = componentRef(mapping(data.get("root")), propertyPath);
        if (ref == null) {
            throw new AssertionError("missing component with property " + propertyPath);
        }
        return ref;
    }

    private void assertExactLiteralWrite(Path input, String property, String type, Number value) {
        Map<String, Object> result = call("set", arguments(
                "file", input.toString(), "locator", componentRef(input, property),
                "property", address(property), "type", type, "value", value,
                "override", Boolean.TRUE, "jmeter_home", jmeterHome().toString()));
        assertThat(result).as(type + " JSON literal response").containsEntry("isError", Boolean.FALSE);
    }

    private static String componentRef(Map<String, Object> node, String propertyPath) {
        Object properties = node.get("properties");
        if (properties instanceof List<?>) {
            for (Object candidate : list(properties)) {
                if (address(propertyPath).equals(mapping(candidate).get("property"))) {
                    return String.valueOf(node.get("ref"));
                }
            }
        }
        Object children = node.get("children");
        if (children instanceof List<?>) {
            for (Object child : list(children)) {
                String ref = componentRef(mapping(child), propertyPath);
                if (ref != null) {
                    return ref;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> arguments(Object... entries) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            arguments.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return arguments;
    }

    private static List<Object> address(Object... segments) {
        return Arrays.asList(segments);
    }

    private Path copyFixture(String name) throws Exception {
        return copyNamedFixture("response-assertion.jmx", name);
    }

    private Path copyNamedFixture(String fixture, String name) throws Exception {
        Path source = Paths.get(PropertyGraphMcpTest.class
                .getResource("/property-graph-conformance/" + fixture).toURI());
        Path copy = tempDir.resolve(name);
        Files.copy(source, copy);
        return copy;
    }

    private List<String> responseAssertionStrings(Path input) {
        ResponseAssertion assertion = new SaveServiceJmxLoader(jmeterHome()).load(input)
                .depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("response assertion missing"));
        java.util.ArrayList<String> values = new java.util.ArrayList<String>();
        for (JMeterProperty item
                : (CollectionProperty) assertion.getPropertyOrNull("Asserion.test_strings")) {
            values.add(item.getStringValue());
        }
        return values;
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }

    private static String requiredType(Object candidate) {
        Map<String, Object> branch = mapping(candidate);
        Map<String, Object> properties = mapping(branch.get("properties"));
        return String.valueOf(mapping(properties.get("type")).get("const"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
