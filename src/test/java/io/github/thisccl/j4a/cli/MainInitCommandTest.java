package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.MainCliTestSupport.assertUsageError;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMain;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class MainInitCommandTest {
    private static final Path JMETER_HOME = io.github.thisccl.j4a.TestJMeterRuntime.home();

    @TempDir
    Path tempDir;

    @Test
    void helpDocumentsLocalOnlyInitContract() {
        CliTestResult global = runMain(Collections.<String, String>emptyMap(), "--help");
        CliTestResult command = runMain(Collections.<String, String>emptyMap(), "init", "--help");

        assertThat(global.stdout()).contains(
                "init <out.jmx> [--force-out] [--jmeter-home <path>] [--name <test-plan-name>] [--thread-group-name <name>]");
        assertThat(command.stdout()).contains(
                "Usage: j4a init <out.jmx> [--force-out] [--jmeter-home <path>] [--name <test-plan-name>] [--thread-group-name <name>]");
    }

    @Test
    void initCreatesReadablePlanWithCustomNames() {
        Path output = tempDir.resolve("nested/new-plan.jmx");

        CliTestResult init = runMain(Collections.<String, String>emptyMap(),
                "init", output.toString(), "--jmeter-home", JMETER_HOME.toString(),
                "--name", "计划 A", "--thread-group-name", "线程组 B");
        CliTestResult read = runMain(Collections.<String, String>emptyMap(),
                "read", output.toString(), "--jmeter-home", JMETER_HOME.toString(), "--depth", "2");

        assertThat(init.exitCode()).as(init.stderr()).isZero();
        assertThat(output).isRegularFile();
        assertThat(read.exitCode()).as(read.stderr()).isZero();
        assertThat(read.stdout()).contains("计划 A", "线程组 B");
    }

    @Test
    void initDoesNotOverwriteWithoutForce() throws Exception {
        Path output = tempDir.resolve("existing.jmx");
        Files.write(output, "sentinel".getBytes(StandardCharsets.UTF_8));

        CliTestResult result = runMain(Collections.<String, String>emptyMap(),
                "init", output.toString(), "--jmeter-home", JMETER_HOME.toString());

        assertThat(result.exitCode()).isNotZero();
        assertThat(new String(Files.readAllBytes(output), StandardCharsets.UTF_8)).isEqualTo("sentinel");
        Map<String, Object> document = mapping(new Yaml().load(result.stderr()));
        assertThat(document).containsEntry("Error code", "OUTPUT_FILE_EXISTS");
        List<Object> choices = list(mapping(document.get("recovery")).get("choices"));
        assertThat(choices).hasSize(2);
        assertChoice(mapping(choices.get(0)), "overwrite", output.toString(), true);
        assertChoice(mapping(choices.get(1)), "choose-output", "<different-output.jmx>", false);
    }

    @Test
    void initFailureDoesNotCreateTarget() {
        Path output = tempDir.resolve("absent.jmx");
        Path missing = tempDir.resolve("missing-home");

        CliTestResult result = runMain(Collections.<String, String>emptyMap(),
                "init", output.toString(), "--jmeter-home", missing.toString());

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stdout()).isEmpty();
        assertThat(output).doesNotExist();
    }

    @Test
    void initRejectsParentFileAsFilesystemFailureWithoutChangingSentinel() throws Exception {
        Path parentFile = tempDir.resolve("blocked-parent");
        byte[] sentinel = "parent-sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(parentFile, sentinel);
        Path output = parentFile.resolve("child.jmx");

        CliTestResult result = runMain(Collections.<String, String>emptyMap(),
                "init", output.toString(), "--jmeter-home", JMETER_HOME.toString());

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(
                "Error code: FILESYSTEM_WRITE_ERROR",
                "Category: filesystem",
                "FileAlreadyExistsException",
                "check the output path and filesystem permissions, then retry the command.");
        assertThat(result.stderr()).doesNotContain("SEMANTIC_LOAD_ERROR", "Category: runtime");
        assertThat(Files.exists(output)).isFalse();
        assertThat(Files.readAllBytes(parentFile)).containsExactly(sentinel);
    }

    @Test
    void initValidatesArgumentsBeforeWriting() {
        assertUsageError(runMain(Collections.<String, String>emptyMap(), "init"), "output JMX file is required");
        assertUsageError(runMain(Collections.<String, String>emptyMap(), "init", "--force-out"),
                "output JMX file is required");
        assertUsageError(runMain(Collections.<String, String>emptyMap(),
                "init", tempDir.resolve("x.jmx").toString(), "--name"), "--name requires a value");
    }

    private static void assertChoice(
            Map<String, Object> choice, String action, String output, boolean force) {
        assertThat(choice.keySet()).containsExactly("action", "command", "argv");
        assertThat(choice).containsEntry("action", action).containsEntry("command", "init");
        List<Object> argv = list(choice.get("argv"));
        assertThat(argv).startsWith("init", output)
                .containsSubsequence("--jmeter-home", JMETER_HOME.toString());
        assertThat(argv.contains("--force-out")).isEqualTo(force);
        assertThat(argv.stream().filter("--force-out"::equals)).hasSizeLessThanOrEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
