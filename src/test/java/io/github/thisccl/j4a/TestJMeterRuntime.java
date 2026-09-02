package io.github.thisccl.j4a;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class TestJMeterRuntime {
    private static final String CURRENT_VERSION = "5.6.3";

    private TestJMeterRuntime() {
    }

    public static Path home() {
        return configuredHome("j4a.test.jmeterHome", "JMX_AGENT_TEST_JMETER_HOME", CURRENT_VERSION);
    }

    private static Path configuredHome(String property, String environment, String expectedVersion) {
        String configured = System.getProperty(property);
        if (!isBlank(configured)) {
            return requireHome(Paths.get(configured), expectedVersion, property);
        }
        configured = System.getenv(environment);
        if (!isBlank(configured)) {
            return requireHome(Paths.get(configured), expectedVersion, environment);
        }
        configured = System.getenv("JMETER_HOME");
        if (!isBlank(configured)) {
            return requireHome(Paths.get(configured), expectedVersion, "JMETER_HOME");
        }
        String path = System.getenv("PATH");
        String actualVersion = "not-found";
        if (!isBlank(path)) {
            for (String entry : path.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
                Path bin = Paths.get(entry).toAbsolutePath().normalize();
                Path home = bin.getFileName() != null && "bin".equals(bin.getFileName().toString())
                        ? bin.getParent() : null;
                if (home != null && Files.isRegularFile(home.resolve("bin").resolve("jmeter.properties"))) {
                    actualVersion = implementationVersion(home);
                    if (expectedVersion.equals(actualVersion)) {
                        return home;
                    }
                }
            }
        }
        throw invalidVersion(expectedVersion, actualVersion, "PATH");
    }

    private static Path requireHome(Path home, String expectedVersion, String source) {
        Path normalized = home.toAbsolutePath().normalize();
        String actualVersion = implementationVersion(normalized);
        if (!expectedVersion.equals(actualVersion)) {
            throw invalidVersion(expectedVersion, actualVersion, source);
        }
        return normalized;
    }

    private static String implementationVersion(Path home) {
        if (!Files.isRegularFile(home.resolve("bin").resolve("jmeter.properties"))) {
            return "missing-jmeter-properties";
        }
        Path coreJar = home.resolve("lib").resolve("ext").resolve("ApacheJMeter_core.jar");
        if (!Files.isRegularFile(coreJar)) {
            return "missing-core-jar";
        }
        try (JarFile archive = new JarFile(coreJar.toFile())) {
            Manifest manifest = archive.getManifest();
            if (manifest == null) {
                return "missing-version";
            }
            Attributes attributes = manifest.getMainAttributes();
            String version = attributes.getValue("Implementation-Version");
            return isBlank(version) ? "missing-version" : version.trim();
        } catch (java.io.IOException exception) {
            return "unreadable-core-jar";
        }
    }

    private static IllegalStateException invalidVersion(String expected, String actual, String source) {
        return new IllegalStateException(
                "Invalid test JMeter runtime expected=" + expected + " actual=" + actual + " source=" + source);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
