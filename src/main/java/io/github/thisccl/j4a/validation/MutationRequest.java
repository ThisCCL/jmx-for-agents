package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.apply.ApplyPatch;
import java.nio.file.Path;
import java.util.Objects;

final class MutationRequest {
    private final Path source;
    private final Path jmeterHome;
    private final ApplyPatch patch;
    private final Path target;
    private final Path dryRunCandidateDirectory;
    private final boolean replaceExisting;
    private final SessionReferenceRegistry sessionRegistry;

    private MutationRequest(
            Path source,
            Path jmeterHome,
            ApplyPatch patch,
            Path target,
            Path dryRunCandidateDirectory,
            boolean replaceExisting,
            SessionReferenceRegistry sessionRegistry) {
        this.source = normalized(source, "source");
        this.jmeterHome = normalized(jmeterHome, "jmeterHome");
        this.patch = Objects.requireNonNull(patch, "patch");
        this.target = target == null ? null : normalized(target, "target");
        this.dryRunCandidateDirectory = dryRunCandidateDirectory == null
                ? null : normalized(dryRunCandidateDirectory, "dryRunCandidateDirectory");
        this.replaceExisting = replaceExisting;
        this.sessionRegistry = sessionRegistry;
        if ((this.target == null) == (this.dryRunCandidateDirectory == null)) {
            throw new IllegalArgumentException(
                    "Exactly one of target and dryRunCandidateDirectory is required");
        }
    }

    static MutationRequest dryRun(
            Path source,
            Path jmeterHome,
            ApplyPatch patch,
            Path candidateDirectory,
            SessionReferenceRegistry sessionRegistry) {
        return new MutationRequest(
                source, jmeterHome, patch, null, candidateDirectory, false, sessionRegistry);
    }

    static MutationRequest commit(
            Path source,
            Path jmeterHome,
            ApplyPatch patch,
            Path target,
            boolean replaceExisting,
            SessionReferenceRegistry sessionRegistry) {
        return new MutationRequest(
                source, jmeterHome, patch, target, null, replaceExisting, sessionRegistry);
    }

    Path source() {
        return source;
    }

    Path jmeterHome() {
        return jmeterHome;
    }

    ApplyPatch patch() {
        return patch;
    }

    Path target() {
        return target;
    }

    Path dryRunCandidateDirectory() {
        return dryRunCandidateDirectory;
    }

    boolean replaceExisting() {
        return replaceExisting;
    }

    SessionReferenceRegistry sessionRegistry() {
        return sessionRegistry;
    }

    boolean dryRun() {
        return target == null;
    }

    private static Path normalized(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
