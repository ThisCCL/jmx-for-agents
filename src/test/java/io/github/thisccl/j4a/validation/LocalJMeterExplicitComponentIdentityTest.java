package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LocalJMeterExplicitComponentIdentityTest {
    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();
    private final LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
            Duration.ofSeconds(20), Duration.ofSeconds(90), Duration.ofSeconds(90));

    @Test
    void applySetRejectsExplicitUnknownComponentBeforeBorrowingLocatedDescriptor() throws Exception {
        // Given
        fixtures.ensure();
        Path patch = fixtures.root().resolve("explicit-unknown-component.yml");
        Files.write(patch, ("changes:\n"
                + "  - set:\n"
                + "      ref: jmx_330976848c8e\n"
                + "      component: http.request\n"
                + "      properties:\n"
                + "        - property: [HTTPsampler.Arguments]\n"
                + "          type: rows\n"
                + "          value:\n"
                + "            row_type: org.apache.jmeter.protocol.http.util.HTTPArgument\n"
                + "            rows:\n"
                + "              - Argument.name: q\n"
                + "                Argument.value: v\n"
                + "                Argument.metadata: '='\n"
                + "                HTTPArgument.always_encode: true\n"
                + "                HTTPArgument.use_equals: true\n"
                + "                HTTPArgument.content_type: text/plain\n")
                .getBytes(StandardCharsets.UTF_8));
        Path output = fixtures.root().resolve("explicit-unknown-component-output.jmx");
        Files.deleteIfExists(output);

        // When
        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                fixtures.root().resolve("basic.jmx"), fixtures.localHome(), patch, output));

        // Then
        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("COMPONENT_IDENTITY_NOT_FOUND");
        assertThat(result.response().message()).contains("http.request");
        assertThat(output).doesNotExist();
    }

    @Test
    void applyMoveRejectsExplicitUnknownComponentBeforeChangingPlacement() throws Exception {
        fixtures.ensure();
        Path patch = fixtures.root().resolve("move-explicit-unknown-component.yml");
        Files.write(patch, ("changes:\n"
                + "  - move:\n"
                + "      ref: jmx_330976848c8e\n"
                + "      component: http.request\n"
                + "      parent: jmx_19871e6efa95\n"
                + "      position: first\n").getBytes(StandardCharsets.UTF_8));
        Path input = fixtures.root().resolve("basic.jmx");
        Path output = fixtures.root().resolve("move-explicit-unknown-component-output.jmx");
        byte[] inputBefore = Files.readAllBytes(input);
        Files.deleteIfExists(output);

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                input, fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("COMPONENT_IDENTITY_NOT_FOUND");
        assertThat(result.response().message()).contains("http.request");
        assertThat(Files.readAllBytes(input)).containsExactly(inputBefore);
        assertThat(output).doesNotExist();
    }

    @Test
    void applyDeleteRejectsExplicitUnknownComponentBeforeRemovingTarget() throws Exception {
        fixtures.ensure();
        Path patch = fixtures.root().resolve("delete-explicit-unknown-component.yml");
        Files.write(patch, ("changes:\n"
                + "  - delete:\n"
                + "      ref: jmx_330976848c8e\n"
                + "      component: http.request\n").getBytes(StandardCharsets.UTF_8));
        Path input = fixtures.root().resolve("basic.jmx");
        Path output = fixtures.root().resolve("delete-explicit-unknown-component-output.jmx");
        byte[] inputBefore = Files.readAllBytes(input);
        Files.deleteIfExists(output);

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                input, fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("COMPONENT_IDENTITY_NOT_FOUND");
        assertThat(result.response().message()).contains("http.request");
        assertThat(Files.readAllBytes(input)).containsExactly(inputBefore);
        assertThat(output).doesNotExist();
    }

    @Test
    void legacyStructuredTypeAndScalarAddressFailBeforeExplicitComponentIdentity() throws Exception {
        fixtures.ensure();
        Path patch = fixtures.root().resolve("legacy-structured-explicit-unknown-component.yml");
        Files.write(patch, ("changes:\n"
                + "  - set:\n"
                + "      ref: jmx_330976848c8e\n"
                + "      component: http.request\n"
                + "      properties:\n"
                + "        - property: HTTPsampler\\.Arguments\n"
                + "          type: arguments\n"
                + "          value: {rows: []}\n").getBytes(StandardCharsets.UTF_8));
        Path output = fixtures.root().resolve("legacy-structured-explicit-unknown-component-output.jmx");
        Files.deleteIfExists(output);

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                fixtures.root().resolve("basic.jmx"), fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("SEMANTIC_LOAD_ERROR");
        assertThat(result.response().message()).contains("arguments");
        assertThat(result.response().message()).doesNotContain("http.request");
        assertThat(output).doesNotExist();
    }

    @Test
    void emptyPropertyAddressFailsBeforeExplicitComponentIdentity() throws Exception {
        fixtures.ensure();
        Path patch = fixtures.root().resolve("empty-address-explicit-unknown-component.yml");
        Files.write(patch, ("changes:\n"
                + "  - set:\n"
                + "      ref: jmx_330976848c8e\n"
                + "      component: http.request\n"
                + "      properties:\n"
                + "        - property: []\n"
                + "          type: string\n"
                + "          value: must-not-apply\n").getBytes(StandardCharsets.UTF_8));
        Path output = fixtures.root().resolve("empty-address-explicit-unknown-component-output.jmx");
        Files.deleteIfExists(output);

        LocalJMeterWorkerResult result = client.execute(LocalJMeterWorkerRequest.applyPatch(
                fixtures.root().resolve("basic.jmx"), fixtures.localHome(), patch, output));

        assertThat(result.response().success()).isFalse();
        assertThat(result.response().errorCode()).isEqualTo("SEMANTIC_LOAD_ERROR");
        assertThat(result.response().message()).contains("at least one segment");
        assertThat(result.response().message()).doesNotContain("http.request");
        assertThat(output).doesNotExist();
    }
}
