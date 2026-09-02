package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.path.PropertyAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class McpToolArgumentValidator {
    private static final int MAX_SCALAR_DIAGNOSTIC_VALUE_CHARACTERS = 96;
    private static final Map<String, Set<String>> ALLOWED_KEYS = allowedKeysByTool();
    private static final Set<String> STRING_KEYS = setOf(
            "file", "path", "jmeter_home", "ref", "properties", "category", "component", "kind",
            "patchFile", "patchYaml", "out", "name", "threadGroupName", "locator", "type",
            "cursor", "componentToken");
    private static final Set<String> TRUE_MARKERS = setOf(
            "includeDisabledDetails", "details", "diagnostics", "dryRun", "override", "forceOut");
    private static final Set<String> SET_TYPES = setTypes();

    private McpToolArgumentValidator() {
    }

    static String validate(String toolName, Map<String, Object> arguments) {
        Set<String> allowed = ALLOWED_KEYS.get(toolName);
        if (allowed == null) {
            return error(toolName, "is not a supported public tool");
        }
        for (String key : arguments.keySet()) {
            if (!allowed.contains(key)) {
                return error(toolName, "has unexpected argument '" + key + "'. allowed arguments: " + allowed);
            }
        }
        for (String key : STRING_KEYS) {
            if (arguments.containsKey(key) && !(arguments.get(key) instanceof String)) {
                return error(toolName, "argument '" + key + "' must be a string");
            }
        }
        for (String key : TRUE_MARKERS) {
            if (arguments.containsKey(key) && !Boolean.TRUE.equals(arguments.get(key))) {
                return error(toolName, "marker argument '" + key + "' must be the boolean value true");
            }
        }
        String scalarError = scalarError(toolName, arguments);
        if (scalarError != null) {
            return scalarError;
        }
        if (hasJmxInput(toolName)) {
            String inputError = exactlyOne(toolName, arguments, "file", "path");
            if (inputError != null) {
                return inputError;
            }
        }
        if ("components".equals(toolName)) {
            return componentsError(arguments);
        }
        if ("apply".equals(toolName)) {
            return applyError(arguments);
        }
        if ("init".equals(toolName)) {
            return requiredString(toolName, arguments, "out");
        }
        if ("set".equals(toolName)) {
            return setError(arguments);
        }
        return null;
    }

    static void assertSchemaKeys(String toolName, Set<String> schemaKeys) {
        Set<String> allowed = ALLOWED_KEYS.get(toolName);
        if (allowed == null || !allowed.equals(schemaKeys)) {
            throw new IllegalStateException("MCP schema keys for " + toolName + " must equal " + allowed
                    + " but were " + schemaKeys);
        }
    }

    static boolean existingToolErrorHandoff(String toolName, Map<String, Object> arguments) {
        if (!"apply".equals(toolName) || arguments.containsKey("file") == arguments.containsKey("path")) {
            return false;
        }
        Object file = arguments.containsKey("file") ? arguments.get("file") : arguments.get("path");
        return file instanceof String && !((String) file).trim().isEmpty();
    }

    static String applySuggestedNextAction() {
        return "Call apply with exactly one patch source and one valid mode: "
                + "dryRun true, override true, or out; use forceOut only with out.";
    }

    static Map<String, Object> readDepthSchema(Map<String, Object> property) {
        return McpToolSchemaConstraints.readDepth(property);
    }

    static Map<String, Object> readPropertiesSchema(Map<String, Object> property) {
        return McpToolSchemaConstraints.readProperties(property);
    }

    static Map<String, Object> trueMarkerSchema(Map<String, Object> property) {
        return McpToolSchemaConstraints.trueMarker(property);
    }

    static void applyComponentSchema(Map<String, Object> inputSchema) {
        McpToolSchemaConstraints.componentModes(inputSchema);
    }

    private static String scalarError(String toolName, Map<String, Object> arguments) {
        if (arguments.containsKey("depth")) {
            Object depth = arguments.get("depth");
            if (!(depth instanceof Number) || !integer((Number) depth)) {
                return error(toolName, "argument 'depth' must be an integer zero or greater");
            }
            if (((Number) depth).longValue() < 0L) {
                return error(toolName, "argument 'depth' must be zero or greater");
            }
        }
        if (arguments.containsKey("properties")
                && !setOf("none", "key", "all", "writable").contains(arguments.get("properties"))) {
            return error(toolName, "argument 'properties' must be one of none, key, all, or writable");
        }
        if (arguments.containsKey("type") && !SET_TYPES.contains(arguments.get("type"))) {
            return error(toolName, "argument 'type' has unsupported value '" + arguments.get("type")
                    + "'; use one of " + SET_TYPES);
        }
        if (arguments.containsKey("value") && !jsonValue(arguments.get("value"))) {
            return error(toolName, "argument 'value' must be a scalar, object, or list");
        }
        return null;
    }

    private static String componentsError(Map<String, Object> arguments) {
        boolean component = arguments.containsKey("component");
        boolean kind = arguments.containsKey("kind");
        if (component && kind) {
            return error("components", "requires exactly one of 'component' or 'kind', not both");
        }
        boolean selected = component || kind;
        boolean details = arguments.containsKey("details");
        boolean diagnostics = arguments.containsKey("diagnostics");
        boolean category = arguments.containsKey("category");
        boolean componentToken = arguments.containsKey("componentToken");
        if (componentToken && (((String) arguments.get("componentToken")).isEmpty()
                || ((String) arguments.get("componentToken")).length() > 512)) {
            return error("components", "argument 'componentToken' must be a non-empty opaque token of at most 512 ASCII bytes");
        }
        if (componentToken && (selected || category || details || diagnostics
                || arguments.containsKey("limit") || arguments.containsKey("maxBytes")
                || arguments.containsKey("cursor"))) {
            return error("components", "argument 'componentToken' is exclusive with component, kind, category, details, diagnostics, limit, maxBytes, and cursor");
        }
        if (details && !selected && !category) {
            return error("components", "argument 'details' requires exactly one of 'component' or 'kind'");
        }
        if (diagnostics && !selected) {
            return error("components", "argument 'diagnostics' requires exactly one of 'component' or 'kind'");
        }
        if (category && (selected || diagnostics)) {
            return error("components", "argument 'category' is exclusive with component, kind, and diagnostics");
        }
        boolean paging = arguments.containsKey("limit") || arguments.containsKey("maxBytes")
                || arguments.containsKey("cursor");
        if (paging && (!category || !Boolean.TRUE.equals(arguments.get("details")))) {
            if (arguments.containsKey("maxBytes")) {
                return error("components", "argument 'maxBytes' requires category with details true");
            }
            return error("components", "arguments 'limit' and 'cursor' require category with details true");
        }
        if (arguments.containsKey("limit")) {
            Object limit = arguments.get("limit");
            if (!(limit instanceof Number) || !integer((Number) limit)
                    || ((Number) limit).longValue() < 1L || ((Number) limit).longValue() > 50L) {
                return error("components", "argument 'limit' must be an integer between 1 and 50");
            }
        }
        if (arguments.containsKey("maxBytes")) {
            Object maxBytes = arguments.get("maxBytes");
            if (!(maxBytes instanceof Number) || !integer((Number) maxBytes)
                    || ((Number) maxBytes).longValue() < 4096L
                    || ((Number) maxBytes).longValue() > 65536L) {
                return error("components", "argument 'maxBytes' must be an integer between 4096 and 65536");
            }
        }
        return null;
    }

    private static String applyError(Map<String, Object> arguments) {
        String patchError = exactlyOne("apply", arguments, "patchFile", "patchYaml");
        if (patchError != null) {
            return patchError;
        }
        boolean dryRun = arguments.containsKey("dryRun");
        boolean override = arguments.containsKey("override");
        boolean out = arguments.containsKey("out");
        boolean forceOut = arguments.containsKey("forceOut");
        if (dryRun && (override || forceOut)) {
            return error("apply", "dryRun is exclusive with override and forceOut; omit them or choose a write mode");
        }
        if (forceOut && !out) {
            return error("apply", "forceOut requires out and cannot be used with override or dryRun");
        }
        if (!dryRun && out == override) {
            return error("apply", "requires exactly one write mode: dryRun true, override true, or out");
        }
        return null;
    }

    private static String setError(Map<String, Object> arguments) {
        String locatorError = requiredString("set", arguments, "locator");
        if (locatorError != null) return locatorError;
        Object property = arguments.get("property");
        try {
            PropertyAddress.decode(property);
        } catch (IllegalArgumentException exception) {
            return error("set", "argument 'property' must be a non-empty scalar array; each member must be a string "
                    + "or integer from 0 through 2147483647: " + exception.getMessage());
        }
        if (!arguments.containsKey("value") || !jsonValue(arguments.get("value"))) {
            return error("set", "requires argument 'value' as a scalar, object, or list");
        }
        Object value = arguments.get("value");
        if ((value instanceof Map<?, ?> || value instanceof java.util.List<?>)
                && !(arguments.get("type") instanceof String)) {
            return error("set", "structured argument 'value' requires explicit type collection, map, element, rows, or opaque");
        }
        String exactScalarError = exactScalarError(arguments);
        if (exactScalarError != null) {
            return exactScalarError;
        }
        if (arguments.containsKey("forceOut") && !arguments.containsKey("out")) {
            return error("set", "forceOut requires out and cannot be used with override");
        }
        return exactlyOne("set", arguments, "out", "override");
    }

    private static String exactScalarError(Map<String, Object> arguments) {
        Object requested = arguments.get("type");
        if (!(requested instanceof String)) {
            return null;
        }
        String type = (String) requested;
        Object value = arguments.get("value");
        boolean matches;
        if ("string".equals(type) || "raw".equals(type)) {
            matches = value instanceof String;
        } else if ("boolean".equals(type)) {
            matches = value instanceof Boolean;
        } else if ("int".equals(type)) {
            matches = value instanceof Integer;
        } else if ("long".equals(type)) {
            matches = value instanceof Long;
        } else if ("float".equals(type)) {
            matches = value instanceof Float && Float.isFinite(((Float) value).floatValue());
        } else if ("double".equals(type)) {
            matches = value instanceof Double && Double.isFinite(((Double) value).doubleValue());
        } else if ("null".equals(type)) {
            matches = value == null;
        } else {
            return null;
        }
        if (matches) {
            return null;
        }
        return error("set", "property at address " + arguments.get("property")
                + " expects exact type '" + type + "' but received actual type '"
                + actualType(value) + "' with actual value '" + boundedScalarValue(value) + "'");
    }

    private static String actualType(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static String boundedScalarValue(Object value) {
        String rendered = String.valueOf(value);
        if (rendered.length() <= MAX_SCALAR_DIAGNOSTIC_VALUE_CHARACTERS) {
            return rendered;
        }
        return rendered.substring(0, MAX_SCALAR_DIAGNOSTIC_VALUE_CHARACTERS) + "[truncated]";
    }

    private static String exactlyOne(
            String toolName, Map<String, Object> arguments, String first, String second) {
        if (arguments.containsKey(first) == arguments.containsKey(second)) {
            return error(toolName, "requires exactly one of '" + first + "' or '" + second + "'");
        }
        String selected = arguments.containsKey(first) ? first : second;
        return TRUE_MARKERS.contains(selected) ? null : requiredString(toolName, arguments, selected);
    }

    private static String requiredString(String toolName, Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            return error(toolName, "requires non-blank string argument '" + key + "'");
        }
        return null;
    }

    private static boolean hasJmxInput(String toolName) {
        return "read".equals(toolName) || "validate".equals(toolName)
                || "apply".equals(toolName) || "set".equals(toolName);
    }

    private static boolean integer(Number number) {
        double value = number.doubleValue();
        return !Double.isNaN(value) && !Double.isInfinite(value) && value == Math.rint(value);
    }

    private static boolean jsonValue(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Map<?, ?> || value instanceof java.util.List<?>;
    }

    private static String error(String toolName, String message) {
        return toolName + " " + message + ". Correct the named arguments and call " + toolName + " again.";
    }

    private static Map<String, Set<String>> allowedKeysByTool() {
        Map<String, Set<String>> keys = new LinkedHashMap<String, Set<String>>();
        keys.put("read", setOf("file", "path", "jmeter_home", "depth", "ref", "properties",
                "includeDisabledDetails"));
        keys.put("validate", setOf("file", "path", "jmeter_home"));
        keys.put("components", setOf(
                "jmeter_home", "category", "component", "kind", "details", "diagnostics",
                "limit", "maxBytes", "cursor", "componentToken"));
        keys.put("categories", setOf("jmeter_home"));
        keys.put("apply", setOf("file", "path", "patchFile", "patchYaml", "dryRun", "out", "override", "forceOut", "jmeter_home"));
        keys.put("init", setOf("out", "forceOut", "jmeter_home", "name", "threadGroupName"));
        keys.put("set", setOf("file", "path", "locator", "property", "value", "type", "out", "override", "forceOut", "jmeter_home"));
        return Collections.unmodifiableMap(keys);
    }

    private static Set<String> setTypes() {
        return setOf(
                "string", "boolean", "int", "long", "float", "double", "null",
                "raw", "collection", "map", "element", "rows", "opaque");
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }
}
