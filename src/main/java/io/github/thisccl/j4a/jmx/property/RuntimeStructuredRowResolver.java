package io.github.thisccl.j4a.jmx.property;

import java.util.Optional;
import org.apache.jmeter.testelement.TestElement;

public interface RuntimeStructuredRowResolver {
    Optional<RuntimeStructuredRowEvidence> resolve(
            TestElement owner, String property, RuntimeContext runtimeContext);

    static RuntimeStructuredRowResolver none() {
        return (owner, property, runtimeContext) -> Optional.empty();
    }
}
