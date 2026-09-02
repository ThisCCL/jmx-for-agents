package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemovedProfilesContractTest {
    private static final List<String> COMMANDS = Arrays.asList(
            "read", "set", "validate", "components", "categories", "apply", "init");

    @Test
    void publicHelpExposesOnlyLocalJMeterHomeConfiguration() {
        for (String command : COMMANDS) {
            CapturedResult result = help(command);

            assertThat(result.exitCode()).as(command).isZero();
            assertThat(result.stderr()).as(command).isEmpty();
            assertThat(result.stdout()).as(command)
                    .contains("--jmeter-home")
                    .doesNotContain("--profile", "--validation-mode", "profile local", "profile pure");
        }
    }

    @Test
    void obsoleteDeveloperScratchGuidanceDoesNotReturn() {
        assertThat(Paths.get("DEV-NEXT-TODO.md"))
                .as("obsolete actionable profile and alias guidance")
                .doesNotExist();
    }

    @Test
    void publicGuidanceDescribesCandidateVerificationBeforeCommit() throws Exception {
        String guidance = String.join("\n",
                new String(Files.readAllBytes(Paths.get("README.md")), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(Paths.get("skills/j4a-master/SKILL.md")), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(Paths.get("skills/j4a-master/references/apply.md")), StandardCharsets.UTF_8));

        assertThat(guidance)
                .contains("save, reload, and project the owned candidate before committing it")
                .contains("preserves the existing target bytes")
                .contains("Dry-run validates the patch and writes no JMX file")
                .doesNotContain(
                        "After writing, `set` validates",
                        "may persist before later validation fails",
                        "rollback after failed validation",
                        "failures may occur after bytes have been persisted");
    }

    @Test
    void packagedMcpExamplesUseSchemaFieldNamesAndDoNotInventPlacementMetadata() throws Exception {
        for (String command : COMMANDS) {
            String reference = new String(Files.readAllBytes(
                    Paths.get("skills/j4a-master/references/" + command + ".md")), StandardCharsets.UTF_8);
            assertThat(reference).as(command)
                    .contains("Inspect the live schema")
                    .doesNotContain("jmeterHome", "validationMode", "validation_mode");
        }

        String apply = new String(Files.readAllBytes(
                Paths.get("skills/j4a-master/references/apply.md")), StandardCharsets.UTF_8);
        assertThat(apply)
                .contains("Give every add/move one placement selector: `position`, `before`, or `after`.")
                .doesNotContain("Placement is not catalog metadata", "MenuFactory.canAddTo", "Map catalog placement");
    }

    private static CapturedResult help(String command) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));
            int exitCode = Main.run(new String[] {command, "--help"});
            return new CapturedResult(
                    exitCode,
                    new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                    new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static final class CapturedResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CapturedResult(int exitCode, String stdout, String stderr) {
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
