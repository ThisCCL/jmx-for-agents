package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalJMeterWorkerPatchSnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void requestRetainsBoundedSnapshotWhenSourceIsReplacedOrGrows() throws Exception {
        Path patch = tempDir.resolve("patch.yaml");
        Files.write(patch, "changes: []\n".getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.applyPatch(
                tempDir.resolve("input.jmx"), tempDir, patch, tempDir.resolve("out.jmx"));
        Files.write(patch, repeated('x', 4 * 1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerRequest decoded = LocalJMeterWorkerRequest.fromJsonLine(request.toJsonLine());
        assertThat(decoded.patchYaml()).isEqualTo("changes: []\n");
        assertThat(decoded.patchPath()).isEqualTo(patch.toAbsolutePath().normalize().toString());
    }

    @Test
    void commandAndWorkerProtocolBothRejectPatchAboveFourMiB() throws Exception {
        String oversized = repeated('x', 4 * 1024 * 1024 + 1);
        Path patch = tempDir.resolve("oversized.yaml");
        Files.write(patch, oversized.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> LocalJMeterWorkerRequest.applyPatch(
                tempDir.resolve("input.jmx"), tempDir, patch, tempDir.resolve("out.jmx")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PATCH_INPUT_TOO_LARGE");

        String maliciousRequest = LocalJMeterWorkerJson.object()
                .field("operation", "applyPatch")
                .field("patchYaml", oversized)
                .build();
        assertThatThrownBy(() -> LocalJMeterWorkerRequest.fromJsonLine(maliciousRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PATCH_INPUT_TOO_LARGE");
    }

    private static String repeated(char value, int count) {
        char[] characters = new char[count];
        java.util.Arrays.fill(characters, value);
        return new String(characters);
    }
}
