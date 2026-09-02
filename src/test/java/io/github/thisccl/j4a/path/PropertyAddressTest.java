package io.github.thisccl.j4a.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.jmx.property.GraphCapability;
import io.github.thisccl.j4a.jmx.property.GraphNode;
import io.github.thisccl.j4a.jmx.property.GraphOwnership;
import io.github.thisccl.j4a.jmx.property.GraphSnapshot;
import io.github.thisccl.j4a.jmx.property.GraphType;
import io.github.thisccl.j4a.jmx.property.RecursiveValue;
import io.github.thisccl.j4a.jmx.property.RepresentationSource;
import io.github.thisccl.j4a.jmx.property.RuntimeClassConstraint;
import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import io.github.thisccl.j4a.jmx.property.RuntimeFingerprint;
import io.github.thisccl.j4a.jmx.property.StorageKeyStatus;
import io.github.thisccl.j4a.jmx.property.WritableState;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class PropertyAddressTest {
    private static final String STRING_PROPERTY =
            "org.apache.jmeter.testelement.property.StringProperty";
    private static final String COLLECTION_PROPERTY =
            "org.apache.jmeter.testelement.property.CollectionProperty";
    private static final String ELEMENT_PROPERTY =
            "org.apache.jmeter.testelement.property.TestElementProperty";

    @Test
    void scalarArrayRoundTripsPunctuationEmptyStringsAndNumericKinds() {
        PropertyAddress address = PropertyAddress.decode(Arrays.<Object>asList(
                "literal.dot[part]/'quoted'\\tail",
                "",
                "0",
                Integer.valueOf(0),
                Integer.valueOf(Integer.MAX_VALUE)));

        assertThat(address.segments()).containsExactly(
                "literal.dot[part]/'quoted'\\tail",
                "",
                "0",
                Integer.valueOf(0),
                Integer.valueOf(Integer.MAX_VALUE));
        assertThatThrownBy(() -> address.segments().add("later"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void exactLoadedGraphResolvesHeaderCollectionLeafWithoutParsingPunctuation() {
        GraphNode headers = node(
                path(PropertyPathSegment.property("HeaderManager.headers")),
                GraphType.COLLECTION,
                RecursiveValue.absent(GraphType.COLLECTION, COLLECTION_PROPERTY));
        GraphNode row = node(
                path(
                        PropertyPathSegment.property("HeaderManager.headers"),
                        PropertyPathSegment.index(0)),
                GraphType.ELEMENT,
                RecursiveValue.absent(GraphType.ELEMENT, ELEMENT_PROPERTY));
        GraphNode name = node(
                path(
                        PropertyPathSegment.property("HeaderManager.headers"),
                        PropertyPathSegment.index(0),
                        PropertyPathSegment.property("Header.name")),
                GraphType.STRING,
                RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, "X-Trace"));
        GraphSnapshot snapshot = new GraphSnapshot(
                runtimeContext(), Arrays.asList(headers, row, name));

        GraphNode resolved = snapshot.resolve(PropertyAddress.decode(Arrays.<Object>asList(
                "HeaderManager.headers", Integer.valueOf(0), "Header.name")));

        assertThat(resolved).isSameAs(name);
        assertThat(resolved.path().segments()).containsExactly(
                PropertyPathSegment.property("HeaderManager.headers"),
                PropertyPathSegment.index(0),
                PropertyPathSegment.property("Header.name"));
    }

    @Test
    void exactLoadedGraphResolvesLeafWhenIntermediateCollectionRowIsNotProjected() {
        GraphNode collection = node(
                path(
                        PropertyPathSegment.property("qa.element"),
                        PropertyPathSegment.property("Arguments.arguments")),
                GraphType.COLLECTION,
                RecursiveValue.absent(GraphType.COLLECTION, COLLECTION_PROPERTY));
        GraphNode value = node(
                path(
                        PropertyPathSegment.property("qa.element"),
                        PropertyPathSegment.property("Arguments.arguments"),
                        PropertyPathSegment.index(0),
                        PropertyPathSegment.property("Argument.value")),
                GraphType.STRING,
                RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, "nested-value"));
        GraphSnapshot snapshot = new GraphSnapshot(runtimeContext(), Arrays.asList(collection, value));

        assertThat(snapshot.resolve(PropertyAddress.decode(Arrays.<Object>asList(
                "qa.element", "Arguments.arguments", Integer.valueOf(0), "Argument.value"))))
                .isSameAs(value);
    }

    @Test
    void rejectsStringPathsEmptyArraysAndEveryNonScalarOrOutOfRangeMember() {
        List<String> stringPaths = Arrays.asList(
                "HeaderManager\\.headers[0].Header\\.name",
                "$.HeaderManager.headers[0].Header.name",
                "/HeaderManager.headers/0/Header.name",
                "HeaderManager.headers");
        for (String stringPath : stringPaths) {
            assertThatThrownBy(() -> PropertyAddress.decode(stringPath))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scalar array");
        }

        assertThatThrownBy(() -> PropertyAddress.decode(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> PropertyAddress.decode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scalar array");

        LinkedHashMap<String, Object> objectSegment = new LinkedHashMap<String, Object>();
        objectSegment.put("property", "HeaderManager.headers");
        List<Object> malformedMembers = Arrays.<Object>asList(
                null,
                Boolean.TRUE,
                objectSegment,
                Double.valueOf(0.0D),
                Integer.valueOf(-1),
                Long.valueOf(2147483648L),
                new BigInteger("2147483648"));
        for (Object malformedMember : malformedMembers) {
            assertThatThrownBy(() -> PropertyAddress.decode(
                    Collections.singletonList(malformedMember)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("segment[0]");
        }
    }

    @Test
    void currentNodeKindDistinguishesNumericStringMapKeysFromCollectionIndexes() {
        GraphNode map = node(
                path(PropertyPathSegment.property("lookup")),
                GraphType.MAP,
                RecursiveValue.absent(GraphType.MAP, "org.apache.jmeter.testelement.property.MapProperty"));
        GraphNode numericKey = node(
                path(PropertyPathSegment.property("lookup"), PropertyPathSegment.key("0")),
                GraphType.STRING,
                RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, "map-value"));
        GraphNode emptyKey = node(
                path(PropertyPathSegment.property("lookup"), PropertyPathSegment.key("")),
                GraphType.STRING,
                RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, "empty-key"));
        GraphNode collection = node(
                path(PropertyPathSegment.property("items")),
                GraphType.COLLECTION,
                RecursiveValue.absent(GraphType.COLLECTION, COLLECTION_PROPERTY));
        GraphNode first = node(
                path(PropertyPathSegment.property("items"), PropertyPathSegment.index(0)),
                GraphType.STRING,
                RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, "first"));
        GraphNode emptyProperty = node(
                path(PropertyPathSegment.property("")),
                GraphType.STRING,
                RecursiveValue.scalar(GraphType.STRING, STRING_PROPERTY, "empty-property"));
        GraphSnapshot snapshot = new GraphSnapshot(
                runtimeContext(), Arrays.asList(map, numericKey, emptyKey, collection, first, emptyProperty));

        assertThat(snapshot.resolve(PropertyAddress.decode(Arrays.<Object>asList("lookup", "0"))))
                .isSameAs(numericKey);
        assertThat(snapshot.resolve(PropertyAddress.decode(Arrays.<Object>asList("lookup", ""))))
                .isSameAs(emptyKey);
        assertThat(snapshot.resolve(PropertyAddress.decode(Arrays.<Object>asList("items", 0))))
                .isSameAs(first);
        assertThat(snapshot.resolve(PropertyAddress.decode(Collections.<Object>singletonList(""))))
                .isSameAs(emptyProperty);
        assertWrongType(snapshot, Arrays.<Object>asList("lookup", 0));
        assertWrongType(snapshot, Arrays.<Object>asList("items", "0"));
    }

    private static PropertyPath path(PropertyPathSegment... segments) {
        return new PropertyPath(Arrays.asList(segments));
    }

    private static GraphNode node(PropertyPath path, GraphType type, RecursiveValue value) {
        GraphCapability capability = new GraphCapability(
                StorageKeyStatus.NON_KEY,
                WritableState.WRITABLE,
                null,
                GraphOwnership.USER,
                RepresentationSource.RUNTIME,
                RuntimeClassConstraint.propertyClass(value.propertyClass()));
        return new GraphNode(path, type, value, capability);
    }

    private static RuntimeContext runtimeContext() {
        return new RuntimeContext(
                "property-address-test",
                new RuntimeFingerprint(
                        "/opt/jmeter-fixture/apache-jmeter-5.6.3",
                        "5.6.3",
                        Collections.singletonMap("lib/ApacheJMeter_core.jar", "test-sha256")));
    }

    private static void assertWrongType(GraphSnapshot snapshot, List<Object> segments) {
        assertThatThrownBy(() -> snapshot.resolve(PropertyAddress.decode(segments)))
                .isInstanceOf(PropertyPathResolutionException.class)
                .extracting("errorCode")
                .isEqualTo(PropertyPathErrorCode.WRONG_TYPE);
    }
}
