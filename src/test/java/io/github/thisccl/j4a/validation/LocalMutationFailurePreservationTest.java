package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
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

class LocalMutationFailurePreservationTest extends SessionApplyCommitTestSupport {
    @TempDir
    Path tempDir;

    @Test
    void staleRegistryFingerprintRejectsBeforeCandidateOrPublication() throws Exception {
        Path source = sourceCopy("stale-source.jmx");
        Path target = targetWithSentinel("stale-target.jmx");
        byte[] targetBefore = Files.readAllBytes(target);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        Files.write(source, "externally-replaced".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> execute(MutationRequest.commit(
                source, jmeterHome(), patch(setName(refs.get(0), "never")),
                target, true, registry)))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertThat(registry.documentStatus(
                DocumentIdentity.of(jmeterHome(), source), fingerprint(source)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.ABSENT);
        assertThat(candidate()).isNull();
    }

    @Test
    void precommitSourceChangePreservesTargetAndDiscardsProvisionalState() throws Exception {
        Path source = sourceCopy("precommit-source.jmx");
        Path target = targetWithSentinel("precommit-target.jmx");
        byte[] targetBefore = Files.readAllBytes(target);
        byte[] external = "external-precommit-change".getBytes(StandardCharsets.UTF_8);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        AtomicReference<Throwable> watcherFailure = new AtomicReference<Throwable>();
        Thread watcher = sourceChanger(source, external, watcherFailure);
        watcher.start();

        assertThatThrownBy(() -> execute(MutationRequest.commit(
                source, jmeterHome(), patch(manyAdds(refs.get(1), 24)),
                target, true, registry)))
                .isInstanceOfSatisfying(ReferenceFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("MCP_REF_NOT_FOUND"));
        watcher.join();
        assertThat(watcherFailure.get()).isNull();
        assertThat(Files.readAllBytes(source)).containsExactly(external);
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertThat(registry.documentStatus(
                DocumentIdentity.of(jmeterHome(), source), fingerprint(source)))
                .isEqualTo(SessionReferenceRegistry.DocumentStatus.ABSENT);
        assertThat(candidate()).isNull();
    }

    @Test
    void realCandidateReloadCorruptionPreservesTargetRegistryAndCleanup() throws Exception {
        Path source = sourceCopy("reload-source.jmx");
        Path target = targetWithSentinel("reload-target.jmx");
        byte[] targetBefore = Files.readAllBytes(target);
        SessionReferenceRegistry registry = new SessionReferenceRegistry(3, 40);
        List<String> refs = references(operations(registry).execute(read(source, null)));
        Object registryBefore = registrySnapshot(registry);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean corrupted = new AtomicBoolean();
        AtomicReference<Throwable> watcherFailure = new AtomicReference<Throwable>();
        Path quarantine = tempDir.resolve("candidate-quarantine.jmx");
        Thread watcher = candidateCorruptor(
                quarantine, stop, corrupted, watcherFailure);
        watcher.start();

        assertThatThrownBy(() -> execute(MutationRequest.commit(
                source, jmeterHome(), patch(manyAdds(refs.get(1), 24)),
                target, true, registry)))
                .isInstanceOf(Exception.class);
        stop.set(true);
        watcher.join();
        assertThat(corrupted).isTrue();
        assertThat(watcherFailure.get()).isNull();
        Files.deleteIfExists(quarantine);
        assertThat(Files.readAllBytes(target)).containsExactly(targetBefore);
        assertThat(registrySnapshot(registry)).isEqualTo(registryBefore);
        assertThat(candidate()).isNull();
    }

    private Thread sourceChanger(
            Path source, byte[] external, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                Path found = awaitCandidate();
                if (found == null) {
                    throw new IllegalStateException("candidate was not observed");
                }
                Files.write(source, external);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "local-mutation-source-changer");
    }

    private Thread candidateCorruptor(
            Path quarantine,
            AtomicBoolean stop,
            AtomicBoolean corrupted,
            AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                Path found = awaitCandidate();
                if (found == null) {
                    throw new IllegalStateException("candidate was not observed");
                }
                if (!stop.get()) {
                    Files.move(found, quarantine, StandardCopyOption.ATOMIC_MOVE);
                    Files.createDirectory(found);
                    corrupted.set(true);
                }
            } catch (Throwable throwable) {
                if (!stop.get()) {
                    failure.set(throwable);
                }
            }
        }, "local-mutation-candidate-corruptor");
    }

    private Path awaitCandidate() throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Path found = candidate();
            if (found != null) {
                return found;
            }
            Thread.yield();
        }
        return null;
    }

    private Path candidate() throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                tempDir, "jmx-agent-worker-candidate-*.jmx")) {
            for (Path path : stream) {
                return path;
            }
        }
        return null;
    }

    private static MutationResult execute(MutationRequest request) throws Exception {
        return new LocalMutationTransaction().execute(request);
    }

    private static ApplyPatch patch(String yaml) throws Exception {
        return new ApplyPatchParser().parse(yaml);
    }

    private static String setName(String ref, String name) {
        return "changes:\n  - set:\n      ref: " + ref + "\n"
                + "      properties:\n        - property: [TestElement.name]\n"
                + "          value: " + name + "\n          type: string\n";
    }

    private static String manyAdds(String parent, int count) {
        StringBuilder yaml = new StringBuilder("changes:\n");
        for (int index = 0; index < count; index++) {
            yaml.append("  - add:\n      parent: ").append(parent)
                    .append("\n      position: last\n")
                    .append("      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n")
                    .append("      properties:\n        - property: [TestElement.name]\n")
                    .append("          value: candidate-").append(index)
                    .append("\n          type: string\n");
        }
        return yaml.toString();
    }

    private Path sourceCopy(String name) throws Exception {
        Path source = tempDir.resolve(name);
        Files.copy(java.nio.file.Paths.get("src/test/resources/fixtures/simple-http.jmx"), source);
        return source;
    }

    private Path targetWithSentinel(String name) throws Exception {
        Path target = tempDir.resolve(name);
        Files.write(target, (name + "-sentinel").getBytes(StandardCharsets.UTF_8));
        return target;
    }
}
