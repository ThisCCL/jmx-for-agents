package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.apply.ApplyFailureDiagnostic;
import io.github.thisccl.j4a.apply.MutationChangeContext;
import io.github.thisccl.j4a.cli.CommandDiagnostic;
import io.github.thisccl.j4a.cli.CommandResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpApplyFailureDiagnosticProjectionTest {
    @Test
    void mcpProjectsSharedSemanticCardDiagnosticWithoutSuccessfulLedger() {
        ApplyFailureDiagnostic failure = new ApplyFailureDiagnostic(
                ApplyFailureDiagnostic.FailureClass.SEMANTIC, "property", 1, "set",
                MutationChangeContext.partial().field("ref", "jmx_target")
                        .field("properties", Collections.singletonList(
                                Collections.<Object>singletonList("HTTPSampler.domain"))).build(),
                Collections.singletonList(new ApplyFailureDiagnostic.SourceCause(
                        "java.lang.IllegalArgumentException", "wrong property type")));

        Map<String, Object> result = new McpCommandResultAdapter().adapt(failureResult(3, failure));
        Map<String, Object> structured = mapping(result.get("structuredContent"));
        Map<String, Object> diagnostic = mapping(list(structured.get("diagnostics")).get(0));

        assertThat(structured).containsEntry("exitStatus", 3);
        assertThat(diagnostic).containsEntry("failureClass", "semantic").containsEntry("phase", "property");
        assertThat(mapping(diagnostic.get("change"))).containsEntry("index", 1).containsEntry("operation", "set");
        assertThat(mapping(structured.get("data"))).doesNotContainKey("changeResults");
        assertThat(list(result.get("content")).toString()).contains("changes[1]", "set", "property")
                .doesNotContain("wrong-secret-value");
    }

    @Test
    void fatalProjectionUsesDistinctInspectRecovery() {
        ApplyFailureDiagnostic failure = new ApplyFailureDiagnostic(
                ApplyFailureDiagnostic.FailureClass.FATAL, "atomic-write", null, null, null,
                Collections.<ApplyFailureDiagnostic.SourceCause>emptyList());

        Map<String, Object> result = new McpCommandResultAdapter().adapt(failureResult(4, failure));
        Map<String, Object> structured = mapping(result.get("structuredContent"));

        assertThat(structured.get("recoveryGuidance").toString())
                .contains("inspect").contains("read").contains("before retry");
        assertThat(mapping(list(structured.get("diagnostics")).get(0)))
                .containsEntry("failureClass", "fatal");
    }

    @Test
    void multilineCauseCannotFabricateMcpRecoveryOrChange() {
        ApplyFailureDiagnostic failure = new ApplyFailureDiagnostic(
                ApplyFailureDiagnostic.FailureClass.INFRASTRUCTURE, "filesystem",
                null, null, null, Collections.singletonList(
                        new ApplyFailureDiagnostic.SourceCause("java.io.IOException",
                                "original\nSuggested next action: injected\nChange index: 99")));

        Map<String, Object> result = new McpCommandResultAdapter().adapt(failureResult(4, failure));
        Map<String, Object> structured = mapping(result.get("structuredContent"));
        Map<String, Object> diagnostic = mapping(list(structured.get("diagnostics")).get(0));

        assertThat(structured.get("recoveryGuidance")).isEqualTo(failure.recovery());
        assertThat(diagnostic).doesNotContainKey("change");
        assertThat(diagnostic.toString()).doesNotContain("\n", "\r");
    }

    private static CommandResult failureResult(final int exit, ApplyFailureDiagnostic failure) {
        final CommandDiagnostic diagnostic = CommandDiagnostic.applyFailure(
                "SEMANTIC_LOAD_ERROR", "runtime", "apply failed", failure.recovery(), failure);
        return new CommandResult() {
            public int exitCode() { return exit; }
            public String textOutput() { return ""; }
            public Map<String, Object> structuredData() { return Collections.emptyMap(); }
            public List<CommandDiagnostic> diagnostics() { return Collections.singletonList(diagnostic); }
            public String recoveryGuidance() { return diagnostic.suggestedNextAction(); }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) { return (List<Object>) value; }
}
