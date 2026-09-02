package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.cli.CommandResult;
import io.github.thisccl.j4a.cli.J4aCommandExecutor;
import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpCliRuntimeParityTest {
    private DefaultLocalProfileQaFixtures fixtures;

    @BeforeEach
    void createRuntime() throws Exception {
        fixtures = DefaultLocalProfileQaFixtures.fresh();
        fixtures.ensure();
    }

    @Test
    void equivalentRuntimeInputsProduceTheSameErrorEnvelope() {
        String file = Paths.get("src/test/resources/fixtures/simple-http.jmx").toString();
        String missingHome = Paths.get("build/qa/missing-jmeter-home").toAbsolutePath().toString();
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("file", file);
        arguments.put("jmeter_home", missingHome);
        McpToolInvocation invocation = McpToolInvocation.from("validate", arguments);
        J4aCommandExecutor executor = new J4aCommandExecutor();

        CommandResult mcp = executor.execute(invocation.args(), Collections.<String, String>emptyMap());
        CommandResult cli = executor.execute(
                new String[] {"validate", file, "--jmeter-home", missingHome},
                Collections.<String, String>emptyMap());

        assertThat(mcp.exitCode()).isEqualTo(cli.exitCode());
        assertThat(mcp.diagnostics()).hasSize(1);
        assertThat(cli.diagnostics()).hasSize(1);
        assertThat(mcp.diagnostics().get(0).code()).isEqualTo(cli.diagnostics().get(0).code());
        assertThat(mcp.diagnostics().get(0).category()).isEqualTo(cli.diagnostics().get(0).category());
        assertThat(mcp.diagnostics().get(0).message()).isEqualTo(cli.diagnostics().get(0).message());
    }

    @Test
    void successfulReadProducesEqualYaml() throws Exception {
        String file = fixtures.root().resolve("basic.jmx").toString();
        Map<String, Object> arguments = runtimeArguments();
        arguments.put("file", file);

        CommandResult mcp = executeMcp("read", arguments);
        CommandResult cli = executeCli("read", file, "--jmeter-home", fixtures.localHome().toString());

        assertThat(mcp.exitCode()).isZero();
        assertThat(mcp.textOutput()).isEqualTo(cli.textOutput()).contains("root:");
        assertThat(mcp.structuredData()).isEqualTo(cli.structuredData());
    }

    @Test
    void applyExistingTargetsFailEquallyAndPreserveBytes() throws Exception {
        Path mcpOutput = fixtures.root().resolve("out").resolve("mcp-existing.jmx");
        Path cliOutput = fixtures.root().resolve("out").resolve("cli-existing.jmx");
        byte[] sentinel = "existing-output".getBytes(StandardCharsets.UTF_8);
        Files.write(mcpOutput, sentinel);
        Files.write(cliOutput, sentinel);
        String yaml = setPatch("parity.example");
        Map<String, Object> arguments = runtimeArguments();
        arguments.put("file", fixtures.root().resolve("basic.jmx").toString());
        arguments.put("patchYaml", yaml);
        arguments.put("out", mcpOutput.toString());

        CommandResult mcp = executeMcp("apply", arguments);
        CommandResult cli = executeCliWithStdin(yaml, "apply", fixtures.root().resolve("basic.jmx").toString(),
                "--patch", "-", "--out", cliOutput.toString(), "--jmeter-home", fixtures.localHome().toString());

        assertEquivalentError(mcp, cli);
        assertThat(Files.readAllBytes(mcpOutput)).containsExactly(sentinel);
        assertThat(Files.readAllBytes(cliOutput)).containsExactly(sentinel);
    }

    @Test
    void initAndSetAuthorizedWritesProduceEqualFiles() throws Exception {
        Path mcpInit = fixtures.root().resolve("out").resolve("mcp-init.jmx");
        Path cliInit = fixtures.root().resolve("out").resolve("cli-init.jmx");
        Map<String, Object> initArguments = runtimeArguments();
        initArguments.put("out", mcpInit.toString());
        initArguments.put("name", "Parity Plan");
        initArguments.put("threadGroupName", "Parity Threads");

        CommandResult mcpInitResult = executeMcp("init", initArguments);
        CommandResult cliInitResult = executeCli("init", cliInit.toString(), "--name", "Parity Plan",
                "--thread-group-name", "Parity Threads", "--jmeter-home", fixtures.localHome().toString());

        assertThat(mcpInitResult.exitCode()).isEqualTo(cliInitResult.exitCode()).isZero();
        assertThat(Files.readAllBytes(mcpInit)).containsExactly(Files.readAllBytes(cliInit));

        Path mcpSet = fixtures.root().resolve("out").resolve("mcp-set.jmx");
        Path cliSet = fixtures.root().resolve("out").resolve("cli-set.jmx");
        Map<String, Object> setArguments = runtimeArguments();
        setArguments.put("file", fixtures.root().resolve("basic.jmx").toString());
        setArguments.put("locator", "jmx_330976848c8e");
        setArguments.put("property", Arrays.asList("HTTPSampler.domain"));
        setArguments.put("value", "parity.example");
        setArguments.put("out", mcpSet.toString());

        CommandResult mcpSetResult = executeMcp("set", setArguments);
        CommandResult cliSetResult = executeCli("set", fixtures.root().resolve("basic.jmx").toString(),
                "--locator", "jmx_330976848c8e", "--property", "[\"HTTPSampler.domain\"]",
                "--value", "parity.example", "--out", cliSet.toString(),
                "--jmeter-home", fixtures.localHome().toString());

        assertThat(mcpSetResult.exitCode()).isEqualTo(cliSetResult.exitCode()).isZero();
        assertThat(Files.readAllBytes(mcpSet)).containsExactly(Files.readAllBytes(cliSet));
    }

    @Test
    void initParentFileFailuresHaveFilesystemParityAndPreserveSentinels() throws Exception {
        byte[] sentinel = "parent-sentinel".getBytes(StandardCharsets.UTF_8);
        Path mcpParent = fixtures.root().resolve("mcp-parent-file");
        Path cliParent = fixtures.root().resolve("cli-parent-file");
        Files.write(mcpParent, sentinel);
        Files.write(cliParent, sentinel);
        Path mcpTarget = mcpParent.resolve("child.jmx");
        Path cliTarget = cliParent.resolve("child.jmx");
        Map<String, Object> arguments = runtimeArguments();
        arguments.put("out", mcpTarget.toString());

        CommandResult mcp = executeMcp("init", arguments);
        CommandResult cli = executeCli(
                "init", cliTarget.toString(), "--jmeter-home", fixtures.localHome().toString());

        assertEquivalentError(mcp, cli);
        assertThat(mcp.exitCode()).isEqualTo(4);
        assertThat(mcp.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("FILESYSTEM_WRITE_ERROR");
            assertThat(diagnostic.category()).isEqualTo("filesystem");
            assertThat(diagnostic.suggestedNextAction()).isEqualTo(
                    "check the output path and filesystem permissions, then retry the command.");
        });
        assertThat(Files.exists(mcpTarget)).isFalse();
        assertThat(Files.exists(cliTarget)).isFalse();
        assertThat(Files.readAllBytes(mcpParent)).containsExactly(sentinel);
        assertThat(Files.readAllBytes(cliParent)).containsExactly(sentinel);
    }

    private Map<String, Object> runtimeArguments() throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("jmeter_home", fixtures.localHome().toString());
        return arguments;
    }

    private static CommandResult executeMcp(String tool, Map<String, Object> arguments) {
        McpToolInvocation invocation = McpToolInvocation.from(tool, arguments);
        return new J4aCommandExecutor().execute(
                invocation.args(), Collections.<String, String>emptyMap(), invocation.stdin());
    }

    private static CommandResult executeCli(String... args) {
        return new J4aCommandExecutor().execute(args, Collections.<String, String>emptyMap());
    }

    private static CommandResult executeCliWithStdin(String stdin, String first, String... rest) {
        String[] args = new String[rest.length + 1];
        args[0] = first;
        System.arraycopy(rest, 0, args, 1, rest.length);
        return new J4aCommandExecutor().execute(args, Collections.<String, String>emptyMap(),
                new java.io.ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertEquivalentError(CommandResult mcp, CommandResult cli) {
        assertThat(mcp.exitCode()).isEqualTo(cli.exitCode());
        assertThat(mcp.diagnostics()).hasSize(1);
        assertThat(cli.diagnostics()).hasSize(1);
        assertThat(mcp.diagnostics().get(0).code()).isEqualTo(cli.diagnostics().get(0).code());
        assertThat(mcp.diagnostics().get(0).category()).isEqualTo(cli.diagnostics().get(0).category());
    }

    private static String setPatch(String domain) {
        return "changes:\n  - set:\n      ref: jmx_330976848c8e\n"
                + "      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n"
                + "        - property: [HTTPSampler.domain]\n          value: " + domain
                + "\n          type: string\n";
    }
}
