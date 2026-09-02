package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LocalJMeterWorkerDispositionTest {
    private LocalJMeterWorkerClient client;

    @AfterEach
    void closeWorker() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void domainFailureRetainsHealthyReusableWorker() throws Exception {
        client = LocalJMeterWorkerClient.reusable();
        Path home = fakeJMeterHome();

        LocalJMeterWorkerResult failed = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "domain-failure"));
        LocalJMeterWorkerResult next = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-domain-failure"));

        assertThat(failed.response().success()).isFalse();
        assertThat(failed.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.DOMAIN_FAILURE);
        assertThat(workerPid(next)).isEqualTo(workerPid(failed));
        assertThat(next.response().payload()).contains("request-count: 2");
        System.out.println("DOMAIN_PID=" + workerPid(next) + " REQUEST_COUNT=2");
    }

    @Test
    void fatalCallFailsWithoutReplayAndNextCallRebuilds() throws Exception {
        client = LocalJMeterWorkerClient.reusable();
        Path home = fakeJMeterHome();
        Path requestCountFile = home.resolve("request-count.txt");
        Path workerPidFile = home.resolve("worker.pid");
        Files.write(home.resolve("bin").resolve("user.properties"),
                ("j4a.fake.request.count=" + requestCountFile.toAbsolutePath() + "\n"
                        + "j4a.fake.worker.pid=" + workerPidFile.toAbsolutePath() + "\n")
                        .getBytes(StandardCharsets.UTF_8));

        LocalJMeterWorkerResult failed = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "malformed"));

        assertThat(failed.response().disposition()).isEqualTo(LocalJMeterWorkerDisposition.FATAL_FAILURE);
        assertThat(failed.response().success()).isFalse();
        assertThat(Files.readAllLines(requestCountFile, StandardCharsets.UTF_8)).hasSize(1);
        long failedPid = Long.parseLong(new String(Files.readAllBytes(workerPidFile), StandardCharsets.UTF_8).trim());

        LocalJMeterWorkerResult next = client.execute(
                LocalJMeterWorkerRequest.componentDetails(home, "after-fatal"));
        assertThat(next.response().payload()).contains("request-count: 1");
        assertThat(next.workerExited()).isFalse();
        assertThat(workerPid(next)).isNotEqualTo(failedPid);
        System.out.println("FATAL_PID=" + failedPid + " REPLACEMENT_PID=" + workerPid(next)
                + " FAILED_REQUEST_COUNT=1 REPLACEMENT_REQUEST_COUNT=1");
    }

    private static Path fakeJMeterHome() throws Exception {
        Path home = Files.createTempDirectory("j4a-worker-disposition-home-");
        Files.createDirectories(home.resolve("bin"));
        Files.createDirectories(home.resolve("lib").resolve("ext"));
        Path core = Paths.get(org.apache.jmeter.util.JMeterUtils.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Files.copy(core, home.resolve("lib").resolve("ApacheJMeter_core-local.jar"));
        LocalJMeterSharedWorkerProtocolFixture.create(home.resolve("lib").resolve("shared-worker-protocol.jar"));
        Files.write(home.resolve("bin").resolve("jmeter.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("saveservice.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("upgrade.properties"), new byte[0]);
        Files.write(home.resolve("bin").resolve("user.properties"), new byte[0]);
        return home;
    }

    private static long workerPid(LocalJMeterWorkerResult result) {
        String payload = result.response().payload();
        String marker = "worker-pid: ";
        int start = payload.indexOf(marker);
        assertThat(start).as(payload).isGreaterThanOrEqualTo(0);
        int end = payload.indexOf(';', start + marker.length());
        String value = end < 0
                ? payload.substring(start + marker.length())
                : payload.substring(start + marker.length(), end);
        return Long.parseLong(value.trim());
    }
}
