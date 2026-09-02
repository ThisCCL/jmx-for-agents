package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class LocalJMeterWorkerTestSupport {
    private LocalJMeterWorkerTestSupport() {
    }

    static LocalJMeterWorkerClient client(
            String javaExecutable, Duration startupTimeout, Duration preflightTimeout, Duration operationTimeout,
            List<String> jvmArgs)
            throws Exception {
        Constructor<LocalJMeterWorkerClient> constructor = LocalJMeterWorkerClient.class.getDeclaredConstructor(
                String.class, Duration.class, Duration.class, Duration.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(javaExecutable, startupTimeout, preflightTimeout, operationTimeout, jvmArgs);
    }

    static String currentJavaExecutable() {
        Path javaHome = java.nio.file.Paths.get(System.getProperty("java.home"));
        String executable = isWindows() ? "java.exe" : "java";
        return javaHome.resolve("bin").resolve(executable).toString();
    }

    static void assertNoLivePid(Path pidFile) throws IOException, InterruptedException {
        assertThat(pidFile).isRegularFile();
        String text = new String(java.nio.file.Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim();
        assertThat(text).isNotEmpty();
        long pid = Long.parseLong(text);
        if (isWindows()) {
            Process process = new ProcessBuilder("cmd", "/c", "tasklist /FI \"PID eq " + pid + "\" /NH").start();
            process.waitFor(5, TimeUnit.SECONDS);
            String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
            assertThat(output).doesNotContain(String.valueOf(pid));
        } else {
            Process process = new ProcessBuilder("sh", "-c", "kill -0 " + pid).start();
            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
            assertThat(exited).isTrue();
            assertThat(process.exitValue()).isNotEqualTo(0);
        }
    }

    static void assertClassNotLoadableInMainProcess(String className) {
        assertThatThrownBy(() -> Class.forName(className))
                .isInstanceOf(ClassNotFoundException.class);
    }

    static int countOccurrences(String text, String value) {
        int count = 0;
        int index = text.indexOf(value);
        while (index >= 0) {
            count++;
            index = text.indexOf(value, index + value.length());
        }
        return count;
    }

    private static byte[] readAll(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
