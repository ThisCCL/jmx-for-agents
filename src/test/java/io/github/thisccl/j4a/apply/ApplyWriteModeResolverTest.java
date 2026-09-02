package io.github.thisccl.j4a.apply;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyWriteModeResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void normalizedSpellingsOfTheSamePathAreRejected() throws Exception {
        Path directory = Files.createDirectory(tempDir.resolve("normalized"));
        Path input = Files.write(directory.resolve("source.jmx"), "source".getBytes(StandardCharsets.UTF_8));
        Path normalizedAlias = directory.resolve("child").resolve("..").resolve(input.getFileName());

        assertThatThrownBy(() -> ApplyWriteModeResolver.requireDistinctCopyTarget(input, normalizedAlias))
                .isInstanceOf(ApplyWriteModeResolver.UsageException.class)
                .hasMessageContaining("--out must differ");
    }

    @Test
    void symlinkAliasesOfTheSameExistingFileAreRejected() throws Exception {
        Path input = Files.write(tempDir.resolve("existing.jmx"), "source".getBytes(StandardCharsets.UTF_8));
        Path alias = Files.createSymbolicLink(tempDir.resolve("existing-alias.jmx"), input);

        assertThatThrownBy(() -> ApplyWriteModeResolver.requireDistinctCopyTarget(input, alias))
                .isInstanceOf(ApplyWriteModeResolver.UsageException.class)
                .hasMessageContaining("--out must differ");
    }

    @Test
    void equivalentMissingTargetsThroughSymlinkParentsAreRejected() throws Exception {
        Path realDirectory = Files.createDirectory(tempDir.resolve("real"));
        Path aliasDirectory = Files.createSymbolicLink(tempDir.resolve("alias"), realDirectory);
        Path first = realDirectory.resolve("future").resolve("plan.jmx");
        Path second = aliasDirectory.resolve("future").resolve("plan.jmx");

        assertThatThrownBy(() -> ApplyWriteModeResolver.requireDistinctCopyTarget(first, second))
                .isInstanceOf(ApplyWriteModeResolver.UsageException.class)
                .hasMessageContaining("--out must differ");
    }

    @Test
    void distinctMissingSiblingSuffixesRemainDistinct() throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("siblings"));
        Path first = parent.resolve("first.jmx");
        Path second = parent.resolve("second.jmx");

        assertThatCode(() -> ApplyWriteModeResolver.requireDistinctCopyTarget(first, second))
                .doesNotThrowAnyException();
    }
}
