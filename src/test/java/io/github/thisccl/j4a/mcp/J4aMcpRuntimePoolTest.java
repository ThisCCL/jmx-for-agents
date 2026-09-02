package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.LocalJMeterWorkerClient;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResult;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerProcessTestProbe;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class J4aMcpRuntimePoolTest {
    @TempDir
    Path tempDir;

    @Test
    void poolIsMcpOwnedCloseableAndDoesNotExposeStaticRuntimeState() {
        assertThat(AutoCloseable.class).isAssignableFrom(J4aMcpRuntimePool.class);
        assertThat(J4aMcpRuntimePool.class.getDeclaredFields())
                .allSatisfy(field -> assertThat(Modifier.isStatic(field.getModifiers())).isFalse());
        assertThat(J4aMcpRuntimePool.class.getDeclaredMethods())
                .anySatisfy(method -> {
                    assertThat(method.getName()).isEqualTo("workerClient");
                    assertThat(method.getReturnType()).isEqualTo(LocalJMeterWorkerClient.class);
                });
    }

    @Test
    void poolIdentityDoesNotFingerprintFilesAndDoesNotSerializeDifferentHomesGlobally() throws Exception {
        Class<?> key = Class.forName(
                "io.github.thisccl.j4a.validation.LocalJMeterSharedWorkerKey");
        Class<?> pool = Class.forName(
                "io.github.thisccl.j4a.validation.LocalJMeterSharedWorkers");

        assertThat(key.getDeclaredFields())
                .noneSatisfy(field -> assertThat(field.getName()).isEqualTo("classpathFingerprint"));
        assertThat(Modifier.isSynchronized(pool.getDeclaredMethod(
                "execute", LocalJMeterWorkerClient.class, LocalJMeterWorkerRequest.class).getModifiers())).isFalse();
    }

    @Test
    void invalidHomeLeavesNoFuture() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"components\",\"arguments\":{}}}\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream stdout = new PrintStream(output, true, StandardCharsets.UTF_8.name());
        new McpStdioServer(new ByteArrayInputStream((request + request.replace("\"id\":1", "\"id\":2"))
                .getBytes(StandardCharsets.UTF_8)), stdout,
                Collections.singletonMap("JMX_AGENT_JMETER_HOME", tempDir.resolve("missing").toString())).run();

        String responses = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertThat(responses).contains("\"exitStatus\":4");
        assertThat(responses).doesNotContain("JMX_AGENT_LOCAL_WORKER_READY");
    }

    @Test
    void realPathAliasesReuseOneMcpRuntime() throws Exception {
        Path home = syntheticHome();
        Path alias = tempDir.resolve("jmeter-home-alias");
        Files.createSymbolicLink(alias, home);
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        try {
            LocalJMeterWorkerResult first = pool.execute(
                    LocalJMeterWorkerRequest.componentDetails(home, "first"));
            LocalJMeterWorkerResult second = pool.execute(
                    LocalJMeterWorkerRequest.componentDetails(alias, "second"));

            assertThat(first.response().payload()).contains("request-count: 1");
            assertThat(second.response().payload()).contains("request-count: 2");
            assertThat(workerPid(second)).isEqualTo(workerPid(first));
        } finally {
            pool.close();
        }
    }

    @Test
    void fatalInFlightCallIsNotReplayedAndLaterCallRebuilds() throws Exception {
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        Path home = syntheticHome();
        Path requestCount = home.resolve("requests.txt");
        Path workerPid = home.resolve("worker.pid");
        Path release = home.resolve("release");
        Files.write(home.resolve("bin").resolve("user.properties"),
                java.util.Arrays.asList(
                        "j4a.fake.request.count=" + requestCount,
                        "j4a.fake.worker.pid=" + workerPid,
                        "j4a.fake.release=" + release));
        try {
            AtomicReference<LocalJMeterWorkerResult> first = new AtomicReference<LocalJMeterWorkerResult>();
            AtomicReference<LocalJMeterWorkerResult> queued = new AtomicReference<LocalJMeterWorkerResult>();
            Thread firstCaller = caller(pool, home, "blocked-malformed", first);
            firstCaller.start();
            awaitFile(requestCount);
            Thread queuedCaller = caller(pool, home, "queued-after-fatal", queued);
            queuedCaller.start();
            awaitBlocked(queuedCaller);
            long fatalPid = Long.parseLong(new String(
                    Files.readAllBytes(workerPid), StandardCharsets.UTF_8).trim());
            Files.createFile(release);
            firstCaller.join(TimeUnit.SECONDS.toMillis(10));
            queuedCaller.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(firstCaller.isAlive()).isFalse();
            assertThat(queuedCaller.isAlive()).isFalse();
            assertThat(queued.get().response().success()).as(queued.get().response().toJsonLine()).isTrue();
            assertThat(queued.get().response().toJsonLine())
                    .contains("queued-after-fatal")
                    .doesNotContain("blocked-malformed");
            assertThat(queued.get().response().payload()).contains("request-count: 1");
            assertThat(Files.readAllLines(requestCount, StandardCharsets.UTF_8)).hasSize(2);
            long queuedPid = Long.parseLong(new String(
                    Files.readAllBytes(workerPid), StandardCharsets.UTF_8).trim());
            LocalJMeterWorkerResult replacement = pool.execute(
                    LocalJMeterWorkerRequest.componentDetails(home, "after-fatal"));
            long replacementPid = Long.parseLong(
                    new String(Files.readAllBytes(workerPid), StandardCharsets.UTF_8).trim());

            assertThat(first.get().response().success()).isFalse();
            assertThat(first.get().response().message()).contains("malformed");
            assertThat(replacement.response().success()).isTrue();
            assertThat(replacement.response().payload()).contains("request-count: 2");
            assertThat(Files.readAllLines(requestCount, StandardCharsets.UTF_8)).hasSize(3);
            assertThat(replacementPid).isNotEqualTo(fatalPid);
            assertThat(replacementPid).isEqualTo(queuedPid);
            System.out.println("CONCURRENT_FATAL callers=2 totalRequests=3 fatalPid=" + fatalPid
                    + " replacementPid=" + replacementPid + " queuedRerouted=true");
        } finally {
            pool.close();
        }
    }

    @Test
    void differentRealHomesUseDistinctProcessesAndClassesConcurrently() throws Exception {
        Path withPlugin = tempDir.resolve("jmeter-home-with-plugin");
        Path withoutPlugin = tempDir.resolve("jmeter-home-without-plugin");
        createReal563ShapeHome(withPlugin, true);
        createReal563ShapeHome(withoutPlugin, false);
        Path jmx = java.nio.file.Paths.get("src/test/resources/fixtures/simple-http.jmx").toAbsolutePath();
        Set<Long> baselinePids = LocalJMeterWorkerProcessTestProbe.recordedProcessIds();
        Set<Long> ownedTree = new LinkedHashSet<Long>();
        J4aMcpRuntimePool pool = new J4aMcpRuntimePool();
        try {
            AtomicReference<LocalJMeterWorkerResult> withPluginResult =
                    new AtomicReference<LocalJMeterWorkerResult>();
            AtomicReference<LocalJMeterWorkerResult> withoutPluginResult =
                    new AtomicReference<LocalJMeterWorkerResult>();
            Thread withPluginCaller = request(
                    pool, LocalJMeterWorkerRequest.validate(jmx, withPlugin), withPluginResult);
            Thread withoutPluginCaller = request(
                    pool, LocalJMeterWorkerRequest.validate(jmx, withoutPlugin), withoutPluginResult);
            withPluginCaller.start();
            withoutPluginCaller.start();
            withPluginCaller.join(TimeUnit.SECONDS.toMillis(60));
            withoutPluginCaller.join(TimeUnit.SECONDS.toMillis(60));

            assertThat(withPluginCaller.isAlive()).isFalse();
            assertThat(withoutPluginCaller.isAlive()).isFalse();
            assertThat(withPluginResult.get().response().success()).isTrue();
            assertThat(withoutPluginResult.get().response().success()).isTrue();
            Set<Long> processIds = LocalJMeterWorkerProcessTestProbe.recordedProcessIds();
            processIds.removeAll(baselinePids);
            assertThat(processIds).hasSize(2);
            for (Long processId : processIds) {
                collectOwnedTree(processId.longValue(), ownedTree);
            }

            LocalJMeterWorkerResult withPluginClasses = pool.execute(
                    LocalJMeterWorkerRequest.discoverComponents(withPlugin));
            LocalJMeterWorkerResult withoutPluginClasses = pool.execute(
                    LocalJMeterWorkerRequest.discoverComponents(withoutPlugin));
            String pluginClass = io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures.EXT_PLUGIN_CLASS;
            assertThat(withPluginClasses.response().success()).isTrue();
            assertThat(withPluginClasses.response().payload()).contains(pluginClass);
            assertThat(withoutPluginClasses.response().success()).isTrue();
            assertThat(withoutPluginClasses.response().payload()).doesNotContain(pluginClass);
            System.out.println("DISTINCT_HOMES pids=" + processIds
                    + " withPlugin=" + pluginClass + " withoutPlugin=standard-only");
        } finally {
            pool.close();
        }
        assertEventuallyDead(ownedTree);
    }

    private static Thread request(
            final J4aMcpRuntimePool pool,
            final LocalJMeterWorkerRequest request,
            final AtomicReference<LocalJMeterWorkerResult> result) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                result.set(pool.execute(request));
            }
        }, "mcp-distinct-home-runtime");
    }

    private static void collectOwnedTree(long pid, Set<Long> tree) throws Exception {
        if (!tree.add(Long.valueOf(pid))) return;
        Path children = java.nio.file.Paths.get("/proc", Long.toString(pid), "task", Long.toString(pid), "children");
        if (!Files.isRegularFile(children)) return;
        String text = new String(Files.readAllBytes(children), StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return;
        for (String child : text.split("\\s+")) {
            collectOwnedTree(Long.parseLong(child), tree);
        }
    }

    private static void assertEventuallyDead(Set<Long> ownedTree) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Set<Long> live = liveOwnedProcesses(ownedTree);
        while (!live.isEmpty() && System.nanoTime() < deadline) {
            Thread.yield();
            live = liveOwnedProcesses(ownedTree);
        }
        assertThat(live).isEmpty();
    }

    private static Set<Long> liveOwnedProcesses(Set<Long> ownedTree) {
        Set<Long> live = new LinkedHashSet<Long>();
        for (Long pid : ownedTree) {
            if (Files.exists(java.nio.file.Paths.get("/proc", pid.toString()))) {
                live.add(pid);
            }
        }
        return live;
    }

    private static void createReal563ShapeHome(Path home, boolean includePlugins) throws Exception {
        Class<?> fixtures = Class.forName(
                "io.github.thisccl.j4a.validation.DefaultLocalProfileHomeFixtures");
        java.lang.reflect.Method createHome = fixtures.getDeclaredMethod("createHome", Path.class, boolean.class);
        createHome.setAccessible(true);
        createHome.invoke(null, home, Boolean.valueOf(includePlugins));
    }

    private static Thread caller(
            final J4aMcpRuntimePool pool,
            final Path home,
            final String component,
            final AtomicReference<LocalJMeterWorkerResult> result) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                result.set(pool.execute(LocalJMeterWorkerRequest.componentDetails(home, component)));
            }
        }, "mcp-runtime-pool-" + component);
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(path).exists();
    }

    private static void awaitBlocked(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
    }

    private static Path syntheticHome() throws Exception {
        Class<?> testSupport = Class.forName(
                "io.github.thisccl.j4a.validation.LocalJMeterSharedWorkersTest");
        java.lang.reflect.Method fakeHome = testSupport.getDeclaredMethod("fakeJMeterHome");
        fakeHome.setAccessible(true);
        return (Path) fakeHome.invoke(null);
    }

    private static long workerPid(LocalJMeterWorkerResult result) {
        String payload = result.response().payload();
        int start = payload.indexOf("worker-pid: ");
        return Long.parseLong(payload.substring(start + "worker-pid: ".length()).trim());
    }
}
