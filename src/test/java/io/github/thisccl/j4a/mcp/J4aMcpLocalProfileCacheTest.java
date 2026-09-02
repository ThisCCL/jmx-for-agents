package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class J4aMcpLocalProfileCacheTest {
    private String previousWorkerReuse;

    @AfterEach
    void restoreSystemProperties() {
        restore("j4a.worker.reuse", previousWorkerReuse);
    }

    @Test
    void mcpSessionReusesCompatibleLocalProfileWorker() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        previousWorkerReuse = System.getProperty("j4a.worker.reuse");
        System.clearProperty("j4a.worker.reuse");

        ServerResult result = runServer(
                call(1, "components", "\"jmeter_home\":"
                        + jsonString(fixtures.localHome().toString()) + ",\"category\":\"menu_generative_controller\""),
                call(2, "categories", "\"jmeter_home\":"
                        + jsonString(fixtures.localHome().toString())));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(2);
        assertToolSuccess(messages.get(0), "components");
        assertToolSuccess(messages.get(1), "categories");
    }

    @Test
    void differentHomesRemainIsolatedInOneMcpSession() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
        Path pluginHome = fixtures.localHome();
        Path plainHome = fixtures.localHomeWithoutPlugin();
        appendLanguage(pluginHome, "en");
        appendLanguage(plainHome, "zh_CN");

        try {
            ServerResult result = runServer(
                    call(1, "components", "\"jmeter_home\":"
                            + jsonString(pluginHome.toString()) + ",\"category\":\"sampler\""),
                    call(2, "categories", "\"jmeter_home\":" + jsonString(pluginHome.toString())),
                    call(3, "components", "\"jmeter_home\":"
                            + jsonString(plainHome.toString()) + ",\"category\":\"sampler\""),
                    call(4, "categories", "\"jmeter_home\":" + jsonString(plainHome.toString())));

            assertThat(result.exitCode()).isZero();
            assertThat(result.stderr()).isEmpty();
            List<Map<String, Object>> messages = parseJsonLines(result.stdout());
            assertThat(messages).hasSize(4);
            assertThat(toolText(messages.get(0)))
                    .contains("component: io.github.thisccl.j4a.synthetic.ExtOnlySamplerGui");
            assertThat(toolText(messages.get(2)))
                    .doesNotContain("component: io.github.thisccl.j4a.synthetic.ExtOnlySamplerGui");
            assertThat(toolText(messages.get(1))).contains("label: Sampler");
            assertThat(toolText(messages.get(3)))
                    .doesNotContain("label: Sampler")
                    .containsPattern("[\\u4e00-\\u9fff]");
            for (Map<String, Object> message : messages) {
                Map<String, Object> toolResult = mapping(message.get("result"));
                Object textData = new Yaml().load(toolText(message));
                assertThat(mapping(mapping(toolResult.get("structuredContent")).get("data")))
                        .isEqualTo(textData);
            }
        } finally {
            fixtures.delete();
        }
    }

    private static void appendLanguage(Path home, String language) throws IOException {
        Path properties = home.resolve("bin").resolve("jmeter.properties");
        Files.write(properties, ("\nlanguage=" + language + "\n").getBytes(StandardCharsets.ISO_8859_1),
                java.nio.file.StandardOpenOption.APPEND);
    }

    @Test
    void profileAffectingContentMutationInvalidatesMcpSessionWorkerCache() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Path localHome = fixtures.localHome();
        Path userProperties = localHome.resolve("bin").resolve("user.properties");
        FileTime stableTimestamp = FileTime.fromMillis(1234567890000L);
        Files.write(userProperties, "j4a.cache.key=alpha\n".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(userProperties, stableTimestamp);
        previousWorkerReuse = System.getProperty("j4a.worker.reuse");
        System.clearProperty("j4a.worker.reuse");

        ServerResult result = runServerWithMutation(
                call(1, "components", "\"jmeter_home\":"
                        + jsonString(localHome.toString()) + ",\"category\":\"menu_generative_controller\""),
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Files.write(userProperties, "j4a.cache.key=bravo\n".getBytes(StandardCharsets.UTF_8));
                            Files.setLastModifiedTime(userProperties, stableTimestamp);
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }
                },
                call(2, "categories", "\"jmeter_home\":"
                        + jsonString(localHome.toString())));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        List<Map<String, Object>> messages = parseJsonLines(result.stdout());
        assertThat(messages).hasSize(3);
        assertToolSuccess(messages.get(0), "components");
        assertToolSuccess(messages.get(1), "categories");
    }

    private static void assertToolSuccess(Map<String, Object> message, String command) {
        Map<String, Object> toolResult = mapping(message.get("result"));
        assertThat(toolResult).containsEntry("isError", Boolean.FALSE);
        Map<String, Object> structured = mapping(toolResult.get("structuredContent"));
        assertThat(mapping(structured.get("data"))).containsKey("categories");
        assertThat(mapping(structured.get("data"))).isEqualTo(new Yaml().load(toolText(message)));
    }

    private static String toolText(Map<String, Object> message) {
        Map<String, Object> toolResult = mapping(message.get("result"));
        return mapping(list(toolResult.get("content")).get(0)).get("text").toString();
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

    private static ServerResult runServerWithMutation(String firstCall, Runnable mutation, String secondCall)
            throws Exception {
        PipedInputStream stdin = new PipedInputStream();
        PipedOutputStream inputWriter = new PipedOutputStream(stdin);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<Integer> exitCode = new AtomicReference<Integer>();
        Thread server = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    exitCode.set(Integer.valueOf(J4aMcpServer.run(
                            stdin, new PrintStream(stdout, true), new PrintStream(stderr, true), new String[0])));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }
        }, "j4a-mcp-cache-invalidation-test");
        server.start();
        writeLine(inputWriter, firstCall);
        waitForFirstResponse(stdout);
        mutation.run();
        writeLine(inputWriter, secondCall);
        writeLine(inputWriter, "{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"shutdown\",\"params\":{}}");
        inputWriter.close();
        server.join(TimeUnit.SECONDS.toMillis(30));
        assertThat(server.isAlive()).as("MCP server thread should stop after shutdown").isFalse();
        if (failure.get() != null) {
            throw new AssertionError("MCP server failed", failure.get());
        }
        return new ServerResult(
                exitCode.get() == null ? -1 : exitCode.get().intValue(),
                new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    private static void writeLine(PipedOutputStream output, String line) throws IOException {
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static void waitForFirstResponse(ByteArrayOutputStream stdout) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (new String(stdout.toByteArray(), StandardCharsets.UTF_8).contains("\"id\":1")) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Timed out waiting for first MCP response.");
    }

    private static String jsonString(String value) {
        return McpJson.write(value);
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
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
