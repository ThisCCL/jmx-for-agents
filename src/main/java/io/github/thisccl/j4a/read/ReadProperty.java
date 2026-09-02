package io.github.thisccl.j4a.read;

import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.path.PropertyAddressDocument;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ReadProperty {
    private final String rawName;
    private final Map<String, Object> document;
    private final GraphNode graphNode;
    private final boolean synthesizedName;
    private final PropertyPath propertyPath;

    ReadProperty(String rawName, Object address, Object value, String type, GraphNode graphNode) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("property", address);
        fields.put("value", value);
        fields.put("type", type);
        this.rawName = rawName;
        this.document = Collections.unmodifiableMap(fields);
        this.graphNode = Objects.requireNonNull(graphNode, "graph node is required");
        this.synthesizedName = false;
        this.propertyPath = graphNode.path();
    }

    ReadProperty(String rawName, Map<String, Object> document, GraphNode graphNode) {
        this.rawName = rawName;
        this.document = Collections.unmodifiableMap(
                new LinkedHashMap<String, Object>(document));
        this.graphNode = Objects.requireNonNull(graphNode, "graph node is required");
        this.synthesizedName = false;
        this.propertyPath = graphNode.path();
    }

    private ReadProperty(String rawName, PropertyPath path, Object value, String type) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("property", PropertyAddressDocument.scalarSegments(path));
        fields.put("value", value);
        fields.put("type", type);
        this.rawName = rawName;
        this.document = Collections.unmodifiableMap(fields);
        this.graphNode = null;
        this.synthesizedName = true;
        this.propertyPath = path;
    }

    static ReadProperty synthesizedName(String value) {
        return new ReadProperty("TestElement.name", new PropertyPath(Collections.singletonList(
                PropertyPathSegment.property("TestElement.name"))), value, "string");
    }

    String rawName() {
        return rawName;
    }

    String path() {
        return jsonAddress(PropertyAddressDocument.scalarSegments(propertyPath));
    }

    Object address() {
        return PropertyAddressDocument.scalarSegments(propertyPath);
    }

    Object value() {
        return document.get("value");
    }

    String type() {
        return (String) document.get("type");
    }

    Map<String, Object> document() {
        return document;
    }

    GraphNode graphNode() {
        return graphNode;
    }

    boolean synthesizedName() {
        return synthesizedName;
    }

    private static String jsonAddress(java.util.List<Object> segments) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < segments.size(); index++) {
            if (index > 0) {
                result.append(',');
            }
            Object segment = segments.get(index);
            if (segment instanceof Integer) {
                result.append(segment);
            } else {
                appendJsonString(result, (String) segment);
            }
        }
        return result.append(']').toString();
    }

    private static void appendJsonString(StringBuilder result, String value) {
        result.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\':
                    result.append("\\\\");
                    break;
                case '"':
                    result.append("\\\"");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", Integer.valueOf(character)));
                    } else {
                        result.append(character);
                    }
            }
        }
        result.append('"');
    }
}
