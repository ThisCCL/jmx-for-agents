package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class LocalJMeterSessionReferenceLifecycleTest {
    private static final String REFERENCE_MODE_PROPERTY = "j4a.worker.reference.mode";
    private static final Path JMETER_HOME = io.github.thisccl.j4a.TestJMeterRuntime.home();

    @TempDir
    Path tempDir;

    @Test
    void reusableModeIsAStartupOnlyChildProperty() throws Exception {
        Path source = sourceCopy("startup-mode.jmx");
        LocalJMeterWorkerRequest request = read(source, null);

        List<String> oneShotCommand = new LocalJMeterWorkerClient().command(request, "one-shot-worker");
        try (LocalJMeterWorkerClient reusable = LocalJMeterWorkerClient.reusable()) {
            List<String> reusableCommand = reusable.command(request, "reusable-worker");

            assertThat(oneShotCommand)
                    .noneMatch(argument -> argument.startsWith("-D" + REFERENCE_MODE_PROPERTY + "="));
            assertThat(reusableCommand)
                    .containsOnlyOnce("-D" + REFERENCE_MODE_PROPERTY + "=session");
            assertThat(request.toJsonLine())
                    .doesNotContain(REFERENCE_MODE_PROPERTY, "referenceMode", "sessionMode");
        }
    }

    @Test
    void malformedStartupModeFailsBeforeServingARequest() throws Exception {
        Path source = sourceCopy("malformed-mode.jmx");
        LocalJMeterWorkerClient client = LocalJMeterWorkerTestSupport.client(
                LocalJMeterWorkerTestSupport.currentJavaExecutable(),
                Duration.ofSeconds(15),
                Duration.ofSeconds(60),
                Duration.ofSeconds(60),
                Collections.singletonList("-D" + REFERENCE_MODE_PROPERTY + "=not-a-worker-mode"));

        LocalJMeterWorkerResult result = client.execute(read(source, null));

        assertThat(result.response().success()).isFalse();
        assertThat(result.workerExited()).isTrue();
        assertThat(result.response().stderr())
                .contains("Unsupported local worker reference mode", "not-a-worker-mode");
    }

    @Test
    void oneShotExecutionKeepsSnapshotReferencesAndStartsFreshWorkers() throws Exception {
        Path source = sourceCopy("one-shot.jmx");
        LocalJMeterWorkerClient client = new LocalJMeterWorkerClient();

        LocalJMeterWorkerResult first = client.execute(read(source, null));
        String firstReference = firstReference(first);
        LocalJMeterWorkerResult second = client.execute(read(source, null));
        String secondReference = firstReference(second);
        LocalJMeterWorkerResult focused = client.execute(read(source, firstReference));

        assertThat(firstReference).startsWith("jmx_");
        assertThat(secondReference).isEqualTo(firstReference);
        assertThat(focused.response().success()).as(focused.response().toJsonLine()).isTrue();
        assertThat(references(focused.response().payload())).contains(firstReference);
        assertThat(first.workerExited()).isTrue();
        assertThat(second.workerExited()).isTrue();
        assertThat(focused.workerExited()).isTrue();
        System.out.println("SNAPSHOT_WORKERS firstRef=" + firstReference
                + " repeatedRef=" + secondReference + " allExited=true");
    }

    @Test
    void reusableExecutionSharesOnlyOneGenerationAndLosesRefsOnReplacement() throws Exception {
        Path source = sourceCopy("generation.jmx");
        Path initialized = tempDir.resolve("order-proven-init.jmx");
        Set<Long> baseline = LocalJMeterWorkerProcessTestProbe.recordedProcessIds();
        String oldReference;
        Set<Long> firstGeneration;
        try (LocalJMeterWorkerClient firstClient = LocalJMeterWorkerClient.reusable()) {
            LocalJMeterWorkerResult initializedResult = firstClient.execute(LocalJMeterWorkerRequest.initJmx(
                    initialized, JMETER_HOME, "Order-Proven Plan", "Order-Proven Thread Group", "2", "NONE"));
            LocalJMeterWorkerResult first = firstClient.execute(read(source, null));
            oldReference = firstReference(first);
            LocalJMeterWorkerResult repeated = firstClient.execute(read(source, null));
            LocalJMeterWorkerResult focused = firstClient.execute(read(source, oldReference));

            assertThat(initializedResult.response().success())
                    .as(initializedResult.response().toJsonLine()).isTrue();
            assertThat(initialized).isRegularFile();
            assertThat(oldReference).matches("[A-Za-z0-9_-]{16}");
            assertThat(firstReference(repeated)).isEqualTo(oldReference);
            assertThat(focused.response().success()).as(focused.response().toJsonLine()).isTrue();
            assertThat(references(focused.response().payload())).contains(oldReference);
            assertThat(first.workerExited()).isFalse();
            assertThat(repeated.workerExited()).isFalse();
            assertThat(focused.workerExited()).isFalse();
            firstGeneration = newWorkerProcessIdsSince(baseline);
            assertThat(firstGeneration).hasSize(1);
        }

        try (LocalJMeterWorkerClient replacement = LocalJMeterWorkerClient.reusable()) {
            LocalJMeterWorkerResult staleFocus = replacement.execute(read(source, oldReference));
            LocalJMeterWorkerResult fresh = replacement.execute(read(source, null));
            String freshReference = firstReference(fresh);

            assertThat(staleFocus.response().success()).isFalse();
            assertThat(staleFocus.response().errorCode()).isEqualTo("MCP_REF_NOT_FOUND");
            assertThat(fresh.response().success()).as(fresh.response().toJsonLine()).isTrue();
            assertThat(freshReference).matches("[A-Za-z0-9_-]{16}").isNotEqualTo(oldReference);
            assertThat(staleFocus.workerExited()).isFalse();
            assertThat(fresh.workerExited()).isFalse();
            assertThat(newWorkerProcessIdsSince(baseline))
                    .anyMatch(processId -> !firstGeneration.contains(processId));
            System.out.println("SESSION_GENERATION orderGateInit=true oldRef=" + oldReference
                    + " freshRef=" + freshReference + " staleCode=" + staleFocus.response().errorCode()
                    + " firstGeneration=" + firstGeneration + " replacementObserved=true");
        }
    }

    @Test
    void reusableGenerationReloadsThePlanAndRefreshesRefsAfterExternalChange() throws Exception {
        Path source = sourceCopy("request-loading.jmx");
        try (LocalJMeterWorkerClient client = LocalJMeterWorkerClient.reusable()) {
            LocalJMeterWorkerResult before = client.execute(read(source, null));
            String beforeReference = firstReference(before);
            String changed = new String(Files.readAllBytes(source), StandardCharsets.UTF_8)
                    .replace("Synthetic Test Plan", "EXTERNALLY CHANGED PLAN");
            Files.write(source, changed.getBytes(StandardCharsets.UTF_8));

            LocalJMeterWorkerResult after = client.execute(read(source, null));
            String afterReference = firstReference(after);

            assertThat(after.response().success()).as(after.response().toJsonLine()).isTrue();
            assertThat(after.response().payload()).contains("EXTERNALLY CHANGED PLAN");
            assertThat(afterReference).matches("[A-Za-z0-9_-]{16}").isNotEqualTo(beforeReference);
            System.out.println("SESSION_EXTERNAL_REFRESH beforeRef=" + beforeReference
                    + " afterRef=" + afterReference + " changedPayload=true");
        }
    }

    @Test
    void sessionInitPublishesTargetBoundRefsAndReplacementInvalidatesOldRefs() throws Exception {
        Path target = tempDir.resolve("session-init.jmx");
        Path snapshotTarget = tempDir.resolve("snapshot-init.jmx");
        LocalJMeterWorkerResult snapshotInit = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.initJmx(
                        snapshotTarget, JMETER_HOME,
                        "First Session Plan", "First Session Threads", "2", "NONE"));
        List<String> snapshotReferences = references(snapshotInit.response().payload());
        assertThat(snapshotInit.response().success()).as(snapshotInit.response().toJsonLine()).isTrue();
        assertThat(snapshotInit.workerExited()).isTrue();
        assertThat(snapshotReferences).hasSize(2).allMatch(reference -> reference.startsWith("jmx_"));

        SessionReferenceRegistry registry = new SessionReferenceRegistry();
        LocalJMeterWorkerOperations operations = LocalJMeterWorkerOperations.session(
                registry,
                new LocalJMeterWorkerOrderCapability(() -> { }));

        String created = operations.execute(LocalJMeterWorkerRequest.initJmx(
                target, JMETER_HOME, "First Session Plan", "First Session Threads", "2", "NONE"));
        List<String> oldReferences = references(created);
        byte[] committedBytes = Files.readAllBytes(target);
        DocumentIdentity identity = DocumentIdentity.of(JMETER_HOME, target);

        assertThat(target).isRegularFile();
        assertThat(Files.readAllBytes(target)).containsExactly(Files.readAllBytes(snapshotTarget));
        assertThat(oldReferences).hasSize(2)
                .allMatch(reference -> reference.matches("[A-Za-z0-9_-]{16}"))
                .noneMatch(reference -> reference.startsWith("jmx_"));
        assertThat(registry.documentStatus(identity, fingerprint(target)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        for (String reference : oldReferences) {
            assertThat(references(operations.execute(read(target, reference, "0")))).contains(reference);
        }

        String replaced = operations.execute(LocalJMeterWorkerRequest.initJmx(
                target, JMETER_HOME, "First Session Plan", "First Session Threads", "2", "NONE"));
        List<String> newReferences = references(replaced);

        assertThat(Files.readAllBytes(target)).containsExactly(committedBytes);
        assertThat(newReferences).hasSize(2)
                .allMatch(reference -> reference.matches("[A-Za-z0-9_-]{16}"))
                .noneMatch(reference -> reference.startsWith("jmx_"))
                .doesNotContainAnyElementsOf(oldReferences);
        for (String reference : oldReferences) {
            assertThatThrownBy(() -> operations.execute(read(target, reference, "0")))
                    .isInstanceOfSatisfying(ReferenceFailure.class,
                            failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        }
        for (String reference : newReferences) {
            assertThat(references(operations.execute(read(target, reference, "0")))).contains(reference);
        }
        System.out.println("SESSION_INIT oneShotExactBytes=true oldRefsInvalidated=true newRefsFocused=true");
    }

    @Test
    void sessionInitCapacityFailurePreservesMissingAndExistingTargetsAndRefs() throws Exception {
        SessionReferenceRegistry registry = new SessionReferenceRegistry(4, 1);
        LocalJMeterWorkerOperations operations = LocalJMeterWorkerOperations.session(
                registry, new LocalJMeterWorkerOrderCapability(() -> { }));
        Path existing = sourceCopy("capacity-existing.jmx");
        byte[] existingBytes = Files.readAllBytes(existing);
        String existingReference = firstReference(operations.execute(read(existing, null, "0")));
        Object registryBefore = registrySnapshot(registry);
        Path missing = tempDir.resolve("capacity-missing.jmx");

        for (Path target : java.util.Arrays.asList(missing, existing)) {
            assertThatThrownBy(() -> operations.execute(LocalJMeterWorkerRequest.initJmx(
                    target, JMETER_HOME, "Capacity Plan", "Capacity Threads", "2", "NONE")))
                    .isInstanceOfSatisfying(ReferenceFailure.class, failure -> {
                        assertThat(failure.code()).isEqualTo("MCP_REF_CAPACITY_EXCEEDED");
                        assertThat(failure.category()).isEqualTo("runtime");
                    });
            assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        }

        assertThat(missing).doesNotExist();
        assertThat(Files.readAllBytes(existing)).containsExactly(existingBytes);
        assertThat(references(operations.execute(read(existing, existingReference, "0"))))
                .isNotEmpty()
                .allMatch(existingReference::equals);
        assertThat(registry.documentStatus(DocumentIdentity.of(JMETER_HOME, existing), fingerprint(existing)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
    }

    @Test
    void sessionInitOrderFailurePrecedesTargetAndRegistryWorkForMissingAndExistingTargets() throws Exception {
        SessionReferenceRegistry registry = new SessionReferenceRegistry(4, 16);
        AtomicInteger proofAttempts = new AtomicInteger();
        LocalJMeterWorkerOperations operations = LocalJMeterWorkerOperations.session(
                registry, new LocalJMeterWorkerOrderCapability(() -> {
                    proofAttempts.incrementAndGet();
                    throw new IOException("injected order failure");
                }));
        Path existing = sourceCopy("order-existing.jmx");
        byte[] existingBytes = Files.readAllBytes(existing);
        String existingReference = firstReference(operations.execute(read(existing, null, "0")));
        Object registryBefore = registrySnapshot(registry);
        Path missing = tempDir.resolve("order-missing.jmx");

        for (Path target : java.util.Arrays.asList(missing, existing)) {
            assertThatThrownBy(() -> operations.execute(LocalJMeterWorkerRequest.initJmx(
                    target, JMETER_HOME, "Order Plan", "Order Threads", "2", "NONE")))
                    .isInstanceOfSatisfying(ReferenceFailure.class,
                            failure -> assertThat(failure.code()).isEqualTo("MCP_REF_ORDER_UNPROVEN"));
            assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        }

        assertThat(proofAttempts).hasValue(1);
        assertThat(missing).doesNotExist();
        assertThat(Files.readAllBytes(existing)).containsExactly(existingBytes);
        assertThat(references(operations.execute(read(existing, existingReference, "0"))))
                .isNotEmpty()
                .allMatch(existingReference::equals);
    }

    private Path sourceCopy(String name) throws Exception {
        assertThat(JMETER_HOME.resolve("bin/ApacheJMeter.jar")).isRegularFile();
        Path source = tempDir.resolve(name);
        Files.copy(Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        return source;
    }

    private static LocalJMeterWorkerRequest read(Path source, String reference) {
        return read(source, reference, "5");
    }

    private static LocalJMeterWorkerRequest read(Path source, String reference, String depth) {
        return LocalJMeterWorkerRequest.renderReadData(source, JMETER_HOME, depth, reference, "NONE", "false");
    }

    private static String fingerprint(Path source) throws IOException {
        return SourceSnapshot.read(source, ignored -> Boolean.TRUE).fingerprint();
    }

    private static Object registrySnapshot(SessionReferenceRegistry registry) throws Exception {
        java.lang.reflect.Method snapshot = SessionReferenceRegistry.class.getDeclaredMethod("snapshot");
        snapshot.setAccessible(true);
        return snapshot.invoke(registry);
    }

    private static void assertTargetAndRefsUnchanged(
            LocalJMeterWorkerOperations operations,
            SessionReferenceRegistry registry,
            Path target,
            DocumentIdentity identity,
            byte[] expectedBytes,
            List<String> expectedReferences) throws Exception {
        assertThat(Files.readAllBytes(target)).containsExactly(expectedBytes);
        assertThat(registry.documentStatus(identity, fingerprint(target)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        for (String reference : expectedReferences) {
            assertThat(references(operations.execute(read(target, reference, "0")))).contains(reference);
        }
    }

    private static String firstReference(LocalJMeterWorkerResult result) {
        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        return firstReference(result.response().payload());
    }

    private static String firstReference(String yaml) {
        List<String> references = references(yaml);
        assertThat(references).isNotEmpty();
        return references.get(0);
    }

    private static List<String> references(String yaml) {
        List<String> references = new ArrayList<String>();
        collectReferences(new Yaml().load(yaml), references);
        return references;
    }

    private static void collectReferences(Object value, List<String> references) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if ("ref".equals(entry.getKey()) && entry.getValue() instanceof String) {
                    references.add((String) entry.getValue());
                }
                collectReferences(entry.getValue(), references);
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                collectReferences(item, references);
            }
        }
    }

    private static Set<Long> newWorkerProcessIdsSince(Set<Long> baseline) {
        Set<Long> current = new LinkedHashSet<Long>(LocalJMeterWorkerProcessTestProbe.recordedProcessIds());
        current.removeAll(baseline);
        return current;
    }

}
