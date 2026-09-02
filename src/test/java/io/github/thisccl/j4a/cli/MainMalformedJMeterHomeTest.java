package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.HTTP_REQUEST_REF;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.fixture;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMain;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainMalformedJMeterHomeTest {
    @TempDir
    Path tempDir;

    @Test
    void malformedJMeterHomeIsRuntimeInputErrorForApplyAndSet() {
        Path input = fixture("simple-http.jmx");
        Path applyOutput = tempDir.resolve("malformed-home-apply.jmx");
        Path setOutput = tempDir.resolve("malformed-home-set.jmx");
        String malformedHome = "bad\u0000jmeter-home";

        CliTestResult apply = runMain(patchFile().toString(), java.util.Collections.emptyMap(),
                "apply", input.toString(), "--patch", "-", "--out", applyOutput.toString(),
                "--jmeter-home", malformedHome);
        CliTestResult set = runMain(java.util.Collections.emptyMap(),
                "set", input.toString(), "--locator", HTTP_REQUEST_REF,
                "--property", "HTTPSampler\\.domain", "--value", "changed.example",
                "--out", setOutput.toString(), "--jmeter-home", malformedHome);

        assertMalformedJMeterHomeError(apply);
        assertMalformedJMeterHomeError(set);
    }

    @Test
    void missingJMeterHomeValueIsUsageErrorForApplyAndSet() {
        Path input = fixture("simple-http.jmx");
        Path applyOutput = tempDir.resolve("missing-home-value-apply.jmx");
        Path setOutput = tempDir.resolve("missing-home-value-set.jmx");

        CliTestResult apply = runMain(patchFile().toString(), java.util.Collections.emptyMap(),
                "apply", input.toString(), "--patch", "-", "--out", applyOutput.toString(),
                "--jmeter-home");
        CliTestResult set = runMain(java.util.Collections.emptyMap(),
                "set", input.toString(), "--locator", HTTP_REQUEST_REF,
                "--property", "HTTPSampler\\.domain", "--value", "changed.example",
                "--out", setOutput.toString(), "--jmeter-home");

        assertMissingJMeterHomeValueError(apply);
        assertMissingJMeterHomeValueError(set);
        assertThat(applyOutput).doesNotExist();
        assertThat(setOutput).doesNotExist();
    }

    private String patchFile() {
        return "changes:\n  - set:\n      ref: " + HTTP_REQUEST_REF
                + "\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n"
                + "        - property: HTTPSampler\\.domain\n"
                + "          value: changed.example\n"
                + "          type: string\n";
    }

    private static void assertMalformedJMeterHomeError(CliTestResult result) {
        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stdout()).isEmpty();
        assertThat(normalize(result.stderr())).contains(
                "Error code: LOCAL_JMETER_RUNTIME_ERROR",
                "Category: runtime",
                "Invalid local JMeter home from --jmeter-home");
        assertThat(result.stderr()).doesNotContain("INTERNAL_ERROR", "LOCATOR_NOT_FOUND");
        assertNoStackTrace(result.stderr());
    }

    private static void assertMissingJMeterHomeValueError(CliTestResult result) {
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(normalize(result.stderr())).contains(
                "Error code: USAGE_ERROR",
                "Category: usage",
                "--jmeter-home requires a value");
        assertThat(result.stderr()).doesNotContain("INTERNAL_ERROR", "LOCATOR_NOT_FOUND");
        assertNoStackTrace(result.stderr());
    }

    private static String normalize(String value) {
        return value.replace(System.lineSeparator(), "\n");
    }

    private static void assertNoStackTrace(String stderr) {
        assertThat(stderr)
                .doesNotContain("Debug stack trace:")
                .doesNotContain("\tat io.github")
                .doesNotContain("Caused by:");
    }
}
