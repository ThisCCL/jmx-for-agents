package io.github.thisccl.j4a.cli;


import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainHelpTest {
    @TempDir
    Path tempDir;

    private static String setTypes() {
        return "string|boolean|int|long|float|double|null|raw|collection|map|element|rows|opaque";
    }

    @Test
    void helpListsExactCommandContractAndFlags() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));

            int exitCode = Main.run(new String[] {"--help"});

            assertThat(exitCode).isZero();
            assertThat(new String(stderr.toByteArray(), StandardCharsets.UTF_8)).isEmpty();
            assertThat(new String(stdout.toByteArray(), StandardCharsets.UTF_8)).contains(
                    "Usage: j4a <command> [options]",
                    "read <file> [--depth <n>] [--ref <ref>] [--properties key|all|writable] [--include-disabled-details] [--jmeter-home <path>]",
                    "set <file> --locator <id> --property <json-array> --value <value> (--out <file>|--override) [--force-out] [--type " + setTypes() + "] [--jmeter-home <path>]",
                    "validate <file> [--jmeter-home <path>]",
                    "components [<component>|--component-token <opaque>] [--category <category> --details true [--limit <1..50>] [--max-bytes <4096..65536>] [--cursor <opaque>]] [--jmeter-home <path>] [--diagnostics true]",
                    "categories ls [--jmeter-home <path>]",
                    "--depth <n>",
                    "--ref <ref>",
                    "--properties key|all|writable",
                    "--include-disabled-details",
                    "--locator",
                    "--property <json-array>",
                    "--value",
                    "--out",
                    "--override",
                    "--force-out",
                    "--type " + setTypes(),
                    "--category <category>",
                    "--jmeter-home",
                    "--debug");
            assertThat(new String(stdout.toByteArray(), StandardCharsets.UTF_8)).doesNotContain(
                    "--verbose", "--format markdown|yaml", "--property-address", "canonical strings");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void setHelpDocumentsUnifiedRowsAndExplicitStructuredTypes() {
        CommandResult result = runMain("set", "--help");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains(
                "--type " + setTypes(),
                "Structured object/list values require an explicit type copied from focused read.",
                "--property '[\"HTTPSampler.path\"]'",
                "Universal rows use --type rows");
    }

    @Test
    void categoriesHelpDescribesCategoryListingAndFilteringRelationship() {
        CommandResult categoriesHelp = runMain("categories", "--help");
        CommandResult categoriesLsHelp = runMain("categories", "ls", "--help");

        assertThat(categoriesHelp.exitCode()).isZero();
        assertThat(categoriesHelp.stderr()).isEmpty();
        assertThat(categoriesHelp.stdout()).contains(
                "Usage: j4a categories ls [--jmeter-home <path>]",
                "component_count",
                "components --category",
                "lower-kebab",
                "JMX_AGENT_JMETER_HOME",
                "one release");
        assertThat(categoriesLsHelp.exitCode()).isZero();
        assertThat(categoriesLsHelp.stderr()).isEmpty();
        assertThat(categoriesLsHelp.stdout()).contains(
                "Usage: j4a categories ls [--jmeter-home <path>]",
                "components --category");
        assertThat(categoriesHelp.stdout()).doesNotContain(
                "menu_", "--profile", "--validation-mode", "pure", "bundled");
    }

    @Test
    void componentsHelpDocumentsConciseListsAndEditableMetadataDetails() {
        CommandResult componentsHelp = runMain("components", "--help");

        assertThat(componentsHelp.exitCode()).isZero();
        assertThat(componentsHelp.stderr()).isEmpty();
        assertThat(componentsHelp.stdout()).contains(
                "Default list output includes category, component, and label fields",
                "Selecting a component returns compact writable authoring metadata",
                "--details true is a compatibility marker",
                "--diagnostics true for the full capability projection",
                "runtime-proven or runtime-metadata-unavailable",
                "metadata-source availability, not GUI completeness",
                "successful empty semantic observation is runtime-proven",
                "only worker replacement may observe again",
                "never open top-level windows",
                "never", "execute samplers/load",
                "lower-kebab",
                "categories ls",
                "JMX_AGENT_JMETER_HOME",
                "one release",
                "value_shape",
                "row_type",
                "runtime FQCN component ids");
        assertThat(componentsHelp.stdout()).doesNotContain(
                "diagnostic status metadata", "testclass", "guiclass",
                "group", "kind", "menu_", "--profile", "--validation-mode");
    }

    @Test
    void readHelpDocumentsGuiOnlyPropertyFiltering() {
        CommandResult readHelp = runMain("read", "--help");

        assertThat(readHelp.exitCode()).isZero();
        assertThat(readHelp.stderr()).isEmpty();
        assertThat(readHelp.stdout()).contains(
                "--properties all omits GUI/class-loader bookkeeping",
                "TestElement\\.gui_class",
                "TestElement\\.test_class");
    }

    @Test
    void setAndApplyHelpDocumentReusableRecursiveValueDocuments() {
        CommandResult setHelp = runMain("set", "--help");
        CommandResult applyHelp = runMain("apply", "--help");

        assertThat(setHelp.exitCode()).isZero();
        assertThat(setHelp.stderr()).isEmpty();
        assertThat(setHelp.stdout()).contains(
                "float", "null", "collection", "map", "element", "opaque",
                "one safe YAML value fragment",
                "exact mapping below value:",
                "scalar values remain literal");
        assertThat(applyHelp.exitCode()).isZero();
        assertThat(applyHelp.stderr()).isEmpty();
        assertThat(applyHelp.stdout()).contains(
                "complete property/type/value record from read",
                "unchanged",
                "append adds after the current final row",
                "insert accepts indexes from zero through the current row count",
                "remove requires an existing row index",
                "property: [\"HeaderManager.headers\"]",
                "row: {Header.name: X-Trace, Header.value: enabled}",
                "Header.name",
                "Header.value",
                "row_properties");
        assertThat(applyHelp.stdout()).doesNotContain(
                "escaped property path", "canonical component ids", "typed segment",
                "row: {name: X-Trace, value: enabled}");
    }

    @Test
    void categoriesLsRejectsExtraOperand() {
        CommandResult result = runMain("categories", "ls", "extra");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(
                "Error code: USAGE_ERROR",
                "Unsupported categories ls operand: extra",
                "categories ls --help");
    }

    @Test
    void categoriesLsRejectsUnsupportedCategoryOption() {
        CommandResult result = runMain("categories", "ls", "--category", "sampler");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains(
                "Error code: USAGE_ERROR",
                "--category is not supported by categories ls",
                "components --category");
    }

    @Test
    void setRequiresOutOrOverride() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));

            int exitCode = Main.run(new String[] {"set", "fixture.jmx", "--locator", "abc", "--property", "x", "--value", "y",
                    "--jmeter-home", io.github.thisccl.j4a.TestJMeterRuntime.home().toString()});

            assertThat(exitCode).isEqualTo(2);
            assertThat(new String(stderr.toByteArray(), StandardCharsets.UTF_8)).contains(
                    "Error code: USAGE_ERROR",
                    "Category: usage",
                    "--out or --override is required",
                    "Suggested next action");
            assertThat(new String(stdout.toByteArray(), StandardCharsets.UTF_8)).isEmpty();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void setWithKnownBadPropertyArrayReturnsPropertyPathError() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));

            int exitCode = Main.run(new String[] {
                    "set",
                    fixture("simple-http.jmx").toString(),
                    "--locator",
                    "jmx_330976848c8e",
                    "--property",
                    "[\"HTTPSampler.missing\"]",
                    "--value",
                    "y",
                    "--jmeter-home",
                    io.github.thisccl.j4a.TestJMeterRuntime.home().toString(),
                    "--out",
                    tempDir.resolve("out.jmx").toString()
            });

            assertThat(exitCode).isEqualTo(3);
            assertThat(new String(stdout.toByteArray(), StandardCharsets.UTF_8)).isEmpty();
            assertThat(new String(stderr.toByteArray(), StandardCharsets.UTF_8)).contains(
                    "Error code: MISSING_PROPERTY",
                    "Category: property-path",
                    "HTTPSampler.missing");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(MainHelpTest.class.getResource("/fixtures/" + name).toURI());
        } catch (Exception exception) {
            throw new IllegalStateException("Fixture URI is invalid: " + name, exception);
        }
    }

    private static CommandResult runMain(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));
            int exitCode = Main.run(args);
            return new CommandResult(exitCode, new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                    new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static final class CommandResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CommandResult(int exitCode, String stdout, String stderr) {
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
