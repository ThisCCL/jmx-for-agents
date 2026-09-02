package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;

class LocalJMeterWorkerFilesystemErrorTest {
    @Test
    void accessDeniedDuringApplyIsInfrastructureRatherThanSemanticLoad() {
        java.nio.file.AccessDeniedException failure =
                new java.nio.file.AccessDeniedException("jmx-agent-worker-candidate-redacted.jmx");

        assertThat(LocalJMeterWorkerJmx.errorCode(failure)).isEqualTo("FILESYSTEM_WRITE_ERROR");
        assertThat(LocalJMeterWorkerJmx.category("FILESYSTEM_WRITE_ERROR", "applyPatch"))
                .isEqualTo("filesystem");
    }
    @Test
    void missingSourceSnapshotMapsToTheEstablishedReadErrorContract() {
        NoSuchFileException cause = new NoSuchFileException("missing.jmx");

        String code = LocalJMeterWorkerJmx.errorCode(new java.io.IOException("snapshot failed", cause));

        assertThat(code).isEqualTo("JMX_READ_ERROR");
        assertThat(LocalJMeterWorkerJmx.category(code, "renderReadData")).isEqualTo("filesystem");
        assertThat(LocalJMeterWorkerJmx.suggestedAction(code, cause))
                .contains("check that the file exists");
    }

    @Test
    void parentFileCauseMapsToFilesystemThroughRawAndWrappedBoundaries() {
        FileAlreadyExistsException cause = new FileAlreadyExistsException("blocked-parent");

        for (Throwable failure : new Throwable[] {cause, new RuntimeException(cause)}) {
            String code = LocalJMeterWorkerJmx.errorCode(failure);

            assertThat(code).isEqualTo("FILESYSTEM_WRITE_ERROR");
            assertThat(LocalJMeterWorkerJmx.category(code, "initJmx")).isEqualTo("filesystem");
            assertThat(LocalJMeterWorkerJmx.suggestedAction(code, failure)).isEqualTo(
                    "check the output path and filesystem permissions, then retry the command.");
        }
    }

    @Test
    void unrelatedSemanticAndUnknownCategoryMappingsRemainTyped() {
        IllegalStateException semantic = new IllegalStateException("semantic failure");
        UnknownComponentCategoryException unknown =
                new UnknownComponentCategoryException("not-a-category");

        assertThat(LocalJMeterWorkerJmx.errorCode(semantic)).isEqualTo("SEMANTIC_LOAD_ERROR");
        assertThat(LocalJMeterWorkerJmx.category("SEMANTIC_LOAD_ERROR", "initJmx"))
                .isEqualTo("runtime");
        assertThat(LocalJMeterWorkerJmx.errorCode(unknown)).isEqualTo("USAGE_ERROR");
        assertThat(LocalJMeterWorkerJmx.category("USAGE_ERROR", "discoverComponents"))
                .isEqualTo("usage");
        assertThat(LocalJMeterWorkerJmx.semanticMessage(unknown))
                .isEqualTo("Unknown component category: not-a-category");
        assertThat(LocalJMeterWorkerJmx.suggestedAction("USAGE_ERROR", unknown))
                .isEqualTo(UnknownComponentCategoryException.SUGGESTED_ACTION);
    }

    @Test
    void usageRecoveryCopiesExactPropertyTypeAndValueShape() {
        String action = LocalJMeterWorkerJmx.suggestedAction(
                "USAGE_ERROR", new IllegalArgumentException("invalid property value"));

        assertThat(action).isEqualTo(
                "copy the exact property type and value shape from focused read or components details, "
                        + "then retry the command.");
        assertThat(action).doesNotContain("registered", "plugin", "Argument", "class");
    }

    @Test
    void rowsRecoveryUsesTargetBoundRowAssertionWithoutClassAuthorization() {
        String action = LocalJMeterWorkerJmx.suggestedAction(
                "ARGUMENT_ROW_TYPE_ERROR", new IllegalArgumentException("row shape mismatch"));

        assertThat(action).isEqualTo(
                "copy the emitted row_type and row fields from focused read or components details; "
                        + "row_type is a target-bound assertion, not class authorization, then retry the command.");
        assertThat(LocalJMeterWorkerJmx.category("ARGUMENT_ROW_TYPE_ERROR", "apply"))
                .isEqualTo("apply");
        assertThat(action).doesNotContain("plugin", "subclass", "loadable", "classloading");
    }
}
