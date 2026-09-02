package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class MainLocalProfileResolutionTest {
    private static final String HTTP_REQUEST_REF = "jmx_330976848c8e";

    @TempDir
    Path tempDir;

    @Test
    void localProfileCommandsUseExplicitJMeterHomeBeforeEnvironmentHomes() throws IOException {
        Path explicit = validLocalHome("explicit");
        Path agent = validLocalHome("agent");
        Path jmeter = validLocalHome("jmeter");
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JMX_AGENT_JMETER_HOME", agent.toString());
        environment.put("JMETER_HOME", jmeter.toString());

        List<CliResult> results = runCommands(commands("--jmeter-home", explicit.toString()), environment);

        assertCommandsUseHome(results, explicit, agent.toString(), jmeter.toString());
    }

    @Test
    void localProfileCommandsUseAgentEnvironmentHomeBeforeJMeterHome() throws IOException {
        Path agent = validLocalHome("agent");
        Path jmeter = validLocalHome("jmeter");
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("JMX_AGENT_JMETER_HOME", agent.toString());
        environment.put("JMETER_HOME", jmeter.toString());

        List<CliResult> results = runCommands(commands(), environment);

        assertCommandsUseHome(results, agent, jmeter.toString());
    }

    @Test
    void localProfileCommandsUseJMeterHomeWhenAgentEnvironmentHomeIsAbsent() throws IOException {
        Path jmeter = validLocalHome("jmeter");
        Map<String, String> environment = Collections.singletonMap("JMETER_HOME", jmeter.toString());

        List<CliResult> results = runCommands(commands(), environment);

        assertCommandsUseHome(results, jmeter);
    }

    private static List<CliResult> runCommands(List<CommandCase> commands, Map<String, String> environment) {
        List<CliResult> results = new ArrayList<>();
        for (CommandCase command : commands) {
            results.add(command.run(environment));
        }
        return results;
    }

    private static void assertCommandsUseHome(List<CliResult> results, Path expectedHome, String... forbiddenHomes) {
        List<Executable> assertions = new ArrayList<>();
        for (CliResult result : results) {
            assertions.add(() -> {
                assertLocalProfileFailureUses(result, expectedHome);
                if (forbiddenHomes.length > 0) {
                    assertThat(result.stderr())
                            .as(result.command() + " stderr")
                            .doesNotContain(forbiddenHomes);
                }
            });
        }
        assertAll("local runtime resolution results", assertions);
    }

    private static void assertLocalProfileFailureUses(CliResult result, Path expectedHome) {
        assertThat(result.stdout()).as(result.command() + " stdout").isEmpty();
        assertThat(result.stderr()).as(result.command() + " stderr").contains(
                "Error code: LOCAL_JMETER_RUNTIME_ERROR",
                "Category: runtime",
                expectedHome.toAbsolutePath().normalize().toString());
        assertThat(result.exitCode()).as(result.command() + " exit code").isEqualTo(4);
    }

    private List<CommandCase> commands(String... extraArgs) {
        Path fixture = fixture("simple-http.jmx");
        Path setOutput = tempDir.resolve("set-output.jmx");
        String[] read = append(new String[] {"read", fixture.toString()}, extraArgs);
        String[] validate = append(new String[] {"validate", fixture.toString()}, extraArgs);
        String[] components = append(new String[] {"components"}, extraArgs);
        String[] apply = append(new String[] {"apply", fixture.toString(), "--patch", "-", "--dry-run"}, extraArgs);
        String[] set = append(new String[] {
                "set",
                fixture.toString(),
                "--locator",
                HTTP_REQUEST_REF,
                "--property",
                "[\"HTTPSampler.domain\"]",
                "--value",
                "changed.example",
                "--out",
                setOutput.toString()
        }, extraArgs);
        return Arrays.asList(
                new CommandCase("read", "", read),
                new CommandCase("validate", "", validate),
                new CommandCase("components", "", components),
                new CommandCase("apply", setPatch(), apply),
                new CommandCase("set", "", set));
    }

    private static String[] append(String[] args, String... extraArgs) {
        String[] combined = new String[args.length + extraArgs.length];
        System.arraycopy(args, 0, combined, 0, args.length);
        System.arraycopy(extraArgs, 0, combined, args.length, extraArgs.length);
        return combined;
    }

    private Path validLocalHome(String name) throws IOException {
        Path home = tempDir.resolve(name);
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        Files.write(home.resolve("bin").resolve("jmeter.properties"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(home.resolve("bin").resolve("saveservice.properties"), "".getBytes(StandardCharsets.UTF_8));
        Files.write(home.resolve("bin").resolve("upgrade.properties"), "".getBytes(StandardCharsets.UTF_8));
        return home;
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(MainLocalProfileResolutionTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Fixture URI is invalid: " + name, exception);
        }
    }

    private static String setPatch() {
        return String.format("changes:\n  - set:\n      ref: %s\n      component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui\n      properties:\n        - property: [HTTPSampler.domain]\n          value: changed.example\n          type: string\n", HTTP_REQUEST_REF);
    }

    private static final class CommandCase {
        private final String name;
        private final String stdin;
        private final String[] args;

        private CommandCase(String name, String stdin, String[] args) {
            this.name = name;
            this.stdin = stdin;
            this.args = args;
        }

        private CliResult run(Map<String, String> environment) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            java.io.InputStream originalIn = System.in;
            try {
                System.setOut(new PrintStream(stdout, true));
                System.setErr(new PrintStream(stderr, true));
                System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
                int exitCode = Main.run(args, environment);
                return new CliResult(
                        name,
                        exitCode,
                        new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                        new String(stderr.toByteArray(), StandardCharsets.UTF_8));
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
                System.setIn(originalIn);
            }
        }
    }

    private static final class CliResult {
        private final String command;
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private CliResult(String command, int exitCode, String stdout, String stderr) {
            this.command = command;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        private String command() {
            return command;
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

        @Override
        public String toString() {
            return command + " exit=" + exitCode + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
        }
    }
}
