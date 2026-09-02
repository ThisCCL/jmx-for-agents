package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainCliContractTest {
    private static final String[] COMMANDS = {"read", "set", "validate", "components", "apply"};
    private static final String[] SHARED_PATCH_FIELDS = {
            "ref", "component", "property", "value", "type", "parent", "before", "after", "position"
    };

    @Test
    void globalHelpListsCommands() {
        CliResult result = runMain("--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains(
                "Usage: j4a <command> [options]",
                "read <file> [--depth <n>] [--ref <ref>] [--properties key|all|writable] [--include-disabled-details] [--jmeter-home <path>]",
                "set <file> --locator <id> --property <json-array> --value <value>",
                "validate <file> [--jmeter-home <path>]",
                "components [<component>|--component-token <opaque>] [--category <category> --details true [--limit <1..50>] [--max-bytes <4096..65536>] [--cursor <opaque>]] [--jmeter-home <path>] [--diagnostics true]",
                "apply <file> --patch <file|-> ((--out <file> [--force-out])|--override|--dry-run [--out <ignored-file>]) [--jmeter-home <path>]");
        assertThat(result.stdout()).doesNotContain("--property-address", "canonical strings");
        for (String command : COMMANDS) {
            assertThat(result.stdout()).contains(command);
        }
    }

    @Test
    void commandHelpDocumentsCurrentYamlContractFlags() {
        CliResult readHelp = runMain("read", "--help");
        CliResult setHelp = runMain("set", "--help");
        CliResult validateHelp = runMain("validate", "--help");
        CliResult componentsHelp = runMain("components", "--help");
        CliResult applyHelp = runMain("apply", "--help");
        CliResult readShortHelp = runMain("read", "-h");
        CliResult setShortHelp = runMain("set", "-h");
        CliResult validateShortHelp = runMain("validate", "-h");
        CliResult applyShortHelp = runMain("apply", "-h");

        assertSuccessfulHelp(readHelp);
        assertSuccessfulHelp(setHelp);
        assertSuccessfulHelp(validateHelp);
        assertSuccessfulHelp(componentsHelp);
        assertSuccessfulHelp(applyHelp);
        assertSuccessfulHelp(readShortHelp);
        assertSuccessfulHelp(setShortHelp);
        assertSuccessfulHelp(validateShortHelp);
        assertSuccessfulHelp(applyShortHelp);
        assertThat(readHelp.stdout()).contains(
                "YAML",
                "--depth <n>",
                "--ref <ref>",
                "--properties key|all|writable",
                "property: [\"HTTPSampler.path\"]",
                "--include-disabled-details");
        assertThat(readHelp.stdout()).doesNotContain("--verbose", "--property-address", "canonical strings");
        assertThat(setHelp.stdout()).contains(
                "Edits one existing property",
                "--out",
                "--override",
                "Copy the locator and property array from read output",
                "--property '[\"HTTPSampler.path\"]'");
        assertThat(validateHelp.stdout()).contains(
                "Validation does not run a load test", "--jmeter-home <path>");
        assertThat(componentsHelp.stdout()).contains(
                "YAML",
                "--category <category>",
                "--details true",
                "--diagnostics true",
                "property: [\"HTTPSampler.path\"]",
                "metadata");
        assertThat(componentsHelp.stdout()).doesNotContain(
                "--format markdown|yaml", "addCard", "Pasteable", "--property-address", "canonical strings");
        assertThat(readShortHelp.stdout()).isEqualTo(readHelp.stdout());
        assertThat(setShortHelp.stdout()).isEqualTo(setHelp.stdout());
        assertThat(validateShortHelp.stdout()).isEqualTo(validateHelp.stdout());
        assertThat(applyShortHelp.stdout()).isEqualTo(applyHelp.stdout());
    }

    @Test
    void globalShortHelpMatchesLongHelp() {
        CliResult longHelp = runMain("--help");
        CliResult shortHelp = runMain("-h");

        assertSuccessfulHelp(longHelp);
        assertSuccessfulHelp(shortHelp);
        assertThat(shortHelp.stdout()).isEqualTo(longHelp.stdout());
    }

    @Test
    void commandHelpSharesReadComponentsApplyPatchVocabulary() {
        CliResult readHelp = runMain("read", "--help");
        CliResult componentsHelp = runMain("components", "--help");
        CliResult applyHelp = runMain("apply", "--help");

        assertSuccessfulHelp(readHelp);
        assertSuccessfulHelp(componentsHelp);
        assertSuccessfulHelp(applyHelp);
        for (String field : SHARED_PATCH_FIELDS) {
            assertThat(readHelp.stdout()).contains(field);
            assertThat(componentsHelp.stdout()).contains(field);
            assertThat(applyHelp.stdout()).contains(field);
        }
    }

    @Test
    void placementVocabularyUsesParentBeforeAfterAndPositionOnly() {
        CliResult readHelp = runMain("read", "--help");
        CliResult componentsHelp = runMain("components", "--help");
        CliResult applyHelp = runMain("apply", "--help");

        assertThat(readHelp.stdout()).contains("parent", "before", "after", "position");
        assertThat(componentsHelp.stdout()).contains("parent", "before", "after", "position");
        assertThat(applyHelp.stdout()).contains("parent", "before", "after", "position");
        assertThat(readHelp.stdout()).doesNotContain("previous", "next");
        assertThat(componentsHelp.stdout()).doesNotContain("previous", "next");
        assertThat(applyHelp.stdout()).doesNotContain("previous", "next");
    }

    @Test
    void yamlAssertionHelperParsesSharedVocabularyForLaterPhaseContracts() {
        Map<String, Object> parsed = CliYamlAssertions.parseMapping("ref: jmx_330976848c8e\ncomponent: http.request\nproperty: [HTTPSampler.path]\nvalue: /contract\ntype: string\nparent: jmx_19871e6efa95\nbefore: jmx_before\nafter: jmx_after\nposition: last\n");

        assertThat(parsed).containsKeys(SHARED_PATCH_FIELDS);
    }

    private static void assertSuccessfulHelp(CliResult result) {
        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
    }

    private static CliResult runMain(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));
            int exitCode = Main.run(args);
            return new CliResult(exitCode, new String(stdout.toByteArray(), StandardCharsets.UTF_8), new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static final class CliResult {


        private final int exitCode;


        private final String stdout;


        private final String stderr;



        private CliResult(int exitCode, String stdout, String stderr) {


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
