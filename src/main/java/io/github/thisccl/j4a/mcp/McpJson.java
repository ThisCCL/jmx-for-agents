package io.github.thisccl.j4a.mcp;

import java.util.Map;

final class McpJson {
    private McpJson() {
    }

    static Object parse(String json) {
        return McpJsonNumbers.untyped(new JsonParser(json).parse());
    }

    static Object parseRequest(String json) {
        return new JsonParser(json).parse();
    }

    static String write(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(output, value);
        return output.toString();
    }

    private static void writeValue(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            writeString(output, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value);
        } else if (value instanceof Map) {
            writeObject(output, value);
        } else if (value instanceof Iterable) {
            writeArray(output, (Iterable<?>) value);
        } else {
            writeString(output, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder output, Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        output.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                output.append(',');
            }
            first = false;
            writeString(output, entry.getKey());
            output.append(':');
            writeValue(output, entry.getValue());
        }
        output.append('}');
    }

    private static void writeArray(StringBuilder output, Iterable<?> values) {
        output.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                output.append(',');
            }
            first = false;
            writeValue(output, value);
        }
        output.append(']');
    }

    private static void writeString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\':
                    output.append("\\\\");
                    break;
                case '"':
                    output.append("\\\"");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", Integer.valueOf(character)));
                    } else {
                        output.append(character);
                    }
                    break;
            }
        }
        output.append('"');
    }
}
