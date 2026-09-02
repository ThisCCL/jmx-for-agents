package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class CanonicalAgentGuidanceContractTest {
    private static final String ASSERTION_REF = "jmx_99fb165049e0";

    @TempDir
    Path tempDir;

    @Test
    void canonicalGenericCollectionExampleTraversesTheRealPropertyGraphWritePath() throws Exception {
        Map<String, Object> guidance = guidance();
        Map<String, Object> generic = mapping(new Yaml().load(
                String.valueOf(guidance.get("generic_collection_example"))));
        Map<String, Object> semantic = mapping(new Yaml().load(
                String.valueOf(guidance.get("semantic_row_example"))));
        Path input = copyFixture();
        Path output = tempDir.resolve("canonical-guidance-output.jmx");

        CliTestResult result = MainCliTestSupport.runMain(
                "", Collections.<String, String>emptyMap(),
                "set", input.toString(),
                "--locator", ASSERTION_REF,
                "--property", "[\"Asserion.test_strings\"]",
                "--type", String.valueOf(generic.get("type")),
                "--value", new Yaml().dump(generic.get("value")),
                "--out", output.toString(),
                "--jmeter-home", jmeterHome().toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(responseAssertionStrings(output)).containsExactly("canonical-guidance");
        assertThat(generic.get("value")).isInstanceOf(Map.class);
        assertThat(semantic.get("value")).isInstanceOf(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> guidance() throws Exception {
        Path path = Paths.get(
                "src/main/resources/io/github/thisccl/j4a/guidance/agent-guidance.json");
        assertThat(path).exists();
        try (InputStream input = Files.newInputStream(path)) {
            Object parsed = new Yaml().load(input);
            assertThat(parsed).isInstanceOf(Map.class);
            return (Map<String, Object>) parsed;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private Path copyFixture() throws Exception {
        Path source = Paths.get(CanonicalAgentGuidanceContractTest.class
                .getResource("/property-graph-conformance/response-assertion.jmx").toURI());
        Path copy = tempDir.resolve("canonical-guidance-input.jmx");
        Files.copy(source, copy);
        return copy;
    }

    private List<String> responseAssertionStrings(Path input) {
        ResponseAssertion assertion = new SaveServiceJmxLoader(jmeterHome()).load(input)
                .depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("response assertion missing"));
        List<String> values = new ArrayList<String>();
        for (JMeterProperty item : (CollectionProperty) assertion.getPropertyOrNull("Asserion.test_strings")) {
            values.add(item.getStringValue());
        }
        return values;
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }
}
