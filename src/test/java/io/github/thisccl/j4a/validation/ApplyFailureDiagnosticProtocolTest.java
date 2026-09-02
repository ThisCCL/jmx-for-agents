package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.apply.ApplyFailureDiagnostic;
import io.github.thisccl.j4a.apply.MutationChangeContext;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApplyFailureDiagnosticProtocolTest {
    @Test
    void typedApplyFailureRoundTripsWithoutValuesOrInternals() {
        MutationChangeContext context = MutationChangeContext.partial()
                .field("alias", "request")
                .field("component", "org.example.Sampler")
                .field("parent", "jmx_parent")
                .build();
        ApplyFailureDiagnostic failure = new ApplyFailureDiagnostic(
                ApplyFailureDiagnostic.FailureClass.SEMANTIC,
                "materialization",
                2,
                "add",
                context,
                Arrays.asList(new ApplyFailureDiagnostic.SourceCause(
                        "java.lang.IllegalArgumentException", "component could not be created")));
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.applyPatchYaml(
                Paths.get("source.jmx"), Paths.get("jmeter-home"), "changes: []", Paths.get("target.jmx"), true);

        LocalJMeterWorkerResponse decoded = LocalJMeterWorkerResponse.fromJsonLine(
                LocalJMeterWorkerResponse.failure(request, "SEMANTIC_LOAD_ERROR", "semantic", null,
                        "apply failed", "fix the card", "", "", failure).toJsonLine());

        assertThat(decoded.failureDiagnostic()).contains(failure);
        Map<String, Object> change = decoded.failureDiagnostic().get().change().get().toMap();
        assertThat(change).containsEntry("index", 2).containsEntry("operation", "add");
        assertThat(((Map<?, ?>) change.get("context")).keySet())
                .containsExactly("alias", "component", "parent");
        assertThat(decoded.toJsonLine()).doesNotContain("submitted-value", "stackTrace", "temporary");
    }
}
