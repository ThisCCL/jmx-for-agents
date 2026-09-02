package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class LocalJMeterFileCommitter {
    private LocalJMeterFileCommitter() {
    }

    static void commit(Path candidate, Path target, boolean replaceExisting) throws CommitException {
        try {
            if (replaceExisting) {
                Files.move(candidate, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createLink(target, candidate);
                discardLinkedCandidate(candidate, target);
            }
        } catch (FileAlreadyExistsException exception) {
            throw CommitException.targetExists(target, exception);
        } catch (IOException exception) {
            throw CommitException.writeFailed(target, exception);
        } catch (UnsupportedOperationException | IllegalArgumentException | SecurityException exception) {
            throw CommitException.writeFailed(target, new IOException(exception.getMessage(), exception));
        }
    }

    private static void discardLinkedCandidate(Path candidate, Path target) throws IOException {
        try {
            Files.delete(candidate);
        } catch (IOException | SecurityException cleanupFailure) {
            IOException failure = new IOException(
                    "Could not remove temporary output candidate after linking target: " + candidate,
                    cleanupFailure);
            try {
                Files.delete(target);
            } catch (IOException | SecurityException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    static final class CommitException extends IOException {
        private static final String ERROR_CODE = "FILESYSTEM_WRITE_ERROR";
        private static final String CATEGORY = "filesystem";

        private final String suggestedAction;

        private CommitException(String message, String suggestedAction, IOException cause) {
            super(message, cause);
            this.suggestedAction = suggestedAction;
        }

        private static CommitException targetExists(Path target, IOException cause) {
            return new CommitException(
                    "Local apply output already exists: " + target,
                    "choose a different --out path or rerun with --force-out to replace the existing output.",
                    cause);
        }

        private static CommitException writeFailed(Path target, IOException cause) {
            return new CommitException(
                    "Local apply could not write output: " + target + " (" + cause.getMessage() + ")",
                    "check the output path and filesystem permissions, then retry apply.",
                    cause);
        }

        String errorCode() {
            return ERROR_CODE;
        }

        String category() {
            return CATEGORY;
        }

        String suggestedAction() {
            return suggestedAction;
        }
    }
}
