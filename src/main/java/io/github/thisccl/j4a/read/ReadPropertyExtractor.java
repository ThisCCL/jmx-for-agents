package io.github.thisccl.j4a.read;

import io.github.thisccl.j4a.jmx.property.DefaultJMeterPropertyGraph;
import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.jmx.property.GraphPresence;
import io.github.thisccl.j4a.jmx.property.GraphSnapshot;
import io.github.thisccl.j4a.jmx.property.GraphType;
import io.github.thisccl.j4a.jmx.property.PropertyGraphDocumentMapper;
import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowDocument;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowEvidence;
import io.github.thisccl.j4a.jmx.property.RuntimeStructuredRowResolver;
import io.github.thisccl.j4a.path.PropertyAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.testelement.property.JMeterProperty;

final class ReadPropertyExtractor {
    List<ReadProperty> properties(
            JmxComponentNode node,
            RuntimeContext runtimeContext,
            RuntimeStructuredRowResolver rowResolver) {
        GraphSnapshot snapshot = new DefaultJMeterPropertyGraph()
                .inspect(node.element(), runtimeContext);
        PropertyGraphDocumentMapper mapper = new PropertyGraphDocumentMapper();
        List<ReadProperty> properties = new ArrayList<ReadProperty>();
        for (Map<String, Object> document : mapper.topLevelDocuments(snapshot, false)) {
            Object address = document.get("property");
            GraphNode graphNode = snapshot.resolve(PropertyAddress.decode(address));
            String rawName = graphNode.path().segments().get(0).name();
            JMeterProperty property = node.element().getPropertyOrNull(rawName);
            java.util.Optional<RuntimeStructuredRowDocument.Projection> rows = property == null
                    ? java.util.Optional.<RuntimeStructuredRowDocument.Projection>empty()
                    : RuntimeStructuredRowDocument.observe(node.element(), property, runtimeContext);
            if (rows.isPresent()
                    && ((List<?>) rows.get().value().get("rows")).isEmpty()) {
                java.util.Optional<RuntimeStructuredRowEvidence> evidence = rowResolver.resolve(
                        node.element(), rawName, runtimeContext);
                if (evidence.isPresent()) {
                    java.util.Optional<RuntimeStructuredRowDocument.Projection> refined =
                            evidence.get().refineEmptyDocument(
                                    node.element(), property, runtimeContext);
                    if (refined.isPresent()) rows = refined;
                }
            }
            if (rows.isPresent()) {
                properties.add(new ReadProperty(
                        rawName, address, rows.get().value(), "rows", graphNode));
            } else if (graphNode.value().presence() == GraphPresence.PRESENT
                    && graphNode.type().isScalar()) {
                properties.add(new ReadProperty(
                        rawName, address, legacyScalar(graphNode), publicType(graphNode.type()), graphNode));
            } else {
                properties.add(new ReadProperty(rawName, canonical(document), graphNode));
            }
        }
        if (properties.stream().noneMatch(property -> "TestElement.name".equals(property.rawName()))) {
            properties.add(0, ReadProperty.synthesizedName(node.displayName()));
        }
        return Collections.unmodifiableList(properties);
    }

    private static Map<String, Object> canonical(Map<String, Object> document) {
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<String, Object>();
        canonical.put("property", document.get("property"));
        canonical.put("type", document.get("type"));
        canonical.put("value", document.get("value"));
        return canonical;
    }

    private static String publicType(GraphType type) {
        return type.wireName();
    }

    private static Object legacyScalar(GraphNode node) {
        return node.value().scalarValue();
    }
}
