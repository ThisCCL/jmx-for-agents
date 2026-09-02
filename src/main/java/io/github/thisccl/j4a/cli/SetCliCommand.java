package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.jmx.JmxLoadException;
import io.github.thisccl.j4a.apply.ApplyPatchParseException;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.apply.ApplyWriteModeResolver;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.path.PropertyPathException;
import io.github.thisccl.j4a.path.PropertyPathResolutionException;
import io.github.thisccl.j4a.validation.LocalJMeterEnvironmentException;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResponse;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

final class SetCliCommand {
    private SetCliCommand() {
    }

    static int run(String[] args, Map<String, String> environment, boolean debug) {
        if (args.length < 2) {
            CliSupport.printUsageError("A JMX file is required.", null, "pass the .jmx file path after set.", debug);
            return 2;
        }
        Path input = Paths.get(args[1]);
        try {
            return runParsed(args, environment, debug, input);
        } catch (StopCommandException exception) {
            return exception.exitCode();
        } catch (ApplyWriteModeResolver.UsageException exception) {
            CliSupport.printUsageError(exception.getMessage(), input, exception.suggestedNextAction(), debug);
            return 2;
        } catch (ApplyPatchParseException exception) {
            CliSupport.printUsageError(exception.getMessage(), input,
                    "fix the structured value so it uses the row fields documented by components --details.", debug);
            return 2;
        } catch (IllegalArgumentException exception) {
            CliSupport.printLocatorError(input, optionValue(args, "--locator"), exception, debug);
            return 3;
        } catch (PropertyPathResolutionException exception) {
            CliSupport.printPropertyPathError(input, optionValue(args, "--locator"), exception, debug);
            return 3;
        } catch (PropertyPathException exception) {
            CliSupport.printPropertyPathError(input, optionValue(args, "--locator"), exception, debug);
            return 3;
        } catch (LocalJMeterEnvironmentException exception) {
            CliSupport.printLocalEnvironmentFailure(target(args, input), exception, debug);
            return 4;
        } catch (JmxLoadException exception) {
            CliSupport.printError(new CliError(
                    "JMX_READ_WRITE_ERROR",
                    "filesystem",
                    exception.getMessage(),
                    input,
                    null,
                    null,
                    null,
                    null,
                    "check that the input and output paths are accessible, then rerun set.",
                    exception), debug);
            return 4;
        }
    }

    private static int runParsed(String[] args, Map<String, String> environment, boolean debug, Path input) {
        LocalJMeterRuntime runtime = runtime(args, environment, input, debug);
        boolean override = hasOption(args, "--override");
        String out = optionValue(args, "--out");
        ApplyWriteModeResolver.AuthorizedCommit authorized = ApplyWriteModeResolver.resolve(
                input, false, override, out, hasOption(args, "--force-out")).authorizeCommit();
        String locator = required(args, "--locator", input, "copy a locator from read output and pass --locator <id>.", debug);
        String propertyDocument = required(args, "--property", input,
                "copy a property array from read --properties all output and pass it as one JSON array.", debug);
        ApplyPatchParser patchParser = new ApplyPatchParser();
        PropertyAddress propertyAddress = parseJsonPropertyAddress(propertyDocument);
        String value = required(args, "--value", input, "pass --value <value> for the existing property path.", debug);
        String typeText = optionValue(args, "--type");
        ApplyPatch.ValueType patchType = patchValueType(typeText, input, debug);
        Path target = authorized.target();
        boolean replaceExisting = authorized.replaceExisting();
        Object decoded = decodeValue(patchParser, value, propertyAddress, patchType);
        return runRecursive(
                input, target, locator, propertyAddress, decoded, patchType,
                runtime, replaceExisting, debug);
    }

    private static Object decodeValue(
            ApplyPatchParser parser,
            String literal,
            PropertyAddress property,
            ApplyPatch.ValueType type) {
        if (type.structuredCollection() || type.recursiveGraph()) {
            return parser.parseStructuredValue(literal, property, type);
        }
        if (type.graphScalar() && type != ApplyPatch.ValueType.STRING) {
            return parseGraphValue(parser, literal, property, type);
        }
        return literal;
    }

    private static Object parseGraphValue(
            ApplyPatchParser parser, String literal, PropertyAddress property, ApplyPatch.ValueType type) {
        if (type == ApplyPatch.ValueType.INT
                || type == ApplyPatch.ValueType.LONG
                || type == ApplyPatch.ValueType.FLOAT
                || type == ApplyPatch.ValueType.DOUBLE) {
            try {
                return ApplyPatchParser.decodeExactNumericLiteral(
                        literal, type.name().toLowerCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new ApplyPatchParseException(exception.getMessage(), exception);
            }
        }
        return parser.parseStructuredValue(literal, property, type);
    }

    private static LocalJMeterRuntime runtime(
            String[] args, Map<String, String> environment, Path input, boolean debug) {
        try {
            return LocalJMeterRuntime.fromArgs(args, environment);
        } catch (IllegalArgumentException exception) {
            CliSupport.printUsageError(exception.getMessage(), input,
                    "rerun set --help and use supported set flags.", debug);
            throw new StopCommandException(2);
        }
    }

    private static int runRecursive(
            Path input, Path target, String locator, PropertyAddress propertyAddress, Object value,
            ApplyPatch.ValueType valueType, LocalJMeterRuntime runtime, boolean replaceExisting, boolean debug) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("property", propertyAddress.segments());
        property.put("type", valueType.name().toLowerCase(java.util.Locale.ROOT));
        property.put("value", value);
        Map<String, Object> set = new LinkedHashMap<String, Object>();
        set.put("ref", locator);
        set.put("properties", Collections.<Object>singletonList(property));
        Map<String, Object> change = new LinkedHashMap<String, Object>();
        change.put("set", set);
        Map<String, Object> patchDocument = new LinkedHashMap<String, Object>();
        patchDocument.put("changes", Collections.<Object>singletonList(change));
        String patchYaml = new Yaml().dump(patchDocument);
        new ApplyPatchParser().parse(patchYaml);
        LocalJMeterWorkerResponse response = CliSupport.executeLocalWorker(
                LocalJMeterWorkerRequest.applyPatchYaml(
                        input, runtime.home(), patchYaml, target, replaceExisting));
        if (!response.success()) {
            CliSupport.printLocalWorkerFailure(target, response, debug);
            return CliSupport.localWorkerExitCode(response);
        }
        System.out.println("Wrote: " + target);
        System.out.println("Validation passed: " + target);
        return 0;
    }

    private static String required(String[] args, String option, Path input, String suggestedAction, boolean debug) {
        String value = optionValue(args, option);
        if (value == null) {
            CliSupport.printUsageError(option + " is required.", input, suggestedAction, debug);
            throw new StopCommandException(2);
        }
        return value;
    }

    private static ApplyPatch.ValueType patchValueType(String type, Path input, boolean debug) {
        if (type == null) {
            return ApplyPatch.ValueType.STRING;
        }
        try {
            return ApplyPatch.ValueType.parse(type);
        } catch (ApplyPatchParseException exception) {
            CliSupport.printUsageError("Unsupported --type: " + type, input,
                    "use --type string, boolean, int, long, float, double, null, raw, collection, map, element, rows, or opaque.", debug);
            throw new StopCommandException(2);
        }
    }

    private static Path target(String[] args, Path input) {
        return hasOption(args, "--override") ? input : Paths.get(optionValue(args, "--out"));
    }

    private static boolean hasOption(String[] args, String option) {
        for (String arg : args) {
            if (option.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String optionValue(String[] args, String option) {
        for (int index = 0; index < args.length - 1; index++) {
            if (option.equals(args[index])) {
                return args[index + 1];
            }
        }
        return null;
    }

    private static PropertyAddress parseJsonPropertyAddress(String document) {
        try {
            return PropertyAddress.decode(new JsonScalarArrayParser(document).parse());
        } catch (IllegalArgumentException exception) {
            throw new ApplyPatchParseException(
                    "property must be exactly one JSON scalar array: " + exception.getMessage(), exception);
        }
    }

    private static final class JsonScalarArrayParser {
        private final String document;
        private int index;

        private JsonScalarArrayParser(String document) {
            this.document = document == null ? "" : document;
        }

        private List<Object> parse() {
            skipWhitespace();
            expect('[');
            List<Object> values = new ArrayList<Object>();
            skipWhitespace();
            if (read(']')) {
                finish();
                return values;
            }
            while (true) {
                values.add(parseScalar());
                skipWhitespace();
                if (read(']')) {
                    finish();
                    return values;
                }
                expect(',');
            }
        }

        private Object parseScalar() {
            skipWhitespace();
            char current = peek();
            if (current == '"') {
                return parseString();
            }
            if (current == 't') {
                expectLiteral("true");
                return Boolean.TRUE;
            }
            if (current == 'f') {
                expectLiteral("false");
                return Boolean.FALSE;
            }
            if (current == 'n') {
                expectLiteral("null");
                return null;
            }
            if (current == '-' || isDigit(current)) {
                return parseNumber();
            }
            throw invalid("expected a JSON scalar");
        }

        private Number parseNumber() {
            int start = index;
            read('-');
            if (read('0')) {
                if (isDigit(peek())) {
                    throw invalid("leading zero in JSON number");
                }
            } else {
                requireDigit();
                while (isDigit(peek())) {
                    index++;
                }
            }
            boolean integer = true;
            if (read('.')) {
                integer = false;
                requireDigit();
                while (isDigit(peek())) {
                    index++;
                }
            }
            if (read('e') || read('E')) {
                integer = false;
                if (!read('+')) {
                    read('-');
                }
                requireDigit();
                while (isDigit(peek())) {
                    index++;
                }
            }
            String number = document.substring(start, index);
            return integer ? new BigInteger(number) : new BigDecimal(number);
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw invalid("unterminated JSON string");
                }
                char current = document.charAt(index++);
                if (current == '"') {
                    return value.toString();
                }
                if (current == '\\') {
                    value.append(parseEscape());
                } else if (current < 0x20) {
                    throw invalid("control character in JSON string");
                } else {
                    value.append(current);
                }
            }
        }

        private char parseEscape() {
            if (atEnd()) {
                throw invalid("unterminated JSON escape");
            }
            char escaped = document.charAt(index++);
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
                    throw invalid("unsupported JSON escape");
            }
        }

        private char parseUnicodeEscape() {
            if (index + 4 > document.length()) {
                throw invalid("incomplete JSON unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(document.charAt(index++), 16);
                if (digit < 0) {
                    throw invalid("invalid JSON unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private void finish() {
            skipWhitespace();
            if (!atEnd()) {
                throw invalid("unexpected trailing content");
            }
        }

        private void expectLiteral(String literal) {
            if (!document.regionMatches(index, literal, 0, literal.length())) {
                throw invalid("invalid JSON literal");
            }
            index += literal.length();
        }

        private void requireDigit() {
            if (!isDigit(peek())) {
                throw invalid("expected digit in JSON number");
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!read(expected)) {
                throw invalid("expected '" + expected + "'");
            }
        }

        private boolean read(char expected) {
            if (!atEnd() && document.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private char peek() {
            return atEnd() ? '\0' : document.charAt(index);
        }

        private void skipWhitespace() {
            while (!atEnd()) {
                char current = document.charAt(index);
                if (current != ' ' && current != '\n' && current != '\r' && current != '\t') {
                    return;
                }
                index++;
            }
        }

        private boolean atEnd() {
            return index >= document.length();
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private IllegalArgumentException invalid(String detail) {
            return new IllegalArgumentException(detail + " at index " + index);
        }
    }

    private static final class StopCommandException extends RuntimeException {
        private final int exitCode;

        private StopCommandException(int exitCode) {
            this.exitCode = exitCode;
        }

        private int exitCode() {
            return exitCode;
        }
    }
}
