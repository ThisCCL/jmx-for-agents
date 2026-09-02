package io.github.thisccl.j4a.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonParser {
    static final int MAX_NESTING_DEPTH = 256;
    private final String json;
    private int index;
    private int nestingDepth;

    JsonParser(String json) {
        this.json = json == null ? "" : json;
    }

    Object parse() {
        Object value = parseValue();
        skipWhitespace();
        if (!isAtEnd()) {
            throw new IllegalArgumentException("unexpected trailing content at index " + index);
        }
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        char character = peek();
        switch (character) {
            case '{':
                enterContainer();
                try {
                    return parseObject();
                } finally {
                    nestingDepth--;
                }
            case '[':
                enterContainer();
                try {
                    return parseArray();
                } finally {
                    nestingDepth--;
                }
            case '"':
                return parseString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                if (character == '-' || Character.isDigit(character)) {
                    return parseNumber();
                }
                throw new IllegalArgumentException("unsupported JSON value at index " + index);
        }
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        expect('{');
        skipWhitespace();
        if (tryRead('}')) {
            return values;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new IllegalArgumentException("object key must be a string at index " + index);
            }
            String name = parseString();
            skipWhitespace();
            expect(':');
            values.put(name, parseValue());
            skipWhitespace();
            if (tryRead('}')) {
                return values;
            }
            expect(',');
        }
    }

    private List<Object> parseArray() {
        List<Object> values = new ArrayList<Object>();
        expect('[');
        skipWhitespace();
        if (tryRead(']')) {
            return values;
        }
        while (true) {
            values.add(parseValue());
            skipWhitespace();
            if (tryRead(']')) {
                return values;
            }
            expect(',');
        }
    }

    private void enterContainer() {
        if (nestingDepth >= MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("JSON nesting exceeds maximum depth of " + MAX_NESTING_DEPTH);
        }
        nestingDepth++;
    }

    private String parseString() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            if (isAtEnd()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char character = json.charAt(index++);
            if (character == '"') {
                return value.toString();
            }
            if (character == '\\') {
                value.append(parseEscape());
            } else {
                if (character < 0x20) {
                    throw new IllegalArgumentException("control character in string at index " + (index - 1));
                }
                value.append(character);
            }
        }
    }

    private char parseEscape() {
        if (isAtEnd()) {
            throw new IllegalArgumentException("unterminated escape sequence");
        }
        char escaped = json.charAt(index++);
        switch (escaped) {
            case '"':
            case '\\':
            case '/':
                return escaped;
            case 'b':
                return '\b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
            case 'u':
                return parseUnicodeEscape();
            default:
                throw new IllegalArgumentException("unsupported escape sequence at index " + (index - 1));
        }
    }

    private char parseUnicodeEscape() {
        if (index + 4 > json.length()) {
            throw new IllegalArgumentException("incomplete unicode escape at index " + index);
        }
        String digits = json.substring(index, index + 4);
        index += 4;
        try {
            return (char) Integer.parseInt(digits, 16);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid unicode escape at index " + (index - 4));
        }
    }

    private Number parseNumber() {
        int start = index;
        if (peek() == '-') {
            index++;
        }
        readDigits();
        boolean decimal = false;
        if (!isAtEnd() && peek() == '.') {
            decimal = true;
            index++;
            readDigits();
        }
        if (!isAtEnd() && (peek() == 'e' || peek() == 'E')) {
            decimal = true;
            index++;
            if (!isAtEnd() && (peek() == '+' || peek() == '-')) {
                index++;
            }
            readDigits();
        }
        String number = json.substring(start, index);
        try {
            return new JsonNumberLiteral(number, decimal);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid number at index " + start);
        }
    }

    private void readDigits() {
        int start = index;
        while (!isAtEnd() && Character.isDigit(peek())) {
            index++;
        }
        if (start == index) {
            throw new IllegalArgumentException("expected digit at index " + index);
        }
    }

    private void expectLiteral(String literal) {
        if (!json.startsWith(literal, index)) {
            throw new IllegalArgumentException("expected " + literal + " at index " + index);
        }
        index += literal.length();
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new IllegalArgumentException("expected '" + expected + "' at index " + index);
        }
        index++;
    }

    private boolean tryRead(char expected) {
        if (!isAtEnd() && json.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private char peek() {
        if (isAtEnd()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        return json.charAt(index);
    }

    private void skipWhitespace() {
        while (!isAtEnd() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
    }

    private boolean isAtEnd() {
        return index >= json.length();
    }
}
