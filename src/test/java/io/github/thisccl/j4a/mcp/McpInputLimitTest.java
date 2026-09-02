package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.cli.CommandResult;
import io.github.thisccl.j4a.cli.CommandDiagnostic;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpInputLimitTest {
    @Test
    void validReadWithoutExplicitDepthReachesCommandExecutionWithCliDefaultsIntact() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<String[]> executedArgs = new AtomicReference<String[]>();
        JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler((args, environment, stdin) -> {
            executions.incrementAndGet();
            executedArgs.set(args);
            return successfulResult();
        }, new McpCommandResultAdapter(), Collections.<String, String>emptyMap());
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("file", "input.jmx");
        McpStdioServer server = new McpStdioServer(new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true), Collections.<String, String>emptyMap());

        new JsonRpcDispatcher(handler).dispatch(toolCall("read", arguments), server);

        assertThat(executions).hasValue(1);
        assertThat(executedArgs.get()).containsExactly("read", "input.jmx");
    }

    @Test
    void oversizedInlinePatchIsRejectedBeforeCommandExecution() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        JsonRpcToolCallHandler handler = handler(executions);
        String patch = repeated('x', McpToolInvocation.MAX_PATCH_YAML_BYTES + 1);
        String request = toolCall(patch);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(new ByteArrayInputStream(new byte[0]),
                new PrintStream(output, true), Collections.<String, String>emptyMap());

        new JsonRpcDispatcher(handler).dispatch(request, server);

        assertThat(executions).hasValue(0);
        assertThat(new String(output.toByteArray(), StandardCharsets.UTF_8)).contains("PATCH_INPUT_TOO_LARGE");
    }

    @Test
    void inlinePatchAtDocumentedLimitReachesCommandExecution() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        JsonRpcToolCallHandler handler = handler(executions);
        String patch = repeated('x', McpToolInvocation.MAX_PATCH_YAML_BYTES);
        McpStdioServer server = new McpStdioServer(new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true), Collections.<String, String>emptyMap());

        new JsonRpcDispatcher(handler).dispatch(toolCall(patch), server);

        assertThat(executions).hasValue(1);
    }

    @Test
    void exactJsonLineLimitIsAcceptedAndOversizeKeepsTheSameSessionUsable() throws Exception {
        String valid = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        String exact = repeated(' ', McpStdioServer.MAX_JSON_RPC_LINE_BYTES
                - valid.getBytes(StandardCharsets.UTF_8).length) + valid;
        String oversized = repeated(' ', McpStdioServer.MAX_JSON_RPC_LINE_BYTES + 1);
        String input = exact + "\n" + oversized + "\n" + valid + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true), Collections.<String, String>emptyMap());

        int exit = server.run();

        assertThat(exit).isZero();
        String[] lines = new String(output.toByteArray(), StandardCharsets.UTF_8).trim().split("\\R");
        assertThat(lines).hasSize(3);
        assertThat(((Number) mapping(McpJson.parse(lines[0])).get("id")).longValue()).isEqualTo(1L);
        assertThat(((Number) mapping(mapping(McpJson.parse(lines[1])).get("error")).get("code")).longValue())
                .isEqualTo(-32600L);
        assertThat(((Number) mapping(McpJson.parse(lines[2])).get("id")).longValue()).isEqualTo(1L);
    }

    private static JsonRpcToolCallHandler handler(AtomicInteger executions) {
        return new JsonRpcToolCallHandler((args, environment, stdin) -> {
            executions.incrementAndGet();
            return successfulResult();
        }, new McpCommandResultAdapter(), Collections.<String, String>emptyMap());
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

    private static String toolCall(String patch) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("file", "input.jmx");
        arguments.put("patchYaml", patch);
        arguments.put("dryRun", Boolean.TRUE);
        return toolCall("apply", arguments);
    }

    private static String toolCall(String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", name);
        params.put("arguments", arguments);
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.put("params", params);
        return McpJson.write(request);
    }

    private static String repeated(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }
}
