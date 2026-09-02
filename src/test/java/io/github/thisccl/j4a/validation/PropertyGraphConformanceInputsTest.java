package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.apache.jmeter.gui.JMeterGUIComponent;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PropertyGraphConformanceInputsTest {
    private static final Path FIXTURE_ROOT = Paths.get(
            "src", "test", "resources", "property-graph-conformance").toAbsolutePath().normalize();
    private static final String PLUGIN_JAR = "synthetic-unseen-property-plugin.jar";
    private static final String MENU_COMPONENT =
            "io.github.thisccl.j4a.synthetic.property.UnseenPropertySamplerGui";
    private static final String TEST_ELEMENT =
            "io.github.thisccl.j4a.synthetic.property.UnseenPropertySampler";
    private static final String PROPERTY_CLASS =
            "io.github.thisccl.j4a.synthetic.property.UnseenStringProperty";
    private static final String PROPERTY_NAME = "qa.unseen.property";
    private static final String PROPERTY_VALUE = "populated-unseen-value";
    private static final List<String> EXPECTED_FIXTURES = Arrays.asList(
            "src/test/resources/property-graph-conformance/builtin-duplicate-collection.jmx="
                    + "22608f18a1153a1e37635115efb9e191ee988f31e2577aedc888e0b6caf16dd6",
            "src/test/resources/property-graph-conformance/builtin-empty-containers.jmx="
                    + "5b16c3c4b934afb447c94b1628312417ec4fb7135dfc418e7504bb71ec1cd57a",
            "src/test/resources/property-graph-conformance/builtin-map.jmx="
                    + "fd6977ca9332c2faf87a39236902d07527694396415650ca5ae0edbe80ee9137",
            "src/test/resources/property-graph-conformance/builtin-nested-element.jmx="
                    + "031a12e9442df1735e9cee3c09d12d9fb677a65fcd894f6b475b7f55ef6920e5",
            "src/test/resources/property-graph-conformance/builtin-null.jmx="
                    + "00e06d1810b55b71742701a4e07b2731f2fcaa8dcde89227f210cc976c843694",
            "src/test/resources/property-graph-conformance/exact-scalars.jmx="
                    + "a0193d0296e410dd7a5839b5825d68fc42819cf48dc75eb879d7de4924dda27a",
            "src/test/resources/property-graph-conformance/synthetic-unseen-property.jmx="
                    + "74e999288309388ac58f75e34a7308a78659d2d30d484b0f1bf43e36fd66b28d");

    @Test
    void enumeratesExactSortedFixturePathsAndHashes() throws Exception {
        List<String> actual = fixturePathsAndHashes();
        assertThat(actual).containsExactlyElementsOf(EXPECTED_FIXTURES);
        for (String fixture : actual) {
            System.out.println("FIXTURE_SHA256 " + fixture);
        }
    }

    @Test
    void loadsSyntheticMenuComponentAndConcretePropertySubclassWithPopulatedValue(
            @TempDir Path temporaryDirectory) throws Exception {
        Path selectedRuntime = io.github.thisccl.j4a.TestJMeterRuntime.home();
        Path syntheticHome = temporaryDirectory.resolve("jmeter-home");
        DefaultLocalProfileHomeFixtures.createHome(syntheticHome, true);
        assertThat(Files.readAllBytes(syntheticHome.resolve("bin").resolve("saveservice.properties")))
                .isEqualTo(Files.readAllBytes(selectedRuntime.resolve("bin").resolve("saveservice.properties")));
        System.out.println("SELECTED_JMETER_HOME " + selectedRuntime);
        Path pluginJar = syntheticHome.resolve("lib").resolve("ext").resolve(PLUGIN_JAR);
        assertJava8Class(pluginJar, MENU_COMPONENT);
        assertJava8Class(pluginJar, TEST_ELEMENT);
        assertJava8Class(pluginJar, PROPERTY_CLASS);
        URL[] pluginClasspath = new URL[] {pluginJar.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(
                pluginClasspath, PropertyGraphConformanceInputsTest.class.getClassLoader())) {
            Class<?> menuType = Class.forName(MENU_COMPONENT, true, loader);
            Class<?> propertyType = Class.forName(PROPERTY_CLASS, true, loader);

            assertThat(menuType).isAssignableTo(JMeterGUIComponent.class);
            assertThat(propertyType).isAssignableTo(JMeterProperty.class);
            assertThat(Modifier.isAbstract(propertyType.getModifiers())).isFalse();

            JMeterGUIComponent menu = (JMeterGUIComponent) menuType.getDeclaredConstructor().newInstance();
            menu.clearGui();
            TestElement element = menu.createTestElement();
            JMeterProperty property = element.getProperty(PROPERTY_NAME);

            assertThat(element.getClass().getName()).isEqualTo(TEST_ELEMENT);
            assertThat(property.getClass().getName()).isEqualTo(PROPERTY_CLASS);
            assertThat(property.getName()).isEqualTo(PROPERTY_NAME);
            assertThat(property.getStringValue()).isEqualTo(PROPERTY_VALUE);
            System.out.println("SYNTHETIC_MENU_FQCN " + menuType.getName());
            System.out.println("SYNTHETIC_PROPERTY_FQCN " + propertyType.getName());
            System.out.println("MATERIALIZED_PROPERTY " + property.getName() + "=" + property.getStringValue());
        }

        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));
        for (String expected : EXPECTED_FIXTURES) {
            Path fixture = Paths.get(expected.substring(0, expected.indexOf('='))).toAbsolutePath().normalize();
            LocalJMeterWorkerResponse response = client.execute(
                    LocalJMeterWorkerRequest.validate(fixture, syntheticHome)).response();
            assertThat(response.success()).as(fixture + System.lineSeparator() + response.toJsonLine()).isTrue();
        }

        Path syntheticFixture = FIXTURE_ROOT.resolve("synthetic-unseen-property.jmx");
        LocalJMeterWorkerResponse read = client.execute(
                LocalJMeterWorkerRequest.renderReadData(syntheticFixture, syntheticHome)).response();
        assertThat(read.success()).as(read.toJsonLine()).isTrue();
        assertThat(read.payload()).contains("component: " + MENU_COMPONENT);
    }

    private static List<String> fixturePathsAndHashes() throws IOException {
        List<Path> fixtures;
        try (Stream<Path> paths = Files.walk(FIXTURE_ROOT)) {
            fixtures = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jmx"))
                    .filter(path -> !"response-assertion.jmx".equals(path.getFileName().toString()))
                    .sorted()
                    .collect(Collectors.toList());
        }
        List<String> result = new ArrayList<String>();
        for (Path fixture : fixtures) {
            String relative = Paths.get("").toAbsolutePath().normalize().relativize(fixture)
                    .toString().replace('\\', '/');
            result.add(relative + "=" + sha256(fixture));
        }
        return result;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void assertJava8Class(Path jarPath, String className) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(className.replace('.', '/') + ".class");
            assertThat(entry).as(className).isNotNull();
            try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
                assertThat(input.readInt()).as(className + " class-file magic").isEqualTo(0xcafebabe);
                input.readUnsignedShort();
                int majorVersion = input.readUnsignedShort();
                assertThat(majorVersion).as(className + " class-file major version").isEqualTo(52);
                System.out.println("PLUGIN_CLASS_VERSION " + className + "=" + majorVersion);
            }
        }
    }
}
