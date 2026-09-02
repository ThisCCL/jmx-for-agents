package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.HTTP_REQUEST_REF;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.THREAD_GROUP_REF;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.argumentRows;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.propertyAsString;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.fixture;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMainWithLocalRuntime;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.LocalJMeterWorkerClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MainSetOutputSafetyTest {
    private static final String DOMAIN_ADDRESS = "[\"HTTPSampler.domain\"]";
    private static final String ARGUMENTS_ADDRESS = "[\"HTTPsampler.Arguments\"]";

    @TempDir
    Path tempDir;

    @Test
    void jsonSegmentArraySetsNestedLoopControllerProperty() throws IOException {
        Path input = tempDir.resolve("typed-address-input.jmx");
        Path output = tempDir.resolve("typed-address-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        String property = "[\"ThreadGroup.main_controller\",\"LoopController.loops\"]";

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(),
                "--locator", THREAD_GROUP_REF,
                "--property", property,
                "--value", "7",
                "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("Wrote", "Validation passed");
        assertThat(readString(output))
                .contains("<stringProp name=\"LoopController.loops\">7</stringProp>");
    }

    @Test
    void setAndApplyUseTheSameScalarArrayToTargetTheSameProperty() throws IOException {
        Path input = tempDir.resolve("shared-address-input.jmx");
        Path setOutput = tempDir.resolve("shared-address-set.jmx");
        Path applyOutput = tempDir.resolve("shared-address-apply.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        String value = "same-node.example";
        String patch = "changes:\n"
                + "  - set:\n"
                + "      ref: " + HTTP_REQUEST_REF + "\n"
                + "      properties:\n"
                + "        - property: [HTTPSampler.domain]\n"
                + "          value: " + value + "\n"
                + "          type: string\n";

        CliTestResult set = runMainWithLocalRuntime("", "set", input.toString(),
                "--locator", HTTP_REQUEST_REF,
                "--property", DOMAIN_ADDRESS,
                "--value", value,
                "--out", setOutput.toString());
        CliTestResult apply = runMainWithLocalRuntime(
                patch, "apply", input.toString(), "--patch", "-", "--out", applyOutput.toString());

        assertThat(set.exitCode()).as(set.stderr()).isZero();
        assertThat(apply.exitCode()).as(apply.stderr()).isZero();
        assertThat(propertyAsString(setOutput, "Synthetic HTTP Request", "HTTPSampler.domain"))
                .isEqualTo(value);
        assertThat(propertyAsString(applyOutput, "Synthetic HTTP Request", "HTTPSampler.domain"))
                .isEqualTo(value);
    }

    @Test
    void legacyStringPropertyIsRejectedBeforeCreatingTheRequestedOutput() throws IOException {
        Path input = tempDir.resolve("legacy-property-input.jmx");
        Path output = tempDir.resolve("legacy-property-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(),
                "--locator", HTTP_REQUEST_REF,
                "--property", "HTTPSampler\\.domain",
                "--value", "must-not-write.example",
                "--out", output.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("property must be exactly one JSON scalar array");
        assertThat(Files.readAllBytes(input)).containsExactly(original);
        assertThat(output).doesNotExist();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("yamlOnlySetPropertyDocuments")
    void yamlOnlyPropertyDocumentsAreRejectedBeforeWorkerOrOutput(
            String shape, String propertyDocument) throws Exception {
        Path input = tempDir.resolve(shape + "-input.jmx");
        Path output = tempDir.resolve(shape + "-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] inputHash = sha256(input);

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(),
                "--locator", HTTP_REQUEST_REF,
                "--property", propertyDocument,
                "--value", "must-not-write.example",
                "--out", output.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("property must be exactly one JSON scalar array");
        assertThat(sha256(input)).containsExactly(inputHash);
        assertThat(output).doesNotExist();
    }

    private static Stream<Arguments> yamlOnlySetPropertyDocuments() {
        return Stream.of(
                Arguments.of("yaml-flow", "[HTTPSampler.domain]"),
                Arguments.of("yaml-block", "- HTTPSampler.domain"));
    }

    @Test
    void scalarSetWritesDistinctNewCopyAndPreservesInput() throws IOException {
        Path input = tempDir.resolve("scalar-copy-input.jmx");
        Path output = tempDir.resolve("scalar-copy-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(),
                "--locator", HTTP_REQUEST_REF, "--property", DOMAIN_ADDRESS,
                "--value", "scalar-copy.example", "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("Wrote", "Validation passed");
        assertThat(propertyAsString(output, "Synthetic HTTP Request", "HTTPSampler.domain"))
                .isEqualTo("scalar-copy.example");
        assertThat(Files.readAllBytes(input)).containsExactly(original);
    }

    @Test
    void explicitOverrideUpdatesInputAfterValidation() throws IOException {
        Path input = tempDir.resolve("override-input.jmx");
        Files.copy(fixture("simple-http.jmx"), input);

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(),
                "--locator", HTTP_REQUEST_REF, "--property", DOMAIN_ADDRESS,
                "--value", "override.example", "--override");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("Wrote", input.toString(), "Validation passed");
        assertThat(propertyAsString(input, "Synthetic HTTP Request", "HTTPSampler.domain"))
                .isEqualTo("override.example");
    }

    @Test
    void failedScalarMutationPreservesSourceAndForcedTarget() throws IOException {
        Path input = tempDir.resolve("failed-input.jmx");
        Path output = tempDir.resolve("failed-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);
        byte[] sentinel = "existing-before-validation".getBytes(StandardCharsets.UTF_8);
        Files.write(output, sentinel);

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(),
                "--locator", "jmx_missing", "--property", DOMAIN_ADDRESS,
                "--value", "must-not-commit.example", "--out", output.toString(), "--force-out");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stdout()).isEmpty();
        assertThat(Files.readAllBytes(input)).containsExactly(original);
        assertThat(Files.readAllBytes(output)).containsExactly(sentinel);
    }

    @Test
    void recursiveSetWritesDistinctNewCopyAndPreservesInput() throws IOException {
        Path input = tempDir.resolve("recursive-copy-input.jmx");
        Path output = tempDir.resolve("recursive-copy-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);
        String value = argumentRows("baseline", "recursive");

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(),
                "--locator", HTTP_REQUEST_REF, "--property", ARGUMENTS_ADDRESS,
                "--type", "rows", "--value", value, "--out", output.toString());

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("Wrote", "Validation passed");
        assertThat(new String(Files.readAllBytes(output), StandardCharsets.UTF_8)).contains("baseline", "recursive");
        assertThat(Files.readAllBytes(input)).containsExactly(original);
    }

    @Test
    void existingOutRequiresForceOut() throws IOException {
        Path input = fixture("simple-http.jmx");
        Path output = tempDir.resolve("edited.jmx");
        Files.write(output, "existing".getBytes(StandardCharsets.UTF_8));

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(), "--locator", HTTP_REQUEST_REF,
                "--property", DOMAIN_ADDRESS, "--value", "changed.example", "--out", output.toString());

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("--force-out", output.toString());
        assertThat(readString(output)).isEqualTo("existing");
    }

    @Test
    void forceOutReplacesDistinctExistingTargetAfterValidation() throws IOException {
        Path input = tempDir.resolve("forced-input.jmx");
        Path output = tempDir.resolve("forced-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);
        Files.write(output, "replace-me".getBytes(StandardCharsets.UTF_8));

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(),
                "--locator", HTTP_REQUEST_REF, "--property", DOMAIN_ADDRESS,
                "--value", "forced.example", "--out", output.toString(), "--force-out");

        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("Wrote", "Validation passed");
        assertThat(propertyAsString(output, "Synthetic HTTP Request", "HTTPSampler.domain"))
                .isEqualTo("forced.example");
        assertThat(Files.readAllBytes(input)).containsExactly(original);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("contradictoryModes")
    void contradictoryModesFailBeforeMutationAndDoNotShadowFlags(
            String shape, String mode, String[] modeArgs) throws IOException {
        Path input = tempDir.resolve(shape + "-" + mode + "-input.jmx");
        Path output = tempDir.resolve(shape + "-" + mode + "-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);
        byte[] sentinel = "contradictory-target".getBytes(StandardCharsets.UTF_8);
        if (Arrays.asList(modeArgs).contains("--out")) {
            Files.write(output, sentinel);
        }

        CliTestResult result = runSet(shape, input, output, modeArgs);

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Error code: USAGE_ERROR");
        if ("out-override".equals(mode)) {
            assertThat(result.stderr()).contains("--out cannot be combined with --override");
        } else if ("override-force".equals(mode)) {
            assertThat(result.stderr()).contains("--force-out cannot be combined with --override");
        } else {
            assertThat(result.stderr()).contains("--force-out requires --out");
        }
        assertThat(Files.readAllBytes(input)).containsExactly(original);
        if (Files.exists(output)) {
            assertThat(Files.readAllBytes(output)).containsExactly(sentinel);
        }
    }

    private static Stream<Arguments> contradictoryModes() {
        return setShapes().flatMap(shape -> Stream.of(
                Arguments.of(shape, "out-override", new String[]{"--out", "$OUT", "--override"}),
                Arguments.of(shape, "override-force", new String[]{"--override", "--force-out"}),
                Arguments.of(shape, "force-without-out", new String[]{"--force-out"})));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("samePathAliases")
    void copyAliasesRequireOverrideAndPreserveInput(String shape, String aliasKind) throws IOException {
        Path directory = Files.createDirectory(tempDir.resolve(shape + "-" + aliasKind));
        Path input = directory.resolve("input.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);
        Path output;
        if ("exact".equals(aliasKind)) {
            output = input;
        } else if ("normalized".equals(aliasKind)) {
            output = directory.resolve("unused").resolve("..").resolve(input.getFileName());
        } else if ("canonical".equals(aliasKind)) {
            output = directory.resolve("hard-link.jmx");
            Files.createLink(output, input);
        } else {
            Path aliasDirectory = Files.createSymbolicLink(tempDir.resolve(shape + "-symlink-dir"), directory);
            output = aliasDirectory.resolve(input.getFileName());
        }

        CliTestResult result = runSet(shape, input, output, "--out", "$OUT", "--force-out");

        assertThat(result.exitCode()).as(result.stderr()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("--out must differ", "--override");
        assertThat(Files.readAllBytes(input)).containsExactly(original);
    }

    private static Stream<Arguments> samePathAliases() {
        return setShapes().flatMap(shape -> Stream.of("exact", "normalized", "canonical", "symlink")
                .map(alias -> Arguments.of(shape, alias)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("setShapes")
    void existingTargetWithoutForceIsPreserved(String shape) throws IOException {
        Path input = tempDir.resolve(shape + "-existing-input.jmx");
        Path output = tempDir.resolve(shape + "-existing-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);
        byte[] sentinel = "existing-target".getBytes(StandardCharsets.UTF_8);
        Files.write(output, sentinel);

        CliTestResult result = runSet(shape, input, output, "--out", "$OUT");

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("--force-out");
        assertThat(Files.readAllBytes(input)).containsExactly(original);
        assertThat(Files.readAllBytes(output)).containsExactly(sentinel);
    }

    private static Stream<String> setShapes() {
        return Stream.of("scalar", "recursive");
    }

    private static CliTestResult runSet(String shape, Path input, Path output, String... modeArgs) {
        List<String> args = new ArrayList<String>();
        args.addAll(Arrays.asList("set", input.toString(), "--locator", HTTP_REQUEST_REF));
        if ("recursive".equals(shape)) {
            args.addAll(Arrays.asList(
                    "--property", ARGUMENTS_ADDRESS, "--type", "rows",
                    "--value", argumentRows("safety", "recursive")));
        } else {
            args.addAll(Arrays.asList(
                    "--property", DOMAIN_ADDRESS, "--value", "safety.example"));
        }
        for (String modeArg : modeArgs) {
            args.add("$OUT".equals(modeArg) ? output.toString() : modeArg);
        }
        return runMainWithLocalRuntime("", args.toArray(new String[args.size()]));
    }

    @Test
    void setCompatibilityStillRequiresLocatorPropertyValueAndWriteTarget() {
        Path input = fixture("simple-http.jmx");
        Path output = tempDir.resolve("missing-locator.jmx");

        CliTestResult result = runMainWithLocalRuntime("", "set", input.toString(),
                "--property", DOMAIN_ADDRESS, "--value", "changed.example", "--out", output.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(
                "Error code: USAGE_ERROR",
                "--locator is required",
                "copy a locator from read output");
        assertThat(output).doesNotExist();
    }

    private static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] sha256(Path path) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }

}
