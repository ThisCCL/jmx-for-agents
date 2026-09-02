package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.VersionInfo;
import io.github.thisccl.j4a.cli.CommandDiagnostic;
import io.github.thisccl.j4a.cli.CommandResult;
import io.github.thisccl.j4a.cli.J4aCommandExecutor;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerClient;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerProtocolProbe;
import io.github.thisccl.j4a.validation.CatastrophicMutationWorkerFixture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class J4aMcpProtocolTest {
    private static final List<String> REQUIRED_TOOL_NAMES = Arrays.asList(
            "read", "validate", "components", "categories", "apply", "init", "set");

    @TempDir
    Path tempDir;

    @Test
    void initializeToolsListAndShutdownUseJsonRpcStdoutOnly() {
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"j4a-protocol-test\",\"version\":\"0.0.0\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"shutdown\",\"params\":{}}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).as("protocol server diagnostics must not leak for a clean transcript").isEmpty();

        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(3);
        assertThat(messages.stream().map(message -> idText(message.get("id"))).collect(Collectors.toList()))
                .containsExactly("1", "2", "99");
        assertThat(messages).allSatisfy(message -> {
            assertThat(message.get("jsonrpc")).isEqualTo("2.0");
            assertThat(message).doesNotContainKey("method");
        });

        Map<String, Object> initializeResult = mapping(messages.get(0).get("result"));
        assertThat(initializeResult.get("protocolVersion")).isEqualTo("2025-06-18");
        assertThat(mapping(initializeResult.get("serverInfo")))
                .containsEntry("name", "j4a")
                .containsEntry("version", VersionInfo.version());
        assertThat(mapping(initializeResult.get("capabilities"))).containsKey("tools");

        Map<String, Object> toolsResult = mapping(messages.get(1).get("result"));
        List<Map<String, Object>> tools = list(toolsResult.get("tools")).stream()
                .map(J4aMcpProtocolTest::mapping)
                .collect(Collectors.toList());
        List<String> toolNames = tools.stream()
                .map(tool -> String.valueOf(tool.get("name")))
                .collect(Collectors.toList());
        assertThat(toolNames).containsExactlyElementsOf(REQUIRED_TOOL_NAMES);
        assertThat(toolNames).noneMatch(name -> name.startsWith("j4a_"));
        assertThat(toolNames).doesNotContain("j4a_read", "j4a_validate", "j4a_probe_addability");
        assertFileOrPathSchema(tool(tools, "read"));
        assertFileOrPathSchema(tool(tools, "validate"));
        assertFileOrPathSchema(tool(tools, "apply"));
        assertFileOrPathSchema(tool(tools, "set"));
        assertThat(list(mapping(tool(tools, "set").get("inputSchema")).get("required")))
                .containsExactly("locator", "property", "value");
    }

    @Test
    void malformedInputReturnsJsonRpcParseErrorWithoutPlainStdoutLogs() {
        ServerResult result = runServer("{not-json");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).as("malformed protocol input must not produce non-protocol stdout leakage").isEmpty();

        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(1);
        Map<String, Object> response = messages.get(0);
        assertThat(response.get("jsonrpc")).isEqualTo("2.0");
        assertThat(response.get("id")).isNull();
        Map<String, Object> error = mapping(response.get("error"));
        assertThat(error).containsEntry("code", -32700);
        assertThat(String.valueOf(error.get("message"))).containsIgnoringCase("parse");
    }

    @Test
    void malformedInputDoesNotPreventFollowingToolsListRequest() {
        ServerResult result = runServer("{not-json",
                "{\"jsonrpc\":\"2.0\",\"id\":81,\"method\":\"tools/list\"}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(2);
        assertThat(mapping(messages.get(0).get("error"))).containsEntry("code", -32700);
        assertThat(mapping(messages.get(1).get("result"))).containsKey("tools");
    }

    @Test
    void overDepthJsonReturnsParseErrorAndFollowingToolsListRequestSucceeds() {
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":82,\"method\":\"tools/list\",\"params\":"
                        + nestedArrays(12000) + "}",
                "{\"jsonrpc\":\"2.0\",\"id\":83,\"method\":\"tools/list\"}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).doesNotContain("StackOverflowError");
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(2);
        assertThat(mapping(messages.get(0).get("error"))).containsEntry("code", -32700);
        assertThat(String.valueOf(mapping(messages.get(0).get("error")).get("message")))
                .containsIgnoringCase("nesting");
        assertThat(mapping(messages.get(1).get("result"))).containsKey("tools");
    }

    @Test
    void oversizedJsonRpcLineIsRejectedAndServerRemainsResponsive() {
        String oversized = repeated(' ', McpStdioServer.MAX_JSON_RPC_LINE_BYTES + 1);
        ServerResult result = runServer(oversized,
                "{\"jsonrpc\":\"2.0\",\"id\":91,\"method\":\"tools/list\"}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(2);
        assertThat(mapping(messages.get(0).get("error"))).containsEntry("code", -32600);
        assertThat(String.valueOf(mapping(messages.get(0).get("error")).get("message")))
                .contains("maximum").doesNotContain(oversized);
        assertThat(mapping(messages.get(1).get("result"))).containsKey("tools");
    }

    @Test
    void toolsCallReadUsesSharedCommandExecutorAndMcpAdapter() {
        Path fixture = fixture("simple-http.jmx");
        Path home = localHome();
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":{\"file\":"
                        + jsonString(fixture.toString()) + ",\"jmeter_home\":" + jsonString(home.toString()) + "}}}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();

        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(1);
        Map<String, Object> toolResult = mapping(messages.get(0).get("result"));
        assertThat(toolResult).containsEntry("isError", Boolean.FALSE);
        assertThat(list(toolResult.get("content"))).singleElement()
                .satisfies(content -> assertThat(mapping(content).get("text").toString())
                        .contains("org.apache.jmeter.testelement.TestPlan"));
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        assertThat(structured).containsEntry("exitStatus", 0);
        Map<String, Object> data = mapping(structured.get("data"));
        assertThat(data).containsKey("root").doesNotContainKeys("command", "format", "file");
        assertThat(data).isEqualTo(new Yaml().load(
                String.valueOf(mapping(list(toolResult.get("content")).get(0)).get("text"))));
        assertThat(list(structured.get("diagnostics"))).isEmpty();
    }

    @Test
    void toolsCallReadAcceptsPathAliasAdvertisedBySchema() {
        Path fixture = fixture("simple-http.jmx");
        Path home = localHome();
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":33,\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":{\"path\":"
                        + jsonString(fixture.toString()) + ",\"jmeter_home\":" + jsonString(home.toString()) + "}}}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        Map<String, Object> toolResult = mapping(messages.get(0).get("result"));
        assertThat(toolResult).containsEntry("isError", Boolean.FALSE);
        assertThat(list(toolResult.get("content"))).singleElement()
                .satisfies(content -> assertThat(mapping(content).get("text").toString())
                        .contains("org.apache.jmeter.testelement.TestPlan"));
    }

    @Test
    void malformedToolCallEnvelopeKeepsInvalidParamsBoundary() {
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tools/call\",\"params\":[]}",
                "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\",\"params\":{\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":[]}}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(parseJsonLines(result.stdout())).allSatisfy(response ->
                assertThat(mapping(response.get("error"))).containsEntry("code", -32602));
    }

    @Test
    void unknownToolKeepsMethodNotFoundBoundary() {
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":44,\"method\":\"tools/call\",\"params\":{\"name\":\"does-not-exist\",\"arguments\":{}}}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(mapping(parseJsonLines(result.stdout()).get(0).get("error")))
                .containsEntry("code", -32601);
    }

    @Test
    void validKnownToolExecutesOnceWithCurrentInputBoundsAndHomeOverride() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        List<String> observedArgs = new ArrayList<String>();
        JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler((args, environment, stdin) -> {
            executions.incrementAndGet();
            observedArgs.addAll(Arrays.asList(args));
            return successfulResult();
        }, new McpCommandResultAdapter(), Collections.<String, String>emptyMap());

        Map<String, Object> response = dispatch(handler, "read", arguments(
                "file", "input.jmx", "jmeter_home", "/runtime/jmeter", "depth", Integer.valueOf(0)));

        assertThat(executions).hasValue(1);
        assertThat(observedArgs).containsExactly(
                "read", "input.jmx", "--jmeter-home", "/runtime/jmeter", "--depth", "0");
        assertThat(mapping(response.get("result"))).containsEntry("isError", Boolean.FALSE);
        assertThat(McpStdioServer.MAX_JSON_RPC_LINE_BYTES).isEqualTo(8 * 1024 * 1024);
        assertThat(McpToolInvocation.MAX_PATCH_YAML_BYTES).isEqualTo(4 * 1024 * 1024);
    }

    @Test
    void rejectedAddressFormsAndUnknownKeyDoNotExecuteWhileNativeArrayKeepsSessionUsable() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        List<String[]> observedArgs = new ArrayList<String[]>();
        JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler((args, environment, stdin) -> {
            executions.incrementAndGet();
            observedArgs.add(args);
            return successfulResult();
        }, new McpCommandResultAdapter(), Collections.<String, String>emptyMap());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(new ByteArrayInputStream(new byte[0]),
                new PrintStream(output, true), Collections.<String, String>emptyMap());
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(handler);

        dispatcher.dispatch(toolCall(801, "set", arguments(
                "file", "input.jmx", "locator", "ref-1", "property", "TestElement.name",
                "value", "rejected", "override", Boolean.TRUE)), server);
        dispatcher.dispatch(toolCall(802, "set", arguments(
                "file", "input.jmx", "locator", "ref-1",
                "property", Arrays.<Object>asList(arguments("property", "TestElement.name")),
                "value", "rejected-object", "override", Boolean.TRUE)), server);
        dispatcher.dispatch(toolCall(803, "set", arguments(
                "file", "input.jmx", "locator", "ref-1",
                "property", Arrays.<Object>asList("TestElement.name"),
                "value", "rejected-unknown", "override", Boolean.TRUE,
                "unexpected", Boolean.TRUE)), server);
        dispatcher.dispatch(toolCall(804, "set", arguments(
                "file", "input.jmx", "locator", "ref-1",
                "property", Arrays.<Object>asList("TestElement.name"),
                "value", "accepted", "override", Boolean.TRUE)), server);

        assertThat(executions).as("only the valid follow-up may reach command execution").hasValue(1);
        assertThat(observedArgs).singleElement().satisfies(args -> assertThat(args).containsSubsequence(
                "--property", "[\"TestElement.name\"]"));
        List<Map<String, Object>> responses = parseJsonLines(
                new String(output.toByteArray(), StandardCharsets.UTF_8));
        assertThat(responses).hasSize(4);
        assertUsageToolResult(responses.get(0), "property", "non-empty scalar array");
        assertUsageToolResult(responses.get(1), "property", "string or integer");
        assertUsageToolResult(responses.get(2), "unexpected argument", "unexpected");
        assertThat(mapping(responses.get(3).get("result"))).containsEntry("isError", Boolean.FALSE);
    }

    @Test
    void failedMutationIsExecutedOnceAndNeverReplayed() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler((args, environment, stdin) -> {
            executions.incrementAndGet();
            return failedResult();
        }, new McpCommandResultAdapter(), Collections.<String, String>emptyMap());

        Map<String, Object> response = dispatch(handler, "set", arguments(
                "file", "input.jmx", "locator", "ref-1", "property", Arrays.asList("TestElement.name"),
                "value", "changed", "override", Boolean.TRUE));

        assertThat(executions).hasValue(1);
        assertThat(mapping(response.get("result"))).containsEntry("isError", Boolean.TRUE);
    }

    @Test
    void deletedComponentStaleMutationExecutesOneRealWorkerRequestBeforeExplicitRecoveryRead() throws Exception {
        Path source = tempDir.resolve("deleted-component.jmx");
        Files.copy(fixture("simple-http.jmx"), source);
        Path target = tempDir.resolve("must-not-write.jmx");
        Path home = localHome();
        String sourceSpelling = source.getParent().resolve(".").resolve(source.getFileName()).toString();
        String homeSpelling = home.resolve(".").toString();

        LocalJMeterWorkerClient workerClient = LocalJMeterWorkerClient.reusable();
        try (LocalJMeterWorkerProtocolProbe worker = LocalJMeterWorkerProtocolProbe.start()) {
            J4aCommandExecutor executor = new J4aCommandExecutor(workerClient);
            JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler(
                    executor, new McpCommandResultAdapter(),
                    Collections.<String, String>emptyMap());
            Map<String, Object> read = dispatch(handler, "read", arguments(
                    "path", sourceSpelling, "jmeter_home", homeSpelling, "depth", Integer.valueOf(5)));
            String deletedRef = lastReference(toolText(mapping(read.get("result"))));
            assertThat(worker.invocations()).isEqualTo(1);

            Map<String, Object> deleted = dispatch(handler, "apply", arguments(
                    "path", sourceSpelling,
                    "patchYaml", "changes:\n  - delete:\n      ref: " + deletedRef + "\n",
                    "override", Boolean.TRUE,
                    "jmeter_home", homeSpelling));
            Map<String, Object> deletedResult = mapping(deleted.get("result"));
            assertThat(deletedResult).containsEntry("isError", Boolean.FALSE);
            Map<String, Object> deletedData =
                    mapping(mapping(deletedResult.get("structuredContent")).get("data"));
            assertThat(list(deletedData.get("deletedRefs")))
                    .contains(deletedRef);
            assertThat(list(deletedData.get("changeResults"))).singleElement().satisfies(change ->
                    assertThat(mapping(change))
                            .containsEntry("index", Integer.valueOf(0))
                            .containsEntry("operation", "delete")
                            .containsEntry("status", "committed")
                            .doesNotContainKey("resultRef"));
            String deletedText = mapping(list(deletedResult.get("content")).get(0))
                    .get("text").toString();
            assertThat(mapping(new org.yaml.snakeyaml.Yaml().load(deletedText)))
                    .isEqualTo(deletedData);
            assertThat(deletedText).doesNotContain("fingerprint", "generation", "internalHandle");
            assertThat(worker.invocations()).isEqualTo(2);
            byte[] committedSource = Files.readAllBytes(source);

            Map<String, Object> stale = dispatch(handler, "set", arguments(
                    "path", sourceSpelling,
                    "locator", deletedRef,
                    "property", Arrays.asList("TestElement.name"),
                    "value", "must-not-replay",
                    "out", target.toString(),
                    "forceOut", Boolean.TRUE,
                    "jmeter_home", homeSpelling));
            Map<String, Object> staleResult = mapping(stale.get("result"));
            Map<String, Object> structured = mapping(staleResult.get("structuredContent"));
            Map<String, Object> recovery = mapping(structured.get("recovery"));

            assertThat(staleResult).containsEntry("isError", Boolean.TRUE);
            assertThat(list(structured.get("diagnostics"))).singleElement().satisfies(diagnostic ->
                    assertThat(mapping(diagnostic))
                            .containsEntry("code", "MCP_REF_NOT_FOUND")
                            .containsEntry("category", "usage")
                            .containsEntry("message", "The session component ref is unavailable. It may be mistyped, deleted, "
                                    + "refreshed after an external change, evicted, or lost after MCP/runtime restart.")
                            .containsEntry("suggestedNextAction", "Call the read MCP tool with the same file and same JMeter home "
                                    + "(jmeter_home) to obtain fresh refs, then use the returned ref field in a new MCP tool call."));
            assertThat(recovery).containsOnlyKeys("tool", "arguments").containsEntry("tool", "read");
            assertThat(mapping(recovery.get("arguments")))
                    .containsExactlyEntriesOf(arguments("path", sourceSpelling, "jmeter_home", homeSpelling))
                    .doesNotContainKeys("ref", "locator", "patchYaml", "value", "out", "forceOut", "canonical", "internal");
            assertThat(worker.invocations()).isEqualTo(3);
            assertThat(Files.readAllBytes(source)).containsExactly(committedSource);
            assertThat(target).doesNotExist();

            McpToolInvocation recoveryInvocation = McpToolInvocation.from(
                    String.valueOf(recovery.get("tool")), mapping(recovery.get("arguments")));
            assertThat(recoveryInvocation.valid()).isTrue();
            CommandResult fresh = executor.execute(
                    recoveryInvocation.args(), Collections.<String, String>emptyMap(), recoveryInvocation.stdin());
            assertThat(fresh.exitCode()).isZero();
            assertThat(fresh.structuredData()).containsEntry("command", "read");
            assertThat(worker.invocations()).isEqualTo(4);
        } finally {
            workerClient.close();
        }
    }

    @Test
    void catastrophicAcceptedMutationIsNotReplayedAndRecoversByInspectingTarget() throws Exception {
        Path executionCount = tempDir.resolve("mutation-executions.txt");
        Path home = CatastrophicMutationWorkerFixture.createHome(tempDir, executionCount);
        Path source = tempDir.resolve("catastrophic-source.jmx");
        Path target = tempDir.resolve("catastrophic-target.jmx");
        byte[] sourceBytes = "source-remains-unchanged".getBytes(StandardCharsets.UTF_8);
        Files.write(source, sourceBytes);

        LocalJMeterWorkerClient workerClient = LocalJMeterWorkerClient.reusable();
        try {
            JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler(
                    new J4aCommandExecutor(workerClient), new McpCommandResultAdapter(),
                    Collections.<String, String>emptyMap());
            Map<String, Object> initial = dispatch(handler, "read", arguments(
                    "file", source.toString(), "jmeter_home", home.toString(),
                    "depth", Integer.valueOf(5)));
            String oldRef = lastReference(toolText(mapping(initial.get("result"))));

            Map<String, Object> failed = dispatch(handler, "apply", arguments(
                    "file", source.toString(),
                    "patchYaml", "changes:\n  - set:\n      ref: " + oldRef + "\n"
                            + "      properties:\n        - property: [TestElement.name]\n"
                            + "          type: string\n          value: catastrophic-worker-loss\n",
                    "out", target.toString(), "forceOut", Boolean.TRUE,
                    "jmeter_home", home.toString()));

            Map<String, Object> failedResult = mapping(failed.get("result"));
            Map<String, Object> structured = mapping(failedResult.get("structuredContent"));
            Map<String, Object> recovery = mapping(structured.get("recovery"));
            assertThat(failedResult).containsEntry("isError", Boolean.TRUE);
            assertThat(list(structured.get("diagnostics"))).singleElement().satisfies(item ->
                    assertThat(mapping(item)).containsEntry("code", "LOCAL_JMETER_RUNTIME_ERROR"));
            assertThat(recovery).containsOnlyKeys("tool", "arguments").containsEntry("tool", "read");
            assertThat(mapping(recovery.get("arguments"))).containsExactlyEntriesOf(arguments(
                    "file", target.toString(), "jmeter_home", home.toString()));
            assertThat(Files.readAllLines(executionCount, StandardCharsets.UTF_8))
                    .containsExactly("execution");
            assertThat(Files.readAllBytes(target))
                    .containsExactly("CATASTROPHIC_MUTATION_COMMITTED".getBytes(StandardCharsets.UTF_8));
            assertThat(Files.readAllBytes(source)).containsExactly(sourceBytes);

            Map<String, Object> stale = dispatch(handler, "read", arguments(
                    "file", target.toString(), "jmeter_home", home.toString(), "ref", oldRef));
            assertThat(mapping(stale.get("result"))).containsEntry("isError", Boolean.TRUE);
            Map<String, Object> inspected = dispatch(handler,
                    String.valueOf(recovery.get("tool")), mapping(recovery.get("arguments")));
            String freshRef = lastReference(toolText(mapping(inspected.get("result"))));
            assertThat(freshRef).isNotEqualTo(oldRef);
            assertThat(Files.readAllLines(executionCount, StandardCharsets.UTF_8))
                    .containsExactly("execution");
        } finally {
            workerClient.close();
        }
    }

    @Test
    void staleRefRecoveryPreservesPathAliasAndExplicitHomeWithoutMutationArguments() {
        Path fixture = fixture("simple-http.jmx");
        Path home = localHome();
        String originalPath = fixture.getParent().resolve(".").resolve(fixture.getFileName()).toString();
        String originalHome = home.resolve(".").toString();
        ServerResult result = runServer(call(710, "set",
                "\"path\":" + jsonString(originalPath)
                        + ",\"locator\":\"AAAAAAAAAAAAAAAA\",\"property\":[\"TestElement.name\"],"
                        + "\"value\":\"untrusted-value\",\"override\":true,\"jmeter_home\":"
                        + jsonString(originalHome)));

        Map<String, Object> toolResult = mapping(parseJsonLines(result.stdout()).get(0).get("result"));
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        Map<String, Object> recovery = mapping(structured.get("recovery"));
        assertThat(recovery).containsOnlyKeys("tool", "arguments").containsEntry("tool", "read");
        assertThat(mapping(recovery.get("arguments")))
                .containsExactlyEntriesOf(arguments("path", originalPath, "jmeter_home", originalHome))
                .doesNotContainKeys("file", "locator", "property", "value", "override", "patchYaml", "patchFile");
        assertThat(list(structured.get("diagnostics"))).singleElement().satisfies(item ->
                assertThat(mapping(item))
                        .containsEntry("code", "MCP_REF_NOT_FOUND")
                        .containsEntry("category", "usage"));
        assertThat(result.stdout()).doesNotContain("untrusted-value");
    }

    @Test
    void staleRefRecoveryOmitsHomeWhenCallerUsedEnvironmentSelection() {
        Path fixture = fixture("simple-http.jmx");
        Path home = localHome();
        String originalFile = fixture.getParent().resolve(".").resolve(fixture.getFileName()).toString();
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", home.toString());
        ServerResult result = runServer(environment, call(711, "read",
                "\"file\":" + jsonString(originalFile) + ",\"ref\":\"AAAAAAAAAAAAAAAA\""));

        Map<String, Object> toolResult = mapping(parseJsonLines(result.stdout()).get(0).get("result"));
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        assertThat(mapping(mapping(structured.get("recovery")).get("arguments")))
                .containsExactlyEntriesOf(arguments("file", originalFile))
                .doesNotContainKey("jmeter_home");
    }

    @Test
    void toolsCallReadMissingFileReturnsAdaptedToolError() {
        Path home = localHome();
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"read\",\"arguments\":{\"file\":\"build/tmp/does-not-exist-task-3-mcp.jmx\",\"jmeter_home\":"
                        + jsonString(home.toString()) + "}}}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();

        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(1);
        Map<String, Object> toolResult = mapping(messages.get(0).get("result"));
        assertThat(toolResult).containsEntry("isError", Boolean.TRUE);
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        assertThat(structured).containsEntry("exitStatus", 4);
        assertThat(list(structured.get("diagnostics"))).singleElement()
                .satisfies(diagnostic -> assertThat(mapping(diagnostic))
                        .containsEntry("code", "JMX_READ_ERROR")
                        .containsEntry("category", "filesystem"));
        assertThat(structured.get("recoveryGuidance").toString()).contains("check that the file exists");
        assertThat(structured).doesNotContainKey("recovery");
    }

    @Test
    void knownToolWithInvalidArgumentsReturnsActionableUsageToolResult() {
        ServerResult result = runServer(
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"apply\",\"arguments\":{}}}");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();

        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(1);
        assertUsageToolResult(messages.get(0), "apply", "file", "path", "exactly one");
    }

    @Test
    void adapterTranslatesCliOnlyCatalogRecoveryToCategoriesMcpTool() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "rerun categories ls to list valid category ids, then retry components --category."));

        assertMcpRecovery(adapted, "call the categories MCP tool", "category");
    }

    @Test
    void adapterTranslatesReferenceRecoveryToReadMcpToolWithoutCliFallbackOrReplay() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "select or fix a conforming JMeter runtime/home, read there for new refs, "
                        + "and retry; otherwise use stateless CLI write/read recovery."));

        assertMcpRecovery(adapted, "call the read MCP tool", "same file", "same JMeter home");
    }

    @Test
    void adapterMakesCollectionRecoveryNameComponentsTemplateAndFocusedReadTools() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "copy value_template from components or an exact value from focused read."));

        assertMcpRecovery(adapted, "call the read MCP tool", "components MCP tool", "value_template");
    }

    @Test
    void adapterTranslatesValidateDefaultRecoveryToValidateMcpTool() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "pass a readable .jmx file and configure a local JMeter home."));

        assertMcpRecovery(adapted, "call the validate MCP tool", "file", "jmeter_home");
    }

    @Test
    void adapterTranslatesApplyDefaultRecoveryToApplyMcpTool() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "pass a valid patch and a safe output target, or use --dry-run to check the patch."));

        assertMcpRecovery(adapted, "call the apply MCP tool", "file", "patchYaml", "dryRun", "out");
    }

    @Test
    void adapterTranslatesInitDefaultRecoveryToInitMcpTool() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "pass a writable output .jmx path and --force-out only when replacing an existing file."));

        assertMcpRecovery(adapted, "call the init MCP tool", "out", "forceOut");
    }

    @Test
    void adapterTranslatesSetDefaultRecoveryToReadAndSetMcpTools() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "rerun read to refresh locators and property paths, then retry set with a safe output target."));

        assertMcpRecovery(adapted, "call the set MCP tool", "read MCP tool", "locator", "property", "out");
    }

    @Test
    void adapterTranslatesReadRecoveryToReadMcpTool() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "rerun read --help and use supported read flags."));

        assertMcpRecovery(adapted, "call the read MCP tool", "file", "jmeter_home");
    }

    @Test
    void adapterTranslatesCategoriesDefaultRecoveryToCategoriesMcpTool() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "rerun categories ls with --jmeter-home or an environment-selected local JMeter home."));

        assertMcpRecovery(adapted, "call the categories MCP tool", "jmeter_home");
    }

    @Test
    void adapterTranslatesUnknownDefaultRecoveryToReadMcpTool() {
        Map<String, Object> adapted = new McpCommandResultAdapter().adapt(failedResult(
                "rerun the command with supported options."));

        assertMcpRecovery(adapted, "call the read MCP tool", "file", "jmeter_home");
    }

    @Test
    void unexpectedArgumentsForEveryPublicToolAreRejectedBeforeCommandExecution() throws Exception {
        for (String toolName : REQUIRED_TOOL_NAMES) {
            assertRejectedBeforeExecution(toolName, arguments("unexpected", Boolean.TRUE),
                    "unexpected", toolName, "allowed");
        }
        assertRejectedBeforeExecution("read", arguments("file", "input.jmx", "properites", "all"),
                "properites", "read", "allowed");
    }

    @Test
    void ambiguousAliasesAreRejectedBeforeCommandExecution() throws Exception {
        assertRejectedBeforeExecution("read", arguments("file", "input.jmx", "path", "other.jmx"),
                "file", "path", "exactly one");
        assertRejectedBeforeExecution("components", arguments(
                "component", "org.example.Component", "kind", "org.example.Kind"),
                "component", "kind", "exactly one");
    }

    @Test
    void invalidComponentModesAndFalseMarkersAreRejectedBeforeCommandExecution() throws Exception {
        assertRejectedBeforeExecution("components", arguments("details", Boolean.TRUE),
                "details", "component", "kind");
        assertRejectedBeforeExecution("components", arguments("component", "org.example.Component", "details", Boolean.FALSE),
                "details", "true");
        assertRejectedBeforeExecution("components", arguments("category", "samplers", "component", "org.example.Component"),
                "category", "component", "exclusive");
        assertRejectedBeforeExecution("components", arguments("category", "samplers", "details", Boolean.TRUE,
                "maxBytes", Integer.valueOf(4095)), "maxBytes", "4096", "65536");
        assertRejectedBeforeExecution("components", arguments("maxBytes", Integer.valueOf(4096)),
                "maxBytes", "category", "details");
        assertRejectedBeforeExecution("components", arguments("componentToken", "opaque", "category", "samplers"),
                "componentToken", "exclusive");
        assertRejectedBeforeExecution("components", arguments("componentToken", ""),
                "componentToken", "non-empty", "512");
    }

    @Test
    void invalidReadBoundsEnumsAndTypesAreRejectedBeforeCommandExecution() throws Exception {
        assertRejectedBeforeExecution("read", arguments("file", "input.jmx", "depth", Integer.valueOf(-1)),
                "depth", "zero or greater");
        assertRejectedBeforeExecution("read", arguments("file", "input.jmx", "properties", "verbose"),
                "properties", "none", "key", "all");
        assertRejectedBeforeExecution("read", arguments("file", "input.jmx", "depth", "1"),
                "depth", "integer");
        assertRejectedBeforeExecution("read", arguments(
                "file", "input.jmx", "includeDisabledDetails", Boolean.FALSE),
                "includeDisabledDetails", "true");
    }

    @Test
    void conflictingSetAndApplyWriteModesAreRejectedBeforeCommandExecution() throws Exception {
        Map<String, Object> set = arguments(
                "file", "input.jmx", "locator", "jmx_1", "property", Arrays.asList("name"), "value", "next");
        set.put("out", "copy.jmx");
        set.put("override", Boolean.TRUE);
        assertRejectedBeforeExecution("set", set, "out", "override", "exactly one");

        Map<String, Object> setOverrideForce = arguments(
                "file", "input.jmx", "locator", "jmx_1", "property", Arrays.asList("name"), "value", "next",
                "override", Boolean.TRUE, "forceOut", Boolean.TRUE);
        assertRejectedBeforeExecution("set", setOverrideForce, "override", "forceOut");
        Map<String, Object> setForceOnly = arguments(
                "file", "input.jmx", "locator", "jmx_1", "property", Arrays.asList("name"), "value", "next",
                "forceOut", Boolean.TRUE);
        assertRejectedBeforeExecution("set", setForceOnly, "forceOut", "out");

        assertRejectedBeforeExecution("apply", arguments(
                "file", "input.jmx", "patchYaml", "changes: []", "dryRun", Boolean.FALSE),
                "dryRun", "true");
        assertRejectedBeforeExecution("apply", arguments("file", "input.jmx", "patchYaml", "changes: []"),
                "dryRun", "override", "out");
    }

    @Test
    void wrongTopLevelTypesForEveryPublicToolAreRejectedBeforeCommandExecution() throws Exception {
        assertRejectedBeforeExecution("read", arguments("file", Integer.valueOf(1)), "file", "string");
        assertRejectedBeforeExecution("validate", arguments("file", "input.jmx", "jmeter_home", Boolean.TRUE),
                "jmeter_home", "string");
        assertRejectedBeforeExecution("components", arguments("category", Boolean.TRUE), "category", "string");
        assertRejectedBeforeExecution("categories", arguments("jmeter_home", Boolean.TRUE), "jmeter_home", "string");
        assertRejectedBeforeExecution("apply", arguments(
                "file", "input.jmx", "patchYaml", Integer.valueOf(1), "dryRun", Boolean.TRUE),
                "patchYaml", "string");
        assertRejectedBeforeExecution("init", arguments("out", "new.jmx", "forceOut", Boolean.FALSE),
                "forceOut", "true");
        assertRejectedBeforeExecution("set", arguments(
                "file", "input.jmx", "locator", Integer.valueOf(1), "property", Arrays.asList("name"), "value", "next",
                "override", Boolean.TRUE), "locator", "string");
        assertRejectedBeforeExecution("set", arguments(
                "file", "input.jmx", "locator", "jmx_1", "property", Arrays.asList("name"), "value", "next",
                "type", "not-a-public-type", "override", Boolean.TRUE), "type", "not-a-public-type");
    }

    private static void assertRejectedBeforeExecution(
            String toolName, Map<String, Object> arguments, String... expectedFragments) throws Exception {
        AtomicInteger executions = new AtomicInteger();
        JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler((args, environment, stdin) -> {
            executions.incrementAndGet();
            return successfulResult();
        }, new McpCommandResultAdapter(), Collections.<String, String>emptyMap());
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", Integer.valueOf(700));
        request.put("method", "tools/call");
        request.put("params", params);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(new ByteArrayInputStream(new byte[0]),
                new PrintStream(output, true), Collections.<String, String>emptyMap());

        new JsonRpcDispatcher(handler).dispatch(McpJson.write(request), server);

        assertThat(executions).as("%s must fail before command execution", toolName).hasValue(0);
        List<Map<String, Object>> responses = parseJsonLines(
                new String(output.toByteArray(), StandardCharsets.UTF_8));
        assertThat(responses).as("%s validation response count", toolName).hasSize(1);
        assertUsageToolResult(responses.get(0), expectedFragments);
    }

    private static void assertUsageToolResult(Map<String, Object> response, String... expectedFragments) {
        assertThat(response).doesNotContainKey("error");
        Map<String, Object> toolResult = mapping(response.get("result"));
        assertThat(toolResult).containsEntry("isError", Boolean.TRUE);
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        assertThat(structured).containsEntry("exitStatus", 2).containsKey("recoveryGuidance");
        assertThat(String.valueOf(structured.get("recoveryGuidance")))
                .isNotBlank()
                .contains(expectedFragments);
        assertThat(list(structured.get("diagnostics"))).singleElement().satisfies(item ->
                assertThat(mapping(item))
                        .containsEntry("code", "USAGE_ERROR")
                        .containsEntry("category", "usage")
                        .hasEntrySatisfying("suggestedNextAction", action -> assertThat(action).isNotNull()));
    }

    private static void assertMcpRecovery(
            Map<String, Object> adapted, String requiredAction, String... requiredContext) {
        Map<String, Object> structured = mapping(adapted.get("structuredContent"));
        String recovery = String.valueOf(structured.get("recoveryGuidance"));
        assertThat(recovery).containsIgnoringCase(requiredAction).contains(requiredContext);
        assertThat(recovery).doesNotContain("--")
                .doesNotContainIgnoringCase("categories ls", "CLI", "fallback", "retry", "replay");
    }

    private static CommandResult successfulResult() {
        return new CommandResult() {
            public int exitCode() { return 0; }
            public String textOutput() { return "ok"; }
            public Map<String, Object> structuredData() { return Collections.emptyMap(); }
            public List<CommandDiagnostic> diagnostics() { return Collections.emptyList(); }
            public String recoveryGuidance() { return null; }
        };
    }

    private static CommandResult failedResult() {
        return failedResult(null);
    }

    private static CommandResult failedResult(String recoveryGuidance) {
        return new CommandResult() {
            public int exitCode() { return 4; }
            public String textOutput() { return ""; }
            public Map<String, Object> structuredData() { return Collections.emptyMap(); }
            public List<CommandDiagnostic> diagnostics() { return Collections.emptyList(); }
            public String recoveryGuidance() { return recoveryGuidance; }
        };
    }

    private static Map<String, Object> dispatch(
            JsonRpcToolCallHandler handler, String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", toolName);
        params.put("arguments", arguments);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", Integer.valueOf(701));
        request.put("method", "tools/call");
        request.put("params", params);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(new ByteArrayInputStream(new byte[0]),
                new PrintStream(output, true), Collections.<String, String>emptyMap());

        new JsonRpcDispatcher(handler).dispatch(McpJson.write(request), server);

        List<Map<String, Object>> responses = parseJsonLines(
                new String(output.toByteArray(), StandardCharsets.UTF_8));
        assertThat(responses).as("one JSON-RPC request must produce one response").hasSize(1);
        return responses.get(0);
    }

    private static Map<String, Object> arguments(Object... entries) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            arguments.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return arguments;
    }

    private static String toolCall(int id, String tool, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", tool);
        params.put("arguments", arguments);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", Integer.valueOf(id));
        request.put("method", "tools/call");
        request.put("params", params);
        return McpJson.write(request);
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

    private static ServerResult runServer(Map<String, String> environment, String... lines) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        String input = String.join("\n", lines) + "\n";

        McpStdioServer server = new McpStdioServer(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true), environment);
        int exitCode;
        try {
            exitCode = server.run();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("MCP server failed", exception);
        }

        return new ServerResult(
                exitCode,
                new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    private static Path localHome() {
        io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures fixtures =
                io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures.fresh();
        try {
            fixtures.ensure();
            return fixtures.localHome();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to create local JMeter protocol fixture.", exception);
        }
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

    private static String idText(Object value) {
        if (value instanceof Number) {
            return String.valueOf(((Number) value).longValue());
        }
        return String.valueOf(value);
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(J4aMcpProtocolTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Fixture URI is invalid: " + name, exception);
        }
    }

    private static Map<String, Object> tool(List<Map<String, Object>> tools, String name) {
        return tools.stream()
                .filter(tool -> name.equals(tool.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing tool: " + name));
    }

    private static void assertFileOrPathSchema(Map<String, Object> tool) {
        Map<String, Object> inputSchema = mapping(tool.get("inputSchema"));
        assertThat(mapping(inputSchema.get("properties"))).containsKeys("file", "path");
        assertThat(list(inputSchema.get("allOf"))).anySatisfy(group -> {
            List<Object> oneOf = list(mapping(group).get("oneOf"));
            assertThat(oneOf).anySatisfy(schema -> assertThat(mapping(schema).get("required"))
                    .isEqualTo(Arrays.asList("file")));
            assertThat(oneOf).anySatisfy(schema -> assertThat(mapping(schema).get("required"))
                    .isEqualTo(Arrays.asList("path")));
        });
    }

    private static String jsonString(String value) {
        return McpJson.write(value);
    }

    private static String repeated(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static String nestedArrays(int depth) {
        return repeated('[', depth) + "0" + repeated(']', depth);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseJsonLines(String stdout) {
        assertThat(stdout).as("stdout must contain only newline-delimited JSON-RPC messages").isNotBlank();
        Yaml yaml = new Yaml();
        List<Map<String, Object>> messages = new ArrayList<>();
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

    private static String toolText(Map<String, Object> result) {
        return String.valueOf(mapping(list(result.get("content")).get(0)).get("text"));
    }

    private static String lastReference(String yamlText) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s*ref: ([A-Za-z0-9_-]{16})\\s*$")
                .matcher(yamlText);
        String reference = null;
        while (matcher.find()) {
            reference = matcher.group(1);
        }
        assertThat(reference).as("read result must include an opaque component ref").isNotNull();
        return reference;
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
