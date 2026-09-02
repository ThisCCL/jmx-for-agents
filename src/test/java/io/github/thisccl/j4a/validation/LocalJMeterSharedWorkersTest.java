package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LocalJMeterSharedWorkersTest {
    private static final Duration TRANSPORT_STARTUP_TIMEOUT = LocalJMeterWorkerClient.DEFAULT_STARTUP_TIMEOUT;
    private static final long OBSERVATION_POLL_MILLIS = 25L;

    @Test
    void boundedStdoutDiagnosticReportsQueueEvictionBelowByteLimit() throws Exception {
        LocalJMeterSharedWorkers.BoundedLineQueue queue = new LocalJMeterSharedWorkers.BoundedLineQueue(2);
        LocalJMeterSharedWorkers.BoundedLineQueue.Generation generation = queue.beginGeneration();
        queue.offer("one");
        queue.offer("two");
        queue.offer("three");
        LocalJMeterSharedWorkers.BoundedText text = new LocalJMeterSharedWorkers.BoundedText(64 * 1024);
        text.appendLine("three");

        String rendered = text.text(generation.truncation());
        assertThat(rendered).contains("stdout truncated; dropped 1 lines, 4 bytes");
        assertThat(utf8Length(rendered)).isLessThanOrEqualTo(64 * 1024);
    }

    @Test
    void boundedStdoutDiagnosticReportsByteTruncationWithoutQueueEviction() throws Exception {
        LocalJMeterSharedWorkers.BoundedText text = new LocalJMeterSharedWorkers.BoundedText(64 * 1024);
        text.appendLine(noisyLine(64 * 1024));

        String rendered = text.text(LocalJMeterSharedWorkers.Truncation.none());
        assertThat(rendered).contains("stdout truncated; dropped 1 lines, 65537 bytes");
        assertThat(utf8Length(rendered)).isLessThanOrEqualTo(64 * 1024);
    }

    @Test
    void boundedStdoutDiagnosticReportsQueueAndByteTruncationTogether() throws Exception {
        LocalJMeterSharedWorkers.BoundedLineQueue queue = new LocalJMeterSharedWorkers.BoundedLineQueue(1);
        LocalJMeterSharedWorkers.BoundedLineQueue.Generation generation = queue.beginGeneration();
        queue.offer("old");
        queue.offer("new");
        LocalJMeterSharedWorkers.BoundedText text = new LocalJMeterSharedWorkers.BoundedText(64 * 1024);
        text.appendLine(noisyLine(64 * 1024));

        String rendered = text.text(generation.truncation());
        assertThat(rendered).contains("stdout truncated; dropped 2 lines, 65541 bytes");
        assertThat(utf8Length(rendered)).isLessThanOrEqualTo(64 * 1024);
    }

    @Test
    void boundedStdoutDiagnosticWithoutOverflowDoesNotReportTruncation() throws Exception {
        LocalJMeterSharedWorkers.BoundedText text = new LocalJMeterSharedWorkers.BoundedText(64 * 1024);
        text.appendLine("current response");

        assertThat(text.text(LocalJMeterSharedWorkers.Truncation.none())).doesNotContain("truncated");
    }

    @Test
    void boundedStderrDiagnosticReportsByteTruncation() throws Exception {
        byte[] noisyStderr = new byte[64 * 1024 + 1];
        java.util.Arrays.fill(noisyStderr, (byte) 'x');
        LocalJMeterWorkerStreamCollector collector = LocalJMeterWorkerStreamCollector.start(
                new ByteArrayInputStream(noisyStderr));
        String rendered = collector.text();
        assertThat(rendered).contains("stream truncated; dropped 1 lines, 65537 bytes");
        assertThat(utf8Length(rendered)).isLessThanOrEqualTo(64 * 1024);
    }

    @Test
    void boundedStderrReaderReleasesOversizedNewlineFreeLineBodyWhileCountingEveryByte() throws Exception {
        int wireBytes = 16 * 1024 * 1024;
        LocalJMeterWorkerStreamCollector.BoundedLineReader reader =
                new LocalJMeterWorkerStreamCollector.BoundedLineReader(
                        new RepeatingInputStream((byte) 'x', wireBytes), 64 * 1024);

        LocalJMeterWorkerStreamCollector.Line line = reader.readLine();

        assertThat(line.droppedBytes()).isEqualTo(wireBytes);
        assertThat(line.retainedByteCount()).isZero();
        assertThat(reader.readLine()).isNull();
    }

    @Test
    void boundedStdoutReaderDropsOneOversizedPhysicalLineAndPreservesFollowingProtocolFrame() throws Exception {
        int noisyBytes = 64 * 1024 + 1;
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (int index = 0; index < noisyBytes; index++) {
            input.write('x');
        }
        input.write('\n');
        input.write("{\"jsonrpc\":\"still-framed\"}\n".getBytes(StandardCharsets.UTF_8));
        LocalJMeterSharedWorkers.BoundedLineQueue queue = new LocalJMeterSharedWorkers.BoundedLineQueue(2);
        LocalJMeterSharedWorkers.BoundedLineQueue.Generation generation = queue.beginGeneration();

        LocalJMeterSharedWorkers.readStdoutLines(new ByteArrayInputStream(input.toByteArray()), queue);

        assertThat(queue.poll()).isEqualTo("{\"jsonrpc\":\"still-framed\"}");
        assertThat(generation.truncation().marker("stdout"))
                .contains("dropped 1 lines, " + (noisyBytes + 1) + " bytes");
    }

    @Test
    void boundedDiagnosticGenerationDoesNotLeakEarlierQueueEviction() throws Exception {
        LocalJMeterSharedWorkers.BoundedLineQueue queue = new LocalJMeterSharedWorkers.BoundedLineQueue(1);
        queue.beginGeneration();
        queue.offer("previous");
        queue.offer("evicted");
        LocalJMeterSharedWorkers.BoundedLineQueue.Generation current = queue.beginGeneration();
        queue.offer("current");
        LocalJMeterSharedWorkers.BoundedText text = new LocalJMeterSharedWorkers.BoundedText(64 * 1024);
        text.appendLine("current");

        assertThat(text.text(current.truncation())).doesNotContain("truncated", "previous", "evicted");
    }

    @Test
    void sharedWorkerAcceptsReadyMarkerQueuedAtStartupDeadline() throws Exception {
        BlockingQueue<String> lines = new LinkedBlockingQueue<String>();
        lines.add("startup log line");
        lines.add(LocalJMeterWorkerClient.READY_MARKER);

        Class<?> worker = Class.forName("io.github.thisccl.j4a.validation.LocalJMeterSharedWorkers$Worker");
        java.lang.reflect.Method readyMarkerQueued = worker.getDeclaredMethod("readyMarkerQueued", BlockingQueue.class);
        readyMarkerQueued.setAccessible(true);

        assertThat(readyMarkerQueued.invoke(null, lines)).isEqualTo(Boolean.TRUE);
        assertThat(lines).isEmpty();
    }

    private String previousWorkerReuse;
    private LocalJMeterWorkerClient sharedWorkerClient;

    @AfterEach
    void restoreWorkerReuse() {
        if (sharedWorkerClient != null) {
            sharedWorkerClient.close();
        }
        if (previousWorkerReuse == null) {
            System.clearProperty("j4a.worker.reuse");
        } else {
            System.setProperty("j4a.worker.reuse", previousWorkerReuse);
        }
    }

    @Test
    void fixtureFailureAssertionsNeverTerminateAStaleNumericPidTree() {
        assertThat(Arrays.stream(LocalJMeterSharedWorkersTest.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("killPid");
    }

    @Test
    void sharedWorkerReusesCompatibleProtocolProcess() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult first = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "first"));
        LocalJMeterWorkerResult second = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "second"));

        assertThat(first.response().success()).as(first.response().toJsonLine()).isTrue();
        assertThat(second.response().success()).as(second.response().toJsonLine()).isTrue();
        assertThat(first.response().payload()).contains("request-count: 1");
        assertThat(second.response().payload()).contains("request-count: 2");
        assertThat(first.workerExited()).isFalse();
        assertThat(first.exitCode()).isEqualTo(-1);
        assertThat(second.workerExited()).isFalse();
        assertThat(second.exitCode()).isEqualTo(-1);
        assertThat(workerPid(first)).isEqualTo(workerPid(second));
    }

    @Test
    void sharedWorkerReportsObservedNaturalExitCode() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult result = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "exit-seven"));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(result.workerExited()).isTrue();
        assertThat(result.exitCode()).isEqualTo(7);
    }

    @Test
    void naturallyExitedSharedWorkerKillsItsStillRunningDescendantBeforeReturn() throws Exception {
        Path childPidFile = Files.createTempDirectory("j4a-natural-exit-child-").resolve("child.pid");
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.child.pid=" + propertyPath(childPidFile) + "\n").getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult result = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "exit-seven"));
        long childPid = awaitPublishedPid(childPidFile);

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(result.workerExited()).isTrue();
        assertThat(result.exitCode()).isEqualTo(7);
        assertEventuallyDead(childPid);
    }

    @Test
    void fatalInFlightCallIsNotReplayedAndLaterCallRebuilds() throws Exception {
        Path directory = Files.createTempDirectory("j4a-shared-worker-timeout-");
        Path workerPidFile = directory.resolve("worker.pid");
        Path childPidFile = directory.resolve("child.pid");
        Path pidReadyFile = directory.resolve("pid-ready");
        LocalJMeterWorkerClient client = sharedClient(Duration.ofSeconds(5), Duration.ofMillis(500));
        Path home = fakeJMeterHome();
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.worker.pid=" + propertyPath(workerPidFile) + "\n"
                        + "j4a.fake.child.pid=" + propertyPath(childPidFile) + "\n"
                        + "j4a.fake.pid.ready=" + propertyPath(pidReadyFile) + "\n")
                        .getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult timedOut = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "hang-configure"));

        assertThat(timedOut.response().success()).isFalse();
        assertThat(timedOut.response().message()).contains("timed out");
        assertThat(timedOut.workerExited()).isTrue();
        assertThat(timedOut.exitCode()).isNotEqualTo(0);
        assertThat(pidReadyFile).isRegularFile();
        long oldWorkerPid = readPublishedPid(workerPidFile);
        long oldChildPid = readPublishedPid(childPidFile);
        assertEventuallyDead(oldWorkerPid);
        assertEventuallyDead(oldChildPid);

        Files.delete(workerPidFile);
        Files.delete(childPidFile);
        LocalJMeterWorkerResult replacement = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-timeout"));

        assertThat(replacement.response().success()).as(replacement.response().toJsonLine()).isTrue();
        assertThat(replacement.workerExited()).isFalse();
        assertThat(replacement.exitCode()).isEqualTo(-1);
        assertThat(workerPid(replacement)).isNotEqualTo(oldWorkerPid);
    }

    @Test
    void sharedPreflightTimeoutEvictsWithoutDispatchAndLaterCallUsesFreshGeneration() throws Exception {
        Path directory = Files.createTempDirectory("j4a-shared-worker-preflight-timeout-");
        Path requestCount = directory.resolve("request-count");
        LocalJMeterWorkerClient client = sharedClient(Duration.ofMillis(250), Duration.ofSeconds(5));
        Path home = fakeJMeterHome();
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.request.count=" + propertyPath(requestCount) + "\n")
                        .getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult timedOut = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "hang-preflight"));

        assertThat(timedOut.response().success()).isFalse();
        assertThat(timedOut.response().message()).contains("runtime preflight timed out");
        assertThat(timedOut.workerExited()).isTrue();
        assertThat(requestCount).doesNotExist();

        LocalJMeterWorkerResult replacement = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-preflight-timeout"));

        assertThat(replacement.response().success()).as(replacement.response().toJsonLine()).isTrue();
        assertThat(replacement.response().payload()).contains("request-count: 1");
        assertThat(Files.readAllLines(requestCount, StandardCharsets.UTF_8)).hasSize(1);
    }

    @Test
    void sharedPreflightMayUseItsOwnBudgetBeforeShortOperationBudget() throws Exception {
        LocalJMeterWorkerClient client = sharedClient(Duration.ofSeconds(2), Duration.ofSeconds(1));
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult result = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "slow-preflight"));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(result.workerExited()).isFalse();
    }

    @Test
    void queuedPreWriteRequestReroutesAfterSelectedGenerationFails() throws Exception {
        for (int iteration = 0; iteration < 5; iteration++) {
            assertQueuedPreWriteRequestReroutes(iteration);
        }
    }

    private static void assertQueuedPreWriteRequestReroutes(int iteration) throws Exception {
        Path directory = Files.createTempDirectory("j4a-generation-race-");
        Path releaseFatal = directory.resolve("release-fatal");
        Path requestCount = directory.resolve("request-count");
        Path home = fakeJMeterHome();
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.release=" + propertyPath(releaseFatal) + "\n"
                        + "j4a.fake.request.count=" + propertyPath(requestCount) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        CountDownLatch secondSelected = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicBoolean firstCleanSelection = new AtomicBoolean(true);
        LocalJMeterSharedWorkers workers = new LocalJMeterSharedWorkers(
                (process, workerId) -> LocalJMeterWorkerProcess.terminateProcessTreeUninterruptibly(process, workerId),
                request -> {
                    if (request.component() != null && request.component().startsWith("clean-after-fatal-")
                            && firstCleanSelection.compareAndSet(true, false)) {
                        secondSelected.countDown();
                        awaitLatch(releaseSecond);
                    }
                });
        LocalJMeterWorkerClient client = new LocalJMeterWorkerClient();
        AtomicReference<LocalJMeterWorkerResult> fatalResult = new AtomicReference<LocalJMeterWorkerResult>();
        AtomicReference<LocalJMeterWorkerResult> cleanResult = new AtomicReference<LocalJMeterWorkerResult>();
        Thread fatal = new Thread(() -> fatalResult.set(workers.execute(client,
                LocalJMeterWorkerRequest.componentDetails(home, "blocked-malformed-fatal-" + iteration))),
                "fatal-generation-" + iteration);
        Thread clean = new Thread(() -> cleanResult.set(workers.execute(client,
                LocalJMeterWorkerRequest.componentDetails(home, "clean-after-fatal-" + iteration))),
                "queued-generation-" + iteration);
        try {
            fatal.start();
            awaitRequestCount(requestCount, 1);
            clean.start();
            assertThat(secondSelected.await(5, TimeUnit.SECONDS)).isTrue();
            Files.createFile(releaseFatal);
            fatal.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(fatal.isAlive()).isFalse();
            assertThat(fatalResult.get().response().disposition())
                    .isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
            assertThat(Files.readAllLines(requestCount, StandardCharsets.UTF_8)).hasSize(1);
            releaseSecond.countDown();
            clean.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(clean.isAlive()).isFalse();
            assertThat(cleanResult.get().response().success())
                    .as(cleanResult.get().response().toJsonLine()).isTrue();
            assertThat(cleanResult.get().response().toJsonLine())
                    .contains("clean-after-fatal-" + iteration)
                    .doesNotContain("blocked-malformed-fatal-" + iteration);
            assertThat(cleanResult.get().response().payload()).contains("request-count: 1");
            assertThat(Files.readAllLines(requestCount, StandardCharsets.UTF_8)).hasSize(2);
        } finally {
            releaseSecond.countDown();
            workers.close();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for deterministic generation-race latch.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for deterministic generation-race latch.", exception);
        }
    }

    private static void awaitRequestCount(Path requestCount, int expected) throws Exception {
        awaitObservable("request count " + expected + " in " + requestCount, new Observable<Boolean>() {
            @Override
            public Boolean observe() throws IOException {
                if (Files.isRegularFile(requestCount)
                        && Files.readAllLines(requestCount, StandardCharsets.UTF_8).size() >= expected) {
                    return Boolean.TRUE;
                }
                return null;
            }
        });
    }

    @Test
    void realPathAliasesReuseOneWorker() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();
        Path alias = home.getParent().resolve(home.getFileName() + "-alias");
        Files.createSymbolicLink(alias, home);

        LocalJMeterWorkerResult first = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "first"));
        LocalJMeterWorkerResult second = client.execute(LocalJMeterWorkerRequest.componentDetails(alias, "second"));

        assertThat(first.response().payload()).contains("request-count: 1");
        assertThat(second.response().payload()).contains("request-count: 2");
        assertThat(workerPid(second)).isEqualTo(workerPid(first));
        Files.deleteIfExists(alias);
    }

    @Test
    void sameHomeSerializesWhileDifferentHomesRunConcurrently() throws Exception {
        AtomicReference<CountDownLatch> selected = new AtomicReference<CountDownLatch>();
        LocalJMeterSharedWorkers workers = new LocalJMeterSharedWorkers(
                (process, workerId) -> LocalJMeterWorkerProcess.terminateProcessTreeUninterruptibly(process, workerId),
                request -> {
                    CountDownLatch latch = selected.get();
                    if (latch != null && request.component() != null
                            && request.component().startsWith("coordinated-configure")) {
                        latch.countDown();
                    }
                });
        LocalJMeterWorkerClient client = new LocalJMeterWorkerClient();
        try {
            Path firstHome = fakeJMeterHome();
            Path secondHome = fakeJMeterHome();
            Path differentRelease = sharedRelease(firstHome, secondHome);
            selected.set(new CountDownLatch(2));
            ConcurrentExecution different = startConcurrent(workers, client, firstHome, secondHome);
            awaitLatch(selected.get());
            awaitEntryCount(differentRelease.getParent().resolve("entered"), 2);
            Files.createFile(differentRelease);
            different.await();

            Path sameHome = fakeJMeterHome();
            Path sameRelease = sharedRelease(sameHome);
            selected.set(new CountDownLatch(2));
            ConcurrentExecution same = startConcurrent(workers, client, sameHome, sameHome);
            awaitLatch(selected.get());
            awaitEntryCount(sameRelease.getParent().resolve("entered"), 1);
            assertThat(entryCount(sameRelease.getParent().resolve("entered"))).isEqualTo(1);
            Files.createFile(sameRelease);
            same.await();
            awaitEntryCount(sameRelease.getParent().resolve("entered"), 2);
        } finally {
            workers.close();
        }
    }

    private static ConcurrentExecution startConcurrent(
            final LocalJMeterSharedWorkers workers,
            final LocalJMeterWorkerClient client,
            final Path firstHome,
            final Path secondHome) {
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Runnable first = coordinatedRequest(workers, client, firstHome, "coordinated-configure-first", failure);
        Runnable second = coordinatedRequest(workers, client, secondHome, "coordinated-configure-second", failure);
        Thread firstThread = new Thread(first, "runtime-pool-first");
        Thread secondThread = new Thread(second, "runtime-pool-second");
        firstThread.start();
        secondThread.start();
        return new ConcurrentExecution(firstThread, secondThread, failure);
    }

    private static Runnable coordinatedRequest(
            final LocalJMeterSharedWorkers workers,
            final LocalJMeterWorkerClient client,
            final Path home,
            final String component,
            final AtomicReference<Throwable> failure) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    LocalJMeterWorkerResult result = workers.execute(
                            client, LocalJMeterWorkerRequest.componentDetails(home, component));
                    if (!result.response().success()) {
                        throw new AssertionError(result.response().toJsonLine());
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            }
        };
    }

    private static Path sharedRelease(Path... homes) throws IOException {
        Path coordination = Files.createTempDirectory("j4a-shared-worker-coordination-");
        for (Path home : homes) {
            Files.write(home.resolve("bin").resolve("user.properties"),
                    ("j4a.fake.coordination.root=" + propertyPath(coordination) + "\n"
                            + "j4a.fake.coordination.release=" + propertyPath(coordination.resolve("release")) + "\n")
                            .getBytes(StandardCharsets.UTF_8));
        }
        return coordination.resolve("release");
    }

    private static void awaitEntryCount(Path entered, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (entryCount(entered) < expected && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(OBSERVATION_POLL_MILLIS);
        }
        assertThat(entryCount(entered)).isEqualTo(expected);
    }

    private static long entryCount(Path entered) throws IOException {
        if (!Files.isDirectory(entered)) {
            return 0L;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(entered)) {
            return entries.count();
        }
    }

    private static final class ConcurrentExecution {
        private final Thread first;
        private final Thread second;
        private final AtomicReference<Throwable> failure;

        private ConcurrentExecution(Thread first, Thread second, AtomicReference<Throwable> failure) {
            this.first = first;
            this.second = second;
            this.failure = failure;
        }

        private void await() throws InterruptedException {
            first.join(TimeUnit.SECONDS.toMillis(30));
            second.join(TimeUnit.SECONDS.toMillis(30));
            assertThat(first.isAlive()).isFalse();
            assertThat(second.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void interruptedSharedWorkerRestoresInterruptAndDoesNotReuseClosedProcess() throws Exception {
        Path directory = Files.createTempDirectory("j4a-shared-worker-interrupt-");
        Path workerPidFile = directory.resolve("worker.pid");
        Path childPidFile = directory.resolve("child.pid");
        final LocalJMeterWorkerClient client = sharedClient(Duration.ofSeconds(5), Duration.ofSeconds(10));
        final Path home = fakeJMeterHome();
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.worker.pid=" + propertyPath(workerPidFile) + "\n"
                        + "j4a.fake.child.pid=" + propertyPath(childPidFile) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean interruptRestored = new AtomicBoolean();
        final LocalJMeterWorkerResult[] result = new LocalJMeterWorkerResult[1];
        Thread caller = new Thread(new Runnable() {
            @Override
            public void run() {
                started.countDown();
                result[0] = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "hang-configure"));
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "shared-worker-interrupt-test");

        caller.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        long oldWorkerPid = awaitPublishedPid(workerPidFile);
        long oldChildPid = awaitPublishedPid(childPidFile);
        caller.interrupt();
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(caller.isAlive()).isFalse();
        assertThat(interruptRestored.get()).isTrue();
        assertThat(result[0].response().message()).contains("Interrupted");
        assertThat(result[0].workerExited()).isTrue();
        assertThat(result[0].exitCode()).isEqualTo(-1);
        assertEventuallyDead(oldWorkerPid);
        assertEventuallyDead(oldChildPid);

        Files.delete(workerPidFile);
        Files.delete(childPidFile);
        LocalJMeterWorkerResult replacement = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-interrupt"));
        assertThat(replacement.response().success()).as(replacement.response().toJsonLine()).isTrue();
        assertThat(workerPid(replacement)).isNotEqualTo(oldWorkerPid);
    }

    @Test
    void sharedWorkerMalformedResponseDiagnosticsDoNotRetainPriorResponses() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult first = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "first"));
        LocalJMeterWorkerResult malformed = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "malformed"));

        assertThat(first.response().success()).as(first.response().toJsonLine()).isTrue();
        assertThat(first.response().stdout()).contains("FIRST_RESPONSE_STDOUT");
        assertThat(malformed.response().success()).isFalse();
        assertThat(malformed.response().stdout()).contains("{malformed-protocol-response");
        assertThat(malformed.response().stdout()).doesNotContain(
                "FIRST_RESPONSE_STDOUT", "FIRST_RESPONSE_TRAILING_STDOUT", "request-count: 1");
    }

    @Test
    void sharedWorkerMalformedResponseDiagnosticsAreBoundedToCurrentResponse() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "noisy-malformed"));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().stdout()).contains("stdout truncated");
        assertThat(result.response().stdout()).contains("{malformed-protocol-response");
        assertThat(utf8Length(result.response().stdout())).isLessThanOrEqualTo(64 * 1024);
    }

    @Test
    void latePriorResponseOutputBeforeNextPreflightDoesNotContaminateMalformedDiagnostics() throws Exception {
        Path directory = Files.createTempDirectory("j4a-late-output-");
        Path release = directory.resolve("release");
        Path written = directory.resolve("written");
        Path nextPreflightEntered = directory.resolve("next-preflight-entered");
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.late.release=" + propertyPath(release) + "\n"
                        + "j4a.fake.late.written=" + propertyPath(written) + "\n"
                        + "j4a.fake.late.next.entered=" + propertyPath(nextPreflightEntered) + "\n")
                        .getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult first = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "late-output-first"));
        AtomicReference<LocalJMeterWorkerResult> second = new AtomicReference<LocalJMeterWorkerResult>();
        Thread next = new Thread(new Runnable() {
            @Override
            public void run() {
                second.set(client.execute(LocalJMeterWorkerRequest.componentDetails(home, "late-next-malformed")));
            }
        }, "late-old-output-next-request");

        next.start();
        awaitFile(nextPreflightEntered);
        Files.write(release, new byte[0]);
        next.join(TRANSPORT_STARTUP_TIMEOUT.toMillis());

        assertThat(first.response().success()).as(first.response().toJsonLine()).isTrue();
        assertThat(next.isAlive()).isFalse();
        assertThat(written).isRegularFile();
        assertThat(second.get().response().success()).isFalse();
        assertThat(second.get().response().stdout()).contains("{malformed-protocol-response")
                .doesNotContain("LATE_OLD_STDOUT_", "stdout truncated");
    }

    @Test
    void latePriorStderrBeforeNextPreflightDoesNotContaminateMalformedDiagnostics() throws Exception {
        Path directory = Files.createTempDirectory("j4a-late-stderr-");
        Path release = directory.resolve("release");
        Path written = directory.resolve("written");
        Path nextPreflightEntered = directory.resolve("next-preflight-entered");
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.late.stderr.release=" + propertyPath(release) + "\n"
                        + "j4a.fake.late.stderr.written=" + propertyPath(written) + "\n"
                        + "j4a.fake.late.stderr.next.entered=" + propertyPath(nextPreflightEntered) + "\n")
                        .getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult first = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "late-stderr-first"));
        AtomicReference<LocalJMeterWorkerResult> second = new AtomicReference<LocalJMeterWorkerResult>();
        Thread next = new Thread(new Runnable() {
            @Override
            public void run() {
                second.set(client.execute(LocalJMeterWorkerRequest.componentDetails(home, "late-stderr-next-malformed")));
            }
        }, "late-old-stderr-next-request");

        next.start();
        awaitFile(nextPreflightEntered);
        Files.write(release, new byte[0]);
        next.join(TRANSPORT_STARTUP_TIMEOUT.toMillis());

        assertThat(first.response().success()).as(first.response().toJsonLine()).isTrue();
        assertThat(next.isAlive()).isFalse();
        assertThat(written).isRegularFile();
        assertThat(second.get().response().success()).isFalse();
        assertThat(second.get().response().stdout()).contains("{malformed-protocol-response");
        assertThat(second.get().response().stderr()).doesNotContain("LATE_OLD_STDERR_", "stderr truncated");
    }

    @Test
    void foreignJsonResponseIsFatalEvictsWorkerAndDoesNotContaminateNextRequest() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult foreign = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "foreign-response"));
        LocalJMeterWorkerResult clean = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-foreign"));

        assertThat(foreign.response().success()).isFalse();
        assertThat(foreign.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(foreign.response().message()).contains("protocol", "requestId");
        assertThat(foreign.workerExited()).isTrue();
        assertThat(clean.response().success()).as(clean.response().toJsonLine()).isTrue();
        assertThat(clean.response().payload()).contains("request-count: 1");
    }

    @Test
    void contradictorySuccessDispositionIsFatalEvictsWorkerAndIsNotReplayed() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult contradictory = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "contradictory-response"));
        LocalJMeterWorkerResult clean = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-contradictory"));

        assertThat(contradictory.response().success()).isFalse();
        assertThat(contradictory.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(contradictory.response().message()).contains("protocol", "success/disposition");
        assertThat(contradictory.workerExited()).isTrue();
        assertThat(clean.response().success()).as(clean.response().toJsonLine()).isTrue();
        assertThat(clean.response().payload()).contains("request-count: 1");
    }

    @Test
    void missingRequestIdIsFatalEvictsWorkerAndIsNotReplayed() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult missing = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "missing-request-id"));
        LocalJMeterWorkerResult clean = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-missing-id"));

        assertThat(missing.response().success()).isFalse();
        assertThat(missing.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(missing.response().message()).contains("protocol", "requestId");
        assertThat(missing.workerExited()).isTrue();
        assertThat(clean.response().success()).as(clean.response().toJsonLine()).isTrue();
        assertThat(clean.response().payload()).contains("request-count: 1");
    }

    @Test
    void mismatchedResponseProvenanceIsFatalEvictsWorkerAndIsNotReplayed() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult mismatch = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "mismatched-operation"));
        LocalJMeterWorkerResult clean = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-mismatch"));

        assertThat(mismatch.response().success()).isFalse();
        assertThat(mismatch.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(mismatch.response().message()).contains("protocol", "operation");
        assertThat(mismatch.workerExited()).isTrue();
        assertThat(clean.response().success()).as(clean.response().toJsonLine()).isTrue();
        assertThat(clean.response().payload()).contains("request-count: 1");
    }

    @Test
    void dryRunApplyWireRequestKeepsNonceAndCleansCandidateDirectory() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();
        long candidatesBefore = dryRunCandidateDirectoryCount();

        LocalJMeterWorkerResult result = client.execute(dryRunApply(home, "valid-dry-run"));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(result.response().payload()).contains("request-count: 1");
        assertThat(dryRunCandidateDirectoryCount()).isEqualTo(candidatesBefore);
    }

    @Test
    void missingDryRunResponseNonceIsFatalWithoutReplayAndNextWorkerIsClean() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult missing = client.execute(
                dryRunApply(home, "dry-run-missing-response-nonce"));
        LocalJMeterWorkerResult clean = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-missing-dry-run-nonce"));

        assertThat(missing.response().success()).isFalse();
        assertThat(missing.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(missing.response().message()).contains("protocol", "requestId");
        assertThat(missing.workerExited()).isTrue();
        assertThat(clean.response().success()).as(clean.response().toJsonLine()).isTrue();
        assertThat(clean.response().payload()).contains("request-count: 1");
    }

    @Test
    void mismatchedDryRunResponseNonceIsFatalWithoutReplayAndNextWorkerIsClean() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult mismatch = client.execute(
                dryRunApply(home, "dry-run-mismatched-response-nonce"));
        LocalJMeterWorkerResult clean = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-mismatched-dry-run-nonce"));

        assertThat(mismatch.response().success()).isFalse();
        assertThat(mismatch.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(mismatch.response().message()).contains("protocol", "requestId");
        assertThat(mismatch.workerExited()).isTrue();
        assertThat(clean.response().success()).as(clean.response().toJsonLine()).isTrue();
        assertThat(clean.response().payload()).contains("request-count: 1");
    }

    @Test
    void sharedWorkerRetainsProcessWhenPropertiesChangeDuringMcpSession() throws Exception {
        Path childPidFile = Files.createTempDirectory("j4a-stale-shared-worker-child-").resolve("child.pid");
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();
        Path userProperties = home.resolve("bin").resolve("user.properties");
        FileTime stableTimestamp = FileTime.fromMillis(1234567890000L);
        Files.write(userProperties, ("j4a.cache.key=alpha\n"
                + "j4a.fake.child.pid=" + propertyPath(childPidFile) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(userProperties, stableTimestamp);

        LocalJMeterWorkerResult first = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "first"));
        long firstPid = workerPid(first);
        Files.write(userProperties, "j4a.cache.key=bravo\n".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(userProperties, stableTimestamp);
        LocalJMeterWorkerResult second = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "second"));

        assertThat(first.response().success()).as(first.response().toJsonLine()).isTrue();
        assertThat(second.response().success()).as(second.response().toJsonLine()).isTrue();
        assertThat(first.response().payload()).contains("request-count: 1");
        assertThat(second.response().payload()).contains("request-count: 2");
        assertThat(workerPid(second)).isEqualTo(firstPid);
    }

    @Test
    void sharedWorkerRetainsProcessWhenClasspathContentChangesDuringMcpSession() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();
        Path searchDirectory = home.resolve("configured").resolve("search");
        Path searchContent = searchDirectory.resolve("search-plugin.jar");
        Path userClasspathJar = home.resolve("configured").resolve("user-plugin.jar");
        Files.createDirectories(searchDirectory);
        writeMarkerJar(searchContent, "search-alpha");
        writeMarkerJar(userClasspathJar, "user-alpha");
        Files.write(home.resolve("bin").resolve("jmeter.properties"), ("search_paths=configured/search\n"
                + "user.classpath=configured/user-plugin.jar\n").getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult first = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "first"));
        long firstPid = workerPid(first);
        writeMarkerJar(searchContent, "search-bravo");
        LocalJMeterWorkerResult searchChanged = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "search-changed"));
        writeMarkerJar(userClasspathJar, "user-bravo");
        LocalJMeterWorkerResult userClasspathChanged = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "user-classpath-changed"));

        assertThat(first.response().success()).as(first.response().toJsonLine()).isTrue();
        assertThat(searchChanged.response().success()).as(searchChanged.response().toJsonLine()).isTrue();
        assertThat(userClasspathChanged.response().success()).as(userClasspathChanged.response().toJsonLine()).isTrue();
        assertThat(first.response().payload()).contains("request-count: 1");
        assertThat(searchChanged.response().payload()).contains("request-count: 2");
        assertThat(userClasspathChanged.response().payload()).contains("request-count: 3");
        assertThat(workerPid(searchChanged)).isEqualTo(firstPid);
        assertThat(workerPid(userClasspathChanged)).isEqualTo(firstPid);
    }

    @Test
    void sharedWorkerRetainsProcessWhenConfiguredClasspathPathAppearsDuringMcpSession() throws Exception {
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();
        Path dependencyJar = home.resolve("configured").resolve("dependency-plugin.jar");
        Files.write(home.resolve("bin").resolve("jmeter.properties"),
                "plugin_dependency_paths=configured/dependency-plugin.jar\n".getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult first = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "first"));
        long firstPid = workerPid(first);
        writeMarkerJar(dependencyJar, "dependency-alpha");
        LocalJMeterWorkerResult dependencyAppeared = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "dependency-appeared"));

        assertThat(first.response().success()).as(first.response().toJsonLine()).isTrue();
        assertThat(dependencyAppeared.response().success()).as(dependencyAppeared.response().toJsonLine()).isTrue();
        assertThat(first.response().payload()).contains("request-count: 1");
        assertThat(dependencyAppeared.response().payload()).contains("request-count: 2");
        assertThat(workerPid(dependencyAppeared)).isEqualTo(firstPid);
    }

    @Test
    void closeSharedWorkersTerminatesDescendantProcesses() throws Exception {
        Path childPidFile = Files.createTempDirectory("j4a-shared-worker-child-").resolve("child.pid");
        LocalJMeterWorkerClient client = sharedClient();
        Path home = fakeJMeterHome();
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.child.pid=" + propertyPath(childPidFile) + "\n").getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.componentDetails(home, "first"));
        client.close();

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertNoLivePid(childPidFile);
    }

    @Test
    void closeAttemptsEveryWorkerBeforeReportingTerminationFailures() throws Exception {
        AtomicInteger terminations = new AtomicInteger();
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.reusable((process, workerId) -> {
            boolean terminated = LocalJMeterWorkerProcess.terminateProcessTreeUninterruptibly(process, workerId);
            return terminations.incrementAndGet() != 1 && terminated;
        });
        Path firstHome = fakeJMeterHome();
        Path secondHome = fakeJMeterHome();
        LocalJMeterWorkerResult first = client.execute(
                LocalJMeterWorkerRequest.componentDetails(firstHome, "first"));
        LocalJMeterWorkerResult second = client.execute(
                LocalJMeterWorkerRequest.componentDetails(secondHome, "second"));

        Thread.currentThread().interrupt();
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(client::close);
        boolean interruptRestored = Thread.interrupted();

        assertThat(failure).isInstanceOf(LocalJMeterSharedWorkers.WorkerCloseException.class)
                .hasMessageContaining("workers could not be closed");
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0].getMessage())
                .contains("owned by JMeter home")
                .containsAnyOf(firstHome.toRealPath().toString(), secondHome.toRealPath().toString());
        assertThat(terminations).hasValue(2);
        assertThat(interruptRestored).isTrue();
        assertEventuallyDead(workerPid(first));
        assertEventuallyDead(workerPid(second));
    }

    private LocalJMeterWorkerClient sharedClient() throws IOException {
        sharedWorkerClient = LocalJMeterWorkerClient.reusable();
        return sharedWorkerClient;
    }

    private LocalJMeterWorkerClient sharedClient(Duration preflightTimeout, Duration operationTimeout)
            throws IOException {
        sharedWorkerClient = LocalJMeterWorkerClient.reusable(
                Duration.ofSeconds(5), preflightTimeout, operationTimeout);
        return sharedWorkerClient;
    }

    private static LocalJMeterWorkerRequest dryRunApply(Path home, String patchMarker) throws IOException {
        Path input = Files.createTempFile("j4a-shared-worker-input-", ".jmx");
        Path patch = Files.createTempFile("j4a-shared-worker-patch-", ".yaml");
        Files.write(patch, patchMarker.getBytes(StandardCharsets.UTF_8));
        return LocalJMeterWorkerRequest.applyPatch(input, home, patch, null);
    }

    private static long dryRunCandidateDirectoryCount() throws IOException {
        Path temp = Paths.get(System.getProperty("java.io.tmpdir"));
        try (java.util.stream.Stream<Path> paths = Files.list(temp)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("jmx-agent-worker-dry-run-"))
                    .count();
        }
    }

    private static Path fakeJMeterHome() throws IOException {
        Path home = Files.createTempDirectory("j4a-shared-worker-home-");
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        copyLocalJMeterCore(home.resolve("lib").resolve("ApacheJMeter_core-local.jar"));
        LocalJMeterSharedWorkerProtocolFixture.create(home.resolve("lib").resolve("shared-worker-protocol.jar"));
        Files.write(home.resolve("bin").resolve("jmeter.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("saveservice.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("upgrade.properties"), new byte[0]);
        return home;
    }

    private static void copyLocalJMeterCore(Path target) throws IOException {
        Path source;
        try {
            source = Paths.get(org.apache.jmeter.util.JMeterUtils.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception exception) {
            throw new IOException("Unable to locate ApacheJMeter_core fixture jar", exception);
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void assertNoLivePid(Path pidFile) throws IOException, InterruptedException {
        assertThat(pidFile).isRegularFile();
        long pid = Long.parseLong(new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim());
        boolean alive = isPidAlive(pid);
        assertThat(alive).as("descendant process should be terminated").isFalse();
    }

    private static long workerPid(LocalJMeterWorkerResult result) {
        String payload = result.response().payload();
        String marker = "worker-pid: ";
        int start = payload.indexOf(marker);
        assertThat(start).as(payload).isGreaterThanOrEqualTo(0);
        return Long.parseLong(payload.substring(start + marker.length()).trim());
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String noisyLine(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append('x');
        }
        return value.toString();
    }

    private static final class RepeatingInputStream extends java.io.InputStream {
        private final byte value;
        private int remaining;

        private RepeatingInputStream(byte value, int remaining) {
            this.value = value;
            this.remaining = remaining;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            return value & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining == 0) {
                return -1;
            }
            int count = Math.min(length, remaining);
            java.util.Arrays.fill(buffer, offset, offset + count, value);
            remaining -= count;
            return count;
        }
    }

    private static void awaitFile(Path path) throws Exception {
        awaitObservable("file " + path, new Observable<Path>() {
            @Override
            public Path observe() {
                return Files.isRegularFile(path) ? path : null;
            }
        });
    }

    private static long awaitPublishedPid(Path pidFile) throws Exception {
        return awaitObservable("decimal PID in " + pidFile, new Observable<Long>() {
            @Override
            public Long observe() throws IOException {
                return publishedPid(pidFile);
            }
        });
    }

    private static long readPublishedPid(Path pidFile) throws IOException {
        Long pid = publishedPid(pidFile);
        assertThat(pid).as("decimal PID published in %s", pidFile).isNotNull();
        return pid.longValue();
    }

    private static Long publishedPid(Path pidFile) throws IOException {
        if (!Files.isRegularFile(pidFile)) {
            return null;
        }
        String value = new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim();
        if (!value.matches("[0-9]+")) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void assertEventuallyDead(long pid) throws Exception {
        Boolean alive = awaitObservable("termination of fixture PID " + pid, new Observable<Boolean>() {
            @Override
            public Boolean observe() throws Exception {
                return isPidAlive(pid) ? null : Boolean.FALSE;
            }
        });
        assertThat(alive).as("fixture pid %s should be dead", pid).isFalse();
    }

    private static <T> T awaitObservable(String description, Observable<T> observable) throws Exception {
        long deadline = System.nanoTime() + TRANSPORT_STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            T observed = observable.observe();
            if (observed != null) {
                return observed;
            }
            TimeUnit.MILLISECONDS.sleep(OBSERVATION_POLL_MILLIS);
        }
        throw new AssertionError("Timed out after " + TRANSPORT_STARTUP_TIMEOUT + " waiting for " + description + ".");
    }

    private interface Observable<T> {
        T observe() throws Exception;
    }

    private static boolean isPidAlive(long pid) throws IOException, InterruptedException {
        if (!isWindows()) {
            Path stat = Paths.get("/proc", Long.toString(pid), "stat");
            if (!Files.exists(stat)) {
                return false;
            }
            String value = new String(Files.readAllBytes(stat), StandardCharsets.UTF_8);
            int commandEnd = value.lastIndexOf(')');
            return commandEnd < 0 || commandEnd + 2 >= value.length() || value.charAt(commandEnd + 2) != 'Z';
        }
        Process process = isWindows()
                ? new ProcessBuilder("cmd", "/c", "tasklist /FI \"PID eq " + pid + "\" /NH").start()
                : new ProcessBuilder("sh", "-c", "kill -0 " + pid).start();
        process.waitFor();
        if (!isWindows()) {
            return process.exitValue() == 0;
        }
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        return output.contains(Long.toString(pid));
    }

    private static byte[] readAll(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void writeMarkerJar(Path jar, String marker) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("marker.txt"));
            output.write(marker.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String propertyPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
