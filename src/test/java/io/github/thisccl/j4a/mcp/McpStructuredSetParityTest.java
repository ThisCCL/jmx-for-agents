package io.github.thisccl.j4a.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchParseException;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.components.ComponentCatalogRenderer;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import io.github.thisccl.j4a.path.PropertyAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class McpStructuredSetParityTest {
    @Test
    void genericAndSemanticCollectionShapesRemainDistinctWithoutUnknownTemplates() {
        ApplyPatchParser parser = new ApplyPatchParser();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parseStructuredValue(
                "- one\n", address("qa.unknown"), ApplyPatch.ValueType.COLLECTION))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("presence")
                .hasMessageContaining("property_class")
                .hasMessageContaining("items")
                .hasMessageContaining("value_template")
                .hasMessageContaining("components")
                .hasMessageContaining("focused read");
        assertThat(parser.parseStructuredValue(
                "- name: q\n  value: abc\n",
                address("HTTPsampler.Arguments"),
                ApplyPatch.ValueType.ROWS)).isInstanceOf(List.class);

        ComponentCatalog.ComponentProperty unknown = new ComponentCatalog.ComponentProperty(
                new PropertyPath(Collections.singletonList(PropertyPathSegment.property("qa.unknown"))),
                "collection", false, true, null, "user", null, null, null,
                "collection.items", null, null, null, Collections.<String>emptyList(), null);
        ComponentCatalog.ComponentDefinition component = new ComponentCatalog.ComponentDefinition(
                "test", "Test", "example.UnknownGui", "Unknown",
                Collections.singletonList(unknown), "example.UnknownGui", "GUI_COMPONENT");
        assertThat(new ComponentCatalogRenderer().renderRuntimeComponent(component))
                .doesNotContain("value_template");
    }

    @Test
    void setSchemaAcceptsScalarObjectOrListValues() {
        Map<String, Object> set = McpTools.list().stream()
                .map(McpStructuredSetParityTest::mapping)
                .filter(tool -> "set".equals(tool.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("set tool is missing"));

        Map<String, Object> value = mapping(mapping(set.get("inputSchema")).get("properties")).get("value")
                instanceof Map ? mapping(mapping(mapping(set.get("inputSchema")).get("properties")).get("value")) : null;
        assertThat(value).isNotNull();
        assertThat(list(value.get("oneOf"))).extracting(candidate -> mapping(candidate).get("type"))
                .containsExactly("string", "number", "boolean", "object", "array");
    }

    @Test
    void structuredSetSerializesTheJsonValueAndSelectsRowsType() throws IOException {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", "q");
        row.put("value", "abc");
        Map<String, Object> collection = new LinkedHashMap<String, Object>();
        collection.put("row_type", "org.apache.jmeter.protocol.http.util.HTTPArgument");
        collection.put("rows", Arrays.asList(row));
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("file", "in.jmx");
        arguments.put("locator", "jmx_http");
        arguments.put("property", Arrays.asList("HTTPsampler.Arguments"));
        arguments.put("value", collection);
        arguments.put("type", "rows");
        arguments.put("out", "out.jmx");

        McpToolInvocation invocation = McpToolInvocation.from("set", arguments);

        assertThat(invocation.valid()).isTrue();
        assertThat(invocation.args()).containsSubsequence("--type", "rows");
        String serialized = option(invocation.args(), "--value");
        assertThat(McpJson.parse(serialized)).isEqualTo(collection);
    }

    @Test
    void scalarSetSerializesNativeAddressArray() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("file", "in.jmx");
        arguments.put("locator", "jmx_http");
        arguments.put("property", Arrays.asList("HTTPSampler.domain"));
        arguments.put("value", "example.test");
        arguments.put("out", "out.jmx");

        McpToolInvocation invocation = McpToolInvocation.from("set", arguments);

        assertThat(invocation.valid()).isTrue();
        assertThat(option(invocation.args(), "--property")).isEqualTo("[\"HTTPSampler.domain\"]");
        assertThat(option(invocation.args(), "--value")).isEqualTo("example.test");
        assertThat(Arrays.asList(invocation.args())).doesNotContain("--type");
    }

    @ParameterizedTest(name = "{0}-rejects-{1}")
    @MethodSource("wrongExplicitNativeScalarKinds")
    void scalarSetRejectsWrongExplicitNativeKindBeforeWorkerInvocation(
            String type, Object value, String actualType) {
        Map<String, Object> arguments = scalarArguments(type, value);

        String error = McpToolArgumentValidator.validate("set", arguments);

        assertThat(error).contains("expects exact type '" + type + "'")
                .contains("actual type '" + actualType + "'")
                .contains("address [qa.scalar]");
    }

    @Test
    void scalarSetBoundsWrongExplicitNativeValueBeforeWorkerInvocation() {
        String submitted = repeat("sensitive-value-", 300);
        Map<String, Object> arguments = scalarArguments("int", submitted);

        String error = McpToolArgumentValidator.validate("set", arguments);

        assertThat(error).contains("expects exact type 'int'")
                .contains("actual type 'String'")
                .contains("address [qa.scalar]")
                .contains("[truncated]")
                .doesNotContain(submitted)
                .hasSizeLessThan(512);
    }

    @Test
    void scalarSetInfersOnlyTheNativeJsonKindAndRange() {
        McpToolInvocation integer = McpToolInvocation.from(
                "set", scalarArguments(null, Long.valueOf(100L)));
        McpToolInvocation longInteger = McpToolInvocation.from(
                "set", scalarArguments(null, Long.valueOf(Long.MAX_VALUE)));
        McpToolInvocation decimal = McpToolInvocation.from(
                "set", scalarArguments(null, Double.valueOf(2.5D)));

        assertThat(integer.args()).containsSubsequence("--type", "int");
        assertThat(longInteger.args()).containsSubsequence("--type", "long");
        assertThat(decimal.args()).containsSubsequence("--type", "double");
    }

    @Test
    void structuredSetRejectsConflictingExplicitScalarTypeBeforeCliInvocation() {
        Map<String, Object> arguments = structuredArguments();
        arguments.put("type", "string");

        McpToolInvocation invocation = McpToolInvocation.from("set", arguments);

        assertThat(invocation.valid()).isFalse();
        assertThat(invocation.args()).isNull();
    }

    @Test
    void structuredSetRejectsRemovedNamedType() {
        Map<String, Object> arguments = structuredArguments();
        arguments.put("type", "arguments");

        McpToolInvocation invocation = McpToolInvocation.from("set", arguments);

        assertThat(invocation.valid()).isFalse();
        assertThat(invocation.args()).isNull();
    }

    @Test
    void sharedPatchParserPreservesLiteralAliasLikeValues() {
        ApplyPatch patch = new ApplyPatchParser().parse("changes:\n"
                + "  - add:\n"
                + "      parent: jmx_parent\n"
                + "      position: $literal-position\n"
                + "      component: $literal-component\n"
                + "      as: created\n"
                + "      properties:\n"
                + "        - property: [$literal-property]\n"
                + "          value: $literal-value\n"
                + "          type: string\n"
                + "  - set:\n"
                + "      ref: $created\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          value: unchanged\n"
                + "          type: string\n");
        ApplyPatch.AddOperation add = (ApplyPatch.AddOperation) patch.changes().get(0).operation();
        ApplyPatch.SetOperation set = (ApplyPatch.SetOperation) patch.changes().get(1).operation();
        assertThat(add.as()).contains("created");
        assertThat(add.component()).isEqualTo("$literal-component");
        assertThat(add.position()).contains("$literal-position");
        assertThat(add.properties().get(0).property().segments()).containsExactly("$literal-property");
        assertThat(add.properties().get(0).value()).isEqualTo("$literal-value");
        assertThat(set.refExpression()).isInstanceOf(ApplyPatch.AliasReference.class);
    }

    private static String option(String[] args, String option) {
        List<String> values = Arrays.asList(args);
        return values.get(values.indexOf(option) + 1);
    }

    private static Map<String, Object> structuredArguments() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", "q");
        row.put("value", "abc");
        Map<String, Object> collection = new LinkedHashMap<String, Object>();
        collection.put("rows", Arrays.asList(row));
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("file", "in.jmx");
        arguments.put("locator", "jmx_http");
        arguments.put("property", Arrays.asList("HTTPsampler.Arguments"));
        arguments.put("value", collection);
        arguments.put("out", "out.jmx");
        return arguments;
    }

    private static Map<String, Object> scalarArguments(String type, Object value) {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("file", "in.jmx");
        arguments.put("locator", "jmx_http");
        arguments.put("property", Arrays.asList("qa.scalar"));
        if (type != null) {
            arguments.put("type", type);
        }
        arguments.put("value", value);
        arguments.put("out", "out.jmx");
        return arguments;
    }

    private static Stream<Arguments> wrongExplicitNativeScalarKinds() {
        return Stream.of(
                Arguments.of("float", Double.valueOf(1.25D), "Double"),
                Arguments.of("double", Float.valueOf(2.5F), "Float"),
                Arguments.of("long", Integer.valueOf(100), "Integer"),
                Arguments.of("int", Long.valueOf(100L), "Long"),
                Arguments.of("float", Float.valueOf(Float.NaN), "Float"),
                Arguments.of("double", Double.valueOf(Double.POSITIVE_INFINITY), "Double"));
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    private static PropertyAddress address(Object... segments) {
        return new PropertyAddress(Arrays.asList(segments));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}
