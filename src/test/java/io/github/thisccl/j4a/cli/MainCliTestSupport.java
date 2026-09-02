package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class MainCliTestSupport {
    private static final Object LOCAL_RUNTIME_LOCK = new Object();
    private static io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures localRuntimeFixtures;
    private static Path localRuntimeHome;

    private MainCliTestSupport() {
    }

    static CliTestResult runMain(Map<String, String> environment, String... args) {
        return runMain("", environment, args);
    }

    static CliTestResult runMain(String stdin, Map<String, String> environment, String... args) {
        return captureMain(stdin, environment, args);
    }

    static CliTestResult runMainWithLocalRuntime(String stdin, String... args) {
        Path localHome = localRuntimeHome();
        return captureMain(stdin,
                Collections.singletonMap("JMX_AGENT_JMETER_HOME", localHome.toString()), args);
    }

    private static Path localRuntimeHome() {
        synchronized (LOCAL_RUNTIME_LOCK) {
            if (localRuntimeHome == null) {
                localRuntimeFixtures =
                        io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures.cached();
                try {
                    localRuntimeHome = localRuntimeFixtures.localHome();
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(
                            "Unable to create deterministic local JMeter test runtime.", exception);
                }
            }
            return localRuntimeHome;
        }
    }

    static Path fixture(String name) {
        try {
            return Paths.get(MainCliTestSupport.class.getResource("/fixtures/" + name).toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Fixture URI is invalid: " + name, exception);
        }
    }

    static void assertUsageError(CliTestResult result, String message) {
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Error code: USAGE_ERROR", message);
    }

    private static CliTestResult captureMain(String stdin, Map<String, String> environment, String... args) {
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
            return new CliTestResult(
                    exitCode,
                    new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                    new String(stderr.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.setIn(originalIn);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }

}
