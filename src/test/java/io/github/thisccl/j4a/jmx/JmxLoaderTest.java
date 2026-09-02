package io.github.thisccl.j4a.jmx;


import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.nio.file.Path;

import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jorphan.collections.ListedHashTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmxLoaderTest {
    private static final Path LOCAL_JMETER_HOME = io.github.thisccl.j4a.TestJMeterRuntime.home();
    private final JmxLoader loader = new SaveServiceJmxLoader(LOCAL_JMETER_HOME);

    @Test
    void loadsSimpleHttpFixtureAsOrderedListedHashTree() {
        JmxTestPlan testPlan = loader.load(fixture("simple-http.jmx"));

        assertThat(testPlan.tree()).isInstanceOf(ListedHashTree.class);
        assertThat(testPlan.depthFirstTestElementNames())
                .containsExactly("Synthetic Test Plan", "Synthetic Thread Group", "Synthetic HTTP Request");
    }

    @Test
    void loadsDisabledComponentFixtureWithEnabledStatePreserved() {
        JmxTestPlan testPlan = loader.load(fixture("disabled-component.jmx"));

        TestElement disabledSampler = testPlan.depthFirstTestElements().stream()
                .filter(element -> "Disabled HTTP Request".equals(element.getName()))
                .findFirst()
                .get();

        assertThat(disabledSampler.isEnabled()).isFalse();
    }

    @Test
    void rejectsInvalidFixtureWithSemanticLoadError() {
        Path invalidFixture = fixture("invalid.jmx");

        assertThatThrownBy(() -> loader.load(invalidFixture))
                .isInstanceOf(JmxLoadException.class)
                .hasMessageContaining("Unable to load JMX")
                .hasMessageContaining("invalid.jmx");
    }

    @Test
    void rejectsMissingSourceWithSourcePathPreserved(@TempDir Path tempDir) {
        Path missingSource = tempDir.resolve("missing.jmx");

        assertThatThrownBy(() -> loader.load(missingSource))
                .isInstanceOf(JmxLoadException.class)
                .hasMessage("Unable to load JMX: " + missingSource);
    }

    @Test
    void loadsFixtureFromUnicodeResourcePath() {
        JmxTestPlan testPlan = loader.load(fixture("unicode-path/简单脚本.jmx"));

        assertThat(testPlan.depthFirstTestElementNames())
                .containsExactly("Unicode Test Plan", "Unicode Thread Group", "Unicode HTTP Request");
        assertThat(testPlan.depthFirstTestElements())
                .filteredOn(element -> "Unicode HTTP Request".equals(element.getName()))
                .singleElement()
                .extracting(element -> propertyAsString(element, "HTTPSampler.path"))
                .isEqualTo("/路径");
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(JmxLoaderTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Fixture URI is invalid: " + name, e);
        }
    }

    private static String propertyAsString(TestElement element, String propertyName) {
        JMeterProperty property = element.getProperty(propertyName);
        return property == null ? null : property.getStringValue();
    }
}
