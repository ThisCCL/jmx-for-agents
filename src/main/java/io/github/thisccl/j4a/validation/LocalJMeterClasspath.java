package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class LocalJMeterClasspath {
    private LocalJMeterClasspath() {
    }

    static String localFirstClasspath(Path jmeterHome) {
        List<Path> localJars = localJars(jmeterHome);
        if (localJars.stream().noneMatch(LocalJMeterClasspath::containsJMeterCoreClass)) {
            throw new LocalJMeterEnvironmentException(
                    "Local JMeter classpath is missing org.apache.jmeter.util.JMeterUtils "
                            + "(org/apache/jmeter/util/JMeterUtils.class) in local jars under "
                            + jmeterHome.resolve("lib") + " or " + jmeterHome.resolve("lib").resolve("ext"));
        }
        String separator = System.getProperty("path.separator");
        String localClasspath = workerClasspathEntries(jmeterHome).stream()
                .collect(Collectors.joining(separator));
        return localClasspath + separator + absoluteApplicationClasspath();
    }

    static List<Path> localJars(Path jmeterHome) {
        List<Path> jars = new ArrayList<>();
        collectJars(jmeterHome.resolve("lib"), jars);
        collectJars(jmeterHome.resolve("lib").resolve("ext"), jars);
        for (Path classpathPath : configuredClasspathPaths(jmeterHome, true)) {
            collectClasspathEntry(classpathPath, jars);
        }
        jars.sort(Comparator.comparing(Path::toString));
        return jars;
    }

    private static List<String> workerClasspathEntries(Path jmeterHome) {
        List<String> entries = new ArrayList<>();
        entries.add(jmeterHome.resolve("lib").toString() + java.io.File.separator + "*");
        entries.add(jmeterHome.resolve("lib").resolve("ext").toString() + java.io.File.separator + "*");
        for (Path classpathPath : configuredClasspathPaths(jmeterHome, true)) {
            collectWorkerClasspathEntry(classpathPath, entries);
        }
        return new ArrayList<>(new LinkedHashSet<>(entries));
    }

    private static List<Path> configuredClasspathPaths(Path jmeterHome, boolean includeDependencyPaths) {
        Properties properties = new Properties();
        loadProperties(properties, jmeterHome.resolve("bin").resolve("jmeter.properties"));
        loadProperties(properties, jmeterHome.resolve("bin").resolve("user.properties"));
        loadProperties(properties, jmeterHome.resolve("bin").resolve("system.properties"));
        List<Path> paths = new ArrayList<>();
        collectConfiguredPaths(paths, jmeterHome, properties.getProperty("search_paths", ""), ";");
        if (includeDependencyPaths) {
            collectConfiguredPaths(paths, jmeterHome, properties.getProperty("user.classpath", ""),
                    java.util.regex.Pattern.quote(System.getProperty("path.separator")));
            collectConfiguredPaths(paths, jmeterHome, properties.getProperty("plugin_dependency_paths", ""), ";");
        }
        return paths;
    }

    private static void collectConfiguredPaths(
            List<Path> paths, Path jmeterHome, String configured, String separatorRegex) {
        for (String entry : configured.split(separatorRegex)) {
            if (!entry.trim().isEmpty()) {
                Path path = Paths.get(entry.trim());
                paths.add(path.isAbsolute() ? path.normalize() : jmeterHome.resolve(path).normalize());
            }
        }
    }

    private static void collectClasspathEntry(Path path, List<Path> jars) {
        if (Files.isDirectory(path)) {
            collectJars(path, jars);
        } else if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
            jars.add(path.toAbsolutePath().normalize());
        }
    }

    private static void collectWorkerClasspathEntry(Path path, List<String> entries) {
        if (!Files.exists(path)) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        entries.add(normalized.toString());
        if (Files.isDirectory(path)) {
            entries.add(normalized.toString() + java.io.File.separator + "*");
        }
    }

    private static void loadProperties(Properties properties, Path path) {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new LocalJMeterEnvironmentException(
                    "Unable to load local JMeter properties from " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static String absoluteApplicationClasspath() {
        String configuredClasspath = System.getProperty("j4a.worker.application.classpath");
        if (configuredClasspath != null && !configuredClasspath.trim().isEmpty()) {
            return configuredClasspath;
        }
        String separator = System.getProperty("path.separator");
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(separator))) {
            entries.add(Paths.get(entry).toAbsolutePath().normalize().toString());
        }
        return String.join(separator, entries);
    }

    private static void collectJars(Path directory, List<Path> jars) {
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar"))
                    .forEach(path -> jars.add(path.toAbsolutePath().normalize()));
        } catch (IOException exception) {
            throw new LocalJMeterEnvironmentException(
                    "Unable to list local JMeter jars in " + directory + ": " + exception.getMessage(), exception);
        }
    }

    private static boolean containsJMeterCoreClass(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.getEntry("org/apache/jmeter/util/JMeterUtils.class") != null;
        } catch (IOException exception) {
            throw new LocalJMeterEnvironmentException(
                    "Unable to inspect local JMeter jar " + jar + ": " + exception.getMessage(), exception);
        }
    }
}
