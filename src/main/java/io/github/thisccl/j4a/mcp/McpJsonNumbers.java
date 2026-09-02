package io.github.thisccl.j4a.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpJsonNumbers {
    private McpJsonNumbers() {
    }

    static Object untyped(Object value) {
        if (value instanceof JsonNumberLiteral) {
            return ((JsonNumberLiteral) value).untypedValue();
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> normalized = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), untyped(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof List<?>) {
            List<Object> normalized = new ArrayList<Object>();
            for (Object item : (List<?>) value) normalized.add(untyped(item));
            return normalized;
        }
        return value;
    }

    static Map<String, Object> toolArguments(String toolName, Map<String, Object> arguments) {
        Map<String, Object> normalized = new LinkedHashMap<String, Object>();
        String explicitType = arguments.get("type") instanceof String
                ? (String) arguments.get("type") : null;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            if ("set".equals(toolName) && "value".equals(entry.getKey())
                    && value instanceof JsonNumberLiteral && numericType(explicitType)) {
                try {
                    value = ((JsonNumberLiteral) value).exactValue(explicitType);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("set argument 'value' for explicit type '"
                            + explicitType + "' " + exception.getMessage());
                }
            } else if ("set".equals(toolName) && "value".equals(entry.getKey())) {
                value = typed(value);
            } else {
                value = untyped(value);
            }
            normalized.put(entry.getKey(), value);
        }
        return normalized;
    }

    private static Object typed(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> normalized = new LinkedHashMap<String, Object>();
            Object declared = ((Map<?, ?>) value).get("type");
            String declaredType = declared instanceof String ? (String) declared : null;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object nested = entry.getValue();
                if ("value".equals(String.valueOf(entry.getKey()))
                        && nested instanceof JsonNumberLiteral && numericType(declaredType)) {
                    nested = ((JsonNumberLiteral) nested).exactValue(declaredType);
                } else {
                    nested = typed(nested);
                }
                normalized.put(String.valueOf(entry.getKey()), nested);
            }
            return normalized;
        }
        if (value instanceof List<?>) {
            List<Object> normalized = new ArrayList<Object>();
            for (Object item : (List<?>) value) normalized.add(typed(item));
            return normalized;
        }
        return untyped(value);
    }

    private static boolean numericType(String type) {
        return "int".equals(type) || "long".equals(type)
                || "float".equals(type) || "double".equals(type);
    }
}
