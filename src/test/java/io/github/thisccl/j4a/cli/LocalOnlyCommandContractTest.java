package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.MainCliTestSupport.fixture;
import static io.github.thisccl.j4a.cli.MainCliTestSupport.runMain;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalOnlyCommandContractTest {
    @TempDir
    Path tempDir;

    @Test
    void catalogHelpExplainsLocalHomeResolutionWithoutProfilesOrRawExamples() {
        for (String command : Arrays.asList("components", "categories")) {
            CliTestResult result = runMain(Collections.emptyMap(), command, "--help");

            assertThat(result.exitCode()).as(command).isZero();
            assertThat(result.stderr()).as(command).isEmpty();
            assertThat(result.stdout()).as(command)
                    .contains("--jmeter-home", "JMX_AGENT_JMETER_HOME", "JMETER_HOME")
                    .doesNotContain("--profile", "--validation-mode", "menu_");
        }
    }

    @Test
    void everyRemovedFlagIsUnknownAndExitTwo() {
        for (String removed : Arrays.asList("--profile", "--validation-mode")) {
            for (List<String> command : commands(removed)) {
                CliTestResult result = runMain(Collections.emptyMap(), command.toArray(new String[0]));

                assertThat(result.exitCode()).as(String.join(" ", command)).isEqualTo(2);
                assertThat(result.stdout()).as(String.join(" ", command)).isEmpty();
                assertThat(result.stderr()).as(String.join(" ", command))
                        .contains("USAGE_ERROR", "Unknown option: " + removed);
            }
        }
    }

    @Test
    void removedApplyFlagsAreRejectedWithoutReadingAnyKindOfStdin() {
        for (String removed : Arrays.asList("--profile", "--validation-mode")) {
            for (String stdinKind : Arrays.asList("nonempty", "empty", "hanging")) {
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                PrintStream originalErr = System.err;
                try {
                    System.setErr(new PrintStream(stderr, true));
                    int exitCode = Main.run(applyArgs(removed), Collections.emptyMap(),
                            new ReadRejectingInputStream(stdinKind));

                    assertThat(exitCode).as(removed + " / " + stdinKind).isEqualTo(2);
                    assertThat(new String(stderr.toByteArray(), StandardCharsets.UTF_8))
                            .contains("USAGE_ERROR", "Unknown option: " + removed);
                } finally {
                    System.setErr(originalErr);
                }
            }
        }
    }

    private String[] applyArgs(String removed) {
        return new String[] {"apply", fixture("simple-http.jmx").toString(),
                "--patch", "-", "--dry-run", removed, "pure"};
    }

    private static final class ReadRejectingInputStream extends InputStream {
        private final String kind;

        private ReadRejectingInputStream(String kind) {
            this.kind = kind;
        }

        @Override
        public int read() throws IOException {
            throw new AssertionError(kind + " stdin must not be read");
        }
    }

    private List<List<String>> commands(String removed) {
        String input = fixture("simple-http.jmx").toString();
        return Arrays.asList(
                Arrays.asList("read", input, removed, "pure"),
                Arrays.asList("validate", input, removed, "pure"),
                Arrays.asList("components", removed, "pure"),
                Arrays.asList("categories", "ls", removed, "pure"),
                Arrays.asList("apply", input, "--patch", "-", "--dry-run", removed, "pure"),
                Arrays.asList("init", tempDir.resolve("init.jmx").toString(), removed, "pure"),
                Arrays.asList("set", input, "--locator", "jmx_0", "--property", "name", "--value", "x",
                        "--out", tempDir.resolve("set.jmx").toString(), removed, "pure"));
    }
}
