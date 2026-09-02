package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public final class CatastrophicMutationWorkerFixture {
    private CatastrophicMutationWorkerFixture() {
    }

    public static Path createHome(Path directory, Path executionCount) throws IOException {
        Path home = Files.createDirectory(directory.resolve("catastrophic-worker-home"));
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        copyLocalJMeterCore(home.resolve("lib").resolve("ApacheJMeter_core-local.jar"));
        LocalJMeterSharedWorkerProtocolFixture.create(
                home.resolve("lib").resolve("catastrophic-worker-protocol.jar"));
        Files.write(home.resolve("bin").resolve("jmeter.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("saveservice.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("upgrade.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.mutation.execution.count=" + executionCount.toAbsolutePath() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        return home;
    }

    private static void copyLocalJMeterCore(Path target) throws IOException {
        Path source;
        try {
            source = Paths.get(org.apache.jmeter.util.JMeterUtils.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception exception) {
            throw new IOException("Unable to locate ApacheJMeter_core fixture jar", exception);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
