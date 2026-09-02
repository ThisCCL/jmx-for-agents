package io.github.thisccl.j4a.validation;

public final class LocalJMeterWorkerResult {
    public static final int EXIT_CODE_UNKNOWN = -1;

    private final LocalJMeterWorkerResponse response;
    private final boolean workerExited;
    private final int exitCode;

    LocalJMeterWorkerResult(LocalJMeterWorkerResponse response, boolean workerExited, int exitCode) {
        this.response = response;
        this.workerExited = workerExited;
        this.exitCode = exitCode;
    }

    public LocalJMeterWorkerResponse response() {
        return response;
    }

    public boolean workerExited() {
        return workerExited;
    }

    int exitCode() {
        return exitCode;
    }
}
