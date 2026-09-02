package io.github.thisccl.j4a.cli;


import java.nio.file.Paths;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReadCommandGoldenTest {
    @Test
    void defaultReadEmitsYamlSummaryForSimpleHttpFixture() {
        CommandResult result = runRead("simple-http.jmx");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(result.stdout()).get("root"));
        assertThat(root).containsEntry("component", "org.apache.jmeter.testelement.TestPlan")
                .containsEntry("name", "Synthetic Test Plan");
        assertThat(root).doesNotContainKey("properties");
        assertThat(mapping(list(root.get("children")).get(0)))
                .containsEntry("component", "org.apache.jmeter.threads.gui.ThreadGroupGui")
                .containsEntry("children_omitted", true);
        assertThat(result.stdout()).doesNotContain("Set card:", "Move card:", "Delete card:", "```");
    }

    @Test
    void propertiesAllEmitsMoreEditablePropertiesThanKeyMode() {
        CommandResult keyResult = runRead("simple-http.jmx", "--properties", "key");
        CommandResult allResult = runRead("simple-http.jmx", "--properties", "all");

        assertThat(keyResult.exitCode()).isZero();
        assertThat(allResult.exitCode()).isZero();
        assertThat(propertyCount(allResult.stdout())).isGreaterThan(propertyCount(keyResult.stdout()));
        assertThat(allResult.stdout()).contains(
                "- property:\n    - TestElement.name",
                "- ThreadGroup.main_controller",
                "- LoopController.loops");
        assertThat(allResult.stdout()).doesNotContain(
                "TestElement\\.gui_class",
                "TestElement\\.test_class",
                "ThreadGroup\\.main_controller.TestElement\\.gui_class",
                "ThreadGroup\\.main_controller.TestElement\\.test_class");
    }

    @Test
    void focusedReadEmitsPathAndPatchCompatibleProperties() {
        CommandResult result = runRead("simple-http.jmx", "--ref", "jmx_330976848c8e");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        Map<String, Object> yaml = CliYamlAssertions.parseMapping(result.stdout());
        assertThat(yaml).containsOnlyKeys("path", "focus");
        Map<String, Object> focus = mapping(yaml.get("focus"));
        assertThat(focus).containsEntry("ref", "jmx_330976848c8e")
                .containsEntry("component", "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        assertThat(list(focus.get("properties")))
                .extracting(property -> mapping(property).get("property"))
                .contains(
                        java.util.Arrays.asList("HTTPSampler.domain"),
                        java.util.Arrays.asList("HTTPSampler.path"),
                        java.util.Arrays.asList("HTTPSampler.method"));
    }

    @Test
    void defaultReadSummarizesDisabledComponentsWithoutEditableDetails() {
        CommandResult result = runRead("disabled-component.jmx", "--depth", "2", "--properties", "key");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        Map<String, Object> root = mapping(CliYamlAssertions.parseMapping(result.stdout()).get("root"));
        Map<String, Object> threadGroup = mapping(list(root.get("children")).get(0));
        Map<String, Object> sampler = mapping(list(threadGroup.get("children")).get(0));
        assertThat(sampler).containsEntry("component", "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui")
                .containsEntry("enabled", false)
                .containsEntry("child_count", 0);
        assertThat(sampler).doesNotContainKey("properties");
    }

    @Test
    void includeDisabledDetailsExpandsDisabledComponents() {
        CommandResult result = runRead(
                "disabled-component.jmx",
                "--depth",
                "2",
                "--properties",
                "key",
                "--include-disabled-details");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout()).contains(
                "name: Disabled HTTP Request",
                "- property:\n        - HTTPSampler.path",
                "value: /disabled");
    }

    private static CommandResult runRead(String fixtureName, String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        String[] command = new String[4 + args.length];
        command[0] = "read";
        command[1] = fixture(fixtureName).toString();
        System.arraycopy(args, 0, command, 2, args.length);
        command[2 + args.length] = "--jmeter-home";
        command[3 + args.length] = io.github.thisccl.j4a.TestJMeterRuntime.home().toString();

        try {
            System.setOut(new PrintStream(stdout, true));
            System.setErr(new PrintStream(stderr, true));
            int exitCode = Main.run(command);
            return new CommandResult(exitCode, new String(stdout.toByteArray(), StandardCharsets.UTF_8), new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(ReadCommandGoldenTest.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Fixture URI is invalid: " + name, exception);
        }
    }

    private static long propertyCount(String yaml) {
        return java.util.Arrays.stream(yaml.split("\\R")).filter(line -> line.trim().startsWith("- property:")).count();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
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
