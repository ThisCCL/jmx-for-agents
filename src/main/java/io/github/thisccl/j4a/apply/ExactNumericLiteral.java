package io.github.thisccl.j4a.apply;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

final class ExactNumericLiteral extends Number {
    private static final long serialVersionUID = 1L;

    private final String lexeme;
    private final Number safeValue;

    private ExactNumericLiteral(String lexeme, Number safeValue) {
        this.lexeme = lexeme;
        this.safeValue = safeValue;
    }

    static Object untyped(Object value) {
        if (value instanceof ExactNumericLiteral) {
            return ((ExactNumericLiteral) value).safeValue;
        }
        if (value instanceof Map<?, ?>) {
            Map<Object, Object> result = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(untyped(entry.getKey()), untyped(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(untyped(item));
            }
            return result;
        }
        return value;
    }

    static Object untypedScalar(Object value) {
        return value instanceof ExactNumericLiteral
                ? ((ExactNumericLiteral) value).safeValue : value;
    }

    @Override
    public int intValue() {
        return safeValue.intValue();
    }

    @Override
    public long longValue() {
        return safeValue.longValue();
    }

    @Override
    public float floatValue() {
        return safeValue.floatValue();
    }

    @Override
    public double doubleValue() {
        return safeValue.doubleValue();
    }

    @Override
    public String toString() {
        return lexeme;
    }

    static final class Constructor extends SafeConstructor {
        Constructor(LoaderOptions options) {
            super(options);
            preserve(Tag.INT);
            preserve(Tag.FLOAT);
        }

        private void preserve(Tag tag) {
            final Construct delegate = yamlConstructors.get(tag);
            yamlConstructors.put(tag, new AbstractConstruct() {
                @Override
                public Object construct(Node node) {
                    Number safeValue = (Number) delegate.construct(node);
                    return new ExactNumericLiteral(((ScalarNode) node).getValue(), safeValue);
                }
            });
        }
    }
}

final class ExactNumericDecoder {
    private static final int MAX_DISPLAY_CHARACTERS = 96;

    private ExactNumericDecoder() {
    }

    static Number decode(String lexeme, String type) {
        String normalized = lexeme == null ? "" : lexeme.replace("_", "");
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            throw invalid(lexeme, type, exception);
        }
        try {
            if ("int".equals(type)) {
                return Integer.valueOf(decimal.intValueExact());
            }
            if ("long".equals(type)) {
                return Long.valueOf(decimal.longValueExact());
            }
        } catch (ArithmeticException exception) {
            throw invalid(lexeme, type, exception);
        }
        if ("float".equals(type)) {
            float value;
            try {
                value = Float.parseFloat(normalized);
            } catch (NumberFormatException exception) {
                throw invalid(lexeme, type, exception);
            }
            if (!Float.isFinite(value) || value == 0F && decimal.signum() != 0) {
                throw invalid(lexeme, type, null);
            }
            return Float.valueOf(value);
        }
        if ("double".equals(type)) {
            double value;
            try {
                value = Double.parseDouble(normalized);
            } catch (NumberFormatException exception) {
                throw invalid(lexeme, type, exception);
            }
            if (!Double.isFinite(value) || value == 0D && decimal.signum() != 0) {
                throw invalid(lexeme, type, null);
            }
            return Double.valueOf(value);
        }
        throw new IllegalArgumentException("type is not numeric: " + type);
    }

    private static IllegalArgumentException invalid(
            String lexeme, String type, RuntimeException cause) {
        String message = "value " + lexeme
                + " must be finite and inside the Java " + type + " range";
        if (lexeme != null && lexeme.length() > MAX_DISPLAY_CHARACTERS) {
            message = "value " + lexeme.substring(0, MAX_DISPLAY_CHARACTERS)
                    + "[truncated] must be finite and inside the Java " + type + " range";
        }
        return cause == null
                ? new IllegalArgumentException(message)
                : new IllegalArgumentException(message, cause);
    }
}
