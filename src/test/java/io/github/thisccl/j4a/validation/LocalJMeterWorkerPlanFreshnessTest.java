package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LocalJMeterWorkerPlanFreshnessTest {
    @Test
    void sharedWorkerLoadsFreshFileBytesAfterExternalReplacement() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
        fixtures.ensure();
        Path source = fixtures.root().resolve("fresh-plan.jmx");
        String original = new String(
                Files.readAllBytes(fixtures.root().resolve("basic.jmx")), StandardCharsets.UTF_8);
        Files.write(source, original.replace("Synthetic Test Plan", "First File Version")
                .getBytes(StandardCharsets.UTF_8));
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(20), Duration.ofSeconds(90), Duration.ofSeconds(90));
        try {
            LocalJMeterWorkerResult first = client.execute(LocalJMeterWorkerRequest.renderReadData(
                    source, fixtures.localHome(), "2", null, "NONE", "false"));
            Files.write(source, original.replace("Synthetic Test Plan", "Second File Version")
                    .getBytes(StandardCharsets.UTF_8));
            LocalJMeterWorkerResult second = client.execute(LocalJMeterWorkerRequest.renderReadData(
                    source, fixtures.localHome(), "2", null, "NONE", "false"));

            assertThat(first.response().success()).as(first.response().toJsonLine()).isTrue();
            assertThat(first.response().payload()).contains("First File Version")
                    .doesNotContain("Second File Version");
            assertThat(second.response().success()).as(second.response().toJsonLine()).isTrue();
            assertThat(second.response().payload()).contains("Second File Version")
                    .doesNotContain("First File Version");
        } finally {
            client.close();
        }
    }
}
