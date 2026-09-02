package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class LocalJMeterWorkerDryRunDirectories {
    private static final String PREFIX = "jmx-agent-worker-dry-run-";

    private LocalJMeterWorkerDryRunDirectories() {
    }

    static Path create() throws IOException {
        return Files.createTempDirectory(PREFIX);
    }

    static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(LocalJMeterWorkerDryRunDirectories::deleteQuietly);
        } catch (IOException ignored) {
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
