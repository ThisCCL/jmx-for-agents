package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.reference.BoundReferences;
import java.util.Objects;

public final class ReferenceBindings {
    private ReferenceBindings() {
    }

    public static BoundReferences snapshot(JmxTestPlan loadedPlan) {
        return new SnapshotReferenceSpace().bind(Objects.requireNonNull(loadedPlan, "loadedPlan"));
    }
}
