package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

final class DefaultLocalProfileHomeFixtures {
    private static final Object CACHE_LOCK = new Object();
    private static final String CACHE_VERSION = "v13";
    private static final Path BASE_ROOT = Paths.get("build", "qa", "default-local-profile");
    static final String UNSEEN_PROPERTY_PLUGIN_JAR = "synthetic-unseen-property-plugin.jar";
    static final String UNSEEN_PROPERTY_TEST_ELEMENT =
            "io.github.thisccl.j4a.synthetic.property.UnseenPropertySampler";
    static final String UNSEEN_PROPERTY_CLASS =
            "io.github.thisccl.j4a.synthetic.property.UnseenStringProperty";
    static final String UNSEEN_PROPERTY_NAME = "qa.unseen.property";
    static final String UNSEEN_PROPERTY_VALUE = "populated-unseen-value";

    private DefaultLocalProfileHomeFixtures() {
    }

    static void ensureCachedHomes() throws IOException {
        synchronized (CACHE_LOCK) {
            Path cacheRoot = cacheRoot();
            if (isValidCache(cacheRoot)) {
                return;
            }
            deleteRecursively(cacheRoot);
            Files.createDirectories(cacheRoot);
            createHome(cacheRoot.resolve("local-home"), true);
            createHome(cacheRoot.resolve("local-home-without-plugin"), false);
            Files.write(cacheMarker(), cacheReceipt().getBytes(StandardCharsets.UTF_8));
        }
    }

    static void copyCachedHomesTo(Path root) throws IOException {
        copyHome(cacheRoot().resolve("local-home"), root.resolve("local-home"));
        copyHome(cacheRoot().resolve("local-home-without-plugin"), root.resolve("local-home-without-plugin"));
    }

    static void createUnicodeHome(Path home, Path sourceHome) throws IOException {
        if (sourceHome == null) {
            createHome(home, true);
            return;
        }
        copyHome(sourceHome, home);
    }

    static void createMarkerOnlyCoreHome(Path home) throws IOException {
        deleteRecursively(home);
        createBaseHome(home);
        createMarkerJar(home.resolve("lib").resolve("ext").resolve("ApacheJMeter_core-marker.jar"));
    }

    static void createHome(Path home, boolean includePlugins) throws IOException {
        deleteRecursively(home);
        createBaseHome(home);
        copyJMeterModules(home);
        if (includePlugins) {
            createPluginHome(home);
        }
    }

    static Path cacheRootForTesting() {
        return cacheRoot();
    }

    static Path cacheSourceHomeForTesting(String name) {
        return cacheRoot().resolve(name).toAbsolutePath().normalize();
    }

    static String homeDigestForTesting(Path home) throws IOException {
        return directorySha256(home);
    }

    static String fileDigestForTesting(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static boolean isValidCache(Path cacheRoot) throws IOException {
        if (!Files.exists(cacheMarker())) {
            return false;
        }
        try {
            return new String(Files.readAllBytes(cacheMarker()), StandardCharsets.UTF_8).equals(cacheReceipt());
        } catch (IOException exception) {
            return false;
        }
    }

    private static String cacheReceipt() throws IOException {
        Path cacheRoot = cacheRoot();
        return "version=" + CACHE_VERSION + System.lineSeparator()
                + "local-home-sha256=" + directorySha256(cacheRoot.resolve("local-home")) + System.lineSeparator()
                + "local-home-without-plugin-sha256="
                + directorySha256(cacheRoot.resolve("local-home-without-plugin")) + System.lineSeparator();
    }

    private static Path cacheRoot() {
        return BASE_ROOT.resolve("cache").resolve(CACHE_VERSION);
    }

    private static Path cacheMarker() {
        return cacheRoot().resolve(".complete");
    }

    private static void createPluginHome(Path home) throws IOException {
        SyntheticPluginFixtureJar.createWithKpArgument(
                home.resolve("lib").resolve("ext").resolve("synthetic-ext-plugin.jar"),
                DefaultLocalProfileQaFixtures.EXT_PLUGIN_CLASS, false, true, true);
        SyntheticPluginFixtureJar.create(home.resolve("search-extra").resolve("synthetic-search-plugin.jar"),
                DefaultLocalProfileQaFixtures.SEARCH_PLUGIN_CLASS, false, false, true);
        SyntheticPluginFixtureJar.createRequestDefaults(
                home.resolve("lib").resolve("ext").resolve("synthetic-request-defaults-plugin.jar"),
                DefaultLocalProfileQaFixtures.REQUEST_DEFAULTS_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createAbstractSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-abstract-sampler-plugin.jar"),
                DefaultLocalProfileQaFixtures.ABSTRACT_SAMPLER_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createThreadGroup(
                home.resolve("lib").resolve("ext").resolve("synthetic-thread-group-plugin.jar"),
                DefaultLocalProfileQaFixtures.THREAD_GROUP_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createGenericController(
                home.resolve("lib").resolve("ext").resolve("synthetic-generic-controller-plugin.jar"),
                DefaultLocalProfileQaFixtures.GENERIC_CONTROLLER_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createTestFragment(
                home.resolve("lib").resolve("ext").resolve("synthetic-test-fragment-plugin.jar"),
                DefaultLocalProfileQaFixtures.TEST_FRAGMENT_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.create(home.resolve("lib").resolve("ext").resolve("synthetic-slow-plugin.jar"),
                DefaultLocalProfileQaFixtures.SLOW_PLUGIN_CLASS, true, false);
        SyntheticPluginFixtureJar.create(home.resolve("lib").resolve("ext").resolve("synthetic-discovered-only-plugin.jar"),
                DefaultLocalProfileQaFixtures.DISCOVERED_ONLY_PLUGIN_CLASS, false, false);
        SyntheticPluginFixtureJar.create(home.resolve("lib").resolve("ext").resolve("synthetic-alpha-duplicate-plugin.jar"),
                DefaultLocalProfileQaFixtures.DUPLICATE_PLUGIN_ALPHA_CLASS, false, false);
        SyntheticPluginFixtureJar.create(home.resolve("lib").resolve("ext").resolve("synthetic-beta-duplicate-plugin.jar"),
                DefaultLocalProfileQaFixtures.DUPLICATE_PLUGIN_BETA_CLASS, false, false);
        SyntheticPluginFixtureJar.createMalformedSchemaSampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-malformed-schema-plugin.jar"),
                DefaultLocalProfileQaFixtures.MALFORMED_SCHEMA_PLUGIN_CLASS);
        SyntheticPluginFixtureJar.createUnseenPropertySampler(
                home.resolve("lib").resolve("ext").resolve(UNSEEN_PROPERTY_PLUGIN_JAR),
                UNSEEN_PROPERTY_TEST_ELEMENT, UNSEEN_PROPERTY_CLASS,
                UNSEEN_PROPERTY_NAME, UNSEEN_PROPERTY_VALUE);
        SyntheticPluginFixtureJar.createUnseenPropertySampler(
                home.resolve("lib").resolve("ext").resolve("synthetic-unrepresentable-address-plugin.jar"),
                DefaultLocalProfileQaFixtures.UNREPRESENTABLE_ADDRESS_PLUGIN_CLASS,
                "io.github.thisccl.j4a.synthetic.property.UnrepresentableAddressProperty",
                "can't", "unrepresentable-value");
        DefaultLocalProfilePluginFixtures.createAddabilityPlugins(home);
    }

    private static void copyHome(Path source, Path target) throws IOException {
        copyDirectory(source, target);
        rewriteHomePathReferences(target, source);
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        deleteRecursively(target);
        try (Stream<Path> paths = Files.walk(source)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                Path copied = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(copied);
                } else {
                    Files.createDirectories(copied.getParent());
                    Files.copy(path, copied, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void rewriteHomePathReferences(Path home, Path sourceHome) throws IOException {
        String source = propertyPath(sourceHome);
        String target = propertyPath(home);
        rewriteHomePathReferences(home.resolve("bin").resolve("user.properties"), source, target);
        rewriteHomePathReferences(home.resolve("bin").resolve("jmeter.properties"), source, target);
    }

    private static void rewriteHomePathReferences(Path file, String source, String target) throws IOException {
        String contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Files.write(file, contents.replace(source, target).getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            java.util.Iterator<Path> iterator = paths
                    .sorted(java.util.Comparator.reverseOrder())
                    .iterator();
            while (iterator.hasNext()) {
                Files.deleteIfExists(iterator.next());
            }
        }
    }

    private static void createBaseHome(Path home) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib"));
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        Files.createDirectories(home.resolve("search-extra"));
        copyRuntimeConfig("jmeter.properties", home.resolve("bin").resolve("jmeter.properties"));
        Files.write(home.resolve("bin").resolve("jmeter.properties"),
                ("search_paths=search-extra" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        copyRuntimeConfig("saveservice.properties", home.resolve("bin").resolve("saveservice.properties"));
        copyRuntimeConfig("upgrade.properties", home.resolve("bin").resolve("upgrade.properties"));
        Files.write(home.resolve("bin").resolve("user.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("system.properties"), new byte[0]);
    }

    private static void copyJMeterModules(Path home) throws IOException {
        Path source = runtimeHome().resolve("lib").resolve("ext");
        Path target = home.resolve("lib").resolve("ext");
        try (Stream<Path> paths = Files.list(source)) {
            List<Path> modules = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("ApacheJMeter_"))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            if (modules.isEmpty()) {
                throw new IOException("Selected JMeter runtime has no modules under " + source);
            }
            for (Path module : modules) {
                Files.copy(module, target.resolve(module.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void createMarkerJar(Path jar) throws IOException {
        if (Files.exists(jar)) {
            return;
        }
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("marker.txt"));
            output.write("marker".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static void copyRuntimeConfig(String fileName, Path target) throws IOException {
        Path source = runtimeHome().resolve("bin").resolve(fileName);
        if (!Files.isRegularFile(source)) {
            throw new IOException("Missing local JMeter runtime config: " + source);
        }
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path runtimeHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }

    private static String directorySha256(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> paths = Files.walk(root)) {
                List<Path> files = paths
                        .filter(Files::isRegularFile)
                        .sorted()
                        .collect(java.util.stream.Collectors.toList());
                for (Path file : files) {
                    digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(file));
                    digest.update((byte) 0);
                }
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static String propertyPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
