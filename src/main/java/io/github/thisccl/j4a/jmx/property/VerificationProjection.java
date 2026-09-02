package io.github.thisccl.j4a.jmx.property;

import java.util.List;
import java.util.Objects;

public final class VerificationProjection {
    private final RuntimeContext runtimeContext;
    private final List<PropertyWrite> values;

    public VerificationProjection(RuntimeContext runtimeContext, List<PropertyWrite> values) {
        this.runtimeContext = Objects.requireNonNull(runtimeContext, "runtime context is required");
        this.values = MutationReceipt.immutableWrites(values);
    }

    public RuntimeContext runtimeContext() {
        return runtimeContext;
    }

    public List<PropertyWrite> values() {
        return values;
    }
}
