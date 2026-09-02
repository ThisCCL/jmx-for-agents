package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import io.github.thisccl.j4a.cli.CommandResult;
import io.github.thisccl.j4a.cli.J4aCommandExecutor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class McpCategoryDetailsTest {
    @TempDir
    Path tempDir;

    private String previousUserHome;

    @BeforeEach
    void isolateUserHome() throws Exception {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tempDir.resolve("user-home")).toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (previousUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", previousUserHome);
        }
    }

    @Test
    void categoryPagesKeepYamlAndStructuredDataEquivalent() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        String home = McpJson.write(fixtures.localHome().toString());
        ServerResult first = runServer(call(1, "\"category\":\"sampler\",\"details\":true,"
                + "\"limit\":2,\"jmeter_home\":" + home));
        Map<String, Object> firstResult = toolResult(first.stdout);
        Map<String, Object> firstData = structuredData(firstResult);
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", fixtures.localHome().toString());
        CommandResult cli = new J4aCommandExecutor().execute(new String[] {
                "components", "--category", "sampler", "--details", "true", "--limit", "2"
        }, environment);
        String cursor = String.valueOf(firstData.get("next_cursor"));
        ServerResult next = runServer(call(2, "\"category\":\"sampler\",\"details\":true,"
                + "\"limit\":2,\"cursor\":" + McpJson.write(cursor) + ",\"jmeter_home\":" + home));
        Map<String, Object> nextResult = toolResult(next.stdout);

        assertThat(firstResult).containsEntry("isError", Boolean.FALSE);
        assertThat(nextResult).containsEntry("isError", Boolean.FALSE);
        assertThat(cli.exitCode()).isZero();
        assertThat(firstData).isEqualTo(cli.structuredData());
        assertThat(firstData).isEqualTo(yamlData(firstResult));
        assertThat(structuredData(nextResult)).isEqualTo(yamlData(nextResult));
        assertThat(components(firstData)).doesNotContainAnyElementsOf(components(structuredData(nextResult)));
    }

    @Test
    void partialCategoryPageKeepsBothMcpResultFormsEquivalent() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        ServerResult response = runServer(call(4, "\"category\":\"sampler\",\"details\":true,"
                + "\"limit\":50,\"jmeter_home\":" + McpJson.write(fixtures.localHome().toString())));
        Map<String, Object> result = toolResult(response.stdout);

        assertThat(result).containsEntry("isError", Boolean.FALSE);
        assertThat(structuredData(result)).containsEntry("partial", Boolean.TRUE)
                .isEqualTo(yamlData(result));
    }

    @Test
    void invalidCursorIsAnMcpNativeArgumentFailure() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        ServerResult result = runServer(call(3, "\"category\":\"sampler\",\"details\":true,"
                + "\"limit\":2,\"cursor\":\"tampered\",\"jmeter_home\":"
                + McpJson.write(fixtures.localHome().toString())));

        Map<String, Object> toolResult = toolResult(result.stdout);
        assertThat(toolResult).containsEntry("isError", Boolean.TRUE);
        assertThat(String.valueOf(mapping(toolResult.get("structuredContent")).get("diagnostics")))
                .contains("USAGE_ERROR", "Invalid components cursor");
    }

    @Test
    void maxBytesAndComponentTokenShareCliProjectionAndRecoverThroughRealStdio() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", fixtures.localHome().toString());
        J4aCommandExecutor executor = new J4aCommandExecutor();
        String cursor = null;
        String token = null;
        while (token == null) {
            List<String> args = new ArrayList<String>(java.util.Arrays.asList(
                    "components", "--category", "sampler", "--details", "true",
                    "--limit", "50", "--max-bytes", "4096"));
            if (cursor != null) {
                args.add("--cursor");
                args.add(cursor);
            }
            CommandResult pageResult = executor.execute(
                    args.toArray(new String[args.size()]), environment);
            assertThat(pageResult.exitCode()).isZero();
            assertThat(pageResult.textOutput().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(4096);
            Map<String, Object> page = pageResult.structuredData();
            for (Object item : list(page.get("components"))) {
                Map<String, Object> entry = mapping(item);
                if (entry.containsKey("recovery")) {
                    token = String.valueOf(mapping(entry.get("recovery")).get("componentToken"));
                }
            }
            cursor = page.containsKey("next_cursor") ? String.valueOf(page.get("next_cursor")) : null;
            assertThat(token != null || cursor != null).isTrue();
        }

        String home = McpJson.write(fixtures.localHome().toString());
        ServerResult response = runServer(call(9, "\"componentToken\":"
                + McpJson.write(token) + ",\"jmeter_home\":" + home));
        Map<String, Object> result = toolResult(response.stdout);
        assertThat(result).containsEntry("isError", Boolean.FALSE);
        assertThat(structuredData(result)).isEqualTo(yamlData(result))
                .containsEntry("component", "org.apache.jmeter.protocol.http.sampler.AccessLogSampler");
        assertThat(token).hasSizeLessThanOrEqualTo(512)
                .doesNotContain("AccessLogSampler");
    }

    private static String call(int id, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\"components\",\"arguments\":{"
                + arguments + "}}}";
    }

    private static ServerResult runServer(String request) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = J4aMcpServer.run(
                new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true), new PrintStream(stderr, true), new String[0]);
        return new ServerResult(exitCode,
                new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    private static Map<String, Object> toolResult(String stdout) {
        Map<String, Object> message = mapping(new Yaml().load(stdout.trim()));
        return mapping(message.get("result"));
    }

    private static Map<String, Object> structuredData(Map<String, Object> result) {
        return mapping(mapping(result.get("structuredContent")).get("data"));
    }

    private static Map<String, Object> yamlData(Map<String, Object> result) {
        List<Object> content = list(result.get("content"));
        String text = String.valueOf(mapping(content.get(0)).get("text"));
        return mapping(new Yaml().load(text));
    }

    private static List<String> components(Map<String, Object> page) {
        List<String> result = new ArrayList<String>();
        for (Object entry : list(page.get("components"))) {
            result.add(String.valueOf(mapping(entry).get("component")));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) { return (List<Object>) value; }

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
