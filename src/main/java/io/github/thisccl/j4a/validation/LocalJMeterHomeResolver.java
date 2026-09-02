package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LocalJMeterHomeResolver {
    public Optional<Path> resolve(String explicitHome) {
        return resolve(explicitHome, System.getenv());
    }

    public Optional<Path> resolve(String explicitHome, Map<String, String> environment) {
        if (hasText(explicitHome)) {
            return Optional.of(validate(path(explicitHome, "--jmeter-home"), "--jmeter-home"));
        }
        String agentHome = environment.get("JMX_AGENT_JMETER_HOME");
        if (hasText(agentHome)) {
            return Optional.of(validate(path(agentHome, "JMX_AGENT_JMETER_HOME"), "JMX_AGENT_JMETER_HOME"));
        }
        String jmeterHome = environment.get("JMETER_HOME");
        if (hasText(jmeterHome)) {
            return Optional.of(validate(path(jmeterHome, "JMETER_HOME"), "JMETER_HOME"));
        }
        return Optional.empty();
    }

    private static Path path(String value, String source) {
        try {
            return Paths.get(value);
        } catch (InvalidPathException exception) {
            throw new LocalJMeterEnvironmentException(
                    "Invalid local JMeter home from " + source + ": " + exception.getInput()
                            + ". The path is malformed: " + exception.getReason(),
                    exception);
        }
    }

    private static Path validate(Path home, String source) {
        Path normalizedHome = home.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();
        requireDirectory(normalizedHome.resolve("bin"), missing);
        requireDirectory(normalizedHome.resolve("lib"), missing);
        requireDirectory(normalizedHome.resolve("lib").resolve("ext"), missing);
        requireFile(normalizedHome.resolve("bin").resolve("jmeter.properties"), missing);
        requireFile(normalizedHome.resolve("bin").resolve("saveservice.properties"), missing);
        requireFile(normalizedHome.resolve("bin").resolve("upgrade.properties"), missing);
        if (!missing.isEmpty()) {
            throw new LocalJMeterEnvironmentException(
                    "Invalid local JMeter home from " + source + ": " + normalizedHome
                            + ". Expected existing paths: " + joinPaths(missing));
        }
        try {
            return normalizedHome.toRealPath();
        } catch (IOException exception) {
            throw new LocalJMeterEnvironmentException(
                    "Invalid local JMeter home from " + source + ": " + normalizedHome
                            + ". The path cannot be resolved to its real filesystem location.",
                    exception);
        }
    }

    private static void requireDirectory(Path path, List<Path> missing) {
        if (!Files.isDirectory(path)) {
            missing.add(path);
        }
    }

    private static void requireFile(Path path, List<Path> missing) {
        if (!Files.isRegularFile(path)) {
            missing.add(path);
        }
    }

    private static String joinPaths(List<Path> paths) {
        List<String> values = new ArrayList<>();
        for (Path path : paths) {
            values.add(path.toString());
        }
        return String.join(", ", values);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
