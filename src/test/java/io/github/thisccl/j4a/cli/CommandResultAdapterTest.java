package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.mcp.McpCommandResultAdapter;
import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class CommandResultAdapterTest {
    @Test
    void applyReceiptTextAndStructuredDataPreserveAuxiliaryResultsInContractOrder() {
        String receipt = "appliedCount: 3\n"
                + "opaqueDigest: sha256:replacement-digest\n"
                + "createdRefs:\n"
                + "- {alias: first, ref: abcdefghijklmnop}\n"
                + "- {alias: second, ref: ponmlkjihgfedcba}\n"
                + "deletedRefs: [deleted123456789, removed123456789]\n"
                + "changeResults:\n"
                + "- {index: 0, operation: add, status: committed, context: {alias: first, component: first.Component, parent: parent, position: last, properties: []}, resultRef: abcdefghijklmnop}\n"
                + "- {index: 1, operation: add, status: committed, context: {alias: second, component: second.Component, parent: parent, position: last, properties: []}, resultRef: ponmlkjihgfedcba}\n"
                + "- {index: 2, operation: delete, status: committed, context: {ref: deleted123456789}}\n";

        Map<String, Object> data = J4aCommandExecutor.structuredData(new String[] {
                "apply", "input.jmx", "--patch", "-", "--out", "caller/../spelled-output.jmx"
        }, receipt);

        assertThat(data.keySet()).containsExactly(
                "command", "format", "output", "dryRun", "writtenTarget", "opaqueDigest",
                "writeMode", "appliedCount", "createdRefs", "deletedRefs", "changeResults");
        assertThat(data)
                .containsEntry("command", "apply")
                .containsEntry("format", "text")
                .containsEntry("output", "caller/../spelled-output.jmx")
                .containsEntry("dryRun", Boolean.FALSE)
                .containsEntry("writtenTarget", "caller/../spelled-output.jmx")
                .containsEntry("opaqueDigest", "sha256:replacement-digest")
                .containsEntry("writeMode", "copy")
                .containsEntry("appliedCount", Integer.valueOf(3));
        assertThat(data.get("createdRefs")).isEqualTo(Arrays.asList(
                row("first", "abcdefghijklmnop"), row("second", "ponmlkjihgfedcba")));
        assertThat(data.get("deletedRefs")).isEqualTo(
                Arrays.asList("deleted123456789", "removed123456789"));
    }

    @Test
    void dryRunReceiptHasNoWrittenTargetOrDurableIdentities() {
        Map<String, Object> data = J4aCommandExecutor.structuredData(new String[] {
                "apply", "input.jmx", "--patch", "-", "--dry-run", "--out", "ignored.jmx"
        }, "appliedCount: 2\ncreatedRefs: []\ndeletedRefs: []\n"
                + "changeResults:\n"
                + "- {index: 0, operation: set, status: validated, context: {ref: one, properties: []}}\n"
                + "- {index: 1, operation: delete, status: validated, context: {ref: two}}\n");

        assertThat(data)
                .containsEntry("output", "ignored.jmx")
                .containsEntry("dryRun", Boolean.TRUE)
                .containsEntry("writtenTarget", null)
                .containsEntry("writeMode", "dry-run")
                .containsEntry("appliedCount", Integer.valueOf(2))
                .containsEntry("createdRefs", Collections.emptyList())
                .containsEntry("deletedRefs", Collections.emptyList());
    }

    @Test
    void forbiddenWorkerIdentityInternalsAreRejectedAtTheCommandBoundary() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> J4aCommandExecutor.structuredData(
                new String[] {"apply", "input.jmx", "--patch", "-", "--override"},
                "appliedCount: 1\ngeneration: 7\ncreatedRefs: []\ndeletedRefs: []\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation");
    }

    @Test
    void catalogResultsExposeTheExactYamlModelAsStructuredData() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
        Map<String, String> environment = new LinkedHashMap<String, String>();
        environment.put("JMX_AGENT_JMETER_HOME", fixtures.localHome().toString());
        J4aCommandExecutor executor = new J4aCommandExecutor();

        CommandResult categories = executor.execute(new String[] {"categories", "ls"}, environment);
        CommandResult components = executor.execute(
                new String[] {"components", "--category", "timer"}, environment);

        assertYamlStructuredParity(categories);
        assertYamlStructuredParity(components);
    }

    @Test
    void readCommandCanReturnCliEquivalentOutputWithoutWritingProcessStdout() throws Exception {
        Path fixture = MainCliTestSupport.fixture("simple-http.jmx");
        Path home = DefaultLocalProfileQaFixtures.fresh().localHome();
        Map<String, String> environment = Collections.singletonMap("JMX_AGENT_JMETER_HOME", home.toString());
        ByteArrayOutputStream processStdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        CommandResult result;
        try {
            System.setOut(new PrintStream(processStdout, true, StandardCharsets.UTF_8.name()));
            result = new J4aCommandExecutor().execute(new String[] {
                    "read", fixture.toString()
            }, environment, false);
        } finally {
            System.setOut(originalOut);
        }

        CliTestResult cli = MainCliTestSupport.runMainWithLocalRuntime(
                "", "read", fixture.toString());

        assertThat(new String(processStdout.toByteArray(), StandardCharsets.UTF_8)).isEmpty();
        assertThat(result.exitCode()).isZero();
        assertThat(result.textOutput()).isEqualTo(cli.stdout());
        assertThat(result.diagnostics()).isEmpty();
        assertThat(result.structuredData()).containsEntry("command", "read")
                .containsEntry("format", "yaml");
    }

    @Test
    void missingReadFileResultCarriesRecoveryGuidanceAndCliCompatibleStderr() throws Exception {
        Path home = DefaultLocalProfileQaFixtures.fresh().localHome();
        CommandResult result = new J4aCommandExecutor().execute(new String[] {
                "read", "build/tmp/does-not-exist-task-3.jmx"
        }, Collections.singletonMap("JMX_AGENT_JMETER_HOME", home.toString()), false);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CliCommandResultAdapter.emit(
                result,
                false,
                new PrintStream(stdout, true, StandardCharsets.UTF_8.name()),
                new PrintStream(stderr, true, StandardCharsets.UTF_8.name()));

        String renderedError = new String(stderr.toByteArray(), StandardCharsets.UTF_8);
        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.textOutput()).isEmpty();
        assertThat(result.diagnostics()).singleElement()
                .satisfies(diagnostic -> {
                    assertThat(diagnostic.code()).isEqualTo("JMX_READ_ERROR");
                    assertThat(diagnostic.suggestedNextAction())
                            .contains("check that the file exists");
                });
        assertThat(result.recoveryGuidance()).contains("check that the file exists");
        assertThat(new String(stdout.toByteArray(), StandardCharsets.UTF_8)).isEmpty();
        assertThat(renderedError).contains(
                "Error code: JMX_READ_ERROR",
                "Category: filesystem",
                "Suggested next action: check that the file exists and is readable, then retry the command.");

        Map<String, Object> mcpResult = new McpCommandResultAdapter().adapt(result);
        assertThat(mcpResult).containsEntry("isError", Boolean.TRUE);
        assertThat(mcpResult.get("structuredContent").toString())
                .contains("JMX_READ_ERROR", "recoveryGuidance", "check that the file exists");
    }

    @Test
    void missingSnapshotReferenceKeepsTheCliLocatorFailureContract() throws Exception {
        Path fixture = MainCliTestSupport.fixture("simple-http.jmx");
        Path home = DefaultLocalProfileQaFixtures.fresh().localHome();
        CommandResult result = new J4aCommandExecutor().execute(new String[] {
                "read", fixture.toString(), "--ref", "jmx_deadbeefdead"
        }, Collections.singletonMap("JMX_AGENT_JMETER_HOME", home.toString()), false);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        CliCommandResultAdapter.emit(
                result,
                false,
                new PrintStream(stdout, true, StandardCharsets.UTF_8.name()),
                new PrintStream(stderr, true, StandardCharsets.UTF_8.name()));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.textOutput()).isEmpty();
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("LOCATOR_NOT_FOUND");
            assertThat(diagnostic.category()).isEqualTo("locator");
            assertThat(diagnostic.suggestedNextAction())
                    .isEqualTo("rerun read and rebuild the patch with fresh refs, then retry apply.");
        });
        assertThat(new String(stdout.toByteArray(), StandardCharsets.UTF_8)).isEmpty();
        assertThat(new String(stderr.toByteArray(), StandardCharsets.UTF_8)).contains(
                "Error code: LOCATOR_NOT_FOUND",
                "Category: locator",
                "Suggested next action: rerun read and rebuild the patch with fresh refs, then retry apply.");
    }

    @SuppressWarnings("unchecked")
    private static void assertYamlStructuredParity(CommandResult result) {
        assertThat(result.exitCode()).as(result.recoveryGuidance()).isZero();
        Object parsed = new Yaml().load(result.textOutput());
        assertThat(parsed).isInstanceOf(Map.class);
        assertThat(result.structuredData()).isEqualTo((Map<String, Object>) parsed)
                .doesNotContainKeys("command", "format");
    }

    private static Map<String, String> row(String alias, String ref) {
        Map<String, String> row = new LinkedHashMap<String, String>();
        row.put("alias", alias);
        row.put("ref", ref);
        return row;
    }
}
