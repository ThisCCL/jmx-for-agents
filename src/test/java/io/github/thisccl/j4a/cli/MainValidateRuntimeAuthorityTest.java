package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.validation.DefaultLocalProfileQaFixtures;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerClient;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MainValidateRuntimeAuthorityTest {
    @Test
    void validateIgnoresFormerSemanticAndGuiGatesWhenSaveServiceRoundTripSucceeds() throws Exception {
        DefaultLocalProfileQaFixtures fixtures = DefaultLocalProfileQaFixtures.fresh();
        fixtures.ensure();
        Path input = fixtures.root().resolve("validate-runtime-authority.jmx");
        String source = new String(Files.readAllBytes(fixtures.root().resolve("basic.jmx")), StandardCharsets.UTF_8);
        Files.write(input, source.replace("guiclass=\"HttpTestSampleGui\"", "guiclass=\"java.lang.Integer\"")
                .replace("testclass=\"HTTPSamplerProxy\"", "testclass=\"ThreadGroup\"")
                .getBytes(StandardCharsets.UTF_8));
        byte[] before = Files.readAllBytes(input);

        boolean success = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.validate(input, fixtures.localHome())).response().success();

        assertThat(success).isTrue();
        assertThat(Files.readAllBytes(input)).containsExactly(before);

        byte[] externallyEdited = new String(before, StandardCharsets.UTF_8)
                .replace("testname=\"HTTP Request\"", "testname=\"Fresh External Edit\"")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(input, externallyEdited);
        boolean freshSuccess = new LocalJMeterWorkerClient().execute(
                LocalJMeterWorkerRequest.validate(input, fixtures.localHome())).response().success();
        assertThat(freshSuccess).isTrue();
        assertThat(Files.readAllBytes(input)).containsExactly(externallyEdited);
    }
}
