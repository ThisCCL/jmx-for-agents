package io.github.thisccl.j4a.cli;

import java.io.PrintStream;

final class CliCommandResultAdapter {
    private CliCommandResultAdapter() {
    }

    static void emit(CommandResult result, boolean debug, PrintStream stdout, PrintStream stderr) {
        stdout.print(result.textOutput());
        for (CommandDiagnostic diagnostic : result.diagnostics()) {
            CliErrorFormatter.print(diagnostic.toCliError(), debug, stderr);
        }
    }
}
