package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.filesystem.CanonicalPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

final class DocumentIdentity {
    private final Path jmeterHome;
    private final Path documentPath;

    private DocumentIdentity(Path jmeterHome, Path documentPath) {
        this.jmeterHome = jmeterHome;
        this.documentPath = documentPath;
    }

    static DocumentIdentity of(Path jmeterHome, Path documentPath) throws IOException {
        Objects.requireNonNull(jmeterHome, "jmeterHome");
        Objects.requireNonNull(documentPath, "documentPath");
        Path realHome = jmeterHome.toRealPath();
        if (!Files.isDirectory(realHome)) {
            throw new IOException("JMeter home is not a directory: " + realHome);
        }
        return new DocumentIdentity(realHome, CanonicalPath.resolve(documentPath));
    }

    Path jmeterHome() {
        return jmeterHome;
    }

    Path documentPath() {
        return documentPath;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DocumentIdentity)) {
            return false;
        }
        DocumentIdentity that = (DocumentIdentity) other;
        return jmeterHome.equals(that.jmeterHome) && documentPath.equals(that.documentPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jmeterHome, documentPath);
    }
}
