package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.invalidMixedPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.setPatch;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.fixture;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMainWithLocalRuntime;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MainApplyWriteModeTest {
    private static final byte[] OUTPUT_SENTINEL = "write-mode-output-sentinel".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void dryRunPermitsOutAndIgnoresAnExistingTarget() throws IOException {
        Path input = fixture("simple-http.jmx");
        Path output = tempDir.resolve("dry-run-ignored.jmx");
        Files.write(output, OUTPUT_SENTINEL);

        CliTestResult result = runMainWithLocalRuntime(setPatch("ignored.example.test"),
                "apply", input.toString(), "--patch", "-", "--dry-run", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains(
                "appliedCount: 1",
                "createdRefs: []",
                "deletedRefs: []");
        assertThat(Files.readAllBytes(output)).containsExactly(OUTPUT_SENTINEL);
    }

    @Test
    void copyToInputWithForceOutStillRequiresOverrideAndPreservesInput() throws IOException {
        Path input = copyFixture("same-path.jmx");
        byte[] original = Files.readAllBytes(input);

        CliTestResult result = runMainWithLocalRuntime(setPatch("forbidden.example.test"),
                "apply", input.toString(), "--patch", "-", "--out", input.toString(), "--force-out");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Error code: USAGE_ERROR", "--out must differ", "--override");
        assertThat(Files.readAllBytes(input)).containsExactly(original);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contradictoryModes")
    void contradictoryWriteModesAreUsageErrorsAndPreserveEveryRequestedTarget(
            String name, boolean existingOutput, String... modeOptions) throws IOException {
        Path input = copyFixture(name + "-input.jmx");
        Path output = tempDir.resolve(name + "-output.jmx");
        byte[] originalInput = Files.readAllBytes(input);
        if (existingOutput) {
            Files.write(output, OUTPUT_SENTINEL);
        }

        CliTestResult result = runMainWithLocalRuntime(setPatch("must-not-write.example.test"),
                command(input, output, modeOptions));

        assertThat(result.exitCode()).as(name + ": " + result.stderr()).isEqualTo(2);
        assertThat(result.stdout()).as(name).isEmpty();
        assertThat(result.stderr()).as(name).contains("Error code: USAGE_ERROR", "Category: usage");
        assertThat(Files.readAllBytes(input)).as(name + " input").containsExactly(originalInput);
        if (existingOutput) {
            assertThat(Files.readAllBytes(output)).as(name + " output").containsExactly(OUTPUT_SENTINEL);
        } else {
            assertThat(output).as(name + " output").doesNotExist();
        }
    }

    @Test
    void semanticRejectionIsExitThreeAndPreservesForcedCopyTarget() throws IOException {
        Path input = copyFixture("semantic-input.jmx");
        Path output = tempDir.resolve("semantic-output.jmx");
        Files.write(output, OUTPUT_SENTINEL);

        CliTestResult result = runMainWithLocalRuntime(invalidMixedPatch(),
                "apply", input.toString(), "--patch", "-", "--out", output.toString(), "--force-out");

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stderr()).contains("LOCATOR_NOT_FOUND", "Category: locator");
        assertThat(Files.readAllBytes(output)).containsExactly(OUTPUT_SENTINEL);
    }

    @Test
    void existingCopyTargetIsExitFourAndRemainsUnchanged() throws IOException {
        Path input = copyFixture("filesystem-input.jmx");
        Path output = tempDir.resolve("filesystem-output.jmx");
        Files.write(output, OUTPUT_SENTINEL);

        CliTestResult result = runMainWithLocalRuntime(setPatch("must-not-write.example.test"),
                "apply", input.toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stderr()).contains("OUTPUT_FILE_EXISTS", "Category: filesystem", "--force-out");
        assertThat(Files.readAllBytes(output)).containsExactly(OUTPUT_SENTINEL);
    }

    private static Stream<Arguments> contradictoryModes() {
        return Stream.of(
                Arguments.of("dry-run-with-override", false, new String[] {"--dry-run", "--override"}),
                Arguments.of("dry-run-with-force-out", false, new String[] {"--dry-run", "--force-out"}),
                Arguments.of("dry-run-with-out-and-force-out", true,
                        new String[] {"--dry-run", "--out", "$OUT", "--force-out"}),
                Arguments.of("out-with-override", false, new String[] {"--out", "$OUT", "--override"}),
                Arguments.of("override-with-force-out", false, new String[] {"--override", "--force-out"}),
                Arguments.of("blank-out", false, new String[] {"--out", ""}),
                Arguments.of("force-out-without-out", false, new String[] {"--force-out"}));
    }

    private String[] command(Path input, Path output, String[] modeOptions) {
        List<String> args = new ArrayList<>(Arrays.asList(
                "apply", input.toString(), "--patch", "-"));
        for (String option : modeOptions) {
            args.add("$OUT".equals(option) ? output.toString() : option);
        }
        return args.toArray(new String[0]);
    }

    private Path copyFixture(String name) throws IOException {
        Path input = tempDir.resolve(name);
        Files.copy(fixture("simple-http.jmx"), input);
        return input;
    }
}
