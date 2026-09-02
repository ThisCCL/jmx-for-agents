package io.github.thisccl.j4a.apply;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import io.github.thisccl.j4a.jmx.property.PropertyGraphDocumentMapper;
import io.github.thisccl.j4a.jmx.property.PropertyGraphRepresentationException;
import io.github.thisccl.j4a.path.PropertyAddress;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

public final class ApplyPatchParser {
    private static final int MAX_SCALAR_DIAGNOSTIC_VALUE_CHARACTERS = 96;
    private static final Set<String> TOP_LEVEL_FIELDS = setOf("changes");
    private static final Set<String> OPERATIONS =
            setOf("set", "add", "move", "delete", "append", "insert", "remove");
    private static final Set<String> PROPERTY_FIELDS = setOf("property", "value", "type");

    public ApplyPatch parse(String yamlText) {
        Object document = loadYaml(yamlText, true);
        Map<String, Object> root = requireMap(document, "patch document");
        rejectUnknownFields(root, TOP_LEVEL_FIELDS, "top-level");
        if (!root.containsKey("changes")) {
            throw new ApplyPatchParseException("top-level field 'changes' is required");
        }
        List<?> changes = requireList(root.get("changes"), "changes");

        List<ApplyPatch.Change> parsedChanges = new ArrayList<>();
        Set<String> refs = new LinkedHashSet<>();
        for (int index = 0; index < changes.size(); index++) {
            parsedChanges.add(parseChange(changes.get(index), index, refs));
        }
        return new ApplyPatch(parsedChanges);
    }

    public Object parseStructuredValue(
            String yamlText, PropertyAddress property, ApplyPatch.ValueType type) {
        Object value = loadYaml(yamlText, false);
        return parsePropertyValue(value, type, property,
                "property '" + propertyLabel(property) + "'");
    }

    public PropertyAddress parsePropertyAddress(String document) {
        if (document == null) {
            throw new ApplyPatchParseException("property address is required");
        }
        return propertyAddress(loadYaml(document, false), "property");
    }

    private Object loadYaml(String yamlText, boolean rejectJsonDocument) {
        if (yamlText == null || yamlText.trim().isEmpty()) {
            throw new ApplyPatchParseException("patch document must not be empty");
        }
        if (rejectJsonDocument && startsWithFlowRoot(yamlText)) {
            throw new ApplyPatchParseException("JSON patch documents are not accepted; use YAML operation cards");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setWrappedToRootException(false);
        try {
            return new Yaml(new ExactNumericLiteral.Constructor(options)).load(yamlText);
        } catch (YAMLException exception) {
            throw new ApplyPatchParseException("malformed YAML patch: " + exception.getMessage(), exception);
        }
    }

    private ApplyPatch.Change parseChange(Object rawChange, int index, Set<String> refs) {
        Map<String, Object> change = requireMap(rawChange, "changes[" + index + "]");
        if (change.size() != 1) {
            throw new ApplyPatchParseException("changes[" + index + "] must contain exactly one operation key");
        }
        String operation = stringKey(change.keySet().iterator().next(), "changes[" + index + "] operation key");
        if (!OPERATIONS.contains(operation)) {
            throw new ApplyPatchParseException("unknown operation key '" + operation + "'");
        }
        Map<String, Object> fields = requireMap(change.get(operation), operation + " operation");
        try {
            ApplyPatch.Operation parsed;
            switch (operation) {
            case "set":
                parsed = parseSet(fields, refs);
                break;
            case "add":
                parsed = parseAdd(fields);
                break;
            case "move":
                parsed = parseMove(fields, refs);
                break;
            case "delete":
                parsed = parseDelete(fields, refs);
                break;
            case "append":
                parsed = parseAppend(fields, refs);
                break;
            case "insert":
                parsed = parseInsert(fields, refs);
                break;
            case "remove":
                parsed = parseRemove(fields, refs);
                break;
            default:
                throw new ApplyPatchParseException("unknown operation key '" + operation + "'");
            }
            return new ApplyPatch.Change(parsed);
        } catch (ApplyPatchParseException exception) {
            throw exception.atChange(index, operation, partialContext(operation, fields));
        }
    }

    private static MutationChangeContext partialContext(String operation, Map<String, Object> fields) {
        MutationChangeContext.Builder context = MutationChangeContext.partial();
        if ("add".equals(operation)) putSafeString(context, fields, "as", "alias");
        for (String field : Arrays.asList("ref", "component", "parent", "before", "after", "position")) {
            putSafeString(context, fields, field, field);
        }
        if (fields.get("index") instanceof Number) context.field("index", fields.get("index"));
        Object property = fields.get("property");
        List<Object> propertyAddress = safeAddress(property);
        if (propertyAddress != null) context.field("property", propertyAddress);
        Object properties = fields.get("properties");
        if (properties instanceof List<?>) {
            List<Object> addresses = new ArrayList<Object>();
            for (Object item : (List<?>) properties) {
                if (item instanceof Map<?, ?>) {
                    List<Object> address = safeAddress(((Map<?, ?>) item).get("property"));
                    if (address != null) addresses.add(address);
                }
            }
            if (!addresses.isEmpty()) context.field("properties", addresses);
        }
        return context.build();
    }

    private static List<Object> safeAddress(Object value) {
        if (!(value instanceof List<?>) || ((List<?>) value).isEmpty()) return null;
        List<Object> address = new ArrayList<Object>();
        for (Object segment : (List<?>) value) {
            if (!(segment instanceof String) && !(segment instanceof Number)) return null;
            address.add(segment);
        }
        return address;
    }

    private static void putSafeString(
            MutationChangeContext.Builder context, Map<String, Object> fields,
            String source, String target) {
        Object value = fields.get(source);
        if (value instanceof String && !((String) value).trim().isEmpty()) context.field(target, value);
    }

    private ApplyPatch.SetOperation parseSet(Map<String, Object> fields, Set<String> refs) {
        rejectUnknownFields(fields, setOf("ref", "component", "properties"), "set");
        ApplyPatch.ReferenceExpression ref = parseReference(fields, "ref", refs, true);
        return new ApplyPatch.SetOperation(
                ref,
                optionalString(fields, "component"),
                parseProperties(requireList(fields.get("properties"), "properties")));
    }

    private ApplyPatch.AddOperation parseAdd(Map<String, Object> fields) {
        rejectUnknownFields(fields, setOf("parent", "before", "after", "position", "component", "properties", "as"), "add");
        return new ApplyPatch.AddOperation(
                parseReference(fields, "parent", null, false),
                optionalReference(fields, "before"),
                optionalReference(fields, "after"),
                optionalPosition(fields),
                requireString(fields, "component"),
                parseOptionalAddProperties(fields),
                optionalAlias(fields));
    }

    private ApplyPatch.MoveOperation parseMove(Map<String, Object> fields, Set<String> refs) {
        rejectUnknownFields(fields, setOf("ref", "component", "parent", "before", "after", "position"), "move");
        ApplyPatch.ReferenceExpression ref = parseReference(fields, "ref", refs, true);
        return new ApplyPatch.MoveOperation(
                ref,
                optionalString(fields, "component"),
                parseReference(fields, "parent", null, false),
                optionalReference(fields, "before"),
                optionalReference(fields, "after"),
                optionalPosition(fields));
    }

    private ApplyPatch.DeleteOperation parseDelete(Map<String, Object> fields, Set<String> refs) {
        rejectUnknownFields(fields, setOf("ref", "component"), "delete");
        ApplyPatch.ReferenceExpression ref = parseReference(fields, "ref", refs, true);
        return new ApplyPatch.DeleteOperation(ref, optionalString(fields, "component"));
    }

    private ApplyPatch.AppendOperation parseAppend(Map<String, Object> fields, Set<String> refs) {
        rejectUnknownFields(fields, setOf("ref", "property", "row"), "append");
        return new ApplyPatch.AppendOperation(
                parseReference(fields, "ref", refs, false),
                propertyAddress(fields.get("property"), "append.property"),
                requireMap(ExactNumericLiteral.untyped(fields.get("row")), "field 'row'"));
    }

    private ApplyPatch.InsertOperation parseInsert(Map<String, Object> fields, Set<String> refs) {
        rejectUnknownFields(fields, setOf("ref", "property", "index", "row"), "insert");
        return new ApplyPatch.InsertOperation(
                parseReference(fields, "ref", refs, false),
                propertyAddress(fields.get("property"), "insert.property"),
                requireNonNegativeIndex(fields, "index"),
                requireMap(ExactNumericLiteral.untyped(fields.get("row")), "field 'row'"));
    }

    private ApplyPatch.RemoveOperation parseRemove(Map<String, Object> fields, Set<String> refs) {
        rejectUnknownFields(fields, setOf("ref", "property", "index"), "remove");
        return new ApplyPatch.RemoveOperation(
                parseReference(fields, "ref", refs, false),
                propertyAddress(fields.get("property"), "remove.property"),
                requireNonNegativeIndex(fields, "index"));
    }

    private List<ApplyPatch.PropertyChange> parseProperties(List<?> properties) {
        if (properties.isEmpty()) {
            throw new ApplyPatchParseException("field 'properties' must contain at least one property record");
        }
        return parsePropertyRecords(properties);
    }

    private List<ApplyPatch.PropertyChange> parseOptionalAddProperties(Map<String, Object> fields) {
        if (!fields.containsKey("properties")) {
            return Collections.emptyList();
        }
        return parsePropertyRecords(requireList(fields.get("properties"), "properties"));
    }

    private List<ApplyPatch.PropertyChange> parsePropertyRecords(List<?> properties) {
        List<ApplyPatch.PropertyChange> parsed = new ArrayList<>();
        for (int index = 0; index < properties.size(); index++) {
            Map<String, Object> fields = requireMap(properties.get(index), "properties[" + index + "]");
            rejectUnknownFields(fields, PROPERTY_FIELDS, "property record");
            ApplyPatch.ValueType type = ApplyPatch.ValueType.parse(requireString(fields, "type"));
            PropertyAddress property = propertyAddress(
                    fields.get("property"), "properties[" + index + "].property");
            String label = propertyLabel(property);
            String context = "property record[" + index + "] property '" + label + "'";
            Object value = parsePropertyValue(fields.get("value"), type, property, context);
            parsed.add(new ApplyPatch.PropertyChange(
                    property,
                    value,
                    type));
        }
        return parsed;
    }

    private static PropertyAddress propertyAddress(Object source, String path) {
        try {
            return PropertyAddress.decode(ExactNumericLiteral.untyped(source));
        } catch (IllegalArgumentException exception) {
            throw new ApplyPatchParseException(path + ": " + exception.getMessage(), exception);
        }
    }

    private static String propertyLabel(PropertyAddress property) {
        return property.toString();
    }

    private Object parsePropertyValue(
            Object value, ApplyPatch.ValueType type, PropertyAddress property, String context) {
        if (type == ApplyPatch.ValueType.ROWS) {
            if (!(value instanceof List<?>) && !(value instanceof Map<?, ?>)) {
                throw new ApplyPatchParseException(
                        context + " value for type 'rows' must be a list or an object containing 'rows'");
            }
            return ExactNumericLiteral.untyped(value);
        }
        if (type.recursiveGraph()) {
            if (type == ApplyPatch.ValueType.COLLECTION && !(value instanceof Map<?, ?>)) {
                throw genericCollectionShapeError(context, null);
            }
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("property", validationProperty());
            document.put("type", type.name().toLowerCase(java.util.Locale.ROOT));
            document.put("value", requireMap(value, context + " value"));
            try {
                new PropertyGraphDocumentMapper().fromDocument(document);
                return document.get("value");
            } catch (PropertyGraphRepresentationException exception) {
                if (type == ApplyPatch.ValueType.COLLECTION) {
                    throw genericCollectionShapeError(context, exception);
                }
                throw new ApplyPatchParseException(exception.getMessage(), exception);
            }
        }
        if (type.graphScalar() && value instanceof Map<?, ?>) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("property", validationProperty());
            document.put("type", type.name().toLowerCase(java.util.Locale.ROOT));
            document.put("value", requireMap(value, context + " value"));
            try {
                new PropertyGraphDocumentMapper().fromDocument(document);
                return document.get("value");
            } catch (PropertyGraphRepresentationException exception) {
                throw new ApplyPatchParseException(exception.getMessage(), exception);
            }
        }
        if (type == ApplyPatch.ValueType.NULL && value == null) {
            return null;
        }
        if (type.graphScalar()) {
            return parseScalar(value, type, property, context);
        }
        if (!isScalar(value)) {
            throw new ApplyPatchParseException("field 'value' must be a scalar");
        }
        return value;
    }

    private ApplyPatchParseException genericCollectionShapeError(
            String context, PropertyGraphRepresentationException cause) {
        String prefix = cause == null ? context + " value must be a map" : cause.getMessage();
        String message = prefix
                + "; generic collection value requires an envelope with 'presence', "
                + "'property_class', and 'items'; copy 'value_template' from components details "
                + "or copy an exact value from focused read";
        return cause == null
                ? new ApplyPatchParseException(message)
                : new ApplyPatchParseException(message, cause);
    }

    private boolean startsWithFlowRoot(String yamlText) {
        for (String line : yamlText.split("\\R", -1)) {
            String trimmed = stripLeading(line);
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("%") || "---".equals(trimmed)) {
                continue;
            }
            return trimmed.startsWith("{") || trimmed.startsWith("[");
        }
        return false;
    }

    private Object parseScalar(
            Object value, ApplyPatch.ValueType type, PropertyAddress property, String context) {
        switch (type) {
            case STRING:
                return requireScalarInstance(value, String.class, type, property, context);
            case BOOLEAN:
                return requireScalarInstance(value, Boolean.class, type, property, context);
            case INT:
                if (value instanceof ExactNumericLiteral) {
                    return decodeNumericLiteral(value, type, property, context);
                }
                return requireScalarInstance(value, Integer.class, type, property, context);
            case LONG:
                if (value instanceof ExactNumericLiteral) {
                    return decodeNumericLiteral(value, type, property, context);
                }
                return requireScalarInstance(value, Long.class, type, property, context);
            case FLOAT:
                if (value instanceof ExactNumericLiteral) {
                    return decodeNumericLiteral(value, type, property, context);
                }
                Float floatValue = requireScalarInstance(value, Float.class, type, property, context);
                if (!Float.isFinite(floatValue.floatValue())) {
                    throw scalarRangeMismatch(type, value, property, context, "finite Java float range");
                }
                return floatValue;
            case DOUBLE:
                if (value instanceof ExactNumericLiteral) {
                    return decodeNumericLiteral(value, type, property, context);
                }
                Double doubleValue = requireScalarInstance(value, Double.class, type, property, context);
                if (!Double.isFinite(doubleValue.doubleValue())) {
                    throw scalarRangeMismatch(type, value, property, context, "finite Java double range");
                }
                return doubleValue;
            case NULL:
                throw scalarTypeMismatch(type, value, property, context);
            case RAW:
                return requireScalarInstance(value, String.class, type, property, context);
            default:
                throw new IllegalArgumentException("type is not scalar: " + type);
        }
    }

    public static Number decodeExactNumericLiteral(String lexeme, String declaredType) {
        return ExactNumericDecoder.decode(lexeme, declaredType);
    }

    public static Number materializeUntypedNumber(Number value) {
        return (Number) ExactNumericLiteral.untypedScalar(value);
    }

    private Number decodeNumericLiteral(
            Object literal,
            ApplyPatch.ValueType type,
            PropertyAddress property,
            String context) {
        try {
            return decodeExactNumericLiteral(
                    literal.toString(), type.name().toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw scalarRangeMismatch(
                    type, literal, property, context,
                    "finite Java "
                            + type.name().toLowerCase(java.util.Locale.ROOT) + " range");
        }
    }

    private <T> T requireScalarInstance(
            Object value,
            Class<T> expected,
            ApplyPatch.ValueType type,
            PropertyAddress property,
            String context) {
        if (!expected.isInstance(value)) {
            throw scalarTypeMismatch(type, value, property, context);
        }
        return expected.cast(value);
    }

    private ApplyPatchParseException scalarTypeMismatch(
            ApplyPatch.ValueType expected,
            Object actual,
            PropertyAddress property,
            String context) {
        return new ApplyPatchParseException(
                context + " at address " + property
                        + " expected type '" + expected.name().toLowerCase(java.util.Locale.ROOT)
                        + "' but received actual type '" + actualType(actual)
                        + "' with actual value '" + boundedScalarValue(actual) + "'");
    }

    private ApplyPatchParseException scalarRangeMismatch(
            ApplyPatch.ValueType expected,
            Object actual,
            PropertyAddress property,
            String context,
            String range) {
        return new ApplyPatchParseException(
                context + " at address " + property
                        + " expected type '" + expected.name().toLowerCase(java.util.Locale.ROOT)
                        + "' in " + range + " but received actual type '" + actualType(actual)
                        + "' with actual value '" + boundedScalarValue(actual) + "'");
    }

    private String actualType(Object actual) {
        actual = ExactNumericLiteral.untypedScalar(actual);
        return actual == null ? "null" : actual.getClass().getSimpleName();
    }

    private String boundedScalarValue(Object actual) {
        String rendered = String.valueOf(actual);
        if (rendered.length() <= MAX_SCALAR_DIAGNOSTIC_VALUE_CHARACTERS) {
            return rendered;
        }
        return rendered.substring(0, MAX_SCALAR_DIAGNOSTIC_VALUE_CHARACTERS) + "[truncated]";
    }

    private static List<Object> validationProperty() {
        return Collections.<Object>singletonList("$property");
    }

    private List<?> requireList(Object value, String fieldName) {
        if (value == null) {
            throw new ApplyPatchParseException("field '" + fieldName + "' is required");
        }
        if (value instanceof List<?>) {
            return (List<?>) value;
        }
        throw new ApplyPatchParseException("field '" + fieldName + "' must be a list");
    }

    private Map<String, Object> requireMap(Object value, String context) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                typed.put(stringKey(entry.getKey(), context), entry.getValue());
            }
            return typed;
        }
        throw new ApplyPatchParseException(context + " must be a map");
    }

    private void rejectUnknownFields(Map<String, Object> fields, Set<String> allowed, String context) {
        for (String field : fields.keySet()) {
            if (!allowed.contains(field)) {
                String prefix = "top-level".equals(context) ? "unknown top-level field" : "unknown field";
                throw new ApplyPatchParseException(prefix + " '" + field + "'");
            }
        }
    }

    private String requireString(Map<String, Object> fields, String fieldName) {
        if (!fields.containsKey(fieldName)) {
            throw new ApplyPatchParseException("field '" + fieldName + "' is required");
        }
        Object value = fields.get(fieldName);
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return (String) value;
        }
        throw new ApplyPatchParseException("field '" + fieldName + "' must be a non-empty string");
    }

    private Optional<String> optionalString(Map<String, Object> fields, String fieldName) {
        if (!fields.containsKey(fieldName)) {
            return Optional.empty();
        }
        return Optional.of(requireString(fields, fieldName));
    }

    private int requireNonNegativeIndex(Map<String, Object> fields, String fieldName) {
        if (!fields.containsKey(fieldName)) {
            throw new ApplyPatchParseException("field '" + fieldName + "' is required");
        }
        Object value = ExactNumericLiteral.untypedScalar(fields.get(fieldName));
        if (!(value instanceof Integer)) {
            throw new ApplyPatchParseException(
                    "field '" + fieldName + "' must be an integer from 0 through 2147483647");
        }
        int index = ((Integer) value).intValue();
        if (index < 0) {
            throw new ApplyPatchParseException("field '" + fieldName + "' must be zero or greater");
        }
        return index;
    }

    private ApplyPatch.ReferenceExpression parseReference(
            Map<String, Object> fields, String fieldName, Set<String> refs, boolean rememberOrdinary) {
        String spelling = requireString(fields, fieldName);
        if (spelling.startsWith("$")) {
            String alias = spelling.substring(1);
            try {
                return new ApplyPatch.AliasReference(alias);
            } catch (ApplyPatchParseException exception) {
                throw new ApplyPatchParseException(
                        "invalid alias name in field '" + fieldName + "': " + alias, exception);
            }
        }
        if (rememberOrdinary) {
            rememberRef(refs, spelling);
        }
        return new ApplyPatch.OrdinaryReference(spelling);
    }

    private Optional<ApplyPatch.ReferenceExpression> optionalReference(
            Map<String, Object> fields, String fieldName) {
        if (!fields.containsKey(fieldName)) {
            return Optional.empty();
        }
        return Optional.of(parseReference(fields, fieldName, null, false));
    }

    private Optional<String> optionalAlias(Map<String, Object> fields) {
        if (!fields.containsKey("as")) {
            return Optional.empty();
        }
        String alias = requireString(fields, "as");
        try {
            new ApplyPatch.AliasReference(alias);
        } catch (ApplyPatchParseException exception) {
            throw new ApplyPatchParseException(
                    "invalid alias name in field 'as': " + alias, exception);
        }
        return Optional.of(alias);
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static String stripLeading(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(index);
    }

    private Optional<String> optionalPosition(Map<String, Object> fields) {
        if (!fields.containsKey("position")) {
            return Optional.empty();
        }
        Object value = ExactNumericLiteral.untypedScalar(fields.get("position"));
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            return Optional.of((String) value);
        }
        if (value instanceof Integer) {
            return Optional.of(value.toString());
        }
        throw new ApplyPatchParseException("field 'position' must be a non-empty string or integer");
    }

    private String stringKey(Object key, String context) {
        if (key instanceof String) {
            return (String) key;
        }
        throw new ApplyPatchParseException(context + " keys must be strings");
    }

    private boolean isScalar(Object value) {
        return value instanceof String || value instanceof Boolean || value instanceof Number;
    }

    private void rememberRef(Set<String> refs, String ref) {
        if (!refs.add(ref)) {
            throw new ApplyPatchParseException("duplicate ref '" + ref + "'");
        }
    }
}
