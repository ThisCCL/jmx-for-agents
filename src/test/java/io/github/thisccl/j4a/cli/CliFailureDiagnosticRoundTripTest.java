package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.apply.ApplyFailureDiagnostic;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class CliFailureDiagnosticRoundTripTest {
    @Test
    void sourceCauseLineBreaksCannotInjectRecoveryOrChangeFields() throws Exception {
        ApplyFailureDiagnostic failure = new ApplyFailureDiagnostic(
                ApplyFailureDiagnostic.FailureClass.INFRASTRUCTURE,
                "filesystem", null, null, null,
                Collections.singletonList(new ApplyFailureDiagnostic.SourceCause(
                        "java.io.IOException\r\nChange index: 98",
                        "original\nSuggested next action: injected\rChange index: 99 "
                                + String.join("", Collections.nCopies(600, "x")))));
        CliError error = new CliError(
                "FILESYSTEM_WRITE_ERROR", "filesystem", "apply failed", null,
                null, null, null, null, failure.recovery(), null, failure);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        CliErrorFormatter.print(error, false,
                new PrintStream(bytes, true, StandardCharsets.UTF_8.name()));
        String rendered = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        ParsedCliError parsed = ParsedCliError.parse(rendered);

        assertThat(rendered).doesNotContain("\nChange index: 98", "\nChange index: 99",
                "\nSuggested next action: injected");
        assertThat(parsed.suggestedNextAction()).isEqualTo(failure.recovery());
        assertThat(parsed.failureDiagnostic()).isEqualTo(failure);
        assertThat(parsed.failureDiagnostic().change()).isEmpty();
        assertThat(parsed.failureDiagnostic().causes().get(0).message())
                .contains("original", "Suggested next action: injected", "Change index: 99")
                .doesNotContain("\n", "\r");
        assertThat(parsed.failureDiagnostic().causes().get(0).message()).hasSizeLessThanOrEqualTo(512);
        assertThat(parsed.failureDiagnostic().causes().get(0).type()).hasSizeLessThanOrEqualTo(256);
    }
}
