package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComponentCategoryCursorCrossProcessTest {
    @TempDir
    Path tempDir;

    @Test
    void sameHomeGeneratesDeterministicCursorAndDifferentHomeRejectsIt() throws Exception {
        Path firstHome = Files.createDirectories(tempDir.resolve("first-home"));
        Path otherHome = Files.createDirectories(tempDir.resolve("other-home"));

        ProcessResult generated = run(firstHome, "encode");
        ProcessResult repeated = run(firstHome, "encode");
        ProcessResult accepted = run(firstHome, "require", generated.stdout);
        ProcessResult rejected = run(otherHome, "require", generated.stdout);

        assertThat(generated.exitCode).isZero();
        assertThat(repeated.exitCode).isZero();
        assertThat(repeated.stdout).isEqualTo(generated.stdout).startsWith("v4.");
        assertThat(accepted.exitCode).isZero();
        assertThat(accepted.stdout).isEqualTo(ComponentCategoryCursor.componentDigest("a.Component"));
        assertThat(rejected.exitCode).isEqualTo(2);
        assertThat(rejected.stdout).isEmpty();
        assertThat(rejected.stderr).isEqualTo("Invalid components cursor")
                .doesNotContain(firstHome.toString(), otherHome.toString(), ".j4a");
    }

    @Test
    void malformedAndBlockedStateFailWithoutPayloadOrPathLeak() throws Exception {
        Path malformedHome = Files.createDirectories(tempDir.resolve("malformed-home"));
        Path state = Files.createDirectories(malformedHome.resolve(".j4a").resolve("state"));
        Files.write(state.resolve("components-cursor-signing.key"), new byte[33]);
        Path blockedHome = Files.createDirectories(tempDir.resolve("blocked-home"));
        Files.write(blockedHome.resolve(".j4a"), Arrays.asList("blocked"));

        assertUnavailable(run(malformedHome, "encode"), malformedHome);
        assertUnavailable(run(blockedHome, "encode"), blockedHome);
    }

    @Test
    void v4CursorAndLegacyFailureMatrixCrossJvm() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("matrix-home"));
        ProcessResult generated = run(home, "encode");
        String cursor = generated.stdout;
        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");
        ProcessResult accepted = run(home, "require", cursor);
        ProcessResult legacy = run(home, "require", "v2." + cursor.substring(cursor.indexOf('.') + 1));
        ProcessResult corrupted = run(home, "require", tampered);
        ProcessResult wrongProjection = run(home, "require-wrong-projection", cursor);

        assertThat(generated.exitCode).isZero();
        assertThat(cursor).startsWith("v4.");
        assertThat(accepted.exitCode).as("cross-JVM v4 continuation").isZero();
        assertThat(accepted.stdout).isEqualTo(ComponentCategoryCursor.componentDigest("a.Component"));
        assertInvalid(legacy);
        assertInvalid(corrupted);
        assertThat(wrongProjection.exitCode).isEqualTo(2);
        assertThat(wrongProjection.stdout).isEmpty();
        assertThat(wrongProjection.stderr).isEqualTo(
                "Components cursor does not match the selected runtime, category, projection, limit, or max-bytes");
    }

    private static void assertInvalid(ProcessResult result) {
        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.stdout).isEmpty();
        assertThat(result.stderr).isEqualTo("Invalid components cursor");
    }

    private static void assertUnavailable(ProcessResult result, Path home) {
        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.stdout).isEmpty();
        assertThat(result.stderr).isEqualTo("Components cursor signing key is unavailable")
                .doesNotContain(home.toString(), ".j4a", "components-cursor-signing.key",
                        "category:", "components:", "next_cursor:");
    }

    private static ProcessResult run(Path userHome, String... args) throws Exception {
        List<String> command = new ArrayList<String>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString());
        command.add("-Duser.home=" + userHome);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ComponentCategoryCursorProcessProbe.class.getName());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).start();
        boolean exited = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new AssertionError("cursor probe timed out");
        }
        return new ProcessResult(
                process.exitValue(), read(process.getInputStream()), read(process.getErrorStream()));
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean windows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static final class ProcessResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private ProcessResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
