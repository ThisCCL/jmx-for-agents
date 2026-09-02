package io.github.thisccl.j4a.apply;

import io.github.thisccl.j4a.filesystem.CanonicalPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ApplyWriteModeResolver {
    private ApplyWriteModeResolver() {
    }

    public static Resolution resolve(Path input, boolean dryRun, boolean override, String out, boolean forceOut) {
        boolean hasOut = out != null;
        if (hasOut && out.trim().isEmpty()) {
            throw usage("--out must not be blank.",
                    "pass a non-blank --out <file>, or use --override to edit the input file in place.");
        }
        if (dryRun) {
            if (override) {
                throw usage("--dry-run cannot be combined with --override.",
                        "remove --override to preview without writing, or remove --dry-run to edit in place.");
            }
            if (forceOut) {
                throw usage("--dry-run cannot be combined with --force-out.",
                        "remove --force-out; dry-run never writes and ignores any --out path.");
            }
            return new Resolution(Mode.DRY_RUN, null, input, false);
        }
        if (override) {
            if (hasOut) {
                throw usage("--out cannot be combined with --override.",
                        "remove --out to edit the input file in place, or remove --override to write a copy.");
            }
            if (forceOut) {
                throw usage("--force-out cannot be combined with --override.",
                        "remove --force-out; --override already selects the input file for in-place writing.");
            }
            return new Resolution(Mode.IN_PLACE, input, input, true);
        }
        if (!hasOut) {
            if (forceOut) {
                throw usage("--force-out requires --out.",
                        "pass --out <file> with --force-out, or remove --force-out and use --override.");
            }
            throw usage("--out or --override is required.",
                    "pass --out <file> to write a copy, or pass --override to edit the input file in place.");
        }
        Path target = Paths.get(out);
        requireDistinctCopyTarget(input, target);
        return new Resolution(Mode.COPY, target, input, forceOut);
    }

    public static void requireDistinctCopyTarget(Path input, Path target) {
        if (samePath(input, target)) {
            throw usage("--out must differ from the input path; --force-out cannot authorize same-path writes.",
                    "choose a different --out path, or pass --override without --out/--force-out to edit the input file in place.");
        }
    }

    private static boolean samePath(Path first, Path second) {
        Path normalizedFirst = first.toAbsolutePath().normalize();
        Path normalizedSecond = second.toAbsolutePath().normalize();
        if (normalizedFirst.equals(normalizedSecond)) {
            return true;
        }
        try {
            if (Files.exists(normalizedFirst) && Files.exists(normalizedSecond)) {
                return Files.isSameFile(normalizedFirst, normalizedSecond);
            }
            return CanonicalPath.resolve(normalizedFirst).equals(CanonicalPath.resolve(normalizedSecond));
        } catch (IOException exception) {
            throw usage("Unable to safely compare the input and output paths: " + exception.getMessage(),
                    "check that both paths and their parent directories are accessible, then rerun apply.");
        }
    }

    private static UsageException usage(String message, String suggestedNextAction) {
        return new UsageException(message, suggestedNextAction);
    }

    public enum Mode {
        DRY_RUN,
        IN_PLACE,
        COPY
    }

    public static final class Resolution {
        private final Mode mode;
        private final Path target;
        private final Path input;
        private final boolean replaceExisting;

        private Resolution(Mode mode, Path target, Path input, boolean replaceExisting) {
            this.mode = mode;
            this.target = target;
            this.input = input;
            this.replaceExisting = replaceExisting;
        }

        public Mode mode() {
            return mode;
        }

        public Path target() {
            return target;
        }

        public AuthorizedCommit authorizeCommit() {
            if (mode == Mode.COPY) {
                requireDistinctCopyTarget(input, target);
            }
            try {
                Path boundTarget = CanonicalPath.resolve(target.toAbsolutePath().normalize());
                return new AuthorizedCommit(boundTarget, input, mode, replaceExisting);
            } catch (IOException exception) {
                throw usage("Unable to safely authorize the output path: " + exception.getMessage(),
                        "check that the output parent directory is accessible, then rerun apply.");
            }
        }
    }

    public static final class AuthorizedCommit {
        private final Path target;
        private final Path input;
        private final Mode mode;
        private final boolean replaceExisting;

        private AuthorizedCommit(Path target, Path input, Mode mode, boolean replaceExisting) {
            this.target = target;
            this.input = input;
            this.mode = mode;
            this.replaceExisting = replaceExisting;
        }

        public Path target() {
            return target;
        }

        public boolean replaceExisting() {
            return replaceExisting;
        }

        public void recheckSameFile() {
            if (mode == Mode.COPY) {
                requireDistinctCopyTarget(input, target);
            }
        }
    }

    public static final class UsageException extends IllegalArgumentException {
        private final String suggestedNextAction;

        private UsageException(String message, String suggestedNextAction) {
            super(message);
            this.suggestedNextAction = suggestedNextAction;
        }

        public String suggestedNextAction() {
            return suggestedNextAction;
        }
    }
}
