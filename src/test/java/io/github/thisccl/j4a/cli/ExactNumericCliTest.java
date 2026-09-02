package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.testelement.property.DoubleProperty;
import org.apache.jmeter.testelement.property.FloatProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExactNumericCliTest {
    private static final String ASSERTION_REF = "jmx_99fb165049e0";

    @TempDir
    Path tempDir;

    @Test
    void cliSetAndYamlApplyPreserveConcreteFloatAndDoubleProperties() throws Exception {
        Path input = numericFixture("cli-exact-input.jmx");
        Path floatSet = tempDir.resolve("cli-float-set.jmx");
        Path doubleSet = tempDir.resolve("cli-double-set.jmx");
        Path applied = tempDir.resolve("cli-yaml-applied.jmx");

        assertSuccessfulSet(input, floatSet, "qa.float", "float", "2.5");
        assertSuccessfulSet(floatSet, doubleSet, "qa.double", "double", "3.75");
        CliTestResult apply = MainCliTestSupport.runMain(patchYaml(4.5F, 6.25D),
                Collections.<String, String>emptyMap(),
                "apply", doubleSet.toString(), "--patch", "-",
                "--out", applied.toString(), "--jmeter-home", jmeterHome().toString());

        assertThat(apply.exitCode()).as(apply.stderr()).isZero();
        ResponseAssertion assertion = assertion(applied);
        assertThat(assertion.getPropertyOrNull("qa.float"))
                .isExactlyInstanceOf(FloatProperty.class);
        assertThat(assertion.getPropertyOrNull("qa.float").getObjectValue())
                .isEqualTo(Float.valueOf(4.5F));
        assertThat(assertion.getPropertyOrNull("qa.double"))
                .isExactlyInstanceOf(DoubleProperty.class);
        assertThat(assertion.getPropertyOrNull("qa.double").getObjectValue())
                .isEqualTo(Double.valueOf(6.25D));
    }

    @Test
    void cliSetRejectsOverflowNonfiniteAndNonzeroUnderflowAtomically() throws Exception {
        Path input = numericFixture("cli-invalid-input.jmx");
        byte[] before = Files.readAllBytes(input);

        assertRejectedSet(input, "float-overflow.jmx", "qa.float", "float", "1e39");
        assertRejectedSet(input, "float-underflow.jmx", "qa.float", "float", "1e-1000");
        assertRejectedSet(input, "float-nonfinite.jmx", "qa.float", "float", "NaN");
        assertRejectedSet(input, "double-overflow.jmx", "qa.double", "double", "1e309");
        assertRejectedSet(input, "double-underflow.jmx", "qa.double", "double", "1e-10000");
        assertRejectedSet(input, "double-nonfinite.jmx", "qa.double", "double", "Infinity");
        assertThat(Files.readAllBytes(input)).containsExactly(before);
    }

    @Test
    void yamlApplyRejectsDeclaredTypeThatDiffersFromObservedGraphType() throws Exception {
        Path input = numericFixture("cli-mismatch-input.jmx");
        Path output = tempDir.resolve("cli-mismatch-output.jmx");
        byte[] before = Files.readAllBytes(input);
        String yaml = "changes:\n"
                + "  - set:\n"
                + "      ref: " + ASSERTION_REF + "\n"
                + "      properties:\n"
                + "        - property: [qa.float]\n"
                + "          type: double\n"
                + "          value: 2.5\n";

        CliTestResult result = MainCliTestSupport.runMain(yaml,
                Collections.<String, String>emptyMap(),
                "apply", input.toString(), "--patch", "-",
                "--out", output.toString(), "--jmeter-home", jmeterHome().toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("expected type 'float'", "received type 'double'", "[qa.float]");
        assertThat(output).doesNotExist();
        assertThat(Files.readAllBytes(input)).containsExactly(before);
    }

    private void assertSuccessfulSet(
            Path input, Path output, String property, String type, String value) {
        CliTestResult result = MainCliTestSupport.runMain("",
                Collections.<String, String>emptyMap(),
                "set", input.toString(), "--locator", ASSERTION_REF,
                "--property", "[\"" + property + "\"]",
                "--type", type, "--value", value,
                "--out", output.toString(), "--jmeter-home", jmeterHome().toString());
        assertThat(result.exitCode()).as(result.stderr()).isZero();
    }

    private void assertRejectedSet(
            Path input, String outputName, String property, String type, String value) {
        Path output = tempDir.resolve(outputName);
        CliTestResult result = MainCliTestSupport.runMain("",
                Collections.<String, String>emptyMap(),
                "set", input.toString(), "--locator", ASSERTION_REF,
                "--property", "[\"" + property + "\"]",
                "--type", type, "--value", value,
                "--out", output.toString(), "--jmeter-home", jmeterHome().toString());
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains(type, "finite", "range");
        assertThat(output).doesNotExist();
    }

    private Path numericFixture(String name) throws Exception {
        Path source = Paths.get(ExactNumericCliTest.class
                .getResource("/property-graph-conformance/response-assertion.jmx").toURI());
        Path copy = tempDir.resolve(name);
        Files.copy(source, copy);
        SaveServiceJmxLoader loader = new SaveServiceJmxLoader(jmeterHome());
        JmxTestPlan plan = loader.load(copy);
        ResponseAssertion assertion = plan.depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("response assertion missing"));
        assertion.setProperty(new FloatProperty("qa.float", 1.25F));
        assertion.setProperty(new DoubleProperty("qa.double", 1.5D));
        loader.save(plan, copy);
        return copy;
    }

    private ResponseAssertion assertion(Path path) throws Exception {
        return new SaveServiceJmxLoader(jmeterHome()).load(path).depthFirstTestElements().stream()
                .filter(ResponseAssertion.class::isInstance)
                .map(ResponseAssertion.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("response assertion missing"));
    }

    private static String patchYaml(float floatValue, double doubleValue) {
        return "changes:\n"
                + "  - set:\n"
                + "      ref: " + ASSERTION_REF + "\n"
                + "      properties:\n"
                + "        - property: [qa.float]\n"
                + "          type: float\n"
                + "          value: " + floatValue + "\n"
                + "        - property: [qa.double]\n"
                + "          type: double\n"
                + "          value: " + doubleValue + "\n";
    }

    private static Path jmeterHome() {
        return io.github.thisccl.j4a.TestJMeterRuntime.home();
    }
}
