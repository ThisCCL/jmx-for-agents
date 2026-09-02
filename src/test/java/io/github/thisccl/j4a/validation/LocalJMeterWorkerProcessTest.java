package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalJMeterWorkerProcessTest {
    @TempDir
    Path tempDir;

    @Test
    void unknownExitCodeRemainsAJava8PrimitiveSentinel() throws Exception {
        assertThat(LocalJMeterWorkerResult.class.getDeclaredField("exitCode").getType())
                .isEqualTo(Integer.TYPE);
        LocalJMeterWorkerResult result = LocalJMeterWorkerClient.result(
                LocalJMeterWorkerResponse.failure(
                        LocalJMeterWorkerRequest.componentDetails(tempDir, "fixture"),
                        "LOCAL_JMETER_RUNTIME_ERROR", "runtime", null,
                        "fixture", "fixture", "", ""),
                false,
                -1);

        assertThat(result.exitCode()).isEqualTo(-1);
    }

    @Test
    void cleanupHelpersIgnoreAttackerControlledPathAndCurrentDirectory() throws Exception {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("PATH", tempDir.toString());

        if (isWindows()) {
            writeExecutable(tempDir.resolve("powershell.exe"));
            writeExecutable(tempDir.resolve("taskkill.exe"));

            List<String> lookup = LocalJMeterWorkerProcess.processIdLookupCommandForTesting("worker", environment);
            List<String> kill = LocalJMeterWorkerProcess.killProcessTreeCommandForTesting(1234L, environment);

            assertTrustedHelper(lookup.get(0), tempDir);
            assertTrustedHelper(kill.get(0), tempDir);
            assertThat(lookup.get(0)).endsWith("powershell.exe");
            assertThat(kill.get(0)).endsWith("taskkill.exe");
        } else {
            writeExecutable(tempDir.resolve("sh"));
            writeExecutable(tempDir.resolve("pkill"));
            writeExecutable(tempDir.resolve("kill"));
            writeExecutable(tempDir.resolve("sleep"));

            List<String> kill = LocalJMeterWorkerProcess.killProcessTreeCommandForTesting(1234L, environment);

            assertTrustedHelper(kill.get(0), tempDir);
            assertThat(kill.get(1)).isEqualTo("-c");
            assertThat(kill.get(2)).doesNotContain(tempDir.toString());
        }
    }

    @Test
    void windowsCleanupHelpersIgnoreHostileSystemRoot() throws Exception {
        if (!isWindows()) {
            return;
        }
        Path hostileRoot = tempDir.resolve("attacker-windows");
        writeExecutable(hostileRoot.resolve("System32").resolve("WindowsPowerShell").resolve("v1.0")
                .resolve("powershell.exe"));
        writeExecutable(hostileRoot.resolve("System32").resolve("taskkill.exe"));
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("SystemRoot", hostileRoot.toString());

        List<String> lookup = LocalJMeterWorkerProcess.processIdLookupCommandForTesting("worker", environment);
        List<String> kill = LocalJMeterWorkerProcess.killProcessTreeCommandForTesting(1234L, environment);

        assertTrustedHelper(lookup.get(0), hostileRoot);
        assertTrustedHelper(kill.get(0), hostileRoot);
        assertThat(lookup.get(0)).endsWith("powershell.exe");
        assertThat(kill.get(0)).endsWith("taskkill.exe");
    }

    @Test
    void exitedWorkerCleanupIgnoresRecycledRootPidAndSelectsOnlyExactMarkerOwners() {
        if (!isWindows()) {
            return;
        }
        long recycledRootPid = 424242L;
        String workerId = "worker-a1b2c3";

        List<String> cleanup = LocalJMeterWorkerProcess.exitedWorkerCleanupCommandForTesting(
                recycledRootPid, workerId, System.getenv());

        assertThat(cleanup).hasSize(4);
        String script = cleanup.get(3);
        assertThat(script)
                .doesNotContain(String.valueOf(recycledRootPid), "ParentProcessId", "$root", "taskkill")
                .contains("-Dj4a.worker.id=" + workerId)
                .contains("$candidate.Name -ine 'java.exe'")
                .doesNotContain("java*", "javaw.exe", "java-helper.exe")
                .contains("$arguments -contains $marker")
                .contains("$_.ProcessId -eq $procId")
                .contains("Stop-Process -Id $procId -Force");
        assertThat(script.indexOf("$_.ProcessId -eq $procId"))
                .isLessThan(script.indexOf("Stop-Process -Id $procId -Force"));
    }

    @Test
    void liveWorkerCleanupRetainsRecursiveTreeTerminationBehindExactRootMarkerCheck() {
        if (!isWindows()) {
            return;
        }
        long liveRootPid = 525252L;
        String workerId = "worker-d4e5f6";

        List<String> cleanup = LocalJMeterWorkerProcess.liveWorkerCleanupCommandForTesting(
                liveRootPid, workerId, System.getenv());

        assertThat(cleanup).hasSize(4);
        String script = cleanup.get(3);
        assertThat(script)
                .contains("$_.ProcessId -eq " + liveRootPid)
                .contains("-Dj4a.worker.id=" + workerId)
                .contains("$candidate.Name -ine 'java.exe'")
                .doesNotContain("java*", "javaw.exe", "java-helper.exe")
                .contains("$arguments -contains $marker")
                .contains("taskkill.exe", "/PID", "/T", "/F");
        int taskkill = script.indexOf("taskkill.exe");
        int finalPidRefresh = script.lastIndexOf("$_.ProcessId -eq " + liveRootPid, taskkill);
        int finalOwnershipCheck = script.lastIndexOf("Test-OwnedJava", taskkill);
        assertThat(finalPidRefresh).isPositive();
        assertThat(finalOwnershipCheck).isGreaterThan(finalPidRefresh).isLessThan(taskkill);
    }

    private static void writeExecutable(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, "attacker".getBytes(StandardCharsets.UTF_8));
        path.toFile().setExecutable(true);
    }

    private static void assertTrustedHelper(String executable, Path attackerRoot) {
        Path resolved = java.nio.file.Paths.get(executable).toAbsolutePath().normalize();
        assertThat(resolved).isAbsolute();
        assertThat(resolved.toString()).doesNotStartWith(attackerRoot.toAbsolutePath().normalize().toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
