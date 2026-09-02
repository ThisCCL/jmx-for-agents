package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ResolvedNodeHandle;
import org.apache.jmeter.testelement.TestElement;

public final class ReferenceTestSupport {
    private ReferenceTestSupport() {
    }

    public static BoundReferences snapshot(JmxTestPlan plan) {
        return ReferenceBindings.snapshot(plan);
    }

    public static String expose(BoundReferences references, TestElement element) {
        return ((ExactBoundReferences) references).expose(element);
    }

    public static TestElement element(ResolvedNodeHandle handle) {
        return ExactNodeHandles.element(handle);
    }

    public static ResolvedNodeHandle handle(TestElement element) {
        return ExactNodeHandle.of(element);
    }
}
