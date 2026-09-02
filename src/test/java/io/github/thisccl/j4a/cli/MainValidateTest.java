package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.MainCliTestSupport.fixture;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMain;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainValidateTest {
    private static final Path JMETER_HOME = io.github.thisccl.j4a.TestJMeterRuntime.home();

    @TempDir
    Path tempDir;

    @Test
    void validatePassesUsingExplicitLocalRuntime() {
        CliTestResult result = runMain(Collections.<String, String>emptyMap(),
                "validate", fixture("simple-http.jmx").toString(),
                "--jmeter-home", JMETER_HOME.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("Validation passed", "simple-http.jmx");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void validateRequiresJmxFile() {
        CliTestResult result = runMain(Collections.<String, String>emptyMap(), "validate");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("USAGE_ERROR", "A JMX file is required");
    }

    @Test
    void validateMissingRuntimeFailsClosed() {
        Path missing = tempDir.resolve("missing-home");
        CliTestResult result = runMain(Collections.<String, String>emptyMap(),
                "validate", fixture("simple-http.jmx").toString(),
                "--jmeter-home", missing.toString());

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("LOCAL_JMETER_RUNTIME_ERROR", "Invalid local JMeter home");
    }

    @Test
    void validateMalformedXmlReportsSemanticFailure() {
        CliTestResult result = runMain(Collections.<String, String>emptyMap(),
                "validate", fixture("malformed.jmx").toString(),
                "--jmeter-home", JMETER_HOME.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("XML_PARSE_ERROR", "malformed.jmx");
    }
}
