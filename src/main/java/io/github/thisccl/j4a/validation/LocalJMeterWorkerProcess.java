package io.github.thisccl.j4a.validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.WeakHashMap;

final class LocalJMeterWorkerProcess {
    private static final Map<Process, Long> PROCESS_IDS = new WeakHashMap<Process, Long>();
    private LocalJMeterWorkerProcess() {
    }

    static String javaExecutable() {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return javaHome.resolve("bin").resolve(executable).toString();
    }

    static void addRequiredJvmArguments(List<String> command) {
        command.add("-Djava.awt.headless=true");
    }

    static boolean terminateProcessTree(Process process, String workerId) throws InterruptedException {
        try {
            process.exitValue();
            return terminateExitedWorkerProcesses(workerId);
        } catch (IllegalThreadStateException ignored) {
        }
        Long pid = processId(process, workerId);
        if (pid == null) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return false;
        }
        boolean treeKillCompleted = killProcessTree(pid.longValue(), workerId);
        process.destroyForcibly();
        return treeKillCompleted && process.waitFor(5, TimeUnit.SECONDS);
    }

    static synchronized void recordProcessId(Process process, String workerId) {
        Long pid = processId(process, workerId);
        if (pid != null) {
            PROCESS_IDS.put(process, pid);
        }
    }

    static synchronized List<Long> recordedProcessIdsForTesting() {
        return new ArrayList<Long>(PROCESS_IDS.values());
    }

    private static boolean terminateExitedWorkerProcesses(String workerId) throws InterruptedException {
        List<String> command = exitedWorkerCleanupCommand(workerId, System.getenv());
        if (command.isEmpty()) {
            return false;
        }
        try {
            Process cleanup = new ProcessBuilder(command).start();
            return cleanup.waitFor(30, TimeUnit.SECONDS) && cleanup.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        }
    }

    static List<String> exitedWorkerCleanupCommandForTesting(
            long rootPid, String workerId, Map<String, String> environment) {
        return exitedWorkerCleanupCommand(workerId, environment);
    }

    static List<String> liveWorkerCleanupCommandForTesting(
            long rootPid, String workerId, Map<String, String> environment) {
        return liveWorkerCleanupCommand(rootPid, workerId, environment).orElse(Collections.<String>emptyList());
    }

    private static List<String> exitedWorkerCleanupCommand(
            String workerId, Map<String, String> environment) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Optional<Path> shell = trustedExecutable("sh", environment);
            Optional<Path> pkill = trustedExecutable("pkill", environment);
            Optional<Path> sleep = trustedExecutable("sleep", environment);
            if (!shell.isPresent() || !pkill.isPresent()) {
                return Collections.emptyList();
            }
            String markerPattern = "[-]Dj4a[.]worker[.]id=" + workerId;
            List<String> commands = new ArrayList<String>();
            commands.add(shellPath(pkill.get()) + " -TERM -f '" + markerPattern + "' 2>/dev/null || true");
            if (sleep.isPresent()) {
                commands.add(shellPath(sleep.get()) + " 0.2");
            }
            commands.add(shellPath(pkill.get()) + " -KILL -f '" + markerPattern + "' 2>/dev/null || true");
            commands.add("exit 0");
            return Arrays.asList(shell.get().toString(), "-c", String.join("; ", commands));
        }
        Optional<Path> powerShell = trustedExecutable("powershell", environment);
        if (!powerShell.isPresent()) {
            return Collections.emptyList();
        }
        String marker = powershellLiteral("-Dj4a.worker.id=" + workerId);
        String ownershipCheck = ownershipCheck(marker);
        String script = ownershipCheck
                + "$owned=@(Get-CimInstance Win32_Process|Where-Object {Test-OwnedJava $_}); "
                + "$ids=@($owned|ForEach-Object {[int]$_.ProcessId}); "
                + "foreach($procId in ($ids|Sort-Object -Descending)){"
                + "$current=Get-CimInstance Win32_Process|Where-Object {$_.ProcessId -eq $procId}|Select-Object -First 1; "
                + "if(Test-OwnedJava $current){Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue}}; "
                + "Start-Sleep -Seconds 1; "
                + "$live=@(Get-CimInstance Win32_Process|Where-Object {Test-OwnedJava $_}); "
                + "if($live.Count -eq 0){exit 0}else{exit 1}";
        return Arrays.asList(powerShell.get().toString(), "-NoProfile", "-Command", script);
    }

    private static String ownershipCheck(String marker) {
        return "$marker='" + marker + "'; "
                + "function Test-OwnedJava($candidate){"
                + "if($null -eq $candidate -or $candidate.Name -ine 'java.exe'){return $false}; "
                + "$arguments=@($candidate.CommandLine -split '\\s+'); "
                + "return $arguments -contains $marker}; ";
    }

    private static String powershellLiteral(String value) {
        return value.replace("'", "''");
    }

    static boolean terminateProcessTreeQuietly(Process process, String workerId) {
        try {
            return terminateProcessTree(process, workerId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        }
    }

    static boolean terminateProcessTreeUninterruptibly(Process process, String workerId) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return terminateProcessTree(process, workerId);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Long processId(Process process, String workerId) {
        synchronized (LocalJMeterWorkerProcess.class) {
            Long recorded = PROCESS_IDS.get(process);
            if (recorded != null) {
                return recorded;
            }
        }
        Long parsed = parsePid(process.toString());
        if (parsed != null) {
            return parsed;
        }
        try {
            java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return Long.valueOf(field.getLong(process));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return processIdByWorkerId(workerId);
        }
    }

    private static Long processIdByWorkerId(String workerId) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }
        List<String> command = processIdLookupCommand(workerId, System.getenv());
        if (command.isEmpty()) {
            return null;
        }
        try {
            Process lookup = new ProcessBuilder(command).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copy(lookup.getInputStream(), output);
            lookup.waitFor(5, TimeUnit.SECONDS);
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : Long.valueOf(text.split("\\R")[0].trim());
        } catch (IOException | InterruptedException | NumberFormatException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    static List<String> processIdLookupCommandForTesting(String workerId, Map<String, String> environment) {
        return processIdLookupCommand(workerId, environment);
    }

    static List<String> killProcessTreeCommandForTesting(long pid, Map<String, String> environment) {
        return killProcessTreeCommand(pid, environment).orElse(Collections.emptyList());
    }

    private static List<String> processIdLookupCommand(String workerId, Map<String, String> environment) {
        Optional<Path> powerShell = trustedExecutable("powershell", environment);
        if (!powerShell.isPresent()) {
            return Collections.emptyList();
        }
        String marker = powershellLiteral("-Dj4a.worker.id=" + workerId);
        List<String> command = new ArrayList<>();
        command.add(powerShell.get().toString());
        command.add("-NoProfile");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-Command");
        command.add(ownershipCheck(marker)
                + "Get-CimInstance Win32_Process|Where-Object {Test-OwnedJava $_}"
                + "|Select-Object -First 1 -ExpandProperty ProcessId");
        return command;
    }

    private static Long parsePid(String text) {
        Matcher matcher = Pattern.compile("pid=(\\d+)").matcher(text);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private static boolean killProcessTree(long pid, String workerId) throws InterruptedException {
        Optional<List<String>> command = liveWorkerCleanupCommand(pid, workerId, System.getenv());
        if (!command.isPresent()) {
            return false;
        }
        try {
            Process killer = new ProcessBuilder(command.get()).start();
            return killer.waitFor(30, TimeUnit.SECONDS) && killer.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        }
    }

    private static Optional<List<String>> liveWorkerCleanupCommand(
            long pid, String workerId, Map<String, String> environment) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return killProcessTreeCommand(pid, environment);
        }
        Optional<Path> powerShell = trustedExecutable("powershell", environment);
        Optional<Path> taskkill = trustedExecutable("taskkill", environment);
        if (!powerShell.isPresent() || !taskkill.isPresent()) {
            return Optional.empty();
        }
        String marker = powershellLiteral("-Dj4a.worker.id=" + workerId);
        String taskkillPath = powershellLiteral(taskkill.get().toString());
        String script = ownershipCheck(marker)
                + "$root=Get-CimInstance Win32_Process|Where-Object {$_.ProcessId -eq " + pid
                + "}|Select-Object -First 1; "
                + "if(!(Test-OwnedJava $root)){exit 2}; "
                + "$all=@(Get-CimInstance Win32_Process); $ids=@([int]$root.ProcessId); "
                + "$front=@([int]$root.ProcessId); while($front.Count -gt 0){$next=@(); foreach($parentId in $front){"
                + "$children=@($all|Where-Object {$_.ParentProcessId -eq $parentId}); foreach($child in $children){"
                + "$ids+=[int]$child.ProcessId; $next+=[int]$child.ProcessId}}; $front=$next}; "
                + "$verifiedRoot=Get-CimInstance Win32_Process|Where-Object {$_.ProcessId -eq " + pid
                + "}|Select-Object -First 1; if(!(Test-OwnedJava $verifiedRoot)){exit 4}; "
                + "& '" + taskkillPath + "' /PID " + pid + " /T /F | Out-Null; "
                + "Start-Sleep -Seconds 1; "
                + "$live=@($ids|Where-Object {Get-Process -Id $_ -ErrorAction SilentlyContinue}); "
                + "if($live.Count -eq 0){exit 0}else{exit 1}";
        return Optional.of(Arrays.asList(powerShell.get().toString(), "-NoProfile", "-Command", script));
    }

    private static Optional<List<String>> killProcessTreeCommand(long pid, Map<String, String> environment) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Optional<Path> taskkill = trustedExecutable("taskkill", environment);
            if (!taskkill.isPresent()) {
                return Optional.empty();
            }
            return Optional.of(Arrays.asList(taskkill.get().toString(), "/PID", String.valueOf(pid), "/T", "/F"));
        }
        Optional<Path> shell = trustedExecutable("sh", environment);
        Optional<Path> kill = trustedExecutable("kill", environment);
        if (!shell.isPresent() || !kill.isPresent()) {
            return Optional.empty();
        }
        Optional<Path> pkill = trustedExecutable("pkill", environment);
        Optional<Path> sleep = trustedExecutable("sleep", environment);
        List<String> commands = new ArrayList<>();
        if (pkill.isPresent()) {
            commands.add(shellPath(pkill.get()) + " -TERM -P " + pid + " 2>/dev/null");
        }
        commands.add(shellPath(kill.get()) + " -TERM " + pid + " 2>/dev/null");
        if (sleep.isPresent()) {
            commands.add(shellPath(sleep.get()) + " 0.2");
        }
        if (pkill.isPresent()) {
            commands.add(shellPath(pkill.get()) + " -KILL -P " + pid + " 2>/dev/null");
        }
        commands.add(shellPath(kill.get()) + " -KILL " + pid + " 2>/dev/null");
        commands.add("exit 0");
        return Optional.of(Arrays.asList(shell.get().toString(), "-c", String.join("; ", commands)));
    }

    static Optional<Path> trustedExecutable(String executable, Map<String, String> environment) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            if ("powershell".equals(executable)) {
                return trustedWindowsExecutable("System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            }
            if ("taskkill".equals(executable)) {
                return trustedWindowsExecutable("System32", "taskkill.exe");
            }
            return Optional.empty();
        }
        if ("sh".equals(executable)) {
            return firstExisting("/bin/sh", "/usr/bin/sh");
        }
        if ("pkill".equals(executable)) {
            return firstExisting("/usr/bin/pkill", "/bin/pkill");
        }
        if ("kill".equals(executable)) {
            return firstExisting("/bin/kill", "/usr/bin/kill");
        }
        if ("sleep".equals(executable)) {
            return firstExisting("/bin/sleep", "/usr/bin/sleep");
        }
        return Optional.empty();
    }

    private static Optional<Path> firstExisting(String first, String second) {
        Optional<Path> firstPath = existing(Paths.get(first));
        return firstPath.isPresent() ? firstPath : existing(Paths.get(second));
    }

    private static Optional<Path> trustedWindowsExecutable(String first, String... more) {
        for (Path windowsDirectory : trustedWindowsDirectories()) {
            Path candidate = windowsDirectory.resolve(first);
            for (String part : more) {
                candidate = candidate.resolve(part);
            }
            Optional<Path> executable = existing(candidate);
            if (executable.isPresent()) {
                return executable;
            }
        }
        return Optional.empty();
    }

    private static List<Path> trustedWindowsDirectories() {
        List<Path> directories = new ArrayList<>();
        directories.add(Paths.get("C:\\Windows"));
        Path javaRoot = Paths.get(System.getProperty("java.home", "")).toAbsolutePath().normalize().getRoot();
        if (javaRoot != null) {
            directories.add(javaRoot.resolve("Windows"));
        }
        return directories;
    }

    private static Optional<Path> existing(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return Files.isRegularFile(absolute) ? Optional.of(absolute) : Optional.empty();
    }

    private static String shellPath(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }
}
