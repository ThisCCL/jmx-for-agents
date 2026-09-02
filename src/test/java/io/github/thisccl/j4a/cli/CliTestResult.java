package io.github.thisccl.j4a.cli;

final class CliTestResult {
    private final int exitCode;
    private final String stdout;
    private final String stderr;

    CliTestResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    int exitCode() {
        return exitCode;
    }

    String stdout() {
        return stdout;
    }

    String stderr() {
        return stderr;
    }
}
