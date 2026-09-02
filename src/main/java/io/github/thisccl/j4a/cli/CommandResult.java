package io.github.thisccl.j4a.cli;

import java.util.List;
import java.util.Map;

public interface CommandResult {
    int exitCode();

    String textOutput();

    Map<String, Object> structuredData();

    List<CommandDiagnostic> diagnostics();

    String recoveryGuidance();
}
