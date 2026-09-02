package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.concurrent.TimeUnit;

public final class SlowStartingWorkerLauncher {
    private static final long HANG_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private SlowStartingWorkerLauncher() {
    }

    static List<String> jvmArgs(Path childPidFile) throws IOException {
        Path launcherJar = writeLauncherJar(childPidFile.getParent().resolve("slow-starting-worker-launcher.jar"));
        String childPid = childPidFile.toAbsolutePath().normalize().toString();
        String launcher = launcherJar.toAbsolutePath().normalize().toString();
        return Arrays.asList("-javaagent:" + launcher + "=" + childPid + "|" + launcher);
    }

    public static void premain(String agentArgs) throws Exception {
        String[] args = agentArgs.split("\\|", 2);
        startChildAndHang(Paths.get(args[0]), Paths.get(args[1]));
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--child".equals(args[0])) {
            writePid(Paths.get(args[1]));
            Thread.sleep(HANG_MILLIS);
            return;
        }
        if (args.length == 0) {
            System.exit(2);
            return;
        }
        startChildAndHang(Paths.get(args[0]), Paths.get(args[1]));
    }

    private static void startChildAndHang(Path childPidFile, Path launcherJar) throws Exception {
        Files.deleteIfExists(childPidFile);
        Process child = new ProcessBuilder(
                javaExecutable(),
                "-jar",
                launcherJar.toAbsolutePath().normalize().toString(),
                "--child",
                childPidFile.toAbsolutePath().normalize().toString())
                .start();
        waitForPid(childPidFile, child);
        Thread.sleep(HANG_MILLIS);
    }

    private static void waitForPid(Path pidFile, Process child) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(pidFile)) {
                return;
            }
            if (!child.isAlive()) {
                System.exit(child.exitValue());
                return;
            }
            Thread.sleep(25L);
        }
        System.exit(3);
    }

    private static void writePid(Path pidFile) throws Exception {
        Files.createDirectories(pidFile.toAbsolutePath().normalize().getParent());
        Files.write(pidFile, currentPid().getBytes(StandardCharsets.UTF_8));
    }

    private static Path writeLauncherJar(Path jar) throws IOException {
        Files.createDirectories(jar.toAbsolutePath().normalize().getParent());
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, SlowStartingWorkerLauncher.class.getName());
        attributes.put(new Attributes.Name("Premain-Class"), SlowStartingWorkerLauncher.class.getName());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            String classEntry = SlowStartingWorkerLauncher.class.getName().replace('.', '/') + ".class";
            output.putNextEntry(new JarEntry(classEntry));
            try (InputStream input = SlowStartingWorkerLauncher.class.getClassLoader().getResourceAsStream(classEntry)) {
                if (input == null) {
                    throw new IOException("Missing compiled launcher class resource: " + classEntry);
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
            output.closeEntry();
        }
        return jar;
    }

    private static String javaExecutable() {
        Path javaHome = Paths.get(System.getProperty("java.home"));
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return javaHome.resolve("bin").resolve(executable).toString();
    }

    private static String currentPid() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int separator = runtimeName.indexOf('@');
        return separator >= 0 ? runtimeName.substring(0, separator) : runtimeName;
    }
}
