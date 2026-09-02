package io.github.thisccl.j4a.validation;

import static io.github.thisccl.j4a.validation.LocalJMeterWorkerTestSupport.assertNoLivePid;
import static io.github.thisccl.j4a.validation.LocalJMeterWorkerTestSupport.client;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalJMeterWorkerLifecycleTest {
    @Test
    void workerResultExitCodeContractUsesPrimitiveIntAndUnknownMinusOne() throws Exception {
        assertThat(LocalJMeterWorkerResult.class.getDeclaredField("exitCode").getType())
                .isEqualTo(Integer.TYPE);
        LocalJMeterWorkerResult retained = LocalJMeterWorkerClient.result(
                LocalJMeterWorkerResponse.failure(
                        LocalJMeterWorkerRequest.componentDetails(fixtures.localHome(), "http.request"),
                        "LOCAL_JMETER_RUNTIME_ERROR", "runtime", null,
                        "fixture", "fixture", "", ""),
                false,
                -1);

        assertThat(retained.workerExited()).isFalse();
        assertThat(retained.exitCode()).isEqualTo(-1);
    }

    @Test
    void workerLaunchForcesHeadlessSwingValidation() throws IOException {
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.componentDetails(
                fixtures.localHome(), "http.request");

        assertThat(client.command(request, "worker-id")).contains("-Djava.awt.headless=true");
    }

    @Test
    void workerLaunchPropagatesSemanticEvidenceRollbackProperty() throws IOException {
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.componentDetails(
                fixtures.localHome(), "http.request");
        String previous = System.getProperty(LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY);
        try {
            System.setProperty(LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY, "true");

            assertThat(client.command(request, "worker-id")).contains(
                    "-D" + LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY + "=true");
        } finally {
            if (previous == null) {
                System.clearProperty(LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY);
            } else {
                System.setProperty(LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY, previous);
            }
        }
    }
    private final DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
    private String previousWorkerReuse;

    @BeforeEach
    void disableWorkerReuse() {
        previousWorkerReuse = System.getProperty("j4a.worker.reuse");
        System.setProperty("j4a.worker.reuse", "false");
    }

    @AfterEach
    void restoreWorkerReuse() {
        if (previousWorkerReuse == null) {
            System.clearProperty("j4a.worker.reuse");
        } else {
            System.setProperty("j4a.worker.reuse", previousWorkerReuse);
        }
    }

    @Test
    void workerProtocolIncludesRequiredFieldsForStartupFailure() throws IOException {
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.forJavaExecutable(
                fixtures.root().resolve("missing-java").toAbsolutePath().normalize().toString());
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.validate(
                fixtures.pluginBackedJmx(), fixtures.localHome());

        LocalJMeterWorkerResult result = client.execute(request);

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("LOCAL_JMETER_RUNTIME_ERROR");
        assertThat(result.response().category()).isEqualTo("runtime");
        assertThat(result.response().toJsonLine()).doesNotContain("\"profile\":", "\"selectedProfile\":");
        assertThat(result.response().affectedFile()).contains("plugin-backed.jmx");
        assertThat(result.response().suggestedAction()).contains("--jmeter-home");
        assertThat(result.response().toJsonLine()).contains(
                "\"operation\"",
                "\"jmxPath\"",
                "\"jmeterHome\"",
                "\"component\"",
                "\"patchPath\"",
                "\"success\"",
                "\"errorCode\"",
                "\"category\"",
                "\"affectedFile\"",
                "\"missingClass\"",
                "\"message\"",
                "\"suggestedAction\"",
                "\"payload\"",
                "\"stdout\"",
                "\"stderr\"");
        assertThat(result.workerExited()).isTrue();
    }

    @Test
    void startupTimeoutStopsWorkerBeforeOperationTimeoutAndKillsDescendants() throws Exception {
        Path pidFile = fixtures.root().resolve("startup-timeout-child.pid");
        Files.deleteIfExists(pidFile);
        Path jmx = fixtures.pluginBackedJmx();
        Path localHome = fixtures.localHome();
        LocalJMeterWorkerClient client = client(
                LocalJMeterWorkerTestSupport.currentJavaExecutable(),
                Duration.ofMillis(1500),
                Duration.ofSeconds(15),
                Duration.ofSeconds(15),
                SlowStartingWorkerLauncher.jvmArgs(pidFile));
        long started = System.nanoTime();

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.validate(jmx, localHome));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isLessThan(15000L);
        assertThat(result.response().success()).isFalse();
        assertThat(result.response().message()).contains("startup timed out");
        assertThat(result.workerExited()).isTrue();
        assertNoLivePid(pidFile);
    }

    @Test
    void workerTimeoutReturnsStructuredFailureAndExits() throws IOException {
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofMillis(250), Duration.ofMillis(250));

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.validate(
                fixtures.timeoutBackedJmx(), fixtures.localHome()));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("LOCAL_JMETER_RUNTIME_ERROR");
        assertThat(result.response().category()).isEqualTo("runtime");
        assertThat(result.response().message()).contains("timed out");
        assertThat(result.workerExited()).isTrue();
    }

    @Test
    void runtimePreflightTimeoutIsDistinctFromTransportAndOperationTimeouts() throws IOException {
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.componentDetails(
                fixtures.localHome(), "http.request");

        LocalJMeterWorkerResponse response = LocalJMeterWorkerClient.preflightTimeout(
                request, "transport-ready", "fingerprint-still-running");

        assertThat(response.success()).isFalse();
        assertThat(response.disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(response.message()).contains("runtime preflight timed out")
                .doesNotContain("startup timed out", "while running componentDetails");
    }

    @Test
    void runtimePreflightMarkerIsBoundToTheRequestNonce() throws IOException {
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.componentDetails(
                fixtures.localHome(), "http.request").withRequestId("request-nonce");

        assertThat(LocalJMeterWorkerClient.preflightReadyMarker(request))
                .isEqualTo("JMX_AGENT_LOCAL_WORKER_PREFLIGHT_READY:request-nonce");
        assertThat(LocalJMeterWorkerClient.isPreflightReadyMarker(
                "JMX_AGENT_LOCAL_WORKER_PREFLIGHT_READY:foreign", request)).isFalse();
    }

    @Test
    void malformedProtocolJsonReturnsStructuredLocalProfileFailure() throws IOException {
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.validate(
                fixtures.pluginBackedJmx(), fixtures.localHome());

        LocalJMeterWorkerResponse response = LocalJMeterWorkerClient.parseResponseForTesting(
                request, "JMX_AGENT_LOCAL_WORKER_READY\n{not-json\n", "diagnostic", 0);

        assertThat(response.success()).isFalse();
        assertThat(response.errorCode()).isEqualTo("LOCAL_JMETER_RUNTIME_ERROR");
        assertThat(response.category()).isEqualTo("runtime");
        assertThat(response.toJsonLine()).doesNotContain("\"profile\":", "\"selectedProfile\":");
        assertThat(response.message()).contains("malformed protocol response");
        assertThat(response.stdout()).contains("{not-json");
        assertThat(response.stderr()).contains("diagnostic");
    }

    @Test
    void dryRunApplyTimeoutCleansClientOwnedCandidateLocation() throws IOException {
        Path jmeterHome = fixtures.localHome();
        Path bin = jmeterHome.resolve("bin");
        Path patch = writeEmptyPatch();
        Path callerControlledDirectory = Files.createTempDirectory("jmx-agent-worker-dry-run-important-");
        Path marker = callerControlledDirectory.resolve("marker.txt");
        Files.write(marker, "keep".getBytes(StandardCharsets.UTF_8));
        Set<Path> dryRunDirectoriesBefore = dryRunDirectories();
        deleteWorkerCandidates(bin);
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofMillis(250), Duration.ofMillis(250));

        try {
            LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                    fixtures.timeoutBackedJmx(), jmeterHome, patch, null));

            assertThat(result.response().success()).isFalse();
            assertThat(result.response().message()).contains("timed out");
            assertThat(result.workerExited()).isTrue();
            assertThat(workerCandidates(bin)).isEmpty();
            assertThat(Files.exists(marker)).isTrue();
            assertThat(newDryRunDirectories(dryRunDirectoriesBefore)).isEmpty();
        } finally {
            deleteWorkerCandidates(bin);
            LocalJMeterWorkerDryRunDirectories.deleteRecursively(callerControlledDirectory);
        }
    }

    @Test
    void dryRunApplySuccessRemovesTemporaryCandidate() throws IOException {
        Path jmeterHome = fixtures.localHome();
        Path bin = jmeterHome.resolve("bin");
        Path patch = writeEmptyPatch();
        deleteWorkerCandidates(bin);
        Set<Path> dryRunDirectoriesBefore = dryRunDirectories();
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                fixtures.root().resolve("basic.jmx"), jmeterHome, patch, null));

        assertThat(result.response().success()).isTrue();
        assertThat(result.workerExited()).isTrue();
        assertThat(workerCandidates(bin)).isEmpty();
        assertThat(newDryRunDirectories(dryRunDirectoriesBefore)).isEmpty();
    }

    @Test
    void publicApplyPatchFactoryDoesNotExposeDryRunCandidateDirectory() {
        Set<Integer> publicApplyPatchParameterCounts = Arrays.stream(LocalJMeterWorkerRequest.class.getMethods())
                .filter(method -> method.getName().equals("applyPatch"))
                .map(Method::getParameterCount)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(publicApplyPatchParameterCounts).containsExactlyInAnyOrder(4, 5);
    }

    private Path writeEmptyPatch() throws IOException {
        Path patch = fixtures.root().resolve("empty-apply.yml");
        Files.write(patch, "changes: []\n".getBytes(StandardCharsets.UTF_8));
        return patch;
    }

    private static void deleteWorkerCandidates(Path directory) throws IOException {
        for (Path path : workerCandidates(directory)) {
            Files.deleteIfExists(path);
        }
    }

    private static java.util.List<Path> workerCandidates(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return java.util.Collections.emptyList();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(path -> path.getFileName().toString().startsWith("jmx-agent-worker-candidate-"))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    private static Set<Path> newDryRunDirectories(Set<Path> before) throws IOException {
        Set<Path> after = dryRunDirectories();
        after.removeAll(before);
        return after;
    }

    private static Set<Path> dryRunDirectories() throws IOException {
        Path directory = Paths.get(System.getProperty("java.io.tmpdir"));
        if (!Files.exists(directory)) {
            return java.util.Collections.emptySet();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths
                    .filter(path -> path.getFileName().toString().startsWith("jmx-agent-worker-dry-run-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

}
