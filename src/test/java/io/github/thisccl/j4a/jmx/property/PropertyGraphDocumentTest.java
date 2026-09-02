package io.github.thisccl.j4a.jmx.property;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyAddress;
import io.github.thisccl.j4a.path.PropertyPathErrorCode;
import io.github.thisccl.j4a.path.PropertyPathResolutionException;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PropertyGraphDocumentTest {
    private static final String COLLECTION_PROPERTY =
            "org.apache.jmeter.testelement.property.CollectionProperty";
    private static final String STRING_PROPERTY =
            "org.apache.jmeter.testelement.property.StringProperty";
    private static final String BOOLEAN_PROPERTY =
            "org.apache.jmeter.testelement.property.BooleanProperty";
    private static final String INTEGER_PROPERTY =
            "org.apache.jmeter.testelement.property.IntegerProperty";
    private static final String LONG_PROPERTY =
            "org.apache.jmeter.testelement.property.LongProperty";
    private static final String FLOAT_PROPERTY =
            "org.apache.jmeter.testelement.property.FloatProperty";
    private static final String DOUBLE_PROPERTY =
            "org.apache.jmeter.testelement.property.DoubleProperty";
    private static final String NULL_PROPERTY =
            "org.apache.jmeter.testelement.property.NullProperty";
    private static final String MAP_PROPERTY =
            "org.apache.jmeter.testelement.property.MapProperty";
    private static final String ELEMENT_PROPERTY =
            "org.apache.jmeter.testelement.property.TestElementProperty";
    private static final String OPAQUE_PROPERTY = "example.CustomProperty";

    private final PropertyGraphDocumentMapper mapper = new PropertyGraphDocumentMapper();

    @Test
    void typedGraphSnapshotResolutionReturnsTheExactLoadedNode() {
        PropertyPath loadedPath = new PropertyPath(Arrays.asList(
                PropertyPathSegment.property("HeaderManager.headers"),
                PropertyPathSegment.index(0),
                PropertyPathSegment.property("Header.name")));
        GraphNode loaded = scalarNode(loadedPath, "X-Trace");
        GraphSnapshot snapshot = new GraphSnapshot(runtimeContext(), Collections.singletonList(loaded));

        assertThat(snapshot.resolve(loadedPath)).isSameAs(loaded);
        assertThatThrownBy(() -> snapshot.resolve(new PropertyPath(Arrays.asList(
                PropertyPathSegment.property("HeaderManager.headers"),
                PropertyPathSegment.index(1)))))
                .isInstanceOf(PropertyPathResolutionException.class)
                .extracting("errorCode")
                .isEqualTo(PropertyPathErrorCode.MISSING_PROPERTY);
    }

    @Test
    void readsAndWritesScalarArrayAddressesLosslessly() {
        Map<String, Object> document = map(
                "property", address("", "can't"),
                "type", "string",
                "value", presentScalar(STRING_PROPERTY, "value"));

        Object unresolvedSegment = mapper.fromDocument(document)
                .property().segments().get(1);

        assertThat(unresolvedSegment).isEqualTo("can't");
        assertThat(unresolvedSegment).isNotInstanceOf(PropertyPathSegment.class);
        assertThat(mapper.normalize(document)).isEqualTo(document);
    }

    @Test
    void mapKeyAddressRemainsUnresolvedUntilTheExactSnapshotDeterminesItsKind() {
        PropertyPath mapPath = new PropertyPath(Collections.singletonList(
                PropertyPathSegment.property("lookup")));
        PropertyPath keyPath = new PropertyPath(Arrays.asList(
                PropertyPathSegment.property("lookup"),
                PropertyPathSegment.key("literal.key")));
        GraphNode map = new GraphNode(
                mapPath,
                GraphType.MAP,
                RecursiveValue.map(MAP_PROPERTY, Collections.<RecursiveValue.MapEntry>emptyList()),
                writableCapability(MAP_PROPERTY));
        GraphNode key = scalarNode(keyPath, "value");
        GraphSnapshot snapshot = new GraphSnapshot(runtimeContext(), Arrays.asList(map, key));
        Map<String, Object> document = record(
                address("lookup", "literal.key"),
                "string",
                presentScalar(STRING_PROPERTY, "replacement"));

        Object unresolvedSegment = mapper.fromDocument(document)
                .property().segments().get(1);
        GraphNode resolved = snapshot.resolve(PropertyAddress.decode(document.get("property")));

        assertThat(unresolvedSegment).isEqualTo("literal.key");
        assertThat(unresolvedSegment).isNotInstanceOf(PropertyPathSegment.class);
        assertThat(resolved).isSameAs(key);
        assertThat(resolved.path().segments().get(1).kind())
                .isEqualTo(PropertyPathSegment.Kind.KEY);
    }

    @Test
    void rejectsDeletedStringAndTypedObjectAddressForms() {
        Map<String, Object> stringAddress = map(
                "property", "HeaderManager\\.headers[0].Header\\.name",
                "type", "string",
                "value", presentScalar(STRING_PROPERTY, "value"));
        assertRepresentationError(
                stringAddress,
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                "$.property",
                "$.property: property address must be a non-empty scalar array");

        Map<String, Object> typedObjectAddress = map(
                "property", list(map("property", "HeaderManager.headers")),
                "type", "string",
                "value", presentScalar(STRING_PROPERTY, "value"));
        assertRepresentationError(
                typedObjectAddress,
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                "$.property",
                "$.property: property address segment[0] must be a string or integer");
    }

    @Test
    void writerProjectsTypedPathsToOneScalarArray() {
        PropertyWrite write = new PropertyWrite(
                new PropertyPath(Arrays.asList(
                        PropertyPathSegment.property("HeaderManager.headers"),
                        PropertyPathSegment.index(0),
                        PropertyPathSegment.key(""))),
                GraphType.STRING,
                RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, "value"));

        Map<String, Object> rendered = mapper.toDocument(write);

        assertThat(rendered.get("property"))
                .isEqualTo(Arrays.<Object>asList("HeaderManager.headers", Integer.valueOf(0), ""));
    }

    @Test
    void normalizesScalarAddressResponseAssertionCollectionExactly() {
        Map<String, Object> document = record(
                address("Asserion.test_strings"),
                "collection",
                map(
                        "presence", "present",
                        "property_class", COLLECTION_PROPERTY,
                        "items", list(
                                recursiveScalar("string", STRING_PROPERTY, "login failed"),
                                recursiveScalar("string", STRING_PROPERTY, "access denied"),
                                recursiveScalar("string", STRING_PROPERTY, "login failed"))));

        Map<String, Object> normalized = mapper.normalize(document);

        assertThat(normalized).isEqualTo(document);
        assertThat(new ArrayList<String>(normalized.keySet()))
                .containsExactly("property", "type", "value");
        assertThat(recordValues(items(normalized), "value"))
                .containsExactly("login failed", "access denied", "login failed");
    }

    @Test
    void normalizesEveryScalarAndPresentNullWithExactJavaScalarTypes() {
        List<Map<String, Object>> documents = Arrays.asList(
                record(address("text"), "string", presentScalar(STRING_PROPERTY, "hello")),
                record(address("enabled"), "boolean", presentScalar(BOOLEAN_PROPERTY, Boolean.TRUE)),
                record(address("count"), "int", presentScalar(INTEGER_PROPERTY, Integer.valueOf(7))),
                record(address("total"), "long", presentScalar(LONG_PROPERTY, Long.valueOf(8L))),
                record(address("ratio"), "float", presentScalar(FLOAT_PROPERTY, Float.valueOf(1.25F))),
                record(address("score"), "double", presentScalar(DOUBLE_PROPERTY, Double.valueOf(2.5D))),
                record(address("nothing"), "null", presentScalar(NULL_PROPERTY, null)));

        for (Map<String, Object> document : documents) {
            assertThat(mapper.normalize(document)).isEqualTo(document);
        }
    }

    @Test
    void rejectsWrongNativeScalarKindsAndNumericOverflow() {
        assertThatThrownBy(() -> mapper.normalize(record(
                address("text"), "string", presentScalar(STRING_PROPERTY, Integer.valueOf(100)))))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessageContaining("expected String but was Integer");
        assertThatThrownBy(() -> mapper.normalize(record(
                address("enabled"), "boolean", presentScalar(BOOLEAN_PROPERTY, "true"))))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessageContaining("expected Boolean but was String");
        assertThatThrownBy(() -> mapper.normalize(record(
                address("ratio"), "float", presentScalar(FLOAT_PROPERTY, Double.valueOf(Double.MAX_VALUE)))))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessageContaining("float range");
        assertThatThrownBy(() -> mapper.normalize(record(
                address("score"), "double", presentScalar(DOUBLE_PROPERTY, Double.POSITIVE_INFINITY))))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessageContaining("finite");
    }

    @Test
    void keepsAbsentAndPresentNullStructurallyDistinct() {
        Map<String, Object> absent = record(
                address("optional"),
                "string",
                map("presence", "absent", "property_class", STRING_PROPERTY));
        Map<String, Object> presentNull = record(
                address("optional"),
                "null",
                presentScalar(NULL_PROPERTY, null));

        assertThat(mapper.normalize(absent)).isEqualTo(absent);
        assertThat(mapper.normalize(presentNull)).isEqualTo(presentNull);
        assertThat(mapper.fromDocument(absent).value().presence()).isEqualTo(GraphPresence.ABSENT);
        assertThat(mapper.fromDocument(presentNull).value().presence()).isEqualTo(GraphPresence.PRESENT);
    }

    @Test
    void preservesTypedMapKeyAndEntryOrder() {
        Map<String, Object> document = record(
                address("lookup"),
                "map",
                map(
                        "presence", "present",
                        "property_class", MAP_PROPERTY,
                        "entries", list(
                                map(
                                        "key", map("type", "string", "value", "first"),
                                        "value", recursiveScalar("long", LONG_PROPERTY, Long.valueOf(11L))),
                                map(
                                        "key", map("type", "int", "value", Integer.valueOf(2)),
                                        "value", recursiveScalar("boolean", BOOLEAN_PROPERTY, Boolean.FALSE)))));

        Map<String, Object> normalized = mapper.normalize(document);

        assertThat(normalized).isEqualTo(document);
        assertThat(requireMap(requireMap(entries(normalized).get(0)).get("key")).get("value"))
                .isEqualTo("first");
        assertThat(requireMap(requireMap(entries(normalized).get(1)).get("key")).get("value"))
                .isEqualTo(Integer.valueOf(2));
    }

    @Test
    void normalizesNestedElementPropertiesRecursively() {
        Map<String, Object> document = record(
                address("config"),
                "element",
                map(
                        "presence", "present",
                        "property_class", ELEMENT_PROPERTY,
                        "element_class", "org.apache.jmeter.config.Arguments",
                        "properties", list(
                                record(address("name"), "string", presentScalar(STRING_PROPERTY, "alpha")),
                                record(
                                        address("children"),
                                        "collection",
                                        map(
                                                "presence", "present",
                                                "property_class", COLLECTION_PROPERTY,
                                                "items", list())))));

        assertThat(mapper.normalize(document)).isEqualTo(document);
    }

    @Test
    void rejectsNestedElementPropertyAddressesAtTheExactPropertyBoundary() {
        Map<String, Object> nestedAddress = record(
                address("config"),
                "element",
                map(
                        "presence", "present",
                        "property_class", ELEMENT_PROPERTY,
                        "element_class", "org.apache.jmeter.config.Arguments",
                        "properties", list(record(
                                address("qa.options", "literal.key"),
                                "string",
                                presentScalar(STRING_PROPERTY, "value")))));
        Map<String, Object> integerAddress = record(
                address("config"),
                "element",
                map(
                        "presence", "present",
                        "property_class", ELEMENT_PROPERTY,
                        "element_class", "org.apache.jmeter.config.Arguments",
                        "properties", list(record(
                                address("qa.options", Integer.valueOf(0)),
                                "string",
                                presentScalar(STRING_PROPERTY, "value")))));

        assertRepresentationError(
                nestedAddress,
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                "$.value.properties[0].property",
                "$.value.properties[0].property: element property path requires one property segment");
        assertRepresentationError(
                integerAddress,
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                "$.value.properties[0].property",
                "$.value.properties[0].property: element property path requires one property segment");
    }

    @Test
    void preservesMapEntriesUnderLegalDirectElementPropertyAddress() {
        Map<String, Object> mapValue = map(
                "presence", "present",
                "property_class", MAP_PROPERTY,
                "entries", list(map(
                        "key", map(
                                "type", "string",
                                "value", "literal.key"),
                        "value", recursiveScalar(
                                "string", STRING_PROPERTY, "value"))));
        Map<String, Object> document = record(
                address("config"),
                "element",
                map(
                        "presence", "present",
                        "property_class", ELEMENT_PROPERTY,
                        "element_class", "org.apache.jmeter.config.Arguments",
                        "properties", list(record(
                                address("qa.options"),
                                "map",
                                mapValue))));

        PropertyGraphDocument decoded = mapper.fromDocument(document);

        assertThat(mapper.toDocument(decoded)).isEqualTo(document);
        assertThat(mapper.normalize(document)).isEqualTo(document);
        assertThat(decoded.value().properties()).hasSize(1);
        assertThat(decoded.value().properties().get(0).property().segments().get(0).name())
                .isEqualTo("qa.options");
        assertThat(decoded.value().properties().get(0).value().entries().get(0).key().value())
                .isEqualTo("literal.key");
    }

    @Test
    void keepsOpaqueBindingFieldsOnlyBelowTopLevelValue() {
        String digest = repeat('a', 64);
        Map<String, Object> document = record(
                address("custom"),
                "opaque",
                map(
                        "presence", "present",
                        "property_class", OPAQUE_PROPERTY,
                        "format", "jmeter-save-element-xml-v1",
                        "base_digest", digest,
                        "outer_property_class", OPAQUE_PROPERTY,
                        "runtime_fingerprint", "jmeter-5.6.3:abc123",
                        "payload", "<custom>changed</custom>"));

        Map<String, Object> normalized = mapper.normalize(document);

        assertThat(normalized).isEqualTo(document);
        assertThat(normalized).containsOnlyKeys("property", "type", "value");
        assertThat(stringKeys(requireMap(normalized.get("value"))))
                .contains("format", "base_digest", "outer_property_class", "runtime_fingerprint", "payload");
    }

    @Test
    void writesAnIndependentExactDocumentFromTheImmutableDto() {
        RecursiveValue value = RecursiveValue.collection(
                COLLECTION_PROPERTY,
                Arrays.asList(
                        RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, "one"),
                        RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, "one")));
        PropertyWrite write = new PropertyWrite(
                new PropertyPath(Collections.singletonList(
                        PropertyPathSegment.property("Asserion.test_strings"))),
                GraphType.COLLECTION,
                value);
        Map<String, Object> expected = record(
                Arrays.<Object>asList("Asserion.test_strings"),
                "collection",
                map(
                        "presence", "present",
                        "property_class", COLLECTION_PROPERTY,
                        "items", list(
                                recursiveScalar("string", STRING_PROPERTY, "one"),
                                recursiveScalar("string", STRING_PROPERTY, "one"))));

        assertThat(mapper.toDocument(write)).isEqualTo(expected);
    }

    @Test
    void rejectsForbiddenTopLevelJavaClassAtItsExactPathWithoutChangingInput() {
        Map<String, Object> document = record(
                address("name"), "string", presentScalar(STRING_PROPERTY, "before"));
        document.put("java_class", "java.lang.Runtime");
        Map<String, Object> before = deepMutableCopy(document);

        assertRepresentationError(
                document,
                PropertyGraphRepresentationErrorCode.UNKNOWN_FIELD,
                "$.java_class",
                "$.java_class: unknown field");
        assertThat(document).isEqualTo(before);
    }

    @Test
    void rejectsUnknownNestedFieldAtItsExactPath() {
        Map<String, Object> item = recursiveScalar("string", STRING_PROPERTY, "value");
        item.put("mystery", Boolean.TRUE);
        Map<String, Object> document = record(
                address("items"),
                "collection",
                map(
                        "presence", "present",
                        "property_class", COLLECTION_PROPERTY,
                        "items", list(item)));

        assertRepresentationError(
                document,
                PropertyGraphRepresentationErrorCode.UNKNOWN_FIELD,
                "$.value.items[0].mystery",
                "$.value.items[0].mystery: unknown field");
    }

    @Test
    void rejectsPayloadOnAbsentAtItsExactPath() {
        Map<String, Object> document = record(
                address("items"),
                "collection",
                map(
                        "presence", "absent",
                        "property_class", COLLECTION_PROPERTY,
                        "items", list()));

        assertRepresentationError(
                document,
                PropertyGraphRepresentationErrorCode.CONFLICTING_FIELD,
                "$.value.items",
                "$.value.items: payload is not allowed when presence is 'absent'");
    }

    @Test
    void rejectsDuplicateTypedMapKeyAtTheSecondExactPath() {
        Map<String, Object> key = map("type", "string", "value", "duplicate");
        Map<String, Object> document = record(
                address("lookup"),
                "map",
                map(
                        "presence", "present",
                        "property_class", MAP_PROPERTY,
                        "entries", list(
                                map("key", key, "value", recursiveScalar("string", STRING_PROPERTY, "a")),
                                map(
                                        "key", map("type", "string", "value", "duplicate"),
                                        "value", recursiveScalar("string", STRING_PROPERTY, "b")))));

        assertRepresentationError(
                document,
                PropertyGraphRepresentationErrorCode.DUPLICATE_MAP_KEY,
                "$.value.entries[1].key",
                "$.value.entries[1].key: duplicate typed map key; first declared at $.value.entries[0].key");
    }

    @Test
    void rejectsMissingWrongFamilyWrongTypeAndUnrecognizedTypeFields() {
        Map<String, Object> missingClass = record(
                address("name"), "string", map("presence", "present", "value", "x"));
        assertRepresentationError(
                missingClass,
                PropertyGraphRepresentationErrorCode.MISSING_FIELD,
                "$.value.property_class",
                "$.value.property_class: missing required field");

        Map<String, Object> wrongFamily = record(
                address("items"),
                "collection",
                map(
                        "presence", "present",
                        "property_class", COLLECTION_PROPERTY,
                        "entries", list()));
        assertRepresentationError(
                wrongFamily,
                PropertyGraphRepresentationErrorCode.CONFLICTING_FIELD,
                "$.value.entries",
                "$.value.entries: field is not allowed for type 'collection'");

        Map<String, Object> wrongType = record(
                address("items"),
                "collection",
                map(
                        "presence", "present",
                        "property_class", COLLECTION_PROPERTY,
                        "items", "not-a-list"));
        assertRepresentationError(
                wrongType,
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_TYPE,
                "$.value.items",
                "$.value.items: expected list but was String");

        Map<String, Object> unknownType = record(
                address("items"),
                "vector",
                map("presence", "absent", "property_class", COLLECTION_PROPERTY));
        assertRepresentationError(
                unknownType,
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                "$.type",
                "$.type: unrecognized type 'vector'");
    }

    @Test
    void rejectsNonScalarAndInvalidTypedMapKeyShapes() {
        Map<String, Object> nonScalar = map(
                "type", "collection",
                "value", list());
        Map<String, Object> document = oneEntryMapDocument(nonScalar);
        assertRepresentationError(
                document,
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                "$.value.entries[0].key.type",
                "$.value.entries[0].key.type: map key type must be one of string, boolean, int, long, float, double");

        Map<String, Object> invalidShape = map(
                "type", "string",
                "value", "key",
                "java_class", "java.lang.String");
        assertRepresentationError(
                oneEntryMapDocument(invalidShape),
                PropertyGraphRepresentationErrorCode.UNKNOWN_FIELD,
                "$.value.entries[0].key.java_class",
                "$.value.entries[0].key.java_class: unknown field");
    }

    @Test
    void rejectsConflictingOpaqueClassAndInvalidNullPayload() {
        Map<String, Object> opaque = record(
                address("custom"),
                "opaque",
                map(
                        "presence", "present",
                        "property_class", OPAQUE_PROPERTY,
                        "format", "jmeter-save-element-xml-v1",
                        "base_digest", repeat('b', 64),
                        "outer_property_class", "other.CustomProperty",
                        "runtime_fingerprint", "runtime",
                        "payload", "<x/>"));
        assertRepresentationError(
                opaque,
                PropertyGraphRepresentationErrorCode.CONFLICTING_FIELD,
                "$.value.outer_property_class",
                "$.value.outer_property_class: must match $.value.property_class");

        Map<String, Object> invalidNull = record(
                address("nothing"), "null", presentScalar(NULL_PROPERTY, "not-null"));
        assertRepresentationError(
                invalidNull,
                PropertyGraphRepresentationErrorCode.INVALID_FIELD_VALUE,
                "$.value.value",
                "$.value.value: type 'null' requires a null value");
    }

    @Test
    void returnsDeeplyUnmodifiablePropertyDocuments() {
        Map<String, Object> normalized = mapper.normalize(
                record(address("name"), "string", presentScalar(STRING_PROPERTY, "value")));

        assertThatThrownBy(() -> normalized.put("extra", Boolean.TRUE))
                .isInstanceOf(UnsupportedOperationException.class);
        Map<?, ?> normalizedValue = requireMap(normalized.get("value"));
        assertThatThrownBy(normalizedValue::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private void assertRepresentationError(
            Map<String, Object> document,
            PropertyGraphRepresentationErrorCode errorCode,
            String path,
            String message) {
        assertThatThrownBy(() -> mapper.fromDocument(document))
                .isInstanceOf(PropertyGraphRepresentationException.class)
                .hasMessage(message)
                .satisfies(error -> {
                    PropertyGraphRepresentationException representationError =
                            (PropertyGraphRepresentationException) error;
                    assertThat(representationError.errorCode()).isEqualTo(errorCode);
                    assertThat(representationError.path()).isEqualTo(path);
                });
    }

    private static Map<String, Object> oneEntryMapDocument(Map<String, Object> key) {
        return record(
                address("lookup"),
                "map",
                map(
                        "presence", "present",
                        "property_class", MAP_PROPERTY,
                        "entries", list(
                                map(
                                        "key", key,
                                        "value", recursiveScalar("string", STRING_PROPERTY, "value")))));
    }

    private static Map<String, Object> record(List<?> property, String type, Object value) {
        return map("property", property, "type", type, "value", value);
    }

    private static List<Object> address(Object... segments) {
        return new ArrayList<Object>(Arrays.asList(segments));
    }

    private static Map<String, Object> presentScalar(String propertyClass, Object value) {
        return map("presence", "present", "property_class", propertyClass, "value", value);
    }

    private static Map<String, Object> recursiveScalar(String type, String propertyClass, Object value) {
        return map(
                "type", type,
                "presence", "present",
                "property_class", propertyClass,
                "value", value);
    }

    private static LinkedHashMap<String, Object> map(Object... keysAndValues) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            result.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return result;
    }

    private static List<Object> list(Object... values) {
        return new ArrayList<Object>(Arrays.asList(values));
    }

    private static List<?> items(Map<String, Object> document) {
        return requireList(requireMap(document.get("value")).get("items"));
    }

    private static List<?> entries(Map<String, Object> document) {
        return requireList(requireMap(document.get("value")).get("entries"));
    }

    private static Map<?, ?> requireMap(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new AssertionError("expected map but was " + typeName(value));
        }
        return (Map<?, ?>) value;
    }

    private static List<?> requireList(Object value) {
        if (!(value instanceof List<?>)) {
            throw new AssertionError("expected list but was " + typeName(value));
        }
        return (List<?>) value;
    }

    private static List<Object> recordValues(List<?> records, String field) {
        List<Object> values = new ArrayList<Object>();
        for (Object record : records) {
            values.add(requireMap(record).get(field));
        }
        return values;
    }

    private static List<String> stringKeys(Map<?, ?> map) {
        List<String> keys = new ArrayList<String>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new AssertionError("expected string map key but was " + typeName(key));
            }
            keys.add((String) key);
        }
        return keys;
    }

    private static Map<String, Object> deepMutableCopy(Map<String, Object> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepMutableValue(entry.getValue()));
        }
        return copy;
    }

    private static GraphNode scalarNode(PropertyPath path, String value) {
        GraphCapability capability = writableCapability(STRING_PROPERTY);
        return new GraphNode(
                path,
                GraphType.STRING,
                RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, value),
                capability);
    }

    private static GraphCapability writableCapability(String propertyClass) {
        return new GraphCapability(
                StorageKeyStatus.NON_KEY,
                WritableState.WRITABLE,
                null,
                GraphOwnership.USER,
                RepresentationSource.RUNTIME,
                RuntimeClassConstraint.propertyClass(propertyClass));
    }

    private static RuntimeContext runtimeContext() {
        return new RuntimeContext(
                "property-address-test",
                new RuntimeFingerprint(
                        "/opt/jmeter-fixture/apache-jmeter-5.6.3",
                        "5.6.3",
                        Collections.singletonMap("lib/ApacheJMeter_core.jar", "test-sha256")));
    }

    private static Object deepMutableValue(Object value) {
        if (value instanceof Map<?, ?>) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String)) {
                    throw new AssertionError("expected string map key but was " + typeName(key));
                }
                copy.put((String) key, deepMutableValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?>) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                copy.add(deepMutableValue(item));
            }
            return copy;
        }
        return value;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static String repeat(char value, int count) {
        char[] repeated = new char[count];
        Arrays.fill(repeated, value);
        return new String(repeated);
    }
}
