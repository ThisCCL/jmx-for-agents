package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExactNumericMcpTest {
    private PipedOutputStream requests;
    private BufferedReader responses;
    private ByteArrayOutputStream errors;
    private Thread serverThread;
    private volatile int serverExit = Integer.MIN_VALUE;
    private volatile Throwable serverFailure;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() throws Exception {
        if (requests == null) return;
        requests.close();
        serverThread.join(10_000L);
        assertThat(serverThread.isAlive()).isFalse();
        assertThat(serverFailure).isNull();
        assertThat(serverExit).isZero();
        assertThat(new String(errors.toByteArray(), StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void mcpSetAndPatchYamlUseTheSameExactFloatAndDoubleRules() throws Exception {
        Path input = numericFixture("mcp-exact-input.jmx");
        String ref = componentRef(input);

        assertSuccess(call("set", arguments(
                "file", input.toString(), "locator", ref,
                "property", address("qa.float"), "type", "float", "value", Float.valueOf(2.5F),
                "override", Boolean.TRUE, "jmeter_home", jmeterHome().toString())));
        assertSuccess(call("set", arguments(
                "file", input.toString(), "locator", ref,
                "property", address("qa.double"), "type", "double", "value", Double.valueOf(3.75D),
                "override", Boolean.TRUE, "jmeter_home", jmeterHome().toString())));
        assertSuccess(call("apply", arguments(
                "file", input.toString(), "patchYaml", patchYaml(ref),
                "override", Boolean.TRUE, "jmeter_home", jmeterHome().toString())));

        ResponseAssertion assertion = assertion(input);
        assertThat(assertion.getPropertyOrNull("qa.float"))
                .isExactlyInstanceOf(FloatProperty.class);
        assertThat(assertion.getPropertyOrNull("qa.float").getObjectValue())
                .isEqualTo(Float.valueOf(4.5F));
        assertThat(assertion.getPropertyOrNull("qa.double"))
                .isExactlyInstanceOf(DoubleProperty.class);
        assertThat(assertion.getPropertyOrNull("qa.double").getObjectValue())
                .isEqualTo(Double.valueOf(6.25D));
    }

    @Test
    void nestedTypedJsonScalarsKeepTheirLexemesUntilDeclaredDecoding() {
        Map<String, Object> arguments = mapping(new JsonParser("{"
                + "\"type\":\"element\","
                + "\"value\":{\"type\":\"float\",\"value\":1.25}"
                + "}").parse());

        Map<String, Object> normalized = McpJsonNumbers.toolArguments("set", arguments);

        assertThat(mapping(normalized.get("value")).get("value"))
                .isExactlyInstanceOf(Float.class)
                .isEqualTo(Float.valueOf(1.25F));
    }

    private static void assertSuccess(Map<String, Object> result) {
        assertThat(result).containsEntry("isError", Boolean.FALSE);
    }

    private Map<String, Object> call(String tool, Map<String, Object> arguments) {
        startServer();
        Map<String, Object> params = arguments("name", tool, "arguments", arguments);
        Map<String, Object> request = arguments(
                "jsonrpc", "2.0", "id", Integer.valueOf(1),
                "method", "tools/call", "params", params);
        try {
            requests.write((McpJson.write(request) + "\n").getBytes(StandardCharsets.UTF_8));
            requests.flush();
            String response = responses.readLine();
            assertThat(response).isNotNull();
            return mapping(mapping(McpJson.parse(response)).get("result"));
        } catch (IOException exception) {
            throw new AssertionError("MCP transport failed", exception);
        }
    }

    private void startServer() {
        if (requests != null) return;
        try {
            PipedInputStream input = new PipedInputStream();
            requests = new PipedOutputStream(input);
            PipedOutputStream output = new PipedOutputStream();
            responses = new BufferedReader(new InputStreamReader(
                    new PipedInputStream(output), StandardCharsets.UTF_8));
            errors = new ByteArrayOutputStream();
            PrintStream stdout = new PrintStream(output, true, StandardCharsets.UTF_8.name());
            PrintStream stderr = new PrintStream(errors, true, StandardCharsets.UTF_8.name());
            serverThread = new Thread(() -> {
                try {
                    serverExit = J4aMcpServer.run(input, stdout, stderr, new String[0]);
                } catch (Throwable failure) {
                    serverFailure = failure;
                } finally {
                    stdout.close();
                    stderr.close();
                }
            }, "exact-numeric-mcp-test-server");
            serverThread.start();
        } catch (IOException exception) {
            throw new AssertionError("MCP setup failed", exception);
        }
    }

    private String componentRef(Path input) {
        Map<String, Object> result = call("read", arguments(
                "file", input.toString(), "depth", Integer.valueOf(20), "properties", "all",
                "jmeter_home", jmeterHome().toString()));
        assertSuccess(result);
        String ref = componentRef(mapping(mapping(result.get("structuredContent")).get("data")));
        if (ref == null) throw new AssertionError("numeric component ref missing");
        return ref;
    }

    private static String componentRef(Map<String, Object> data) {
        return componentRefInNode(mapping(data.get("root")));
    }

    private static String componentRefInNode(Map<String, Object> node) {
        Object properties = node.get("properties");
        if (properties instanceof List<?>) {
            for (Object property : list(properties)) {
                if (address("qa.float").equals(mapping(property).get("property"))) {
                    return String.valueOf(node.get("ref"));
                }
            }
        }
        Object children = node.get("children");
        if (children instanceof List<?>) {
            for (Object child : list(children)) {
                String ref = componentRefInNode(mapping(child));
                if (ref != null) return ref;
            }
        }
        return null;
    }

    private Path numericFixture(String name) throws Exception {
        Path source = Paths.get(ExactNumericMcpTest.class
                .getResource("/property-graph-conformance/response-assertion.jmx").toURI());
        Path copy = tempDir.resolve(name);
        Files.copy(source, copy);
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        JmxTestPlan plan = loader.load(copy);
        ResponseAssertion assertion = plan.depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("response assertion missing"));
        assertion.setProperty(new FloatProperty("qa.float", 1.25F));
        assertion.setProperty(new DoubleProperty("qa.double", 1.5D));
        loader.save(plan, copy);
        return copy;
    }

    private ResponseAssertion assertion(Path path) throws Exception {
        return new SaveServiceJmxLoader(jmeterHome()).load(path).depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("response assertion missing"));
    }

    private static String patchYaml(String ref) {
        return "changes:\n"
                + "  - set:\n"
                + "      ref: " + ref + "\n"
                + "      properties:\n"
                + "        - property: [qa.float]\n"
                + "          type: float\n"
                + "          value: 4.5\n"
                + "        - property: [qa.double]\n"
                + "          type: double\n"
                + "          value: 6.25\n";
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }
}
