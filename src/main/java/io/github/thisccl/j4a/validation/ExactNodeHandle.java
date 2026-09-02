package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.reference.ResolvedNodeHandle;
import java.util.Objects;
import org.apache.jmeter.testelement.TestElement;

public final class ExactNodeHandle implements ResolvedNodeHandle {
    private final TestElement element;

    private ExactNodeHandle(TestElement element) {
        this.element = Objects.requireNonNull(element, "element");
    }

    static ExactNodeHandle of(TestElement element) {
        return new ExactNodeHandle(element);
    }

    TestElement element() {
        return element;
    }
}
