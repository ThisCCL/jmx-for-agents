package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerProcessTestProbe;
import org.junit.jupiter.api.Test;

class J4aMcpWarmupAndCacheTest {
    @Test
    void initializeResponseIsFlushedBeforeDefaultHomeWarmupIsScheduled() throws Exception {
        Path home = io.github.thisccl.j4a.TestJMeterRuntime.home();
        assertThat(home.resolve("bin/jmeter.properties")).isRegularFile();
        RecordingPrintStream stdout = new RecordingPrintStream();
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n";
        Map<String, String> environment = Collections.singletonMap(
                "JMX_AGENT_JMETER_HOME", home.toString());
        Set<Long> baseline = LocalJMeterWorkerProcessTestProbe.recordedProcessIds();
        PipedOutputStream requests = new PipedOutputStream();
        PipedInputStream input = new PipedInputStream(requests);
        McpStdioServer server = new McpStdioServer(input, stdout, environment);
        Thread serverThread = new Thread(() -> {
            try {
                server.run();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, "mcp-warmup-contract");

        serverThread.start();
        requests.write(initialize.getBytes(StandardCharsets.UTF_8));
        requests.flush();
        Set<Long> warmupWorkers = awaitNewWorker(baseline);
        requests.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\"}\n"
                .getBytes(StandardCharsets.UTF_8));
        requests.flush();
        serverThread.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(stdout.firstFlushText()).startsWith("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":");
        assertJsonLines(stdout.text());
        assertEventuallyDead(warmupWorkers);
    }

    private static Set<Long> awaitNewWorker(Set<Long> baseline) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Set<Long> current = LocalJMeterWorkerProcessTestProbe.recordedProcessIds();
        current.removeAll(baseline);
        while (current.isEmpty() && System.nanoTime() < deadline) {
            Thread.yield();
            current = LocalJMeterWorkerProcessTestProbe.recordedProcessIds();
            current.removeAll(baseline);
        }
        assertThat(current).isNotEmpty();
        return current;
    }

    private static void assertEventuallyDead(Set<Long> processIds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Set<Long> live = liveProcesses(processIds);
        while (!live.isEmpty() && System.nanoTime() < deadline) {
            Thread.yield();
            live = liveProcesses(processIds);
        }
        assertThat(live).isEmpty();
    }

    private static Set<Long> liveProcesses(Set<Long> processIds) {
        Set<Long> live = new java.util.LinkedHashSet<Long>();
        for (Long processId : processIds) {
            if (Files.exists(Paths.get("/proc", processId.toString()))) {
                live.add(processId);
            }
        }
        return live;
    }

    @Test
    void warmupFailureDoesNotPoisonLaterExplicitCall() throws Exception {
        Path invalid = Paths.get("build", "missing-warmup-home").toAbsolutePath();
        Path explicitHome = fakeJMeterHome();
        Path requestCount = explicitHome.resolve("requests.txt");
        Files.write(explicitHome.resolve("bin/user.properties"), java.util.Collections.singletonList(
                "j4a.fake.request.count=" + requestCount));
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n";
        String explicit = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"components\",\"arguments\":{\"category\":\"domain-failure\","
                + "\"jmeter_home\":\"" + explicitHome.toString().replace("\\", "\\\\") + "\"}}}\n";
        String shutdown = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\"}\n";
        RecordingPrintStream stdout = new RecordingPrintStream();

        new McpStdioServer(new ByteArrayInputStream((initialize + explicit + shutdown).getBytes(StandardCharsets.UTF_8)),
                stdout, Collections.singletonMap("JMX_AGENT_JMETER_HOME", invalid.toString())).run();

        assertThat(stdout.text()).contains("\"id\":1").contains("\"id\":2").contains("\"id\":3");
        assertThat(stdout.text()).contains("\"exitStatus\":3");
        assertThat(Files.readAllLines(requestCount, StandardCharsets.UTF_8)).hasSize(1);
        assertThat(stdout.text()).doesNotContain("Invalid local JMeter home");
    }

    @Test
    void defaultWarmupAndExplicitCallShareOneWorker() throws Exception {
        Path home = fakeJMeterHome();
        Path requestCount = home.resolve("requests.txt");
        Path workerPid = home.resolve("worker.pid");
        Files.write(home.resolve("bin/user.properties"), java.util.Arrays.asList(
                "j4a.fake.request.count=" + requestCount,
                "j4a.fake.worker.pid=" + workerPid));
        String escapedHome = home.toString().replace("\\", "\\\\");
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"components\","
                + "\"arguments\":{\"category\":\"domain-failure\",\"jmeter_home\":\"" + escapedHome + "\"}}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"shutdown\"}\n";
        RecordingPrintStream stdout = new RecordingPrintStream();

        new McpStdioServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), stdout,
                Collections.singletonMap("JMX_AGENT_JMETER_HOME", home.toString())).run();

        assertThat(Files.readAllLines(requestCount, StandardCharsets.UTF_8)).hasSizeBetween(1, 2);
        assertThat(stdout.text()).contains("\"exitStatus\":3");
        assertJsonLines(stdout.text());
        assertThat(workerPid).exists();
    }

    @Test
    void shutdownCancelsInFlightWarmupAndKillsWorker() throws Exception {
        Path home = fakeJMeterHome();
        Path entered = home.resolve("warmup-entered.txt");
        Path release = home.resolve("warmup-release.txt");
        Path workerPid = home.resolve("worker.pid");
        Files.write(home.resolve("bin/user.properties"), java.util.Arrays.asList(
                "j4a.fake.warmup.entered=" + entered,
                "j4a.fake.warmup.release=" + release,
                "j4a.fake.worker.pid=" + workerPid));
        RecordingPrintStream stdout = new RecordingPrintStream();
        PipedOutputStream requests = new PipedOutputStream();
        PipedInputStream input = new PipedInputStream(requests);
        McpStdioServer server = new McpStdioServer(input, stdout,
                Collections.singletonMap("JMX_AGENT_JMETER_HOME", home.toString()));
        Thread serverThread = new Thread(() -> {
            try {
                server.run();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, "mcp-warmup-shutdown-race");

        serverThread.start();
        requests.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                .getBytes(StandardCharsets.UTF_8));
        requests.flush();
        awaitFile(entered);
        awaitFile(workerPid);
        long pid = Long.parseLong(new String(Files.readAllBytes(workerPid), StandardCharsets.UTF_8).trim());
        requests.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\"}\n"
                .getBytes(StandardCharsets.UTF_8));
        requests.flush();
        serverThread.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(serverThread.isAlive()).isFalse();
        assertThat(release).doesNotExist();
        assertEventuallyDead(Collections.singleton(Long.valueOf(pid)));
        assertThat(stdout.text()).contains("\"id\":1").contains("\"id\":2");
    }

    private static void awaitFile(Path path) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(path).exists();
    }

    private static void assertJsonLines(String text) {
        List<String> lines = Arrays.asList(text.split("\\R"));
        assertThat(lines).allSatisfy(line -> assertThat(line).startsWith("{"));
    }

    private static Path fakeJMeterHome() throws Exception {
        Class<?> testSupport = Class.forName(
                "io.github.thisccl.j4a.validation.LocalJMeterSharedWorkersTest");
        java.lang.reflect.Method fakeHome = testSupport.getDeclaredMethod("fakeJMeterHome");
        fakeHome.setAccessible(true);
        return (Path) fakeHome.invoke(null);
    }

    private static final class RecordingPrintStream extends PrintStream {
        private final ByteArrayOutputStream bytes;
        private volatile String firstFlushText;

        private RecordingPrintStream() throws Exception {
            this(new ByteArrayOutputStream());
        }

        private RecordingPrintStream(ByteArrayOutputStream bytes) throws Exception {
            super(bytes, false, StandardCharsets.UTF_8.name());
            this.bytes = bytes;
        }

        @Override
        public void flush() {
            super.flush();
            if (firstFlushText == null && bytes.size() > 0) {
                firstFlushText = text();
            }
        }

        private String firstFlushText() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (firstFlushText == null && System.nanoTime() < deadline) {
                Thread.yield();
            }
            return firstFlushText;
        }

        private String text() {
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
