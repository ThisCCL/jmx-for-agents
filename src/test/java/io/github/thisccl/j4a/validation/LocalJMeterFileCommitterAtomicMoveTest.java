package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.Permission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalJMeterFileCommitterAtomicMoveTest {
    @TempDir
    Path tempDir;

    @Test
    void replacementRequiresAtomicMoveAcrossFilesystems() throws Exception {
        assertCrossFilesystemMoveFailsWithoutChangingEitherFile(true);
    }

    @Test
    void createNewPublishesWithAtomicNoReplaceLinkSemantics() throws Exception {
        byte[] candidateBytes = "candidate-bytes".getBytes(StandardCharsets.UTF_8);
        Path candidate = tempDir.resolve("new-candidate.jmx");
        Path target = tempDir.resolve("new-target.jmx");
        Files.write(candidate, candidateBytes);

        LocalJMeterFileCommitter.commit(candidate, target, false);

        assertThat(target).hasBinaryContent(candidateBytes);
        assertThat(candidate).doesNotExist();
    }

    @Test
    void createNewRollsBackTargetWhenCandidateCleanupFails() throws Exception {
        byte[] candidateBytes = "candidate-bytes".getBytes(StandardCharsets.UTF_8);
        Path candidateDirectory = Files.createDirectory(tempDir.resolve("candidate-directory"));
        Path candidate = candidateDirectory.resolve("candidate.jmx");
        Path target = tempDir.resolve("target.jmx");
        Files.write(candidate, candidateBytes);
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(candidateDirectory);

        try {
            Files.setPosixFilePermissions(candidateDirectory,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

            assertThatThrownBy(() -> LocalJMeterFileCommitter.commit(candidate, target, false))
                    .isInstanceOf(LocalJMeterFileCommitter.CommitException.class);
        } finally {
            Files.setPosixFilePermissions(candidateDirectory, originalPermissions);
        }

        assertThat(candidate).hasBinaryContent(candidateBytes);
        assertThat(target).doesNotExist();
    }

    @Test
    void createNewFailsClosedWhenCandidateCleanupRollbackFails() throws Exception {
        byte[] candidateBytes = "candidate-bytes".getBytes(StandardCharsets.UTF_8);
        Path candidate = tempDir.resolve("rollback-candidate.jmx");
        Path target = tempDir.resolve("rollback-target.jmx");
        Files.write(candidate, candidateBytes);
        SecurityManager originalSecurityManager = System.getSecurityManager();

        try {
            System.setSecurityManager(new DeleteDenyingSecurityManager(candidate, target));

            assertThatThrownBy(() -> LocalJMeterFileCommitter.commit(candidate, target, false))
                    .isInstanceOfSatisfying(LocalJMeterFileCommitter.CommitException.class,
                            failure -> {
                                assertThat(failure.getCause()).isInstanceOf(java.io.IOException.class);
                                assertThat(failure.getCause().getSuppressed()).hasSize(1);
                            });
        } finally {
            System.setSecurityManager(originalSecurityManager);
        }

        assertThat(candidate).hasBinaryContent(candidateBytes);
        assertThat(target).hasBinaryContent(candidateBytes);
    }

    @Test
    void createNewDoesNotReplaceAnExistingTarget() throws Exception {
        byte[] candidateBytes = "candidate-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] targetBytes = "target-bytes".getBytes(StandardCharsets.UTF_8);
        Path candidate = tempDir.resolve("existing-candidate.jmx");
        Path target = tempDir.resolve("existing-target.jmx");
        Files.write(candidate, candidateBytes);
        Files.write(target, targetBytes);

        assertThatThrownBy(() -> LocalJMeterFileCommitter.commit(candidate, target, false))
                .isInstanceOf(LocalJMeterFileCommitter.CommitException.class)
                .hasCauseInstanceOf(java.nio.file.FileAlreadyExistsException.class);

        assertThat(Files.readAllBytes(candidate)).containsExactly(candidateBytes);
        assertThat(Files.readAllBytes(target)).containsExactly(targetBytes);
    }

    @Test
    void createNewRejectsCrossFilesystemOutputWithoutFallback() throws Exception {
        byte[] candidateBytes = "candidate-bytes".getBytes(StandardCharsets.UTF_8);
        Path candidate = tempDir.resolve("cross-filesystem-candidate.jmx");
        Path archive = tempDir.resolve("cross-filesystem-target.zip");
        Files.write(candidate, candidateBytes);

        try (FileSystem targetFileSystem = FileSystems.newFileSystem(
                URI.create("jar:" + archive.toUri()), Collections.singletonMap("create", "true"))) {
            Path target = targetFileSystem.getPath("/target.jmx");

            assertThatThrownBy(() -> LocalJMeterFileCommitter.commit(candidate, target, false))
                    .isInstanceOf(LocalJMeterFileCommitter.CommitException.class);

            assertThat(Files.readAllBytes(candidate)).containsExactly(candidateBytes);
            assertThat(target).doesNotExist();
        }
    }

    private void assertCrossFilesystemMoveFailsWithoutChangingEitherFile(
            boolean replaceExisting) throws Exception {
        byte[] candidateBytes = "candidate-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] targetBytes = "target-bytes".getBytes(StandardCharsets.UTF_8);
        Path candidate = tempDir.resolve("candidate-" + replaceExisting + ".jmx");
        Path archive = tempDir.resolve("target-" + replaceExisting + ".zip");
        Files.write(candidate, candidateBytes);

        try (FileSystem targetFileSystem = FileSystems.newFileSystem(
                URI.create("jar:" + archive.toUri()), Collections.singletonMap("create", "true"))) {
            Path target = targetFileSystem.getPath("/target.jmx");
            if (replaceExisting) {
                Files.write(target, targetBytes);
            }

            assertThatThrownBy(() -> LocalJMeterFileCommitter.commit(
                    candidate, target, replaceExisting))
                    .isInstanceOf(LocalJMeterFileCommitter.CommitException.class)
                    .hasCauseInstanceOf(AtomicMoveNotSupportedException.class);

            assertThat(Files.readAllBytes(candidate)).containsExactly(candidateBytes);
            if (replaceExisting) {
                assertThat(Files.readAllBytes(target)).containsExactly(targetBytes);
            } else {
                assertThat(target).doesNotExist();
            }
        }
    }

    private static final class DeleteDenyingSecurityManager extends SecurityManager {
        private final String candidate;
        private final String target;

        private DeleteDenyingSecurityManager(Path candidate, Path target) {
            this.candidate = candidate.toString();
            this.target = target.toString();
        }

        @Override
        public void checkDelete(String file) {
            if (candidate.equals(file) || target.equals(file)) {
                throw new SecurityException("delete denied for test");
            }
        }

        @Override
        public void checkPermission(Permission permission) {
        }
    }
}
