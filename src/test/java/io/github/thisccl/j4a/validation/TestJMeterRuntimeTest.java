package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.TestJMeterRuntime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestJMeterRuntimeTest {
    private static final String HOME_PROPERTY = "j4a.test.jmeterHome";

    @TempDir
    Path temporaryDirectory;

    private String originalHome;

    @AfterEach
    void restoreProperties() {
        restore(HOME_PROPERTY, originalHome);
    }

    @Test
    void homeRejectsExplicitUnsupportedVersion() throws Exception {
        Path home = createHome("5.7.0", "wrong-version");
        set(HOME_PROPERTY, home);

        assertThatThrownBy(TestJMeterRuntime::home)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=5.6.3")
                .hasMessageContaining("actual=5.7.0")
                .hasMessageContaining("source=" + HOME_PROPERTY)
                .hasMessageNotContaining(home.toString());
    }

    @Test
    void explicitPropertyTakesPrecedenceAndAcceptsExactVersion() throws Exception {
        Path current = createHome("5.6.3", "selected-current");
        set(HOME_PROPERTY, current);

        assertThat(TestJMeterRuntime.home()).isEqualTo(current.toAbsolutePath().normalize());
    }

    @Test
    void homeFailsClosedWhenCoreJarIsMissing() throws Exception {
        Path home = createBaseHome("missing-core");
        set(HOME_PROPERTY, home);

        assertThatThrownBy(TestJMeterRuntime::home)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=5.6.3")
                .hasMessageContaining("actual=missing-core-jar")
                .hasMessageContaining("source=" + HOME_PROPERTY)
                .hasMessageNotContaining(home.toString());
    }

    @Test
    void homeFailsClosedWhenCoreVersionIsMissing() throws Exception {
        Path home = createHome(null, "missing-version");
        set(HOME_PROPERTY, home);

        assertThatThrownBy(TestJMeterRuntime::home)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=5.6.3")
                .hasMessageContaining("actual=missing-version")
                .hasMessageContaining("source=" + HOME_PROPERTY)
                .hasMessageNotContaining(home.toString());
    }

    @Test
    void pathResolutionDoesNotTrustAMatchingDirectoryName() throws Exception {
        Path home = createHome("5.7.0", "apache-jmeter-5.6.3");
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable(), "-cp", System.getProperty("java.class.path"),
                TestJMeterRuntimeTest.class.getName(), "probe-current");
        builder.environment().remove(HOME_PROPERTY);
        builder.environment().remove("JMX_AGENT_TEST_JMETER_HOME");
        builder.environment().remove("JMETER_HOME");
        builder.environment().put("PATH", home.resolve("bin").toString());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        String output = read(process.getInputStream());
        int exitCode = process.waitFor();

        assertThat(exitCode).isEqualTo(2);
        assertThat(output)
                .contains("expected=5.6.3", "actual=5.7.0", "source=PATH")
                .doesNotContain(home.toString());
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1 || !"probe-current".equals(arguments[0])) {
            System.exit(3);
        }
        try {
            TestJMeterRuntime.home();
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage());
            System.exit(2);
        }
    }

    private Path createHome(String version, String name) throws IOException {
        Path home = createBaseHome(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (version != null) {
            manifest.getMainAttributes().putValue("Implementation-Version", version);
        }
        Path coreJar = home.resolve("lib").resolve("ext").resolve("ApacheJMeter_core.jar");
        Files.createDirectories(coreJar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(coreJar), manifest)) {
        }
        return home;
    }

    private Path createBaseHome(String name) throws IOException {
        Path home = temporaryDirectory.resolve(name);
        Path properties = home.resolve("bin").resolve("jmeter.properties");
        Files.createDirectories(properties.getParent());
        Files.write(properties, "# synthetic test runtime\n".getBytes(StandardCharsets.UTF_8));
        return home;
    }

    private void set(String property, Path value) {
        originalHome = System.getProperty(property);
        System.setProperty(property, value.toString());
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return java.nio.file.Paths.get(System.getProperty("java.home"), "bin", executable).toString();
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
}
