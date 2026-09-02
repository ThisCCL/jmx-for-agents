package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowEvidence;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowResolver;
import java.util.Optional;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;

final class LocalStructuredRowEvidenceResolver implements RuntimeStructuredRowResolver {
    @Override
    public Optional<RuntimeStructuredRowEvidence> resolve(
            TestElement element, String property, RuntimeContext runtimeContext) {
        String guiClass = element.getPropertyAsString(TestElement.GUI_CLASS);
        String resolvedGuiClass = SaveService.aliasToClass(guiClass);
        LocalJMeterMenuRegistry.Entry entry = LocalJMeterMenuRegistry.current()
                .resolve(resolvedGuiClass == null ? guiClass : resolvedGuiClass).orElse(null);
        if (entry == null) return Optional.empty();
        return resolve(property, LocalComponentDiscovery.semanticMetadata(entry, runtimeContext));
    }

    static Optional<RuntimeStructuredRowEvidence> resolve(
            String property, LocalJMeterGuiSemanticMetadata.Observation metadata) {
        RuntimeStructuredRowEvidence accepted = null;
        for (LocalJMeterGuiSemanticMetadata.StructuredRowConsumer consumer
                : metadata.structuredRowConsumers()) {
            if (property.equals(consumer.property()) && consumer.evidence() != null) {
                if (accepted != null) return Optional.empty();
                accepted = consumer.evidence();
            }
        }
        return Optional.ofNullable(accepted);
    }
}
