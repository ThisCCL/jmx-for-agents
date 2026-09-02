package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.reference.ResolvedNodeHandle;
import org.apache.jmeter.testelement.TestElement;

final class ExactNodeHandles {
    private ExactNodeHandles() {
    }

    static TestElement element(ResolvedNodeHandle handle) {
        if (!(handle instanceof ExactNodeHandle)) {
            throw new IllegalArgumentException("Resolved node handle is not owned by this reference module.");
        }
        return ((ExactNodeHandle) handle).element();
    }
}
