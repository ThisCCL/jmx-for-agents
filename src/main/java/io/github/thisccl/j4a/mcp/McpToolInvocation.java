package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.apply.ApplyWriteModeResolver;
import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchParseException;
import io.github.thisccl.j4a.path.PropertyValueType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class McpToolInvocation {
    static final int MAX_PATCH_YAML_BYTES = 4 * 1024 * 1024;
    private final String[] args;
    private final InputStream stdin;
    private final String error;
    private final boolean toolError;
    private final String suggestedNextAction;

    private McpToolInvocation(String[] args, InputStream stdin, String error, boolean toolError,
            String suggestedNextAction) {
        this.args = args;
        this.stdin = stdin;
        this.error = error;
        this.toolError = toolError;
        this.suggestedNextAction = suggestedNextAction;
    }

    static McpToolInvocation from(String toolName, Map<String, Object> arguments) {
        if ("read".equals(toolName)) {
            return args(readArgs(arguments), "read requires a string file argument.");
        }
        if ("validate".equals(toolName)) {
            return args(validateArgs(arguments), "validate requires a string file argument.");
        }
        if ("components".equals(toolName)) {
            return args(componentsArgs(arguments), "components arguments are invalid.");
        }
        if ("categories".equals(toolName)) {
            return args(categoriesArgs(arguments), "categories arguments are invalid.");
        }
        if ("apply".equals(toolName)) {
            return applyInvocation(arguments);
        }
        if ("init".equals(toolName)) {
            return args(initArgs(arguments), "init requires a string out argument.");
        }
        if ("set".equals(toolName)) {
            return setInvocation(arguments);
        }
        return invalid("unsupported tool: " + toolName);
    }

    private static McpToolInvocation args(String[] args, String error) {
        return args == null ? invalid(error) : args(args, (InputStream) null);
    }

    private static McpToolInvocation args(String[] args, InputStream stdin) {
        return new McpToolInvocation(args, stdin, null, false, null);
    }

    private static McpToolInvocation invalid(String error) {
        return new McpToolInvocation(null, null, error, false, null);
    }

    private static McpToolInvocation invalidToolCall(String error) {
        return new McpToolInvocation(null, null, error, true, null);
    }

    private static McpToolInvocation invalidWriteMode(ApplyWriteModeResolver.UsageException exception) {
        return new McpToolInvocation(null, null, exception.getMessage(), true, exception.suggestedNextAction());
    }

    private static String[] readArgs(Map<String, Object> arguments) {
        Object fileValue = fileArgument(arguments);
        if (!(fileValue instanceof String) || ((String) fileValue).trim().isEmpty()) {
            return null;
        }

        List<String> args = new ArrayList<String>();
        args.add("read");
        args.add((String) fileValue);
        appendStringOption(args, arguments, "jmeter_home", "--jmeter-home");
        appendStringOption(args, arguments, "ref", "--ref");
        appendStringOption(args, arguments, "depth", "--depth");
        appendPropertiesOption(args, arguments);
        appendBooleanOption(args, arguments, "includeDisabledDetails", "--include-disabled-details");
        return args.toArray(new String[args.size()]);
    }

    private static String[] validateArgs(Map<String, Object> arguments) {
        Object fileValue = fileArgument(arguments);
        if (!(fileValue instanceof String) || ((String) fileValue).trim().isEmpty()) {
            return null;
        }

        List<String> args = new ArrayList<String>();
        args.add("validate");
        args.add((String) fileValue);
        appendStringOption(args, arguments, "jmeter_home", "--jmeter-home");
        return args.toArray(new String[args.size()]);
    }

    private static String[] componentsArgs(Map<String, Object> arguments) {
        List<String> args = new ArrayList<String>();
        args.add("components");
        Object componentValue = arguments.containsKey("component") ? arguments.get("component") : arguments.get("kind");
        if (componentValue instanceof String && !((String) componentValue).trim().isEmpty()) {
            args.add((String) componentValue);
        }
        appendStringOption(args, arguments, "componentToken", "--component-token");
        appendStringOption(args, arguments, "jmeter_home", "--jmeter-home");
        appendStringOption(args, arguments, "category", "--category");
        appendTrueMarkerValueOption(args, arguments, "details", "--details");
        appendTrueMarkerValueOption(args, arguments, "diagnostics", "--diagnostics");
        appendNumberOption(args, arguments, "limit", "--limit");
        appendNumberOption(args, arguments, "maxBytes", "--max-bytes");
        appendStringOption(args, arguments, "cursor", "--cursor");
        return args.toArray(new String[args.size()]);
    }

    private static String[] categoriesArgs(Map<String, Object> arguments) {
        List<String> args = new ArrayList<String>();
        args.add("categories");
        args.add("ls");
        appendStringOption(args, arguments, "jmeter_home", "--jmeter-home");
        return args.toArray(new String[args.size()]);
    }

    private static McpToolInvocation applyInvocation(Map<String, Object> arguments) {
        Object fileValue = fileArgument(arguments);
        if (!(fileValue instanceof String) || ((String) fileValue).trim().isEmpty()) {
            return invalid("apply requires a string file argument.");
        }
        Object patchFile = arguments.get("patchFile");
        Object patchYaml = arguments.get("patchYaml");
        boolean hasPatchFile = patchFile instanceof String;
        boolean hasPatchYaml = patchYaml instanceof String;
        if (hasPatchFile == hasPatchYaml) {
            return invalidPatchSource();
        }
        if (hasPatchYaml && utf8Length((String) patchYaml) > MAX_PATCH_YAML_BYTES) {
            return invalidToolCall("PATCH_INPUT_TOO_LARGE: inline patchYaml exceeds the 4 MiB maximum.");
        }
        String out = arguments.get("out") instanceof String ? (String) arguments.get("out") : null;
        try {
            ApplyWriteModeResolver.resolve(Paths.get((String) fileValue),
                    Boolean.TRUE.equals(arguments.get("dryRun")),
                    Boolean.TRUE.equals(arguments.get("override")), out,
                    Boolean.TRUE.equals(arguments.get("forceOut")));
        } catch (ApplyWriteModeResolver.UsageException exception) {
            return invalidWriteMode(exception);
        }

        List<String> args = new ArrayList<String>();
        args.add("apply");
        args.add((String) fileValue);
        args.add("--patch");
        InputStream stdin = null;
        if (patchYaml instanceof String) {
            args.add("-");
            stdin = new ByteArrayInputStream(((String) patchYaml).getBytes(StandardCharsets.UTF_8));
        } else {
            args.add((String) patchFile);
        }
        appendBooleanOption(args, arguments, "dryRun", "--dry-run");
        appendStringOption(args, arguments, "out", "--out");
        appendBooleanOption(args, arguments, "override", "--override");
        appendBooleanOption(args, arguments, "forceOut", "--force-out");
        appendStringOption(args, arguments, "jmeter_home", "--jmeter-home");
        return args(args.toArray(new String[args.size()]), stdin);
    }

    private static int utf8Length(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x7f) {
                bytes++;
            } else if (character <= 0x7ff) {
                bytes += 2;
            } else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else if (Character.isSurrogate(character)) {
                bytes++;
            } else {
                bytes += 3;
            }
            if (bytes > MAX_PATCH_YAML_BYTES) {
                return bytes;
            }
        }
        return bytes;
    }

    private static McpToolInvocation invalidPatchSource() {
        return new McpToolInvocation(null, null,
                "apply requires exactly one of patchFile or patchYaml.", true,
                "Provide either patchFile or patchYaml, but not both, then retry apply.");
    }

    private static String[] initArgs(Map<String, Object> arguments) {
        Object outValue = arguments.get("out");
        if (!(outValue instanceof String) || ((String) outValue).trim().isEmpty()) {
            return null;
        }

        List<String> args = new ArrayList<String>();
        args.add("init");
        args.add((String) outValue);
        appendBooleanOption(args, arguments, "forceOut", "--force-out");
        appendStringOption(args, arguments, "jmeter_home", "--jmeter-home");
        appendStringOption(args, arguments, "name", "--name");
        appendStringOption(args, arguments, "threadGroupName", "--thread-group-name");
        return args.toArray(new String[args.size()]);
    }

    private static String[] setArgs(Map<String, Object> arguments) {
        Object fileValue = fileArgument(arguments);
        Object locatorValue = arguments.get("locator");
        Object propertyValue = arguments.get("property");
        Object valueValue = arguments.get("value");
        if (!(fileValue instanceof String) || ((String) fileValue).trim().isEmpty()
                || !(locatorValue instanceof String) || ((String) locatorValue).trim().isEmpty()
                || !(propertyValue instanceof List<?>) || ((List<?>) propertyValue).isEmpty()
                || !setValue(valueValue)) {
            return null;
        }

        boolean structured = valueValue instanceof Map<?, ?> || valueValue instanceof List<?>;
        Object requestedType = arguments.get("type");

        List<String> args = new ArrayList<String>();
        args.add("set");
        args.add((String) fileValue);
        args.add("--locator");
        args.add((String) locatorValue);
        args.add("--property");
        args.add(McpJson.write(propertyValue));
        args.add("--value");
        args.add(structured ? McpJson.write(valueValue) : String.valueOf(valueValue));
        if (structured) {
            args.add("--type");
            if (requestedType == null) return null;
            args.add(String.valueOf(requestedType));
        } else {
            String scalarType = requestedType == null
                    ? inferredScalarType(valueValue) : String.valueOf(requestedType);
            if (scalarType != null) {
                args.add("--type");
                args.add(scalarType);
            }
        }
        appendStringOption(args, arguments, "out", "--out");
        appendBooleanOption(args, arguments, "override", "--override");
        appendBooleanOption(args, arguments, "forceOut", "--force-out");
        appendStringOption(args, arguments, "jmeter_home", "--jmeter-home");
        return args.toArray(new String[args.size()]);
    }

    private static McpToolInvocation setInvocation(Map<String, Object> arguments) {
        Object value = arguments.get("value");
        Object requestedType = arguments.get("type");
        boolean structured = value instanceof Map<?, ?> || value instanceof List<?>;
        if (structured && requestedType != null) {
            try {
                ApplyPatch.ValueType.parse(String.valueOf(requestedType));
            } catch (ApplyPatchParseException exception) {
                return invalidToolCall(exception.getMessage());
            }
        }
        if (structured && requestedType != null
                && scalarType(String.valueOf(requestedType))) {
            String property = String.valueOf(arguments.get("property"));
            String locator = String.valueOf(arguments.get("locator"));
            return invalidToolCall("set property '" + property + "' on component locator '" + locator
                    + "' has a structured value, which conflicts"
                    + " with explicit scalar type '" + requestedType
                    + "'; use collection, map, element, rows, or opaque.");
        }
        return args(setArgs(arguments), "set requires string file, locator, property, and scalar/object/list value arguments.");
    }

    private static boolean scalarType(String type) {
        try {
            return !PropertyValueType.parse(type).structuredCollection();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean setValue(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Map<?, ?> || value instanceof List<?>;
    }

    private static String inferredScalarType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return "int";
        }
        if (value instanceof Long) {
            long integer = ((Long) value).longValue();
            return integer >= Integer.MIN_VALUE && integer <= Integer.MAX_VALUE ? "int" : "long";
        }
        if (value instanceof Float) {
            return "float";
        }
        if (value instanceof Double) {
            return "double";
        }
        return null;
    }

    private static Object fileArgument(Map<String, Object> arguments) {
        return arguments.containsKey("file") ? arguments.get("file") : arguments.get("path");
    }

    private static void appendStringOption(List<String> args, Map<String, Object> arguments, String key, String option) {
        Object value = arguments.get(key);
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            args.add(option);
            args.add((String) value);
        } else if (value instanceof Number) {
            args.add(option);
            args.add(String.valueOf(value));
        }
    }

    private static void appendPropertiesOption(List<String> args, Map<String, Object> arguments) {
        Object value = arguments.get("properties");
        if (value instanceof String && "none".equals(value)) {
            return;
        }
        appendStringOption(args, arguments, "properties", "--properties");
    }

    private static void appendBooleanOption(List<String> args, Map<String, Object> arguments, String key, String option) {
        Object value = arguments.get(key);
        if (Boolean.TRUE.equals(value)) {
            args.add(option);
        }
    }

    private static void appendNumberOption(
            List<String> args, Map<String, Object> arguments, String key, String option) {
        Object value = arguments.get(key);
        if (value instanceof Number) {
            args.add(option);
            args.add(String.valueOf(((Number) value).longValue()));
        }
    }

    private static void appendTrueMarkerValueOption(
            List<String> args, Map<String, Object> arguments, String key, String option) {
        if (Boolean.TRUE.equals(arguments.get(key))) {
            args.add(option);
            args.add("true");
        }
    }

    boolean valid() {
        return args != null;
    }

    String[] args() {
        return args;
    }

    InputStream stdin() {
        return stdin;
    }

    String error() {
        return error;
    }

    boolean toolError() {
        return toolError;
    }

    String suggestedNextAction() {
        return suggestedNextAction;
    }
}
