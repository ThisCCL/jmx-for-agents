package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.locator.LocatorNotFoundException;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ReferenceResolution;
import io.github.thisccl.j4a.reference.ResolvedNodeHandle;
import org.apache.jmeter.testelement.TestElement;

final class LocalWorkerLocatorTree {
    private LocalWorkerLocatorTree() {
    }

    static TestElement findElement(JmxTestPlan testPlan, String locator) {
        return findElement(ReferenceBindings.snapshot(testPlan), locator);
    }

    static TestElement findElement(BoundReferences references, String locator) {
        ReferenceResolution resolution = resolve(references, locator);
        if (resolution.status() != ReferenceResolution.Status.RESOLVED || !resolution.handle().isPresent()) {
            throw new LocatorNotFoundException(locator);
        }
        ResolvedNodeHandle handle = resolution.handle().get();
        if (!(handle instanceof ExactNodeHandle)) {
            throw new LocatorNotFoundException(locator);
        }
        return ((ExactNodeHandle) handle).element();
    }

    static ReferenceResolution resolve(BoundReferences references, String locator) {
        return references.resolve(locator);
    }
}
