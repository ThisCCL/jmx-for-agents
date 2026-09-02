package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionDocumentIdentityTest {
    @TempDir
    Path tempDir;

    @Test
    void equivalentHomeAndMissingDocumentAliasesHaveOneIdentity() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("jmeter-home"));
        Path homeAlias = Files.createSymbolicLink(tempDir.resolve("jmeter-home-alias"), home);
        Path documentDirectory = Files.createDirectory(tempDir.resolve("documents"));
        Path documentAlias = Files.createSymbolicLink(tempDir.resolve("documents-alias"), documentDirectory);

        DocumentIdentity direct = DocumentIdentity.of(
                home, documentDirectory.resolve("future").resolve("plan.jmx"));
        DocumentIdentity aliased = DocumentIdentity.of(
                homeAlias,
                documentAlias.resolve("discarded").resolve("..").resolve("future").resolve("plan.jmx"));

        assertThat(aliased).isEqualTo(direct);
        assertThat(aliased.hashCode()).isEqualTo(direct.hashCode());
        assertThat(direct.jmeterHome()).isEqualTo(home.toRealPath());
        assertThat(direct.documentPath())
                .isEqualTo(documentDirectory.toRealPath().resolve("future").resolve("plan.jmx"));
    }

    @Test
    void existingDocumentSymlinkAliasesHaveOneIdentity() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("existing-home"));
        Path document = Files.write(
                tempDir.resolve("existing.jmx"), "source".getBytes(StandardCharsets.UTF_8));
        Path alias = Files.createSymbolicLink(tempDir.resolve("existing-alias.jmx"), document);

        assertThat(DocumentIdentity.of(home, alias))
                .isEqualTo(DocumentIdentity.of(home, document));
    }

    @Test
    void distinctMissingSiblingSuffixesRemainDistinct() throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("sibling-home"));
        Path directory = Files.createDirectory(tempDir.resolve("sibling-documents"));

        DocumentIdentity first = DocumentIdentity.of(home, directory.resolve("first.jmx"));
        DocumentIdentity second = DocumentIdentity.of(home, directory.resolve("second.jmx"));

        assertThat(first).isNotEqualTo(second);
        assertThat(first.documentPath()).isNotEqualTo(second.documentPath());
    }

    @Test
    void theSameDocumentUnderDifferentHomesHasDifferentIdentity() throws Exception {
        Path firstHome = Files.createDirectory(tempDir.resolve("first-home"));
        Path secondHome = Files.createDirectory(tempDir.resolve("second-home"));
        Path document = tempDir.resolve("shared.jmx");

        assertThat(DocumentIdentity.of(firstHome, document))
                .isNotEqualTo(DocumentIdentity.of(secondHome, document));
    }

    @Test
    void missingOrNonDirectoryHomesAreRejected() throws Exception {
        Path document = tempDir.resolve("plan.jmx");
        Path regularFile = Files.write(
                tempDir.resolve("not-a-home"), "file".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> DocumentIdentity.of(tempDir.resolve("missing-home"), document))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> DocumentIdentity.of(regularFile, document))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("directory");
    }
}
