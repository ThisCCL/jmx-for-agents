package io.github.thisccl.j4a.validation;

import java.util.LinkedHashMap;
import java.util.Map;

final class LocalJMeterWorkerJson {
    private LocalJMeterWorkerJson() {
    }

    static Builder object() {
        return new Builder();
    }

    static ObjectFields parseObject(String json) {
        Parser parser = new Parser(json);
        return new ObjectFields(parser.parse());
    }

    static final class Builder {
        private final StringBuilder output = new StringBuilder("{");
        private boolean first = true;

        Builder field(String name, String value) {
            name(name);
            if (value == null) {
                output.append("null");
            } else {
                string(value);
            }
            return this;
        }

        Builder field(String name, boolean value) {
            name(name);
            output.append(value ? "true" : "false");
            return this;
        }

        String build() {
            return output.append('}').toString();
        }

        private void name(String name) {
            if (!first) {
                output.append(',');
            }
            first = false;
            string(name);
            output.append(':');
        }

        private void string(String value) {
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
                        output.append(character);
                        break;
                }
            }
            output.append('"');
        }
    }

    static final class ObjectFields {
        private final Map<String, Object> fields;

        private ObjectFields(Map<String, Object> fields) {
            this.fields = fields;
        }

        String string(String name) {
            Object value = fields.get(name);
            return value == null ? null : String.valueOf(value);
        }

        boolean bool(String name) {
            Object value = fields.get(name);
            return Boolean.TRUE.equals(value);
        }

        void reject(String name) {
            if (fields.containsKey(name)) {
                throw new IllegalArgumentException("Unsupported worker request field: " + name);
            }
        }
    }

    private static final class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = json == null ? "" : json.trim();
        }

        private Map<String, Object> parse() {
            Map<String, Object> values = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            while (peek() != '}') {
                String name = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                values.put(name, parseValue());
                skipWhitespace();
                if (peek() == ',') {
                    index++;
                    skipWhitespace();
                }
            }
            expect('}');
            return values;
        }

        private Object parseValue() {
            char character = peek();
            if (character == '"') {
                return parseString();
            }
            if (json.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (json.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            if (json.startsWith("null", index)) {
                index += 4;
                return null;
            }
            throw new IllegalArgumentException("Unsupported JSON value at index " + index);
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (peek() != '"') {
                char character = json.charAt(index++);
                if (character == '\\') {
                    char escaped = json.charAt(index++);
                    switch (escaped) {
                        case 'n':
                            value.append('\n');
                            break;
                        case 'r':
                            value.append('\r');
                            break;
                        case 't':
                            value.append('\t');
                            break;
                        default:
                            value.append(escaped);
                            break;
                    }
                } else {
                    value.append(character);
                }
            }
            expect('"');
            return value.toString();
        }

        private void expect(char expected) {
            if (peek() != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at index " + index);
            }
            index++;
        }

        private char peek() {
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON.");
            }
            return json.charAt(index);
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }
    }
}
