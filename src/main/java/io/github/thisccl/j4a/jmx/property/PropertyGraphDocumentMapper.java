package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.testelement.TestElement;

public final class PropertyGraphDocumentMapper {
    public PropertyGraphDocument fromDocument(Map<?, ?> document) {
        return new PropertyGraphDocumentReader().read(document);
    }

    public PropertyWrite resolve(Map<?, ?> document, GraphSnapshot snapshot) {
        return fromDocument(document).resolve(snapshot);
    }

    public Map<String, Object> toDocument(PropertyWrite write) {
        return new PropertyGraphDocumentWriter().write(write);
    }

    public Map<String, Object> toDocument(PropertyGraphDocument document) {
        return new PropertyGraphDocumentWriter().write(document);
    }

    public Map<String, Object> toDocument(GraphNode node) {
        return toDocument(new PropertyWrite(node.path(), node.type(), node.value()));
    }

    static RecursiveValue materializationValue(PropertyWrite write) {
        return materializationValue(
                write.value(), write.property().segments(), "$.value");
    }

    public List<Map<String, Object>> topLevelDocuments(
            GraphSnapshot snapshot, boolean includeSystemOwned) {
        ArrayList<Map<String, Object>> documents = new ArrayList<Map<String, Object>>();
        for (GraphNode node : snapshot.nodes()) {
            if (node.path().segments().size() != 1) {
                continue;
            }
            if (!includeSystemOwned
                    && node.capability().ownership() == GraphOwnership.SYSTEM) {
                continue;
            }
            documents.add(includeSystemOwned
                    ? toDocument(new PropertyWrite(node.path(), node.type(), node.value()))
                    : toDocument(new PropertyWrite(
                            node.path(), node.type(), readValue(
                                    node.value(), node.path().segments()))));
        }
        return Collections.unmodifiableList(documents);
    }

    private RecursiveValue readValue(
            RecursiveValue value, List<PropertyPathSegment> prefix) {
        if (value.presence() == GraphPresence.ABSENT || value.type().isScalar()
                || value.type() == GraphType.OPAQUE) {
            return value;
        }
        if (value.type() == GraphType.COLLECTION) {
            ArrayList<RecursiveValue> items = new ArrayList<RecursiveValue>();
            for (int index = 0; index < value.items().size(); index++) {
                ArrayList<PropertyPathSegment> itemPrefix =
                        new ArrayList<PropertyPathSegment>(prefix);
                itemPrefix.add(PropertyPathSegment.index(index));
                items.add(readValue(value.items().get(index), itemPrefix));
            }
            return RecursiveValue.collection(value.propertyClass(), items);
        }
        if (value.type() == GraphType.MAP) {
            ArrayList<RecursiveValue.MapEntry> entries =
                    new ArrayList<RecursiveValue.MapEntry>();
            for (RecursiveValue.MapEntry entry : value.entries()) {
                List<PropertyPathSegment> entryPrefix = mapPrefix(prefix, entry.key());
                entries.add(new RecursiveValue.MapEntry(
                        entry.key(), readValue(entry.value(), entryPrefix)));
            }
            return RecursiveValue.map(value.propertyClass(), entries);
        }
        ArrayList<PropertyWrite> properties = new ArrayList<PropertyWrite>();
        for (PropertyWrite property : value.properties()) {
            String name = property.property().segments()
                    .get(property.property().segments().size() - 1).name();
            if (TestElement.GUI_CLASS.equals(name) || TestElement.TEST_CLASS.equals(name)) {
                continue;
            }
            ArrayList<PropertyPathSegment> childSegments =
                    new ArrayList<PropertyPathSegment>(prefix);
            childSegments.addAll(property.property().segments());
            PropertyPath childPath = new PropertyPath(childSegments);
            RecursiveValue child = readValue(property.value(), childSegments);
            properties.add(new PropertyWrite(childPath, child.type(), child));
        }
        return RecursiveValue.element(value.propertyClass(), value.elementClass(), properties);
    }

    private static RecursiveValue materializationValue(
            RecursiveValue value,
            List<PropertyPathSegment> prefix,
            String documentPath) {
        if (value.presence() == GraphPresence.ABSENT || value.type().isScalar()
                || value.type() == GraphType.OPAQUE) {
            return value;
        }
        if (value.type() == GraphType.COLLECTION) {
            ArrayList<RecursiveValue> items = new ArrayList<RecursiveValue>();
            for (int index = 0; index < value.items().size(); index++) {
                List<PropertyPathSegment> itemPrefix = append(
                        prefix, PropertyPathSegment.index(index));
                items.add(materializationValue(
                        value.items().get(index), itemPrefix,
                        documentPath + ".items[" + index + "]"));
            }
            return RecursiveValue.collection(value.propertyClass(), items);
        }
        if (value.type() == GraphType.MAP) {
            ArrayList<RecursiveValue.MapEntry> entries =
                    new ArrayList<RecursiveValue.MapEntry>();
            for (int index = 0; index < value.entries().size(); index++) {
                RecursiveValue.MapEntry entry = value.entries().get(index);
                entries.add(new RecursiveValue.MapEntry(
                        entry.key(),
                        materializationValue(
                                entry.value(), mapPrefix(prefix, entry.key()),
                                documentPath + ".entries[" + index + "].value")));
            }
            return RecursiveValue.map(value.propertyClass(), entries);
        }
        ArrayList<PropertyWrite> properties = new ArrayList<PropertyWrite>();
        for (int index = 0; index < value.properties().size(); index++) {
            PropertyWrite property = value.properties().get(index);
            List<PropertyPathSegment> childPath = property.property().segments();
            PropertyPathSegment local = localProperty(
                    childPath, prefix,
                    documentPath + ".properties[" + index + "].property");
            List<PropertyPathSegment> absolute = childPath.size() == 1
                    ? append(prefix, local) : childPath;
            RecursiveValue child = materializationValue(
                    property.value(), absolute,
                    documentPath + ".properties[" + index + "].value");
            properties.add(new PropertyWrite(
                    new PropertyPath(Collections.singletonList(local)),
                    child.type(), child));
        }
        return RecursiveValue.element(value.propertyClass(), value.elementClass(), properties);
    }

    private static PropertyPathSegment localProperty(
            List<PropertyPathSegment> child,
            List<PropertyPathSegment> prefix,
            String documentPath) {
        if (child.size() == 1
                && child.get(0).kind() == PropertyPathSegment.Kind.PROPERTY) {
            return child.get(0);
        }
        if (child.size() == prefix.size() + 1
                && child.subList(0, prefix.size()).equals(prefix)
                && child.get(child.size() - 1).kind()
                        == PropertyPathSegment.Kind.PROPERTY) {
            return child.get(child.size() - 1);
        }
        throw MapPropertyCodec.error(
                documentPath,
                "element property path does not descend from containing element path '"
                        + PropertyAddress.fromPath(new PropertyPath(prefix)).segments() + "'");
    }

    private static List<PropertyPathSegment> append(
            List<PropertyPathSegment> prefix, PropertyPathSegment segment) {
        ArrayList<PropertyPathSegment> result =
                new ArrayList<PropertyPathSegment>(prefix.size() + 1);
        result.addAll(prefix);
        result.add(segment);
        return result;
    }

    private static List<PropertyPathSegment> mapPrefix(
            List<PropertyPathSegment> prefix, TypedScalarMapKey key) {
        String value = String.valueOf(key.value());
        return append(prefix, PropertyPathSegment.key(value));
    }

    public Map<String, Object> normalize(Map<?, ?> document) {
        return toDocument(fromDocument(document));
    }
}
