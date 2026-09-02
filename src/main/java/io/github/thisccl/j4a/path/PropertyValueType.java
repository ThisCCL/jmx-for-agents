package io.github.thisccl.j4a.path;

import io.github.thisccl.j4a.apply.ApplyPatch;

public enum PropertyValueType {
    STRING,
    BOOLEAN,
    INT,
    LONG,
    DOUBLE,
    RAW,
    ROWS;

    public static PropertyValueType parse(String type) {
        if (type == null) return STRING;
        for (PropertyValueType valueType : values()) {
            if (valueType.name().equalsIgnoreCase(type)) return valueType;
        }
        if (isRemovedNamedRowType(type)) {
            throw new IllegalArgumentException(
                    "Legacy property value type '" + type + "' is no longer accepted; use rows");
        }
        throw new IllegalArgumentException("Unsupported property value type: " + type);
    }

    public static PropertyValueType fromPatchType(ApplyPatch.ValueType type) {
        for (PropertyValueType valueType : values()) {
            if (valueType.name().equals(type.name())) return valueType;
        }
        throw new IllegalArgumentException("No property value transport for patch type: " + type);
    }

    public ApplyPatch.ValueType patchType() {
        return ApplyPatch.ValueType.valueOf(name());
    }

    public boolean structuredCollection() {
        return this == ROWS;
    }

    private static boolean isRemovedNamedRowType(String type) {
        String normalized = type.toLowerCase(java.util.Locale.ROOT);
        return "arguments".equals(normalized)
                || "headers".equals(normalized)
                || "http_files".equals(normalized)
                || "authorizations".equals(normalized)
                || "cookies".equals(normalized)
                || "dns_servers".equals(normalized)
                || "dns_hosts".equals(normalized);
    }
}
