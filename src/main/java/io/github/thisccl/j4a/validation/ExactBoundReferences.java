package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.reference.BoundReferences;
import org.apache.jmeter.testelement.TestElement;

interface ExactBoundReferences extends BoundReferences {
    String expose(TestElement element);
}
