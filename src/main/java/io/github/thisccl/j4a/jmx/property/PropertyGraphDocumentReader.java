package io.github.thisccl.j4a.jmx.property;

import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PropertyGraphDocumentReader {
    private static final Set<String> TOP_FIELDS = fields("property", "type", "value");
    private static final Set<String> VALUE_COMMON_FIELDS = fields("presence", "property_class");
    private static final Set<String> RECURSIVE_COMMON_FIELDS = fields("type", "presence", "property_class");
    private static final Set<String> ALL_PAYLOAD_FIELDS = fields(
            "value",
            "items",
            "entries",
            "element_class",
            "properties",
            "format",
            "base_digest",
            "outer_property_class",
            "runtime_fingerprint",
            "payload");
    private static final Set<String> SCALAR_FIELDS = fields("value");
    private static final Set<String> COLLECTION_FIELDS = fields("items");
    private static final Set<String> MAP_FIELDS = fields("entries");
    private static final Set<String> ELEMENT_FIELDS = fields("element_class", "properties");
    private static final Set<String> OPAQUE_FIELDS = fields(
            "format", "base_digest", "outer_property_class", "runtime_fingerprint", "payload");

    PropertyGraphDocument read(Map<?, ?> document) {
        Map<String, Object> root = mapping(document, "$");
        rejectUnknown(root, "$", TOP_FIELDS);
        Object propertyField = required(root, "$", "property");
        Object typeField = required(root, "$", "type");
        Object valueField = required(root, "$", "value");
        GraphType type = graphType(typeField, "$.type");
        PropertyAddress property;
        try {
            property = PropertyAddress.decode(propertyField);
        } catch (IllegalArgumentException exception) {
            throw error(
                    PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                    "$.property",
                    exception.getMessage());
        }
        return new PropertyGraphDocument(
                property,
                type,
                value(valueField, type, "$.value", false, rootAncestry(property)));
    }

    private RecursiveValue recursive(Object source, String path, ElementAncestry ancestry) {
        Map<String, Object> node = mapping(source, path);
        Object typeField = required(node, path, "type");
        GraphType type = graphType(typeField, path + ".type");
        return value(node, type, path, true, ancestry);
    }

    private RecursiveValue value(
            Object source,
            GraphType type,
            String path,
            boolean recursive,
            ElementAncestry ancestry) {
        Map<String, Object> value = mapping(source, path);
        Object presenceField = required(value, path, "presence");
        Object propertyClassField = required(value, path, "property_class");
        GraphPresence presence = presence(presenceField, path + ".presence");
        String propertyClass = nonEmptyText(propertyClassField, path + ".property_class");
        validateValueFields(value, type, presence, path, recursive);
        if (presence == GraphPresence.ABSENT) {
            return RecursiveValue.absent(type, propertyClass);
        }
        switch (type) {
            case STRING:
            case BOOLEAN:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
                return RecursiveValue.scalar(
                        type,
                        propertyClass,
                        scalar(required(value, path, "value"), type, path + ".value"));
            case NULL:
                Object nullValue = required(value, path, "value");
                if (nullValue != null) {
                    throw error(
                            PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                            path + ".value",
                            "type 'null' requires a null value");
                }
                return RecursiveValue.presentNull(propertyClass);
            case COLLECTION:
                return RecursiveValue.collection(
                        propertyClass,
                        recursiveList(
                                required(value, path, "items"), path + ".items", ancestry));
            case MAP:
                return RecursiveValue.map(
                        propertyClass,
                        mapEntries(
                                required(value, path, "entries"), path + ".entries", ancestry));
            case ELEMENT:
                return RecursiveValue.element(
                        propertyClass,
                        nonEmptyText(required(value, path, "element_class"), path + ".element_class"),
                        propertyWrites(
                                required(value, path, "properties"),
                                path + ".properties",
                                ancestry));
            case OPAQUE:
                return RecursiveValue.opaque(propertyClass, opaque(value, propertyClass, path));
            default:
                throw new IllegalStateException("unhandled graph type: " + type);
        }
    }

    private void validateValueFields(
            Map<String, Object> value,
            GraphType type,
            GraphPresence presence,
            String path,
            boolean recursive) {
        Set<String> common = recursive ? RECURSIVE_COMMON_FIELDS : VALUE_COMMON_FIELDS;
        Set<String> family = familyFields(type);
        for (String field : value.keySet()) {
            if (common.contains(field)) {
                continue;
            }
            String fieldPath = path + "." + field;
            if (!ALL_PAYLOAD_FIELDS.contains(field)) {
                throw error(PropertyGraphRepresentationErrorCode.UNKNOWN_FIELD, fieldPath, "unknown field");
            }
            if (presence == GraphPresence.ABSENT) {
                throw error(
                        PropertyGraphRepresentationErrorCode.CONFLICTING_FIELD,
                        fieldPath,
                        "payload is not allowed when presence is 'absent'");
            }
            if (!family.contains(field)) {
                throw error(
                        PropertyGraphRepresentationErrorCode.CONFLICTING_FIELD,
                        fieldPath,
                        "field is not allowed for type '" + type.wireName() + "'");
            }
        }
    }

    private List<RecursiveValue> recursiveList(
            Object source, String path, ElementAncestry ancestry) {
        List<?> values = list(source, path);
        ArrayList<RecursiveValue> result = new ArrayList<RecursiveValue>(values.size());
        for (int index = 0; index < values.size(); index++) {
            result.add(recursive(
                    values.get(index),
                    indexed(path, index),
                    append(ancestry, PropertyPathSegment.index(index))));
        }
        return result;
    }

    private List<RecursiveValue.MapEntry> mapEntries(
            Object source, String path, ElementAncestry ancestry) {
        List<?> values = list(source, path);
        ArrayList<RecursiveValue.MapEntry> result = new ArrayList<RecursiveValue.MapEntry>(values.size());
        LinkedHashMap<TypedScalarMapKey, String> firstKeyPaths =
                new LinkedHashMap<TypedScalarMapKey, String>();
        for (int index = 0; index < values.size(); index++) {
            String entryPath = indexed(path, index);
            Map<String, Object> entry = mapping(values.get(index), entryPath);
            rejectUnknown(entry, entryPath, fields("key", "value"));
            String keyPath = entryPath + ".key";
            TypedScalarMapKey key = mapKey(required(entry, entryPath, "key"), keyPath);
            String firstPath = firstKeyPaths.get(key);
            if (firstPath != null) {
                throw error(
                        PropertyGraphRepresentationErrorCode.DUPLICATE_MAP_KEY,
                        keyPath,
                        "duplicate typed map key; first declared at " + firstPath);
            }
            firstKeyPaths.put(key, keyPath);
            RecursiveValue entryValue = recursive(
                    required(entry, entryPath, "value"),
                    entryPath + ".value",
                    append(ancestry, PropertyPathSegment.key(String.valueOf(key.value()))));
            result.add(new RecursiveValue.MapEntry(key, entryValue));
        }
        return result;
    }

    private TypedScalarMapKey mapKey(Object source, String path) {
        Map<String, Object> key = mapping(source, path);
        rejectUnknown(key, path, fields("type", "value"));
        Object typeField = required(key, path, "type");
        String wireType = text(typeField, path + ".type");
        GraphType type;
        try {
            type = GraphType.fromWireName(wireType);
        } catch (IllegalArgumentException exception) {
            throw invalidMapKeyType(path + ".type");
        }
        if (!type.isMapKeyScalar()) {
            throw invalidMapKeyType(path + ".type");
        }
        return new TypedScalarMapKey(
                type, scalar(required(key, path, "value"), type, path + ".value"));
    }

    private List<PropertyWrite> propertyWrites(
            Object source, String path, ElementAncestry ancestry) {
        List<?> values = list(source, path);
        ArrayList<PropertyWrite> result = new ArrayList<PropertyWrite>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String propertyPath = indexed(path, index);
            Map<String, Object> property = mapping(values.get(index), propertyPath);
            try {
                result.add(propertyWrite(property, ancestry));
            } catch (PropertyGraphRepresentationException exception) {
                throw rebase(exception, propertyPath);
            }
        }
        return result;
    }

    private PropertyWrite propertyWrite(
            Map<?, ?> document, ElementAncestry containingElement) {
        Map<String, Object> property = mapping(document, "$");
        rejectUnknown(property, "$", TOP_FIELDS);
        Object propertyField = required(property, "$", "property");
        GraphType type = graphType(required(property, "$", "type"), "$.type");
        PropertyAddress address = propertyAddress(propertyField, "$.property");
        ElementProperty decoded = elementProperty(address, "$.property", containingElement);
        RecursiveValue decodedValue = value(
                required(property, "$", "value"),
                type,
                "$.value",
                false,
                decoded.ancestry);
        return new PropertyWrite(decoded.path, type, decodedValue);
    }

    private ElementProperty elementProperty(
            PropertyAddress address, String path, ElementAncestry containingElement) {
        if (address.segments().size() == 1
                && address.segments().get(0) instanceof String) {
            PropertyPathSegment local = PropertyPathSegment.property(
                    (String) address.segments().get(0));
            return new ElementProperty(
                    new PropertyPath(Collections.singletonList(local)),
                    append(containingElement, local));
        }
        if (containingElement != null
                && address.segments().size() == containingElement.address.size() + 1
                && address.segments().subList(0, containingElement.address.size())
                        .equals(containingElement.address)
                && address.segments().get(address.segments().size() - 1) instanceof String) {
            PropertyPathSegment local = PropertyPathSegment.property(
                    (String) address.segments().get(address.segments().size() - 1));
            ElementAncestry child = append(containingElement, local);
            return new ElementProperty(new PropertyPath(child.path), child);
        }
        throw error(
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                path,
                "element property path requires one property segment");
    }

    private PropertyAddress propertyAddress(Object source, String path) {
        try {
            return PropertyAddress.decode(source);
        } catch (IllegalArgumentException exception) {
            throw error(
                    PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                    path,
                    exception.getMessage());
        }
    }

    private ElementAncestry rootAncestry(PropertyAddress address) {
        if (address.segments().size() != 1
                || !(address.segments().get(0) instanceof String)) {
            return null;
        }
        return append(
                null, PropertyPathSegment.property((String) address.segments().get(0)));
    }

    private ElementAncestry append(
            ElementAncestry ancestry, PropertyPathSegment segment) {
        if (ancestry == null) {
            if (segment.kind() != PropertyPathSegment.Kind.PROPERTY) {
                return null;
            }
            return new ElementAncestry(Collections.singletonList(segment));
        }
        ArrayList<PropertyPathSegment> path =
                new ArrayList<PropertyPathSegment>(ancestry.path.size() + 1);
        path.addAll(ancestry.path);
        path.add(segment);
        return new ElementAncestry(path);
    }

    private PropertyGraphRepresentationException rebase(
            PropertyGraphRepresentationException exception, String basePath) {
        String nestedPath = exception.path().equals("$")
                ? basePath
                : basePath + exception.path().substring(1);
        return error(exception.errorCode(), nestedPath, exception.reason());
    }

    private RecursiveValue.OpaqueValue opaque(
            Map<String, Object> value, String propertyClass, String path) {
        String format = text(required(value, path, "format"), path + ".format");
        if (!RecursiveValue.OpaqueValue.FORMAT.equals(format)) {
            throw error(
                    PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                    path + ".format",
                    "unrecognized opaque format '" + format + "'");
        }
        String digest = nonEmptyText(required(value, path, "base_digest"), path + ".base_digest");
        if (!digest.matches("[0-9a-f]{64}")) {
            throw error(
                    PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                    path + ".base_digest",
                    "expected 64 lowercase hexadecimal characters");
        }
        String outerClass = nonEmptyText(
                required(value, path, "outer_property_class"), path + ".outer_property_class");
        if (!propertyClass.equals(outerClass)) {
            throw error(
                    PropertyGraphRepresentationErrorCode.CONFLICTING_FIELD,
                    path + ".outer_property_class",
                    "must match " + path + ".property_class");
        }
        return new RecursiveValue.OpaqueValue(
                format,
                digest,
                outerClass,
                nonEmptyText(
                        required(value, path, "runtime_fingerprint"),
                        path + ".runtime_fingerprint"),
                text(required(value, path, "payload"), path + ".payload"));
    }

    private Object scalar(Object value, GraphType type, String path) {
        switch (type) {
            case STRING:
                return instance(value, String.class, path);
            case BOOLEAN:
                return instance(value, Boolean.class, path);
            case INT:
                long intValue = integral(value, path);
                if (intValue < Integer.MIN_VALUE || intValue > Integer.MAX_VALUE) {
                    throw error(
                            PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                            path,
                            "integer is outside the Java int range");
                }
                return Integer.valueOf((int) intValue);
            case LONG:
                return Long.valueOf(integral(value, path));
            case FLOAT:
                if (!(value instanceof Number)) {
                    throw expected(path, "number", value);
                }
                try {
                    return ApplyPatchParser.decodeExactNumericLiteral(
                            String.valueOf(value), type.wireName());
                } catch (IllegalArgumentException exception) {
                    throw error(
                            PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                            path,
                            "float value must be finite and inside the Java float range");
                }
            case DOUBLE:
                if (!(value instanceof Number)) {
                    throw expected(path, "number", value);
                }
                try {
                    return ApplyPatchParser.decodeExactNumericLiteral(
                            String.valueOf(value), type.wireName());
                } catch (IllegalArgumentException exception) {
                    throw error(
                            PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                            path,
                            "double value must be finite and inside the Java double range");
                }
            default:
                throw new IllegalArgumentException("type is not a supported scalar: " + type.wireName());
        }
    }

    private long integral(Object value, String path) {
        if (value instanceof Number) {
            value = ApplyPatchParser.materializeUntypedNumber((Number) value);
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger) {
            try {
                return ((BigInteger) value).longValueExact();
            } catch (ArithmeticException exception) {
                throw error(
                        PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                        path,
                        "integer is outside the Java long range");
            }
        }
        throw expected(path, "integer number", value);
    }

    private GraphType graphType(Object source, String path) {
        String wireType = text(source, path);
        try {
            return GraphType.fromWireName(wireType);
        } catch (IllegalArgumentException exception) {
            throw error(
                    PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                    path,
                    "unrecognized type '" + wireType + "'");
        }
    }

    private GraphPresence presence(Object source, String path) {
        String wirePresence = text(source, path);
        try {
            return GraphPresence.fromWireName(wirePresence);
        } catch (IllegalArgumentException exception) {
            throw error(
                    PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                    path,
                    "unrecognized presence '" + wirePresence + "'");
        }
    }

    private void rejectUnknown(Map<String, Object> value, String path, Set<String> allowed) {
        for (String field : value.keySet()) {
            if (!allowed.contains(field)) {
                throw error(
                        PropertyGraphRepresentationErrorCode.UNKNOWN_FIELD,
                        path + "." + field,
                        "unknown field");
            }
        }
    }

    private Object required(Map<String, Object> value, String path, String field) {
        if (!value.containsKey(field)) {
            throw error(
                    PropertyGraphRepresentationErrorCode.MISSING_FIELD,
                    path + "." + field,
                    "missing required field");
        }
        return value.get(field);
    }

    private Map<String, Object> mapping(Object source, String path) {
        if (!(source instanceof Map)) {
            throw expected(path, "mapping", source);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) source).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw error(
                        PropertyGraphRepresentationErrorCode.INVALID_FIELD_TYPE,
                        path,
                        "mapping field names must be strings");
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private List<?> list(Object source, String path) {
        if (!(source instanceof List)) {
            throw expected(path, "list", source);
        }
        return (List<?>) source;
    }

    private String text(Object source, String path) {
        return instance(source, String.class, path);
    }

    private String nonEmptyText(Object source, String path) {
        String value = text(source, path);
        if (value.isEmpty()) {
            throw error(
                    PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                    path,
                    "value must not be empty");
        }
        return value;
    }

    private <T> T instance(Object source, Class<T> expected, String path) {
        if (!expected.isInstance(source)) {
            throw expected(path, expected.getSimpleName(), source);
        }
        return expected.cast(source);
    }

    private PropertyGraphRepresentationException expected(
            String path, String expected, Object actual) {
        return error(
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_TYPE,
                path,
                "expected " + expected + " but was " + typeName(actual));
    }

    private PropertyGraphRepresentationException invalidMapKeyType(String path) {
        return error(
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                path,
                "map key type must be one of string, boolean, int, long, float, double");
    }

    private static Set<String> familyFields(GraphType type) {
        switch (type) {
            case STRING:
            case BOOLEAN:
            case INT:
            case LONG:
            case FLOAT:
            case DOUBLE:
            case NULL:
                return SCALAR_FIELDS;
            case COLLECTION:
                return COLLECTION_FIELDS;
            case MAP:
                return MAP_FIELDS;
            case ELEMENT:
                return ELEMENT_FIELDS;
            case OPAQUE:
                return OPAQUE_FIELDS;
            default:
                throw new IllegalStateException("unhandled graph type: " + type);
        }
    }

    private static Set<String> fields(String... names) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(names)));
    }

    private static String indexed(String path, int index) {
        return path + "[" + index + "]";
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static PropertyGraphRepresentationException error(
            PropertyGraphRepresentationErrorCode code, String path, String reason) {
        return new PropertyGraphRepresentationException(code, path, reason);
    }

    private static final class ElementAncestry {
        private final List<PropertyPathSegment> path;
        private final List<Object> address;

        private ElementAncestry(List<PropertyPathSegment> path) {
            this.path = Collections.unmodifiableList(
                    new ArrayList<PropertyPathSegment>(path));
            this.address = PropertyAddress.fromPath(new PropertyPath(path)).segments();
        }
    }

    private static final class ElementProperty {
        private final PropertyPath path;
        private final ElementAncestry ancestry;

        private ElementProperty(PropertyPath path, ElementAncestry ancestry) {
            this.path = path;
            this.ancestry = ancestry;
        }
    }
}
