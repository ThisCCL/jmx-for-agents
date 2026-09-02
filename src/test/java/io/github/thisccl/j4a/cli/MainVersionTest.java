package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.VersionInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class MainVersionTest {
    @Test
    void soleVersionArgumentPrintsExactEmbeddedVersion() {
        CliResult result = runMain("--version");

        assertThat(result.exitCode).isZero();
        assertThat(result.stdout).isEqualTo(VersionInfo.version() + "\n");
        assertThat(result.stderr).isEmpty();
    }

    @Test
    void versionRejectsTrailingArgumentsAsUsage() {
        CliResult result = runMain("--version", "read");

        assertThat(result.exitCode).isEqualTo(2);
        assertThat(result.stdout).isEmpty();
        assertThat(result.stderr).contains("USAGE_ERROR", "--version", "additional arguments");
    }

    @Test
    void directPackagedJarPrintsExactEmbeddedVersion() throws Exception {
        Path jar = Paths.get(System.getProperty("user.dir"), "build", "libs",
                "j4a-" + VersionInfo.version() + "-all.jar");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-jar", jar.toString(), "--version").start();
        String stdout = readUtf8(process.getInputStream());
        String stderr = readUtf8(process.getErrorStream());

        assertThat(process.waitFor()).isZero();
        assertThat(stdout).isEqualTo(VersionInfo.version() + "\n");
        assertThat(stderr).isEmpty();
    }

    private static CliResult runMain(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));
            int exitCode = Main.run(args);
            return new CliResult(
                    exitCode,
                    new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                    new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class CliResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CliResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
