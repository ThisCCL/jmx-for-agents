package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.path.PropertyAddressDocument;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PropertyGraphDocumentWriter {
    Map<String, Object> write(PropertyWrite write) {
        Objects.requireNonNull(write, "property write is required");
        LinkedHashMap<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("property", PropertyAddressDocument.scalarSegments(write.property()));
        document.put("type", write.type().wireName());
        document.put("value", value(write.value(), false));
        return immutable(document);
    }

    Map<String, Object> write(PropertyGraphDocument source) {
        Objects.requireNonNull(source, "property document is required");
        LinkedHashMap<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("property", source.property().segments());
        document.put("type", source.type().wireName());
        document.put("value", value(source.value(), false));
        return immutable(document);
    }

    private Map<String, Object> recursive(RecursiveValue value) {
        return value(value, true);
    }

    private Map<String, Object> value(RecursiveValue value, boolean recursive) {
        LinkedHashMap<String, Object> document = new LinkedHashMap<String, Object>();
        if (recursive) {
            document.put("type", value.type().wireName());
        }
        document.put("presence", value.presence().wireName());
        document.put("property_class", value.propertyClass());
        if (value.presence() == GraphPresence.ABSENT) {
            return immutable(document);
        }
        switch (value.type()) {
            case STRING:
            case BOOLEAN:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case NULL:
                document.put("value", value.scalarValue());
                break;
            case COLLECTION:
                document.put("items", recursiveValues(value.items()));
                break;
            case MAP:
                document.put("entries", entries(value.entries()));
                break;
            case ELEMENT:
                document.put("element_class", value.elementClass());
                document.put("properties", writes(value.properties()));
                break;
            case OPAQUE:
                RecursiveValue.OpaqueValue opaque = value.opaqueValue();
                document.put("format", opaque.format());
                document.put("base_digest", opaque.baseDigest());
                document.put("outer_property_class", opaque.outerPropertyClass());
                document.put("runtime_fingerprint", opaque.runtimeFingerprint());
                document.put("payload", opaque.payload());
                break;
            default:
                throw new IllegalStateException("unhandled graph type: " + value.type());
        }
        return immutable(document);
    }

    private List<Object> recursiveValues(List<RecursiveValue> values) {
        ArrayList<Object> documents = new ArrayList<Object>(values.size());
        for (RecursiveValue value : values) {
            documents.add(recursive(value));
        }
        return Collections.unmodifiableList(documents);
    }

    private List<Object> entries(List<RecursiveValue.MapEntry> entries) {
        ArrayList<Object> documents = new ArrayList<Object>(entries.size());
        for (RecursiveValue.MapEntry entry : entries) {
            LinkedHashMap<String, Object> document = new LinkedHashMap<String, Object>();
            document.put("key", key(entry.key()));
            document.put("value", recursive(entry.value()));
            documents.add(immutable(document));
        }
        return Collections.unmodifiableList(documents);
    }

    private Map<String, Object> key(TypedScalarMapKey key) {
        LinkedHashMap<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("type", key.type().wireName());
        document.put("value", key.value());
        return immutable(document);
    }

    private List<Object> writes(List<PropertyWrite> writes) {
        ArrayList<Object> documents = new ArrayList<Object>(writes.size());
        for (PropertyWrite write : writes) {
            documents.add(write(write));
        }
        return Collections.unmodifiableList(documents);
    }

    private static Map<String, Object> immutable(LinkedHashMap<String, Object> document) {
        return Collections.unmodifiableMap(document);
    }
}
