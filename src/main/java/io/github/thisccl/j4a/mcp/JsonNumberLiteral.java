package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.apply.ApplyPatchParser;
import java.math.BigDecimal;

final class JsonNumberLiteral extends Number {
    private static final int MAX_DISPLAY_CHARACTERS = 96;
    private final String lexeme;
    private final boolean decimal;
    private final BigDecimal value;

    JsonNumberLiteral(String lexeme, boolean decimal) {
        this.lexeme = lexeme;
        this.decimal = decimal;
        this.value = new BigDecimal(lexeme);
    }

    Object untypedValue() {
        try {
            if (decimal) return Double.valueOf(lexeme);
            return Long.valueOf(lexeme);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("JSON number is outside the supported untyped range: " + display());
        }
    }

    Object exactValue(String type) {
        return ApplyPatchParser.decodeExactNumericLiteral(lexeme, type);
    }

    private String display() {
        return lexeme.length() <= MAX_DISPLAY_CHARACTERS
                ? lexeme : lexeme.substring(0, MAX_DISPLAY_CHARACTERS) + "[truncated]";
    }

    @Override public int intValue() { return value.intValue(); }
    @Override public long longValue() { return value.longValue(); }
    @Override public float floatValue() { return value.floatValue(); }
    @Override public double doubleValue() { return value.doubleValue(); }
    @Override public String toString() { return lexeme; }
}
