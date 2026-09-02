package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class PropertyGraphCliTest {
    private static final String ASSERTION_REF = "jmx_99fb165049e0";
    private static final String HTTP_REF = "jmx_330976848c8e";

    @TempDir
    Path tempDir;

    @Test
    void pinV1ScalarValueRemainsLiteralText() throws Exception {
        Path input = copyFixture("pin-scalar-input.jmx");
        Path output = tempDir.resolve("pin-scalar-output.jmx");

        CliTestResult result = MainCliTestSupport.runMain("", Collections.<String, String>emptyMap(),
                "set", input.toString(),
                "--locator", ASSERTION_REF,
                "--property", "[\"TestElement.name\"]",
                "--type", "string",
                "--value", "false",
                "--out", output.toString(),
                "--jmeter-home", jmeterHome().toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stderr()).isEmpty();
        ResponseAssertion assertion = new SaveServiceJmxLoader(jmeterHome()).load(output)
                .depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("response assertion missing after scalar set"));
        assertThat(assertion.getName()).isEqualTo("false");
    }

    @Test
    void reusableReadRecordAppliesUnchangedAndItsExactValueFragmentSets() throws Exception {
        Path input = copyFixture("reuse-input.jmx");
        Map<String, Object> record = readProperty(input, "Asserion.test_strings");
        Path applyOutput = tempDir.resolve("reuse-apply-output.jmx");

        Map<String, Object> set = new LinkedHashMap<String, Object>();
        set.put("ref", ASSERTION_REF);
        set.put("properties", Collections.<Object>singletonList(record));
        Map<String, Object> operation = new LinkedHashMap<String, Object>();
        operation.put("set", set);
        Map<String, Object> patch = new LinkedHashMap<String, Object>();
        patch.put("changes", Collections.<Object>singletonList(operation));
        CliTestResult apply = MainCliTestSupport.runMain(new Yaml().dump(patch),
                Collections.<String, String>emptyMap(),
                "apply", input.toString(), "--patch", "-",
                "--out", applyOutput.toString(),
                "--jmeter-home", jmeterHome().toString());

        assertThat(apply.exitCode()).as(apply.stderr()).isZero();
        assertThat(readProperty(applyOutput, "Asserion.test_strings")).isEqualTo(record);

        Path setOutput = tempDir.resolve("reuse-set-output.jmx");
        String valueFragment = new Yaml().dump(record.get("value"));
        CliTestResult v1Set = MainCliTestSupport.runMain("", Collections.<String, String>emptyMap(),
                "set", applyOutput.toString(),
                "--locator", ASSERTION_REF,
                "--property", jsonScalarArray(record.get("property")),
                "--type", String.valueOf(record.get("type")),
                "--value", valueFragment,
                "--out", setOutput.toString(),
                "--jmeter-home", jmeterHome().toString());

        assertThat(v1Set.exitCode()).as(v1Set.stderr()).isZero();
        assertThat(readProperty(setOutput, "Asserion.test_strings")).isEqualTo(record);
        assertThat(responseAssertionStrings(setOutput)).containsExactly("login failed", "access denied");
    }

    @Test
    void directResponseAssertionScalarLeafPersistsThroughSetAndApply() throws Exception {
        Path input = copyFixture("direct-leaf-input.jmx");
        byte[] sourceBefore = Files.readAllBytes(input);
        Path setOutput = tempDir.resolve("direct-leaf-set-output.jmx");

        CliTestResult set = MainCliTestSupport.runMain("", Collections.<String, String>emptyMap(),
                "set", input.toString(),
                "--locator", ASSERTION_REF,
                "--property", "[\"Asserion.test_strings\",0]",
                "--type", "string",
                "--value", "changed-index",
                "--out", setOutput.toString(),
                "--jmeter-home", jmeterHome().toString());

        assertThat(set.exitCode()).as(set.stderr()).isZero();
        assertThat(responseAssertionStrings(setOutput))
                .containsExactly("changed-index", "access denied");
        assertThat(Files.readAllBytes(input)).containsExactly(sourceBefore);

        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("property", Arrays.<Object>asList("Asserion.test_strings", Integer.valueOf(0)));
        record.put("type", "string");
        record.put("value", "changed-index");
        Path applyOutput = tempDir.resolve("direct-leaf-apply-output.jmx");
        CliTestResult apply = applyRecord(input, applyOutput, ASSERTION_REF, record);

        assertThat(apply.exitCode()).as(apply.stderr()).isZero();
        assertThat(responseAssertionStrings(applyOutput))
                .containsExactly("changed-index", "access denied");
        assertThat(Files.readAllBytes(input)).containsExactly(sourceBefore);
    }

    @Test
    void recursiveSetKeepsPresentEmptyDistinctFromAbsent() throws Exception {
        Path input = copyFixture("presence-input.jmx");
        Map<String, Object> record = readProperty(input, "Asserion.test_strings");
        Map<String, Object> empty = new LinkedHashMap<String, Object>(
                MainCliTestSupport.mapping(record.get("value")));
        empty.put("items", Collections.emptyList());
        Path emptyOutput = tempDir.resolve("presence-empty.jmx");

        CliTestResult emptySet = setRecursive(input, emptyOutput, record, empty);

        assertThat(emptySet.exitCode()).as(emptySet.stderr()).isZero();
        Map<String, Object> emptyRead = MainCliTestSupport.mapping(
                readProperty(emptyOutput, "Asserion.test_strings").get("value"));
        assertThat(emptyRead.get("presence")).isEqualTo("present");
        assertThat(MainCliTestSupport.list(emptyRead.get("items"))).isEmpty();

        Map<String, Object> absentRecord = readProperty(
                emptyOutput, HTTP_REF, "HTTPSampler.proxyHost");
        Path absentOutput = tempDir.resolve("presence-absent.jmx");

        CliTestResult absentApply = applyRecord(emptyOutput, absentOutput, HTTP_REF, absentRecord);

        assertThat(absentApply.exitCode()).as(absentApply.stderr()).isZero();
        Map<String, Object> absentRead = MainCliTestSupport.mapping(
                readProperty(absentOutput, HTTP_REF, "HTTPSampler.proxyHost").get("value"));
        assertThat(absentRead).containsExactly(
                org.assertj.core.data.MapEntry.entry("presence", "absent"),
                org.assertj.core.data.MapEntry.entry(
                        "property_class", "org.apache.jmeter.testelement.property.StringProperty"));
    }

    @Test
    void presentNullReadRecordAppliesUnchanged() throws Exception {
        Path input = copyResource("builtin-null.jmx", "null-input.jmx");
        String ref = "jmx_d62893619b1f";
        Map<String, Object> record = readProperty(input, ref, "qa.null");
        Path output = tempDir.resolve("null-output.jmx");

        assertThat(record).containsEntry("type", "null").containsEntry("value", null);
        CliTestResult apply = applyRecord(input, output, ref, record);

        assertThat(apply.exitCode()).as(apply.stderr()).isZero();
        assertThat(readProperty(output, ref, "qa.null")).isEqualTo(record);
    }

    @Test
    void floatAndDoubleReadRecordsAndLiteralSetsKeepExactTypes() throws Exception {
        Path fixture = copyFixture("float-fixture.jmx");
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        JmxTestPlan plan = loader.load(fixture);
        ResponseAssertion assertion = plan.depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("response assertion missing"));
        assertion.setProperty(new FloatProperty("qa.float", 1.25F));
        assertion.setProperty(new DoubleProperty("qa.double", 1.5D));
        Path input = tempDir.resolve("float-input.jmx");
        loader.save(plan, input);

        Map<String, Object> record = readProperty(input, "qa.float");
        assertThat(record).containsEntry("type", "float").containsEntry("value", 1.25D);
        assertThat(readProperty(input, "qa.double"))
                .containsEntry("type", "double").containsEntry("value", 1.5D);
        Path output = tempDir.resolve("float-output.jmx");
        CliTestResult set = MainCliTestSupport.runMain("", Collections.<String, String>emptyMap(),
                "set", input.toString(), "--locator", ASSERTION_REF,
                "--property", "[\"qa.float\"]", "--type", "float", "--value", "2.5",
                "--out", output.toString(), "--jmeter-home", jmeterHome().toString());

        assertThat(set.exitCode()).as(set.stderr()).isZero();
        JMeterProperty written = loader.load(output).depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("response assertion missing"))
                .getPropertyOrNull("qa.float");
        assertThat(written).isExactlyInstanceOf(FloatProperty.class);
        assertThat(written.getObjectValue()).isEqualTo(2.5F);

        Path doubleOutput = tempDir.resolve("double-output.jmx");
        CliTestResult doubleSet = MainCliTestSupport.runMain("", Collections.<String, String>emptyMap(),
                "set", output.toString(), "--locator", ASSERTION_REF,
                "--property", "[\"qa.double\"]", "--type", "double", "--value", "3.75",
                "--out", doubleOutput.toString(), "--jmeter-home", jmeterHome().toString());
        assertThat(doubleSet.exitCode()).as(doubleSet.stderr()).isZero();
        JMeterProperty writtenDouble = loader.load(doubleOutput).depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("response assertion missing"))
                .getPropertyOrNull("qa.double");
        assertThat(writtenDouble).isExactlyInstanceOf(DoubleProperty.class);
        assertThat(writtenDouble.getObjectValue()).isEqualTo(3.75D);
    }

    @Test
    void recursiveSetRejectsUnsafeOrIncompatibleDocumentsWithoutOutput() throws Exception {
        Path input = copyFixture("strict-input.jmx");
        rejectRecursiveSet(input, "strict-unknown-field.jmx", address("Asserion.test_strings"),
                "presence: present\nproperty_class: x.Collection\nitems: []\nunknown: true\n",
                "unknown field");
        rejectRecursiveSet(input, "strict-tag.jmx", address("Asserion.test_strings"),
                "!!java.lang.Runtime {}\n", "malformed YAML");
        rejectRecursiveSet(input, "strict-malformed.jmx", address("Asserion.test_strings"),
                "presence: [\n", "malformed YAML");
        rejectRecursiveSet(input, "strict-wrong-kind.jmx", address("Asserion.test_strings"),
                "presence: present\nproperty_class: x.Collection\nitems: wrong\n",
                "expected list");
        rejectRecursiveSet(input, "strict-caller-class.jmx", address("Asserion.test_strings"),
                "presence: present\nproperty_class: java.lang.Runtime\nitems: []\n",
                "does not match the observed graph node");
        Map<String, Object> valid = MainCliTestSupport.mapping(
                readProperty(input, "Asserion.test_strings").get("value"));
        rejectRecursiveSet(input, "strict-unknown-path.jmx", address("qa.missing"),
                new Yaml().dump(valid), "property");
    }

    private void rejectRecursiveSet(
            Path input, String outputName, List<Object> property, String value, String message) {
        Path output = tempDir.resolve(outputName);
        CliTestResult result = MainCliTestSupport.runMain("", Collections.<String, String>emptyMap(),
                "set", input.toString(), "--locator", ASSERTION_REF,
                "--property", jsonScalarArray(property), "--type", "collection", "--value", value,
                "--out", output.toString(), "--jmeter-home", jmeterHome().toString());
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(message);
        assertThat(output).doesNotExist();
    }

    private CliTestResult setRecursive(
            Path input, Path output, Map<String, Object> record, Map<String, Object> value) {
        return MainCliTestSupport.runMain("", Collections.<String, String>emptyMap(),
                "set", input.toString(),
                "--locator", ASSERTION_REF,
                "--property", jsonScalarArray(record.get("property")),
                "--type", String.valueOf(record.get("type")),
                "--value", new Yaml().dump(value),
                "--out", output.toString(),
                "--jmeter-home", jmeterHome().toString());
    }

    private Map<String, Object> readProperty(Path input, String propertyPath) {
        return readProperty(input, ASSERTION_REF, address(propertyPath));
    }

    private Map<String, Object> readProperty(Path input, String ref, String propertyPath) {
        return readProperty(input, ref, address(propertyPath));
    }

    private Map<String, Object> readProperty(Path input, String ref, List<Object> propertyPath) {
        CliTestResult read = MainCliTestSupport.runMain("", Collections.<String, String>emptyMap(),
                "read", input.toString(), "--ref", ref, "--properties", "all",
                "--jmeter-home", jmeterHome().toString());
        assertThat(read.exitCode()).as(read.stderr()).isZero();
        Map<String, Object> focus = MainCliTestSupport.mapping(
                CliYamlAssertions.parseMapping(read.stdout()).get("focus"));
        for (Object candidate : MainCliTestSupport.list(focus.get("properties"))) {
            Map<String, Object> property = MainCliTestSupport.mapping(candidate);
            if (propertyPath.equals(MainCliTestSupport.list(property.get("property")))) {
                return property;
            }
        }
        throw new AssertionError("missing read property " + propertyPath);
    }

    private static List<Object> address(String property) {
        return Collections.<Object>singletonList(property);
    }

    private static String jsonScalarArray(Object property) {
        List<Object> segments = MainCliTestSupport.list(property);
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < segments.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            Object segment = segments.get(index);
            if (segment instanceof String) {
                json.append('"');
                for (char character : ((String) segment).toCharArray()) {
                    switch (character) {
                        case '\\':
                            json.append("\\\\");
                            break;
                        case '"':
                            json.append("\\\"");
                            break;
                        case '\b':
                            json.append("\\b");
                            break;
                        case '\f':
                            json.append("\\f");
                            break;
                        case '\n':
                            json.append("\\n");
                            break;
                        case '\r':
                            json.append("\\r");
                            break;
                        case '\t':
                            json.append("\\t");
                            break;
                        default:
                            if (character < 0x20) {
                                json.append(String.format("\\u%04x", Integer.valueOf(character)));
                            } else {
                                json.append(character);
                            }
                            break;
                    }
                }
                json.append('"');
            } else if (segment instanceof Integer) {
                json.append(((Integer) segment).intValue());
            } else {
                throw new AssertionError("property address has unsupported segment " + segment);
            }
        }
        return json.append(']').toString();
    }

    private CliTestResult applyRecord(
            Path input, Path output, String ref, Map<String, Object> record) {
        Map<String, Object> set = new LinkedHashMap<String, Object>();
        set.put("ref", ref);
        set.put("properties", Collections.<Object>singletonList(record));
        Map<String, Object> operation = new LinkedHashMap<String, Object>();
        operation.put("set", set);
        Map<String, Object> patch = new LinkedHashMap<String, Object>();
        patch.put("changes", Collections.<Object>singletonList(operation));
        return MainCliTestSupport.runMain(new Yaml().dump(patch),
                Collections.<String, String>emptyMap(),
                "apply", input.toString(), "--patch", "-",
                "--out", output.toString(),
                "--jmeter-home", jmeterHome().toString());
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

    private Path copyFixture(String name) throws Exception {
        return copyResource("response-assertion.jmx", name);
    }

    private Path copyResource(String resource, String name) throws Exception {
        Path source = Paths.get(PropertyGraphCliTest.class
                .getResource("/property-graph-conformance/" + resource).toURI());
        Path copy = tempDir.resolve(name);
        Files.copy(source, copy);
        return copy;
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }
}
