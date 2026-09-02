package io.github.thisccl.j4a.jmx.property;

import java.util.Objects;

public final class RuntimeContext {
    private final String workerId;
    private final RuntimeFingerprint fingerprint;

    public RuntimeContext(String workerId, RuntimeFingerprint fingerprint) {
        Objects.requireNonNull(workerId, "worker id is required");
        if (workerId.isEmpty()) {
            throw new IllegalArgumentException("worker id is required");
        }
        this.workerId = workerId;
        this.fingerprint = Objects.requireNonNull(fingerprint, "runtime fingerprint is required");
    }

    public String workerId() {
        return workerId;
    }

    public RuntimeFingerprint fingerprint() {
        return fingerprint;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RuntimeContext)) {
            return false;
        }
        RuntimeContext that = (RuntimeContext) other;
        return workerId.equals(that.workerId) && fingerprint.equals(that.fingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workerId, fingerprint);
    }
}
