package io.github.thisccl.j4a.path;


import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PropertyPathResolverTest {
    private final PropertyPathResolver resolver = new PropertyPathResolver();

    @Test
    void updatesScalarNestedCollectionKeyAndPunctuationBearingTypedPaths() {
        TestNode root = componentTree();

        resolver.set(root, properties("HTTPSampler", "domain"), "example.org", PropertyValueType.STRING);
        resolver.set(root, properties("Arguments", "arguments", 0, "value"), "42", PropertyValueType.INT);
        resolver.set(root, properties("HeaderManager", "headers", 0, "name"), "X-Trace", PropertyValueType.STRING);
        resolver.set(root, path(property("MapLike"), key("key.with.dot")), "true", PropertyValueType.BOOLEAN);
        resolver.set(root, properties("Escaped", "literal.dot", "bracket[name]\\tail"), "kept", PropertyValueType.RAW);

        assertThat(root.child("HTTPSampler").get().child("domain").get().value())
                .isEqualTo(PropertyValue.string("example.org"));
        assertThat(root.child("Arguments").get()
                        .child("arguments").get()
                        .element(0).get()
                        .child("value").get()
                        .value())
                .isEqualTo(PropertyValue.integer(42));
        assertThat(root.child("HeaderManager").get()
                        .child("headers").get()
                        .element(0).get()
                        .child("name").get()
                        .value())
                .isEqualTo(PropertyValue.string("X-Trace"));
        assertThat(root.child("MapLike").get().child("key.with.dot").get().value())
                .isEqualTo(PropertyValue.bool(true));
        assertThat(root.child("Escaped").get()
                        .child("literal.dot").get()
                        .child("bracket[name]\\tail").get()
                        .value())
                .isEqualTo(PropertyValue.raw("kept"));
    }

    @Test
    void reportsMissingPropertyWithoutCreatingNodes() {
        TestNode root = componentTree();

        assertThatThrownBy(() -> resolver.set(root, properties("HTTPSampler", "missing"), "new", PropertyValueType.STRING))
                .isInstanceOf(PropertyPathResolutionException.class)
                .hasMessageContaining("unresolved property segment 'missing'")
                .hasMessageContaining("[HTTPSampler, missing]")
                .extracting("errorCode")
                .isEqualTo(PropertyPathErrorCode.MISSING_PROPERTY);
        assertThat(root.child("HTTPSampler").get().child("missing")).isEmpty();
    }

    @Test
    void reportsWrongIndexWithoutCreatingOrAppendingCollectionItems() {
        TestNode root = componentTree();

        assertThatThrownBy(() -> resolver.set(root, properties("HeaderManager", "headers", 1, "name"), "X", PropertyValueType.STRING))
                .isInstanceOf(PropertyPathResolutionException.class)
                .hasMessageContaining("unresolved collection index [1]")
                .extracting("errorCode")
                .isEqualTo(PropertyPathErrorCode.WRONG_INDEX);
        TestNode headers = (TestNode) root.child("HeaderManager").get().child("headers").get();
        assertThat(headers.size()).isEqualTo(1);
    }

    @Test
    void reportsWrongTypeForTraversalAndConversion() {
        TestNode root = componentTree();

        assertThatThrownBy(() -> resolver.set(root, properties("HTTPSampler", "domain", "name"), "bad", PropertyValueType.STRING))
                .isInstanceOf(PropertyPathResolutionException.class)
                .hasMessageContaining("cannot resolve property segment 'name' from scalar value")
                .extracting("errorCode")
                .isEqualTo(PropertyPathErrorCode.WRONG_TYPE);

        assertThatThrownBy(() -> resolver.set(root, properties("Arguments", "arguments", 0, "value"), "not-a-number", PropertyValueType.INT))
                .isInstanceOf(PropertyPathResolutionException.class)
                .hasMessageContaining("cannot convert value to int")
                .extracting("errorCode")
                .isEqualTo(PropertyPathErrorCode.WRONG_TYPE);
    }

    @Test
    void convertsDefaultStringLongAndDoubleTypes() {
        TestNode root = componentTree();

        resolver.set(root, properties("HTTPSampler", "domain"), "123", PropertyValueType.STRING);
        resolver.set(root, properties("Arguments", "arguments", 0, "value"), "1234567890123", PropertyValueType.LONG);
        resolver.set(root, path(property("MapLike"), key("key.with.dot")), "12.50", PropertyValueType.DOUBLE);

        assertThat(root.child("HTTPSampler").get().child("domain").get().value())
                .isEqualTo(PropertyValue.string("123"));
        assertThat(root.child("Arguments").get()
                        .child("arguments").get()
                        .element(0).get()
                        .child("value").get()
                        .value())
                .isEqualTo(PropertyValue.longValue(1234567890123L));
        assertThat(root.child("MapLike").get().child("key.with.dot").get().value())
                .isEqualTo(PropertyValue.doubleValue(12.5D, "12.50"));
    }

    @Test
    void rejectsAllRegisteredStructuredCollectionTypesThroughTheSharedService() {
        for (PropertyValueType type : PropertyValueType.values()) {
            if (!type.structuredCollection()) {
                continue;
            }
            assertThatThrownBy(() -> resolver.set(componentTree(), properties("HTTPSampler", "domain"), "value", type))
                    .as(type.name())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must use the shared structured-property service");
        }
    }

    @Test
    void treatsSingleQuoteAsLiteralTypedPropertyData() {
        TestNode quoted = TestNode.scalar(PropertyValue.string("old"));
        TestNode root = TestNode.object().put("MapLike", TestNode.object().put("can't", quoted));

        resolver.set(root, properties("MapLike", "can't"), "new", PropertyValueType.STRING);

        assertThat(quoted.value()).isEqualTo(PropertyValue.string("new"));
    }

    private static PropertyPath properties(Object... members) {
        return TestPropertyPaths.properties(members);
    }

    private static PropertyPath path(PropertyPathSegment... segments) {
        return TestPropertyPaths.path(segments);
    }

    private static PropertyPathSegment property(String name) {
        return TestPropertyPaths.property(name);
    }

    private static PropertyPathSegment key(String name) {
        return TestPropertyPaths.key(name);
    }

    private static TestNode componentTree() {
        TestNode root = TestNode.object();
        root.put("HTTPSampler", TestNode.object().put("domain", TestNode.scalar(PropertyValue.string("example.com"))));
        root.put("Arguments", TestNode.object().put("arguments", TestNode.collection(
                TestNode.object().put("value", TestNode.scalar(PropertyValue.string("old"))))));
        root.put("HeaderManager", TestNode.object().put("headers", TestNode.collection(
                TestNode.object().put("name", TestNode.scalar(PropertyValue.string("Content-Type"))))));
        root.put("MapLike", TestNode.object().put("key.with.dot", TestNode.scalar(PropertyValue.string("false"))));
        root.put("Escaped", TestNode.object().put("literal.dot", TestNode.object()
                .put("bracket[name]\\tail", TestNode.scalar(PropertyValue.string("old")))));
        return root;
    }

    private static final class TestNode implements PropertyTreeNode {
        private final Map<String, TestNode> children = new LinkedHashMap<>();
        private final List<TestNode> items = new ArrayList<>();
        private PropertyValue value;

        private static TestNode object() {
            return new TestNode();
        }

        private static TestNode collection(TestNode... items) {
            TestNode node = new TestNode();
            node.items.addAll(Arrays.asList(items));
            return node;
        }

        private static TestNode scalar(PropertyValue value) {
            TestNode node = new TestNode();
            node.value = value;
            return node;
        }

        private TestNode put(String name, TestNode node) {
            children.put(name, node);
            return this;
        }

        private int size() {
            return items.size();
        }

        @Override
        public Optional<PropertyTreeNode> child(String name) {
            return Optional.ofNullable(children.get(name));
        }

        @Override
        public Optional<PropertyTreeNode> element(int index) {
            if (index < 0 || index >= items.size()) {
                return Optional.empty();
            }
            return Optional.of(items.get(index));
        }

        @Override
        public boolean scalar() {
            return value != null;
        }

        @Override
        public PropertyValue value() {
            return value;
        }

        @Override
        public void setValue(PropertyValue value) {
            this.value = value;
        }
    }
}
