package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCandidateReloadFailureTest extends SessionApplyCommitTestSupport {
    @TempDir
    Path tempDir;

    @Test
    void realCandidateReloadFailureDiscardsProvisionalPublicationAndPreservesLiveState()
            throws Exception {
        Path source = sourceCopy("reload-session-source.jmx");
        Path target = tempDir.resolve("reload-session-target.jmx");
        byte[] sourceBefore = Files.readAllBytes(source);
        byte[] targetBefore = "reload-session-target-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(target, targetBefore);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 200);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        Object registryBefore = registrySnapshot(registry);
        Path quarantine = tempDir.resolve("reload-session-quarantine.jmx");
        AtomicBoolean corrupted = new AtomicBoolean();
        AtomicReference<Path> candidate = new AtomicReference<Path>();
        MutationRequest request = MutationRequest.commit(
                source, jmeterHome(),
                LocalJMeterWorkerMutations.parsePatch(mutationWithProvisionalRefs(refs)),
                target, true, registry);
        LocalMutationTransaction transaction = new LocalMutationTransaction(found -> {
            try {
                candidate.set(found);
                Files.move(found, quarantine, StandardCopyOption.ATOMIC_MOVE);
                Files.createDirectory(found);
                corrupted.set(true);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });

        Throwable failure = catchThrowable(() -> transaction.execute(request));

        assertThat(corrupted).isTrue();
        assertThat(failure).isInstanceOfSatisfying(ReferenceFailure.class, referenceFailure -> {
            assertThat(referenceFailure.code()).isEqualTo("MCP_REF_RECONCILIATION_FAILED");
            assertThat(referenceFailure.reason())
                    .isEqualTo(ReferenceFailure.Reason.RECONCILIATION_FAILED);
            assertThat(referenceFailure.category()).isEqualTo("runtime");
            assertThat(referenceFailure.getCause()).isInstanceOf(java.io.IOException.class);
        });
        assertThat(failure).isNotInstanceOf(LocalJMeterFileCommitter.CommitException.class);
        assertThat(Files.readAllBytes(source)).containsExactly(sourceBefore);
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(candidate.get()).doesNotExist();
        assertThat(candidateFiles()).isEmpty();
        Files.deleteIfExists(quarantine);
    }

    private List<Path> candidateFiles() throws Exception {
        List<Path> candidates = new java.util.ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                tempDir, "jmx-agent-worker-candidate-*.jmx")) {
            for (Path candidate : stream) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private Path sourceCopy(String name) throws Exception {
        Path source = tempDir.resolve(name);
        Files.copy(java.nio.file.Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        return source;
    }

    private static String mutationWithProvisionalRefs(List<String> refs) {
        StringBuilder yaml = new StringBuilder("changes:\n")
                .append("  - delete:\n      ref: ").append(refs.get(2)).append('\n')
                .append("  - add:\n      parent: ").append(refs.get(1))
                .append("\n      position: last\n")
                .append("      component: org.apache.jmeter.control.gui.LogicControllerGui\n")
                .append("      as: provisional\n");
        for (int index = 0; index < 96; index++) {
            yaml.append("  - add:\n      parent: ").append(refs.get(1))
                    .append("\n      position: last\n")
                    .append("      component: org.apache.jmeter.control.gui.LogicControllerGui\n")
                    .append("      properties:\n        - property: [TestElement.name]\n")
                    .append("          value: reload-race-").append(index)
                    .append("\n          type: string\n");
        }
        return yaml.toString();
    }
}
