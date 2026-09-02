package io.github.thisccl.j4a.cli;


import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainStartupNoiseTest {
    @TempDir
    Path tempDir;

    @Test
    void readCommandDoesNotEmitStartupNoiseFromShadowJar() throws IOException, InterruptedException {
        ProcessResult result = runMainInShadowJar("read", fixture("simple-http.jmx").toString(),
                "--jmeter-home", io.github.thisccl.j4a.TestJMeterRuntime.home().toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("root:", "Synthetic Thread Group");
        assertThat(result.stderr()).as("successful read must not emit startup noise to stderr").isEmpty();
    }

    @Test
    void shadowJarMetadataSupportsQuietJMeterStartup() throws IOException {
        Path shadowJar = shadowJar();

        try (JarFile jarFile = new JarFile(shadowJar.toFile())) {
            Manifest manifest = jarFile.getManifest();
            assertThat(manifest.getMainAttributes().getValue("Multi-Release")).isEqualTo("true");
            assertThat(jarFile.getEntry("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat"))
                    .isNotNull();
        }
    }

    private static ProcessResult runMainInShadowJar(String... args) throws IOException, InterruptedException {
        List<String> command = Stream.concat(
                        Stream.of(javaBinary(), "-jar", shadowJar().toString()),
                        Stream.of(args))
                .collect(Collectors.toList());
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().remove("JAVA_TOOL_OPTIONS");
        Process process = processBuilder.start();
        boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new AssertionError("Fresh JVM command timed out: " + command);
        }
        return new ProcessResult(
                process.exitValue(),
                new String(readAllBytes(process.getInputStream()), StandardCharsets.UTF_8),
                new String(readAllBytes(process.getErrorStream()), StandardCharsets.UTF_8));
    }

    private static byte[] readAllBytes(java.io.InputStream inputStream) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String javaBinary() {
        return Paths.get(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(MainStartupNoiseTest.class.getResource("/fixtures/" + name).toURI());
        } catch (Exception exception) {
            throw new IllegalStateException("Fixture URI is invalid: " + name, exception);
        }
    }

    private static Path shadowJar() {
        Path libs = Paths.get("build", "libs");
        try (Stream<Path> jars = Files.list(libs)) {
            return jars.filter(path -> path.getFileName().toString().endsWith("-all.jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No shadow jar found under " + libs.toAbsolutePath()));
        } catch (IOException exception) {
            throw new AssertionError("Could not inspect shadow jar under " + libs.toAbsolutePath(), exception);
        }
    }

    private static String setPatch() {
        return "changes:\n  - set:\n      ref: jmx_330976848c8e\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n        - property: HTTPSampler\\.domain\n          value: api.example.test\n          type: string\n";
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



        private int exitCode() {


            return exitCode;


        }



        private String stdout() {


            return stdout;


        }



        private String stderr() {


            return stderr;


        }


    }
}
