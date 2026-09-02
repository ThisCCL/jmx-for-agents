package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalPropertyGraphRuntimeContextTest {
    @TempDir
    Path tempDir;
    private LocalPropertyGraphRuntimeContextTestState runtimeContextState;

    @BeforeEach
    void isolateRuntimeContext() throws Exception {
        runtimeContextState = LocalPropertyGraphRuntimeContextTestState.captureAndClear();
    }

    @AfterEach
    void restoreRuntimeContext() throws Exception {
        if (runtimeContextState != null) {
            runtimeContextState.restore();
        }
    }

    @Test
    void workerPreflightFingerprintsOnceWhileAnotherHomeGetsFreshBytes() throws Exception {
        Path brokenHome = tempDir.resolve("broken");
        Files.createDirectories(brokenHome.resolve("lib").resolve("ext"));
        Files.createSymbolicLink(
                brokenHome.resolve("lib").resolve("missing.jar"),
                brokenHome.resolve("missing-target.jar"));

        assertThatThrownBy(() -> LocalPropertyGraphRuntimeContext.prewarm(brokenHome))
                .isInstanceOf(LocalPropertyGraphRuntimeContext.InitializationException.class)
                .hasMessageContaining("Unable to fingerprint local JMeter runtime once");

        Path firstHome = home("first", "first-version");
        Path firstLibrary = firstHome.resolve("lib").resolve("runtime.jar");

        RuntimeContext preflight = LocalPropertyGraphRuntimeContext.prewarm(firstHome);
        String preflightHash = preflight.fingerprint().librarySha256().get("lib/runtime.jar");
        Files.write(firstLibrary, "changed-after-preflight".getBytes(StandardCharsets.UTF_8));

        RuntimeContext reused = LocalPropertyGraphRuntimeContext.selected(firstHome);
        RuntimeContext repeatedPreflight = LocalPropertyGraphRuntimeContext.prewarm(firstHome);
        RuntimeContext anotherHome = LocalPropertyGraphRuntimeContext.selected(
                home("second", "changed-after-preflight"));

        assertThat(reused).isSameAs(preflight);
        assertThat(repeatedPreflight).isSameAs(preflight);
        assertThat(reused.fingerprint().librarySha256().get("lib/runtime.jar"))
                .isEqualTo(preflightHash);
        assertThat(anotherHome.fingerprint().jmeterHome())
                .isEqualTo(tempDir.resolve("second").toRealPath().toString());
        assertThat(anotherHome.fingerprint().librarySha256().get("lib/runtime.jar"))
                .isNotEqualTo(preflightHash);
    }

    private Path home(String name, String libraryContents) throws Exception {
        Path home = tempDir.resolve(name);
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        Files.write(home.resolve("lib").resolve("runtime.jar"),
                libraryContents.getBytes(StandardCharsets.UTF_8));
        return home;
    }
}
