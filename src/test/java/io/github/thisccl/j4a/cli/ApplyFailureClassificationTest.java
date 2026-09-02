package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.apply.ApplyFailureDiagnostic;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResponse;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ApplyFailureClassificationTest {
    @Test
    void semanticLoadErrorIsClassifiedByApplyPhaseRatherThanGlobally() {
        LocalJMeterWorkerResponse apply = failure("applyPatch", ApplyFailureDiagnostic.FailureClass.SEMANTIC,
                "candidate-reload");
        LocalJMeterWorkerResponse validate = failure("validateJmx", ApplyFailureDiagnostic.FailureClass.INFRASTRUCTURE,
                "initial-source-load");

        assertThat(CliSupport.localWorkerExitCode(apply)).isEqualTo(3);
        assertThat(CliSupport.localWorkerExitCode(validate)).isEqualTo(4);
    }

    @Test
    void fatalUnknownOutcomeRetainsDistinctExitAndRecovery() {
        LocalJMeterWorkerResponse fatal = failure("applyPatch", ApplyFailureDiagnostic.FailureClass.FATAL,
                "atomic-write");

        assertThat(CliSupport.localWorkerExitCode(fatal)).isEqualTo(4);
        assertThat(fatal.failureDiagnostic().get().recovery())
                .contains("inspect").contains("read").contains("before retry");
    }

    private static LocalJMeterWorkerResponse failure(
            String operation, ApplyFailureDiagnostic.FailureClass failureClass, String phase) {
        LocalJMeterWorkerRequest request = "applyPatch".equals(operation)
                ? LocalJMeterWorkerRequest.applyPatchYaml(Paths.get("source.jmx"), Paths.get("jmeter-home"),
                        "changes: []", Paths.get("target.jmx"), true)
                : LocalJMeterWorkerRequest.validate(Paths.get("source.jmx"), Paths.get("jmeter-home"));
        ApplyFailureDiagnostic diagnostic = new ApplyFailureDiagnostic(
                failureClass, phase, null, null, null, Collections.<ApplyFailureDiagnostic.SourceCause>emptyList());
        return LocalJMeterWorkerResponse.failure(request, "SEMANTIC_LOAD_ERROR", "runtime", null,
                "failed", diagnostic.recovery(), "", "", diagnostic);
    }
}
