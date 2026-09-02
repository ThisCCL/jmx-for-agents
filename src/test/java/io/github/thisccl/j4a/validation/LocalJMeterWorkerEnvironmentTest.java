package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalJMeterWorkerEnvironmentTest {
    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();

    @TempDir
    Path tempDir;

    @Test
    void workerCommandUsesWildcardClasspathToAvoidWindowsCreateProcessLimit() throws Exception {
        Path home = tempDir.resolve("jmeter-home");
        Path lib = Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(lib.resolve("ext"));
        Path core = Paths.get(JMeterUtils.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.copy(core, lib.resolve("ApacheJMeter_core-local.jar"));
        for (int index = 0; index < 300; index++) {
            Files.write(lib.resolve(String.format("z-long-classpath-entry-%03d.jar", index)), new byte[0]);
        }

        LocalJMeterWorkerClient client = new LocalJMeterWorkerClient();
        List<String> command = client.command(LocalJMeterWorkerRequest.componentDetails(home, "http.request"), "worker-id");
        String classpath = command.get(command.indexOf("-cp") + 1);

        assertThat(classpath).contains(lib.toString() + java.io.File.separator + "*");
        assertThat(classpath).doesNotContain("z-long-classpath-entry-000.jar");
    }

    @Test
    void localValidatorCharacterizesMissingCoreJarAsLocalEnvironmentFailure() throws IOException {
        Path home = fixtures.root().resolve("characterization-home");
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        Files.write(home.resolve("bin").resolve("jmeter.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("saveservice.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("upgrade.properties"), new byte[0]);

        assertThatThrownBy(() -> new LocalJmxValidator().validate(fixtures.pluginBackedJmx(), home))
                .isInstanceOf(LocalJMeterEnvironmentException.class)
                .hasMessageContaining("org/apache/jmeter/util/JMeterUtils.class");
    }

    @Test
    void markerCoreJarCannotMaskMissingInstallationClasspath() throws IOException {
        Path home = fixtures.markerOnlyCoreHome();

        assertThatThrownBy(() -> LocalJMeterClasspath.localFirstClasspath(home))
                .isInstanceOf(LocalJMeterEnvironmentException.class)
                .hasMessageContaining("org/apache/jmeter/util/JMeterUtils.class");
    }

    @Test
    void localClasspathHonorsJMeterPropertyDependencyPaths() throws IOException {
        Path home = fixtures.localHome();

        List<String> localJars = LocalJMeterClasspath.localJars(home).stream()
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toList());

        assertThat(localJars).contains(
                "user-classpath-dependency.jar",
                "plugin-dependency.jar");
    }
}
