package io.github.thisccl.j4a.filesystem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class CanonicalPath {
    private CanonicalPath() {
    }

    public static Path resolve(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path normalized = path.toAbsolutePath().normalize();
        Path existing = normalized;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return normalized;
        }
        return existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
    }
}
