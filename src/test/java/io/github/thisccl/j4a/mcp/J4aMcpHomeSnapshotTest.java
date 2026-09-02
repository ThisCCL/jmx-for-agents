package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import io.github.thisccl.j4a.cli.CommandDiagnostic;
import io.github.thisccl.j4a.cli.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class J4aMcpHomeSnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void environmentChangesAfterStartupDoNotChangeDefaultHome() throws Exception {
        Path startupHome = tempDir.resolve("startup-home");
        Path laterHome = tempDir.resolve("later-home");
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", startupHome.toString());
        RecordingExecution execution = new RecordingExecution();
        JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler(
                execution, new McpCommandResultAdapter(), environment);

        environment.put("JMX_AGENT_JMETER_HOME", laterHome.toString());
        dispatch(handler, call(1, "{}"));

        assertThat(execution.environment.get("JMX_AGENT_JMETER_HOME")).isEqualTo(startupHome.toString());
    }

    @Test
    void explicitHomeOverridesStartupEnvironmentSnapshot() throws Exception {
        Path startupHome = tempDir.resolve("startup-home");
        Path explicitHome = tempDir.resolve("explicit-home");
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", startupHome.toString());

        RecordingExecution execution = new RecordingExecution();
        JsonRpcToolCallHandler handler = new JsonRpcToolCallHandler(
                execution, new McpCommandResultAdapter(), environment);
        dispatch(handler, call(1, "{\"jmeter_home\":" + McpJson.write(explicitHome.toString()) + "}"));

        assertThat(execution.args).containsSequence("--jmeter-home", explicitHome.toString());
        assertThat(execution.environment.get("JMX_AGENT_JMETER_HOME")).isEqualTo(startupHome.toString());
    }

    @Test
    void invalidHomeCreatesNoPoolEntry() throws Exception {
        Path invalidHome = tempDir.resolve("missing-home");
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", invalidHome.toString());

        String response = run(call(1, "{}") + call(2, "{}"), environment);

        assertThat(response).contains("\"exitStatus\":4");
        assertThat(response).doesNotContain("JMX_AGENT_LOCAL_WORKER_READY");
    }

    private static String run(String requests, Map<String, String> environment) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new McpStdioServer(input(requests), printStream(output), environment).run();
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static PrintStream printStream(ByteArrayOutputStream output) throws Exception {
        return new PrintStream(output, true, StandardCharsets.UTF_8.name());
    }

    private static String call(int id, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"components\",\"arguments\":"
                + arguments + "}}\n";
    }

    @SuppressWarnings("unchecked")
    private static void dispatch(JsonRpcToolCallHandler handler, String request) throws Exception {
        Map<String, Object> parsed = (Map<String, Object>) McpJson.parse(request.trim());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpStdioServer server = new McpStdioServer(input(""), printStream(output), Collections.<String, String>emptyMap());
        handler.dispatch(parsed.get("id"), parsed.get("params"), server);
    }

    private static final class RecordingExecution implements JsonRpcToolCallHandler.CommandExecution {
        private String[] args;
        private Map<String, String> environment;

        @Override
        public CommandResult execute(String[] args, Map<String, String> environment, java.io.InputStream stdin) {
            this.args = args;
            this.environment = environment;
            return new CommandResult() {
                public int exitCode() { return 0; }
                public String textOutput() { return "ok"; }
                public Map<String, Object> structuredData() { return Collections.emptyMap(); }
                public java.util.List<CommandDiagnostic> diagnostics() { return Collections.emptyList(); }
                public String recoveryGuidance() { return null; }
            };
        }
    }
}
