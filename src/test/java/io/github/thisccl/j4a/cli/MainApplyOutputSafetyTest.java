package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.defaultsOnlyHttpAddPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.invalidMixedPatch;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.propertyAsString;
import static io.github.thisccl.j4a.cli.ApplyMutationTestSupport.setPatch;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.fixture;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.apply.ApplyWriteModeResolver;
import io.github.thisccl.j4a.jmx.JmxLoadException;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.jmx.SaveServiceJmxLoader;
import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainApplyOutputSafetyTest {
    @TempDir
    Path tempDir;
    private DefaultLocalProfileQaFixtures fixtures;

    @BeforeEach
    void createRuntime() throws IOException {
        fixtures = DefaultLocalProfileQaFixtures.fresh();
        fixtures.ensure();
    }

    @Test
    void localRuntimeApplyReplacesAuthorizedOutputAfterCandidateRoundTrip() throws IOException {
        Path output = tempDir.resolve("local-profile-sentinel.jmx");
        Files.write(output, "sentinel-before-local-profile-add".getBytes(StandardCharsets.UTF_8));

        CliTestResult result = runLocal(setPatch("authorized.example"),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-",
                "--out", output.toString(), "--force-out");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains("appliedCount: 1");
        assertThat(new String(Files.readAllBytes(output), StandardCharsets.UTF_8)).contains("authorized.example");
    }

    @Test
    void nonDryRunRequiresOutOrOverride() throws IOException {
        CliTestResult result = runLocal(setPatch("api.example.test"),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("--out or --override is required");
    }

    @Test
    void existingOutRequiresForceOut() throws IOException {
        Path output = tempDir.resolve("existing-output.jmx");
        Files.write(output, "existing".getBytes(StandardCharsets.UTF_8));

        CliTestResult result = runLocal(setPatch("api.example.test"),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-", "--out", output.toString());

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("OUTPUT_FILE_EXISTS", "--force-out");
        assertThat(new String(Files.readAllBytes(output), StandardCharsets.UTF_8)).isEqualTo("existing");
    }

    @Test
    void invalidPatchDoesNotOverwriteExistingForcedOutput() throws IOException {
        Path output = tempDir.resolve("forced-output.jmx");
        Files.write(output, "existing".getBytes(StandardCharsets.UTF_8));

        CliTestResult result = runLocal(invalidMixedPatch(),
                "apply", fixture("simple-http.jmx").toString(), "--patch", "-",
                "--out", output.toString(), "--force-out");

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("LOCATOR_NOT_FOUND", "jmx_missing");
        assertThat(new String(Files.readAllBytes(output), StandardCharsets.UTF_8)).isEqualTo("existing");
    }

    @Test
    void overrideModifiesInputAndValidates() throws IOException {
        Path input = tempDir.resolve("override.jmx");
        Files.copy(fixture("simple-http.jmx"), input);

        CliTestResult result = runLocal(setPatch("override.example.test"),
                "apply", input.toString(), "--patch", "-", "--override");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains("appliedCount: 1");
        assertThat(new String(Files.readAllBytes(input), StandardCharsets.UTF_8))
                .contains("override.example.test");
    }

    @Test
    void outSameAsInputRequiresOverride() throws IOException {
        Path input = tempDir.resolve("same.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);

        CliTestResult result = runLocal(setPatch("api.example.test"),
                "apply", input.toString(), "--patch", "-", "--out", input.toString(), "--force-out");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("--out must differ", "--override");
        assertThat(Files.readAllBytes(input)).containsExactly(original);
    }

    @Test
    void aliasedOutSameAsInputRequiresOverrideAndPreservesInput() throws Exception {
        Path realDirectory = Files.createDirectory(tempDir.resolve("real-runtime"));
        Path input = realDirectory.resolve("aliased.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);
        Path aliasDirectory = createDirectoryJunction(tempDir.resolve("alias-runtime"), realDirectory);

        CliTestResult result = runLocal(setPatch("api.example.test"),
                "apply", input.toString(), "--patch", "-",
                "--out", aliasDirectory.resolve(input.getFileName()).toString(), "--force-out");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr()).contains("--out must differ", "--override");
        assertThat(Files.readAllBytes(input)).containsExactly(original);
    }

    @Test
    void authorizedCopyKeepsCanonicalParentWhenJunctionIsSwapped() throws Exception {
        Path inputDirectory = Files.createDirectory(tempDir.resolve("bound-input"));
        Path outputDirectory = Files.createDirectory(tempDir.resolve("bound-output"));
        Path input = inputDirectory.resolve("race.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] original = Files.readAllBytes(input);
        Path aliasDirectory = createDirectoryJunction(tempDir.resolve("bound-alias"), outputDirectory);
        Path target = aliasDirectory.resolve(input.getFileName());
        String yaml = setPatch("bound.example.test");
        io.github.thisccl.j4a.apply.ApplyPatch patch = new ApplyPatchParser().parse(yaml);
        ApplyWriteModeResolver.Resolution writeMode =
                ApplyWriteModeResolver.resolve(input, false, false, target.toString(), true);

        int exitCode = LocalApplyCliCommand.run(
                input, "-", yaml, patch, LocalJMeterRuntime.ofHome(fixtures.localHome()),
                writeMode, target.toString(), false,
                request -> {
                    removeDirectoryJunction(aliasDirectory);
                    createDirectoryJunction(aliasDirectory, inputDirectory);
                    return new LocalJMeterWorkerClient().execute(request).response();
                });

        assertThat(exitCode).isZero();
        assertThat(Files.readAllBytes(input)).containsExactly(original);
        assertThat(new String(Files.readAllBytes(outputDirectory.resolve(input.getFileName())), StandardCharsets.UTF_8))
                .contains("bound.example.test");
    }

    @Test
    void unforcedCopyDoesNotReplaceTargetCreatedAfterAuthorization() throws Exception {
        Path input = tempDir.resolve("late-input.jmx");
        Path output = tempDir.resolve("late-output.jmx");
        Files.copy(fixture("simple-http.jmx"), input);
        byte[] sentinel = "created-after-authorization".getBytes(StandardCharsets.UTF_8);
        String yaml = setPatch("late.example.test");
        io.github.thisccl.j4a.apply.ApplyPatch patch = new ApplyPatchParser().parse(yaml);
        ApplyWriteModeResolver.Resolution writeMode =
                ApplyWriteModeResolver.resolve(input, false, false, output.toString(), false);

        int exitCode = LocalApplyCliCommand.run(
                input, "-", yaml, patch, LocalJMeterRuntime.ofHome(fixtures.localHome()),
                writeMode, output.toString(), false,
                request -> {
                    try {
                        Files.write(output, sentinel);
                    } catch (IOException exception) {
                        throw new AssertionError(exception);
                    }
                    return new LocalJMeterWorkerClient().execute(request).response();
                });

        assertThat(exitCode).isEqualTo(4);
        assertThat(Files.readAllBytes(output)).containsExactly(sentinel);
    }

    private CliTestResult runLocal(String stdin, String... args) throws IOException {
        String[] runtimeArgs = java.util.Arrays.copyOf(args, args.length + 2);
        runtimeArgs[args.length] = "--jmeter-home";
        runtimeArgs[args.length + 1] = fixtures.localHome().toString();
        return runMain(stdin, Collections.<String, String>emptyMap(), runtimeArgs);
    }

    private static Path createDirectoryJunction(Path link, Path target) {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            try {
                return Files.createSymbolicLink(link, target);
            } catch (IOException exception) {
                throw new AssertionError("Could not create directory symbolic link", exception);
            }
        }
        try {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            assertThat(process.waitFor()).isZero();
            return link;
        } catch (IOException exception) {
            throw new AssertionError("Could not create directory junction", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while creating directory junction", exception);
        }
    }

    private static void removeDirectoryJunction(Path link) {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            try {
                Files.deleteIfExists(link);
                return;
            } catch (IOException exception) {
                throw new AssertionError("Could not remove directory symbolic link", exception);
            }
        }
        try {
            Process process = new ProcessBuilder("cmd.exe", "/c", "rmdir", link.toString())
                    .redirectErrorStream(true).start();
            assertThat(process.waitFor()).isZero();
        } catch (IOException exception) {
            throw new AssertionError("Could not remove directory junction", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while removing directory junction", exception);
        }
    }
}
