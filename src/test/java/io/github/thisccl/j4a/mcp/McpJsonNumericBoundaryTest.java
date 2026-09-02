package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.cli.CommandDiagnostic;
import io.github.thisccl.j4a.cli.CommandResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class McpJsonNumericBoundaryTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTypedLiterals")
    void jsonRpcRejectsOverflowAndNonfiniteTypedLiteralsBeforeExecution(
            String name, String type, String literal, String expected) throws Exception {
        AtomicInteger executions = new AtomicInteger();

        Map<String, Object> response = dispatch(setRequest(type, literal), executions, null);

        assertThat(executions).as(name).hasValue(0);
        Map<String, Object> result = mapping(response.get("result"));
        assertThat(result).containsEntry("isError", Boolean.TRUE);
        assertThat(String.valueOf(mapping(result.get("structuredContent")).get("recoveryGuidance")))
                .contains("explicit type '" + type + "'", expected);
    }

    @Test
    void ordinaryJsonIntegerArgumentsStillReachInvocationAsIntegers() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<String[]> invocation = new AtomicReference<String[]>();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"components\",\"arguments\":{"
                + "\"category\":\"timers\",\"details\":true,\"limit\":2}}}";

        Map<String, Object> response = dispatch(request, executions, invocation);

        assertThat(executions).hasValue(1);
        assertThat(Arrays.asList(invocation.get())).containsSubsequence("--limit", "2");
        assertThat(mapping(response.get("result"))).containsEntry("isError", Boolean.FALSE);
    }

    private static Stream<Arguments> invalidTypedLiterals() {
        return Stream.of(
                Arguments.of("int overflow", "int", "2147483648", "Java int range"),
                Arguments.of("long overflow", "long", "9223372036854775808", "Java long range"),
                Arguments.of("float overflow", "float", "1e39", "Java float range"),
                Arguments.of("float underflow", "float", "1e-1000", "Java float range"),
                Arguments.of("double overflow", "double", "1e309", "Java double range"),
                Arguments.of("double underflow", "double", "1e-10000", "Java double range"));
    }

    private static String setRequest(String type, String literal) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"set\",\"arguments\":{"
                + "\"file\":\"input.jmx\",\"locator\":\"jmx_1\",\"property\":[\"qa.scalar\"],"
                + "\"type\":\"" + type + "\",\"value\":" + literal + ",\"override\":true}}}";
    }

    private static Map<String, Object> dispatch(
            String request, AtomicInteger executions, AtomicReference<String[]> invocation) throws Exception {
        JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler((args, environment, stdin) -> {
            executions.incrementAndGet();
            if (invocation != null) invocation.set(args);
            return success();
        }, new McpCommandResultAdapter(), Collections.<String, String>emptyMap());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(new ByteArrayInputStream(new byte[0]),
                new PrintStream(output, true), Collections.<String, String>emptyMap());

        new JsonRpcDispatcher(handler).dispatch(request, server);

        String json = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        return mapping(McpJson.parse(json));
    }

    private static CommandResult success() {
        return new CommandResult() {
            @Override public int exitCode() { return 0; }
            @Override public String textOutput() { return "ok"; }
            @Override public Map<String, Object> structuredData() { return Collections.emptyMap(); }
            @Override public List<CommandDiagnostic> diagnostics() { return Collections.emptyList(); }
            @Override public String recoveryGuidance() { return null; }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }
}
