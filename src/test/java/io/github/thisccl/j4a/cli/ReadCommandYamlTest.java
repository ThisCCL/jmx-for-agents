package io.github.thisccl.j4a.cli;


import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class ReadCommandYamlTest {
    @Test
    void defaultReadEmitsDepthOneYamlWithoutEditableProperties() {
        CommandResult result = runRead("simple-http.jmx");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        Map<String, Object> yaml = CliYamlAssertions.parseMapping(result.stdout());
        Map<String, Object> root = mapping(yaml.get("root"));
        assertThat(root).containsEntry("ref", "jmx_d62893619b1f")
                .containsEntry("component", "org.apache.jmeter.testelement.TestPlan")
                .containsEntry("name", "Synthetic Test Plan")
                .containsEntry("enabled", true);
        assertSourceRange(root, 4, 13);
        assertThat(root).doesNotContainKey("properties");

        List<Object> children = list(root.get("children"));
        assertThat(children).hasSize(1);
        Map<String, Object> threadGroup = mapping(children.get(0));
        assertThat(threadGroup).containsEntry("ref", "jmx_19871e6efa95")
                .containsEntry("component", "org.apache.jmeter.threads.gui.ThreadGroupGui")
                .containsEntry("name", "Synthetic Thread Group")
                .containsEntry("enabled", true)
                .containsEntry("child_count", 1)
                .containsEntry("children_omitted", true);
        assertSourceRange(threadGroup, 15, 27);
        assertThat(threadGroup).doesNotContainKeys("children", "properties");
        assertThat(result.stdout()).doesNotContain("Set card:", "```", "property:", "<TestPlan", "<ThreadGroup", "<HTTPSamplerProxy");
    }

    @Test
    void depthTwoReadExpandsGrandchildrenAndCollapsesDeeperDescendants() {
        CommandResult result = runRead("simple-http.jmx", "--depth", "2");

        assertThat(result.exitCode()).isZero();
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(result.stdout()).get("root"));
        Map<String, Object> threadGroup = mapping(list(root.get("children")).get(0));
        Map<String, Object> sampler = mapping(list(threadGroup.get("children")).get(0));

        assertThat(sampler).containsEntry("ref", "jmx_330976848c8e")
                .containsEntry("component", "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui")
                .containsEntry("name", "Synthetic HTTP Request")
                .containsEntry("enabled", true);
        assertThat(sampler).doesNotContainKeys("properties", "children_omitted");
    }

    @Test
    void focusedReadEmitsPathAndFocusPropertiesOnlyOnFocusRoot() {
        CommandResult result = runRead("simple-http.jmx", "--ref", "jmx_330976848c8e", "--properties", "key");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        Map<String, Object> yaml = CliYamlAssertions.parseMapping(result.stdout());
        assertThat(yaml).containsOnlyKeys("path", "focus");

        List<Object> path = list(yaml.get("path"));
        assertThat(path).hasSize(3);
        assertThat(mapping(path.get(0))).containsEntry("component", "org.apache.jmeter.testelement.TestPlan");
        assertThat(mapping(path.get(1))).containsEntry("component", "org.apache.jmeter.threads.gui.ThreadGroupGui");
        assertThat(mapping(path.get(2))).containsEntry("ref", "jmx_330976848c8e");
        assertSourceRange(mapping(path.get(0)), 4, 13);
        assertSourceRange(mapping(path.get(1)), 15, 27);
        assertSourceRange(mapping(path.get(2)), 29, 40);

        Map<String, Object> focus = mapping(yaml.get("focus"));
        assertThat(focus).containsEntry("component", "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        assertSourceRange(focus, 29, 40);
        assertThat(list(focus.get("properties")))
                .extracting(property -> mapping(property).get("property"))
                .containsExactly(
                        java.util.Collections.singletonList("TestElement.name"),
                        java.util.Collections.singletonList("HTTPSampler.domain"),
                        java.util.Collections.singletonList("HTTPSampler.path"),
                        java.util.Collections.singletonList("HTTPSampler.method"));
        assertThat(result.stdout()).doesNotContain("Set card:", "Move card:", "Delete card:", "<HTTPSamplerProxy");
    }

    @Test
    void unicodePathReadPreservesSourceRanges(@TempDir Path tempDir) throws IOException {
        Path unicodeJmx = tempDir.resolve("路径-计划.jmx");
        Files.copy(fixture("simple-http.jmx"), unicodeJmx);

        CommandResult result = runReadPath(unicodeJmx);

        assertThat(result.exitCode()).isZero();
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(result.stdout()).get("root"));
        assertSourceRange(root, 4, 13);
        assertSourceRange(mapping(list(root.get("children")).get(0)), 15, 27);
    }

    @Test
    void disabledComponentsStaySummaryOnlyUnlessDetailsAreIncluded() {
        CommandResult result = runRead("disabled-component.jmx", "--depth", "2", "--properties", "key");

        assertThat(result.exitCode()).isZero();
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(result.stdout()).get("root"));
        Map<String, Object> threadGroup = mapping(list(root.get("children")).get(0));
        Map<String, Object> sampler = mapping(list(threadGroup.get("children")).get(0));

        assertThat(sampler).containsEntry("ref", "jmx_330976848c8e")
                .containsEntry("component", "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui")
                .containsEntry("name", "Disabled HTTP Request")
                .containsEntry("enabled", false)
                .containsEntry("child_count", 0);
        assertThat(sampler).doesNotContainKey("properties");
    }

    @Test
    void includeDisabledDetailsAllowsDisabledProperties() {
        CommandResult result = runRead(
                "disabled-component.jmx",
                "--depth",
                "2",
                "--properties",
                "key",
                "--include-disabled-details");

        assertThat(result.exitCode()).isZero();
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(result.stdout()).get("root"));
        Map<String, Object> threadGroup = mapping(list(root.get("children")).get(0));
        Map<String, Object> sampler = mapping(list(threadGroup.get("children")).get(0));

        assertThat(list(sampler.get("properties")))
                .extracting(property -> mapping(property).get("property"))
                .contains(java.util.Collections.singletonList("HTTPSampler.path"));
    }

    @Test
    void writablePropertiesPreserveRootFocusAndDisabledReadSemantics() {
        CommandResult root = runRead("simple-http.jmx", "--depth", "2", "--properties", "writable");
        CommandResult focus = runRead(
                "simple-http.jmx", "--ref", "jmx_330976848c8e", "--properties", "writable");
        CommandResult disabled = runRead(
                "disabled-component.jmx", "--depth", "2", "--properties", "writable");
        CommandResult disabledDetails = runRead(
                "disabled-component.jmx", "--depth", "2", "--properties", "writable",
                "--include-disabled-details");

        assertThat(root.exitCode()).isZero();
        assertThat(focus.exitCode()).isZero();
        assertThat(disabled.exitCode()).isZero();
        assertThat(disabledDetails.exitCode()).isZero();
        Map<String, Object> rootDocument = mapping(CliYamlAssertions.parseMapping(root.stdout()).get("root"));
        Map<String, Object> rootSampler = mapping(list(
                mapping(list(rootDocument.get("children")).get(0)).get("children")).get(0));
        Map<String, Object> focusDocument = mapping(CliYamlAssertions.parseMapping(focus.stdout()).get("focus"));
        Map<String, Object> disabledSampler = sampler(disabled.stdout());
        Map<String, Object> disabledDetailsSampler = sampler(disabledDetails.stdout());

        assertThat(rootSampler).containsEntry("ref", "jmx_330976848c8e")
                .containsEntry("enabled", true);
        assertSourceRange(rootSampler, 29, 40);
        assertThat(list(rootSampler.get("properties"))).extracting(property -> mapping(property).get("property"))
                .contains(java.util.Collections.singletonList("TestElement.name"),
                        java.util.Collections.singletonList("HTTPSampler.path"));
        assertThat(focusDocument).containsEntry("ref", "jmx_330976848c8e");
        assertSourceRange(focusDocument, 29, 40);
        assertThat(list(focusDocument.get("properties"))).extracting(property -> mapping(property).get("property"))
                .contains(java.util.Collections.singletonList("TestElement.name"),
                        java.util.Collections.singletonList("HTTPSampler.path"));
        assertThat(disabledSampler).containsEntry("enabled", false).doesNotContainKey("properties");
        assertThat(list(disabledDetailsSampler.get("properties")))
                .extracting(property -> mapping(property).get("property"))
                .contains(java.util.Collections.singletonList("TestElement.name"),
                        java.util.Collections.singletonList("HTTPSampler.path"));
    }

    @Test
    void propertiesAllOmitsGuiAndTestClassPropertiesAtEveryDepth() {
        CommandResult result = runRead("simple-http.jmx", "--properties", "all");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains("TestElement.name", "LoopController.loops");
        assertThat(result.stdout()).doesNotContain(
                "TestElement\\.gui_class",
                "TestElement\\.test_class",
                "ThreadGroup\\.main_controller.TestElement\\.gui_class",
                "ThreadGroup\\.main_controller.TestElement\\.test_class");
    }

    @Test
    void readAlwaysEmitsScalarArraysAndPunctuationDoesNotNeedRecovery() throws IOException {
        CommandResult defaultRead = runRead("simple-http.jmx", "--properties", "all");

        assertThat(defaultRead.exitCode()).isZero();
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(defaultRead.stdout()).get("root"));
        Map<String, Object> threadGroup = mapping(list(root.get("children")).get(0));
        assertThat(list(threadGroup.get("properties")))
                .extracting(property -> mapping(property).get("property"))
                .contains(java.util.Collections.singletonList("ThreadGroup.main_controller"));

        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = java.util.Collections.singletonMap(
                "JMX_AGENT_JMETER_HOME", fixtures.localHome().toString());
        CommandResult punctuation = runReadPath(
                fixtures.unrepresentableAddressJmx(), environment, "--properties", "all");

        assertThat(punctuation.exitCode()).as(punctuation.stderr()).isZero();
        assertThat(punctuation.stdout()).contains("can't");
    }

    @Test
    void readEmitsCopyableScalarArraysForPunctuationWithoutARecoveryMode() throws IOException {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.cached();
        Map<String, String> environment = java.util.Collections.singletonMap(
                "JMX_AGENT_JMETER_HOME", fixtures.localHome().toString());

        CommandResult result = runReadPath(
                fixtures.unrepresentableAddressJmx(), environment, "--properties", "all");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        Map<String, Object> document = CliYamlAssertions.parseMapping(result.stdout());
        List<Map<String, Object>> properties = propertyDocuments(document);
        assertThat(properties).allSatisfy(property -> assertScalarAddress(property.get("property")));
        Object punctuationAddress = properties.stream()
                .map(property -> property.get("property"))
                .filter(address -> list(address).contains("can't"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing punctuation address in:\n" + result.stdout()));
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("property", punctuationAddress);
        property.put("type", "string");
        property.put("value", "copyable");
        Map<String, Object> set = new LinkedHashMap<String, Object>();
        set.put("ref", "jmx_28593c59d6a3");
        set.put("properties", java.util.Collections.singletonList(property));
        Map<String, Object> change = new LinkedHashMap<String, Object>();
        change.put("set", set);
        Map<String, Object> patchFixture = new LinkedHashMap<String, Object>();
        patchFixture.put("changes", java.util.Collections.singletonList(change));

        ApplyPatch parsed = new ApplyPatchParser().parse(new Yaml().dump(patchFixture));
        ApplyPatch.PropertyChange parsedProperty = ((ApplyPatch.SetOperation) parsed.changes().get(0)
                .operation()).properties().get(0);
        assertThat(parsedProperty.property().segments()).isEqualTo(punctuationAddress);
        assertThat(PropertyAddress.decode(punctuationAddress).segments()).isEqualTo(punctuationAddress);
    }

    @Test
    void readRejectsDepthFlagWithoutValue() {
        CommandResult result = runRead("simple-http.jmx", "--depth");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("USAGE_ERROR", "--depth requires a value");
    }

    @Test
    void readRejectsPropertiesFlagWithoutValue() {
        CommandResult result = runRead("simple-http.jmx", "--properties");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("USAGE_ERROR", "--properties requires a value");
    }

    @Test
    void readRejectsRemovedPropertyAddressFlagBeforeOpeningAWorker() {
        CommandResult result = runReadPath(
                fixture("simple-http.jmx"), java.util.Collections.<String, String>emptyMap(),
                "--property-address", "segments");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(
                "USAGE_ERROR", "Unknown option");
        assertThat(result.stderr()).doesNotContain("LOCAL_JMETER_RUNTIME_ERROR");
    }

    @Test
    void readRejectsStaleVerboseFlag() {
        CommandResult result = runRead("simple-http.jmx", "--verbose");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(
                "USAGE_ERROR", "--verbose is not supported", "--properties key, all, or writable");
    }

    @Test
    void readRejectsMcpOnlyNone() {
        CommandResult invalid = runRead("simple-http.jmx", "--properties", "none");

        assertThat(invalid.exitCode()).isEqualTo(2);
        assertThat(invalid.stdout()).isEmpty();
        assertThat(invalid.stderr()).contains("USAGE_ERROR", "--properties must be key, all, or writable");
    }

    private static CommandResult runRead(String fixtureName, String... args) {
        return runReadPath(fixture(fixtureName), args);
    }

    private static CommandResult runReadPath(Path jmxPath, String... args) {
        return runReadPath(jmxPath, System.getenv(), true, args);
    }

    private static CommandResult runReadPath(
            Path jmxPath, Map<String, String> environment, String... args) {
        return runReadPath(jmxPath, environment, false, args);
    }

    private static CommandResult runReadPath(
            Path jmxPath, Map<String, String> environment, boolean explicitHome, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        String[] command = new String[(explicitHome ? 4 : 2) + args.length];
        command[0] = "read";
        command[1] = jmxPath.toString();
        System.arraycopy(args, 0, command, 2, args.length);
        if (explicitHome) {
            command[2 + args.length] = "--jmeter-home";
            command[3 + args.length] = io.github.thisccl.j4a.TestJMeterRuntime.home().toString();
        }

        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));
            int exitCode = Main.run(command, environment);
            return new CommandResult(exitCode, new String(stdout.toByteArray(), StandardCharsets.UTF_8), new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(ReadCommandYamlTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Fixture URI is invalid: " + name, exception);
        }
    }

    private static Map<String, Object> sampler(String output) {
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(output).get("root"));
        Map<String, Object> threadGroup = mapping(list(root.get("children")).get(0));
        return mapping(list(threadGroup.get("children")).get(0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }

    private static void assertScalarAddress(Object value) {
        List<Object> address = list(value);
        assertThat(address).isNotEmpty();
        assertThat(address).allSatisfy(segment -> assertThat(segment)
                .isInstanceOfAny(String.class, Integer.class));
    }

    private static List<Map<String, Object>> propertyDocuments(Object value) {
        java.util.ArrayList<Map<String, Object>> properties = new java.util.ArrayList<Map<String, Object>>();
        collectPropertyDocuments(value, properties);
        return properties;
    }

    private static void collectPropertyDocuments(Object value, List<Map<String, Object>> properties) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = mapping(value);
            if (map.containsKey("property") && map.containsKey("type") && map.containsKey("value")) {
                properties.add(map);
            }
            for (Object child : map.values()) {
                collectPropertyDocuments(child, properties);
            }
        } else if (value instanceof List<?>) {
            for (Object child : (List<?>) value) {
                collectPropertyDocuments(child, properties);
            }
        }
    }

    private static void assertSourceRange(Map<String, Object> component, int startLine, int endLine) {
        Map<String, Object> source = mapping(component.get("source"));
        assertThat(source).containsEntry("start_line", startLine)
                .containsEntry("end_line", endLine);
    }

    private static final class CommandResult {


        private final int exitCode;


        private final String stdout;


        private final String stderr;



        private CommandResult(int exitCode, String stdout, String stderr) {


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
