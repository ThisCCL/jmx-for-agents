package io.github.thisccl.j4a.cli;

import java.util.Collections;
import java.util.List;
import java.util.Map;

final class DefaultCommandResult implements CommandResult {
    private final int exitCode;
    private final String textOutput;
    private final Map<String, Object> structuredData;
    private final List<CommandDiagnostic> diagnostics;
    private final String recoveryGuidance;

    private DefaultCommandResult(
            int exitCode,
            String textOutput,
            Map<String, Object> structuredData,
            List<CommandDiagnostic> diagnostics,
            String recoveryGuidance) {
        this.exitCode = exitCode;
        this.textOutput = textOutput == null ? "" : textOutput;
        this.structuredData = structuredData == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(structuredData);
        this.diagnostics = diagnostics == null
                ? Collections.<CommandDiagnostic>emptyList()
                : Collections.unmodifiableList(diagnostics);
        this.recoveryGuidance = recoveryGuidance;
    }

    static CommandResult success(String textOutput, Map<String, Object> structuredData) {
        return new DefaultCommandResult(0, textOutput, structuredData,
                Collections.<CommandDiagnostic>emptyList(), null);
    }

    static CommandResult failure(int exitCode, CliError error) {
        CommandDiagnostic diagnostic = CommandDiagnostic.from(error);
        return new DefaultCommandResult(exitCode, "", Collections.<String, Object>emptyMap(),
                Collections.singletonList(diagnostic), error.suggestedNextAction());
    }

    @Override
    public int exitCode() {
        return exitCode;
    }

    @Override
    public String textOutput() {
        return textOutput;
    }

    @Override
    public Map<String, Object> structuredData() {
        return structuredData;
    }

    @Override
    public List<CommandDiagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    public String recoveryGuidance() {
        return recoveryGuidance;
    }
}
