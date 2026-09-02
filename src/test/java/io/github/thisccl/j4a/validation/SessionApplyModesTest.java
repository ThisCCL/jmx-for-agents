package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class SessionApplyModesTest extends SessionApplyCommitTestSupport {
    @TempDir
    Path tempDir;

    @Test
    void copyKeepsSourceIdentityAndPublishesOnlySurvivingTargetAliases() throws Exception {
        Path source = sourceCopy("copy-source.jmx");
        Path target = sourceCopy("copy-target.jmx");
        Path companion = sourceCopy("copy-companion.jmx");
        SessionReferenceRegistry registry = new SessionReferenceRegistry(4, 50);
        LocalJMeterWorkerOperations operations = operations(registry);
        List<String> sourceRefs = references(operations.execute(read(source, null)));
        List<String> previousTargetRefs = references(operations.execute(read(target, null)));
        operations.execute(read(companion, null));
        DocumentIdentity sourceIdentity = DocumentIdentity.of(jmeterHome(), source);
        DocumentIdentity targetIdentity = DocumentIdentity.of(jmeterHome(), target);
        String sourceFingerprint = fingerprint(source);
        java.util.Set<String> sourceTokens = registrySnapshotTokens(registry, sourceIdentity);

        String payload = operations.execute(LocalJMeterWorkerRequest.applyPatchYaml(
                source, jmeterHome(), mixedPatch(sourceRefs), target, true));

        Map<String, Object> receipt = mapping(new Yaml().load(payload));
        List<Map<String, Object>> created = mappings(receipt.get("createdRefs"));
        assertThat(receipt.get("appliedCount")).isEqualTo(8);
        assertThat(created).extracting(row -> row.get("alias")).containsExactly("controller", "child");
        assertThat(receipt.get("deletedRefs")).isEqualTo(Collections.emptyList());
        List<Map<String, Object>> changes = mappings(receipt.get("changeResults"));
        assertThat(changes).hasSize(8);
        assertThat(changes).extracting(row -> row.get("status")).containsOnly("committed");
        assertThat(changes.get(1)).containsEntry("resultRef", created.get(0).get("ref"));
        assertThat(changes.get(3)).containsEntry("resultRef", created.get(1).get("ref"));
        assertThat(changes.get(5)).doesNotContainKey("resultRef");
        assertThat(changes.get(6)).doesNotContainKey("resultRef");
        assertThat(payload).doesNotContain("Mixed Plan", "api.example", "fingerprint", "generation");
        assertThat(registry.documentStatus(sourceIdentity, sourceFingerprint))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        assertThat(registrySnapshotTokens(registry, sourceIdentity)).isEqualTo(sourceTokens);
        assertThat(registry.documentStatus(targetIdentity, fingerprint(target)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.MATCH);
        assertThat(registrySnapshotLru(registry)).containsExactly(
                DocumentIdentity.of(jmeterHome(), companion), targetIdentity, sourceIdentity);

        List<String> createdRefs = new ArrayList<String>();
        for (Map<String, Object> row : created) {
            createdRefs.add((String) row.get("ref"));
        }
        assertThat(createdRefs).doesNotContainAnyElementsOf(sourceRefs).doesNotContainAnyElementsOf(previousTargetRefs);
        assertThat(registrySnapshotTokens(registry, targetIdentity)).containsExactlyInAnyOrderElementsOf(createdRefs);
        for (String sourceRef : sourceRefs) {
            assertThat(references(operations.execute(read(source, sourceRef)))).contains(sourceRef);
        }
        for (String createdRef : createdRefs) {
            assertThat(references(operations.execute(read(target, createdRef)))).contains(createdRef);
        }
        assertThatThrownBy(() -> operations.execute(read(target, previousTargetRefs.get(0))))
                .isInstanceOf(ReferenceFailure.class);
    }

    @Test
    void dryRunCompletesCandidateValidationWithoutIdentityOrFileEffects() throws Exception {
        Path source = sourceCopy("dry-source.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 30);
        LocalJMeterWorkerOperations operations = operations(registry);
        List<String> sourceRefs = references(operations.execute(read(source, null)));
        Object registryBefore = registrySnapshot(registry);
        Path dryRunDirectory = Files.createDirectory(tempDir.resolve("dry-run-candidate"));
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.applyPatchYaml(
                source, jmeterHome(), mixedPatch(sourceRefs), null, false)
                .withDryRunCandidateDirectory(dryRunDirectory);
        String payload = LocalJMeterWorkerMutations.applyPatch(request, registry);

        Map<String, Object> receipt = mapping(new Yaml().load(payload));
        assertThat(receipt.get("appliedCount")).isEqualTo(8);
        assertThat(receipt.get("createdRefs")).isEqualTo(Collections.emptyList());
        assertThat(receipt.get("deletedRefs")).isEqualTo(Collections.emptyList());
        assertThat(mappings(receipt.get("changeResults")))
                .hasSize(8)
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("status", "validated")
                        .doesNotContainKey("resultRef"));
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
    }

    @Test
    void copyCapacityFailureCannotEvictSourceOrMutateTarget() throws Exception {
        Path source = sourceCopy("capacity-source.jmx");
        Path target = tempDir.resolve("capacity-target.jmx");
        byte[] sentinel = "capacity-target-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(target, sentinel);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(2, 3);
        LocalJMeterWorkerOperations operations = operations(registry);
        List<String> sourceRefs = references(operations.execute(read(source, null)));
        assertThat(sourceRefs).hasSize(3);
        Object registryBefore = registrySnapshot(registry);
        String patch = "changes:\n  - add:\n      parent: " + sourceRefs.get(1) + "\n"
                + "      position: last\n"
                + "      component: org.apache.jmeter.control.gui.LogicControllerGui\n"
                + "      as: created\n";

        assertThatThrownBy(() -> operations.execute(LocalJMeterWorkerRequest.applyPatchYaml(
                source, jmeterHome(), patch, target, true)))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_CAPACITY_EXCEEDED"));
        assertThat(Files.readAllBytes(target)).containsExactly(sentinel);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
    }

    @Test
    void copyCandidateFailureLeavesRegistryAndBothFilesUnchanged() throws Exception {
        Path source = sourceCopy("candidate-source.jmx");
        Path target = tempDir.resolve("candidate-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] targetBefore = "candidate-target-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(target, targetBefore);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 30);
        LocalJMeterWorkerOperations operations = operations(registry);
        String ref = references(operations.execute(read(source, null))).get(0);
        Object registryBefore = registrySnapshot(registry);
        String patch = "changes:\n  - set:\n      ref: " + ref + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: candidate-only\n          type: string\n";

        Files.delete(target);
        Files.createDirectory(target);
        assertThatThrownBy(() -> LocalJMeterWorkerMutations.applyPatch(
                LocalJMeterWorkerRequest.applyPatchYaml(source, jmeterHome(), patch, target, true),
                registry))
                .isInstanceOf(LocalJMeterFileCommitter.CommitException.class);
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(target).isDirectory();
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
    }

    @Test
    void copySourceChangeBeforeCommitInvalidatesSourceAndPreservesTarget() throws Exception {
        Path source = sourceCopy("stale-copy-source.jmx");
        Path target = tempDir.resolve("stale-copy-target.jmx");
        byte[] targetBefore = "stale-copy-target-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(target, targetBefore);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 30);
        LocalJMeterWorkerOperations operations = operations(registry);
        String ref = references(operations.execute(read(source, null))).get(0);
        String patch = "changes:\n  - set:\n      ref: " + ref + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: candidate-only\n          type: string\n";

        Files.write(source, "external-copy-change".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> LocalJMeterWorkerMutations.applyPatch(
                LocalJMeterWorkerRequest.applyPatchYaml(source, jmeterHome(), patch, target, true),
                registry))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        assertThat(Files.readAllBytes(source))
                .containsExactly("external-copy-change".getBytes(StandardCharsets.UTF_8));
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertThat(registry.documentStatus(DocumentIdentity.of(jmeterHome(), source), fingerprint(source)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.ABSENT);
    }

    @Test
    void failedRuntimeOrderProofPrecedesStaleReferenceAndPreservesAllState() throws Exception {
        Path source = sourceCopy("order-source.jmx");
        Path missing = tempDir.resolve("order-missing-source.jmx");
        Path target = tempDir.resolve("order-target.jmx");
        byte[] sentinel = "order-target-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(target, sentinel);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 30);
        LocalJMeterWorkerOperations readable = operations(registry);
        readable.execute(read(source, null));
        Object registryBefore = registrySnapshot(registry);
        AtomicInteger proofAttempts = new AtomicInteger();
        LocalJMeterWorkerOperations failing = LocalJMeterWorkerOperations.session(
                registry, new LocalJMeterWorkerOrderCapability(() -> {
                    proofAttempts.incrementAndGet();
                    throw new java.io.IOException("injected encounter-order failure");
                }));

        assertThatThrownBy(() -> failing.execute(LocalJMeterWorkerRequest.applyPatchYaml(
                missing,
                jmeterHome(),
                "changes:\n  - delete:\n      ref: stale-ref\n",
                target,
                true)))
                .isInstanceOfSatisfying(ReferenceFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("MCP_REF_ORDER_UNPROVEN");
                    assertThat(failure.reason()).isEqualTo(ReferenceFailure.Reason.ORDER_UNPROVEN);
                });
        assertThat(proofAttempts).hasValue(1);
        assertThat(Files.exists(missing)).isFalse();
        assertThat(Files.readAllBytes(target)).containsExactly(sentinel);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
    }

    private Path sourceCopy(String name) throws Exception {
        Path source = tempDir.resolve(name);
        Files.copy(java.nio.file.Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        return source;
    }
}
