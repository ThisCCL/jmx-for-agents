package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StandaloneWorkerLifecycleTest {
    @Test
    void defaultBudgetsLeaveHeadroomForColdRuntimePreparation() {
        LocalJMeterWorkerClient client = new LocalJMeterWorkerClient();

        assertThat(LocalJMeterWorkerClient.DEFAULT_STARTUP_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
        assertThat(client.preflightTimeoutDuration()).isEqualTo(Duration.ofSeconds(180));
        assertThat(client.operationTimeoutDuration()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void standalonePreflightTimeoutDoesNotDispatchOrReplayOperation() throws Exception {
        Path home = fakeJMeterHome();
        Path requestCount = home.resolve("request-count.txt");
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.request.count=" + requestCount.toAbsolutePath() + "\n")
                        .getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult result = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofMillis(250), Duration.ofSeconds(15)).execute(
                        LocalJMeterWorkerRequest.componentDetails(home, "hang-preflight"));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().message()).contains("runtime preflight timed out");
        assertThat(result.workerExited()).isTrue();
        assertThat(requestCount).doesNotExist();
    }

    @Test
    void standalonePreflightMayUseItsOwnBudgetBeforeShortOperationBudget() throws Exception {
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult result = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(2), Duration.ofSeconds(1)).execute(
                        LocalJMeterWorkerRequest.componentDetails(home, "slow-preflight"));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(result.workerExited()).isTrue();
    }

    @Test
    void standaloneExecutionOwnsOneShotWorkerUntilItIsDead() throws Exception {
        Path home = fakeJMeterHome();
        Path pidFile = home.resolve("worker.pid");
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.worker.pid=" + pidFile.toAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.componentDetails(home, "standalone"));

        assertThat(result.response().success()).isTrue();
        assertThat(result.workerExited()).isTrue();
        assertThat(result.exitCode()).isZero();
        LocalJMeterWorkerTestSupport.assertNoLivePid(pidFile);
        System.out.println("STANDALONE_PID="
                + new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim() + " ALIVE=false");
    }

    @Test
    void standaloneRejectsMissingResponseNonceAsFatalProtocolCorruption() throws Exception {
        assertFatalNonce(LocalJMeterWorkerRequest.componentDetails(fakeJMeterHome(), "missing-request-id"));
    }

    @Test
    void standaloneRejectsForeignResponseNonceAsFatalProtocolCorruption() throws Exception {
        assertFatalNonce(LocalJMeterWorkerRequest.componentDetails(fakeJMeterHome(), "foreign-response"));
    }

    @Test
    void standaloneRejectsMismatchedResponseProvenanceAsFatalProtocolCorruption() throws Exception {
        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.componentDetails(fakeJMeterHome(), "mismatched-operation"));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(result.response().message()).contains("protocol", "operation");
        assertThat(result.workerExited()).isTrue();
    }

    @Test
    void standaloneDryRunSendsNonceAndCleansCandidateDirectory() throws Exception {
        long candidatesBefore = dryRunCandidateDirectoryCount();

        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(
                dryRunApply(fakeJMeterHome(), "valid-dry-run"));

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(result.workerExited()).isTrue();
        assertThat(dryRunCandidateDirectoryCount()).isEqualTo(candidatesBefore);
    }

    @Test
    void standaloneDryRunRejectsMissingNonceAndCleansCandidateDirectory() throws Exception {
        long candidatesBefore = dryRunCandidateDirectoryCount();

        assertFatalNonce(dryRunApply(fakeJMeterHome(), "dry-run-missing-response-nonce"));

        assertThat(dryRunCandidateDirectoryCount()).isEqualTo(candidatesBefore);
    }

    @Test
    void standaloneDryRunRejectsForeignNonceAndCleansCandidateDirectory() throws Exception {
        long candidatesBefore = dryRunCandidateDirectoryCount();

        assertFatalNonce(dryRunApply(fakeJMeterHome(), "dry-run-mismatched-response-nonce"));

        assertThat(dryRunCandidateDirectoryCount()).isEqualTo(candidatesBefore);
    }

    private static void assertFatalNonce(LocalJMeterWorkerRequest request) {
        LocalJMeterWorkerResult result = new LocalJMeterWorkerClient().execute(request);
        assertThat(result.response().success()).isFalse();
        assertThat(result.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(result.response().message()).contains("protocol", "requestId");
        assertThat(result.workerExited()).isTrue();
    }

    private static LocalJMeterWorkerRequest dryRunApply(Path home, String marker) throws Exception {
        Path input = Files.createTempFile("j4a-standalone-input-", ".jmx");
        Path patch = Files.createTempFile("j4a-standalone-patch-", ".yaml");
        Files.write(patch, marker.getBytes(StandardCharsets.UTF_8));
        return LocalJMeterWorkerRequest.applyPatch(input, home, patch, null);
    }

    private static long dryRunCandidateDirectoryCount() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.list(Paths.get(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("jmx-agent-worker-dry-run-"))
                    .count();
        }
    }

    private static Path fakeJMeterHome() throws Exception {
        Path home = Files.createTempDirectory("j4a-standalone-worker-home-");
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        Path core = Paths.get(org.apache.jmeter.util.JMeterUtils.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Files.copy(core, home.resolve("lib").resolve("ApacheJMeter_core-local.jar"));
        LocalJMeterSharedWorkerProtocolFixture.create(home.resolve("lib").resolve("shared-worker-protocol.jar"));
        Files.write(home.resolve("bin").resolve("jmeter.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("saveservice.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("upgrade.properties"), new byte[0]);
        return home;
    }
}
