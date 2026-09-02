package io.github.thisccl.j4a.cli;

import static io.github.thisccl.j4a.cli.MainCategoriesRuntimeAuthorityTest.assertLeakFree;
import static io.github.thisccl.j4a.cli.MainCategoriesRuntimeAuthorityTest.list;
import static io.github.thisccl.j4a.cli.MainCategoriesRuntimeAuthorityTest.mapping;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.components.ComponentCatalogRenderer;
import io.github.thisccl.j4a.path.PropertyPath;
import io.github.thisccl.j4a.path.PropertyPathSegment;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MainComponentsRuntimeAuthorityTest {
    @Test
    void diagnosticDetailProjectionRetainsEveryCapabilityFieldAndNonWritableProperty() {
        Map<String, Object> template = new LinkedHashMap<String, Object>();
        template.put("presence", "present");
        template.put("property_class", "org.example.CollectionProperty");
        template.put("items", Collections.emptyList());
        ComponentCatalog.ComponentProperty writable = new ComponentCatalog.ComponentProperty(
                propertyPath("Example.values"), "collection", false, true, "runtime-proven", "user",
                "runtime", Collections.singletonList("observed"), Collections.emptyList(),
                "collection.items", "org.example.CollectionProperty", "org.example.StringProperty",
                "org.example.Row", Arrays.asList("name", "value"), template);
        ComponentCatalog.ComponentProperty nonWritable = new ComponentCatalog.ComponentProperty(
                propertyPath("Example.runtime"), "string", true, false, "runtime-owned", "runtime",
                "runtime", "observed", "default", null, null, null, null,
                Collections.<String>emptyList(), null);
        ComponentCatalog.ComponentDefinition definition = definition(
                "assertion", "Assertions", "org.example.AssertionGui", "Example Assertion",
                Arrays.asList(writable, nonWritable), "menu_assertions", "ASSERTION");

        Map<String, Object> output = mapping(new Yaml().load(
                new ComponentCatalogRenderer().renderRuntimeComponent(definition, true)));
        List<Object> properties = list(output.get("properties"));

        assertThat(properties).hasSize(2);
        assertThat(mapping(properties.get(0)))
                .containsEntry("property", Arrays.<Object>asList("Example.values"))
                .containsEntry("type", "collection")
                .containsEntry("key", Boolean.FALSE)
                .containsEntry("writable", Boolean.TRUE)
                .containsEntry("reason", "runtime-proven")
                .containsEntry("ownership", "user")
                .containsEntry("representation_source", "runtime")
                .containsEntry("value", Collections.singletonList("observed"))
                .containsEntry("default", Collections.emptyList())
                .containsEntry("value_shape", "collection.items")
                .containsEntry("required_property_class", "org.example.CollectionProperty")
                .containsEntry("required_value_class", "org.example.StringProperty")
                .containsEntry("row_type", "org.example.Row")
                .containsEntry("row_properties", Arrays.asList("name", "value"))
                .containsEntry("value_template", template)
                .containsOnlyKeys("property", "type", "key", "writable", "reason", "ownership",
                        "representation_source", "value", "default", "value_shape",
                        "required_property_class", "required_value_class", "row_type",
                        "row_properties", "value_template");
        assertThat(mapping(properties.get(1)))
                .containsEntry("property", Arrays.<Object>asList("Example.runtime"))
                .containsEntry("writable", Boolean.FALSE)
                .containsEntry("reason", "runtime-owned")
                .containsEntry("ownership", "runtime")
                .containsEntry("value", "observed");
    }

    @Test
    void ordinaryDetailProjectionContainsOnlyWritableAuthoringFields() {
        Map<String, Object> template = new LinkedHashMap<String, Object>();
        template.put("presence", "present");
        template.put("property_class", "org.example.CollectionProperty");
        template.put("items", Collections.emptyList());
        ComponentCatalog.ComponentProperty writable = new ComponentCatalog.ComponentProperty(
                propertyPath("Example.values"), "collection", false, true, "runtime-proven", "user",
                "runtime", Collections.singletonList("observed"), Collections.emptyList(),
                "collection.items", "org.example.CollectionProperty", "org.example.StringProperty",
                "org.example.Row", Arrays.asList("name", "value"), template);
        ComponentCatalog.ComponentProperty nonWritable = new ComponentCatalog.ComponentProperty(
                propertyPath("Example.runtime"), "string", true, false, "runtime-owned", "runtime",
                "runtime", "observed", "default", null, null, null, null,
                Collections.<String>emptyList(), null);
        ComponentCatalog.ComponentDefinition definition = definition(
                "assertion", "Assertions", "org.example.AssertionGui", "Example Assertion",
                Arrays.asList(writable, nonWritable), "menu_assertions", "ASSERTION");

        Map<String, Object> output = mapping(new Yaml().load(
                new ComponentCatalogRenderer().renderRuntimeComponent(definition)));
        List<Object> properties = list(output.get("properties"));

        assertThat(properties).hasSize(1);
        assertThat(mapping(properties.get(0)))
                .containsEntry("property", Arrays.<Object>asList("Example.values"))
                .containsEntry("type", "collection")
                .containsEntry("default", Collections.emptyList())
                .containsEntry("value_shape", "collection.items")
                .containsEntry("row_type", "org.example.Row")
                .containsEntry("row_properties", Arrays.asList("name", "value"))
                .containsEntry("value_template", template)
                .containsOnlyKeys("property", "type", "default", "value_shape", "row_type",
                        "row_properties", "value_template");
    }

    @Test
    void diagnosticDetailRetainsExactIdentityAndActionablePropertyMetadataWithoutInternalFacts() {
        ComponentCatalog.ComponentProperty property = new ComponentCatalog.ComponentProperty(
                propertyPath("HTTPsampler.Arguments"), "arguments", true, true, "internal reason",
                "user", "structured_adapter", null, Collections.singletonMap("name", "value"),
                "arguments.rows", null, null, "org.apache.jmeter.config.Argument",
                Arrays.asList("name", "value", "metadata"), null);
        ComponentCatalog.ComponentDefinition definition = definition(
                "pre-processor", "Pre Processors",
                "org.apache.jmeter.modifiers.JSR223PreProcessor", "JSR223 PreProcessor",
                Collections.singletonList(property), "[res_key=menu_internal]", "MARKER_KIND");

        Map<String, Object> output = mapping(new Yaml().load(
                new ComponentCatalogRenderer().renderRuntimeComponent(definition, true)));

        assertThat(output).containsEntry("component", "org.apache.jmeter.modifiers.JSR223PreProcessor")
                .containsEntry("label", "JSR223 PreProcessor")
                .containsEntry("category", "pre-processor")
                .containsOnlyKeys("component", "label", "category", "properties");
        Map<String, Object> renderedProperty = mapping(list(output.get("properties")).get(0));
        assertThat(renderedProperty).containsEntry(
                "property", Arrays.<Object>asList("HTTPsampler.Arguments"))
                .containsEntry("type", "arguments")
                .containsEntry("key", Boolean.TRUE)
                .containsEntry("writable", Boolean.TRUE)
                .containsEntry("reason", "internal reason")
                .containsEntry("ownership", "user")
                .containsEntry("representation_source", "structured_adapter")
                .containsEntry("default", Collections.singletonMap("name", "value"))
                .containsEntry("value_shape", "arguments.rows")
                .containsEntry("row_type", "org.apache.jmeter.config.Argument")
                .containsEntry("row_properties", Arrays.asList("name", "value", "metadata"))
                .containsOnlyKeys("property", "type", "key", "writable", "reason", "ownership",
                        "representation_source", "default", "value_shape", "row_type", "row_properties");
        assertLeakFree(output);
    }

    private static PropertyPath propertyPath(String name) {
        return new PropertyPath(Collections.singletonList(PropertyPathSegment.property(name)));
    }

    @Test
    void groupedListUsesExplicitOrderCountsAndKnownEmptyComponents() {
        List<ComponentCatalog.ComponentCategory> categories = Arrays.asList(
                new ComponentCatalog.ComponentCategory("post-processor", "Post Processors", 1),
                new ComponentCatalog.ComponentCategory("assertion", "Assertions", 0),
                new ComponentCatalog.ComponentCategory("local", "Local Components", 1));
        List<ComponentCatalog.ComponentDefinition> definitions = Arrays.asList(
                definition("local", "Local Components", "plugin.ExampleGui", "Plugin Example",
                        Collections.<ComponentCatalog.ComponentProperty>emptyList(),
                        "menu_plugin_x", "TEST_BEAN"),
                definition("post-processor", "Post Processors", "example.Post", "Post",
                        Collections.<ComponentCatalog.ComponentProperty>emptyList(),
                        "menu_post_processors", "METADATA_TEST_ELEMENT"),
                definition("menu_stale_group", "[res_key=stale]", "stale.Hidden", "[res_key=hidden]",
                        Collections.<ComponentCatalog.ComponentProperty>emptyList(),
                        "menu_stale", "MARKER_KIND"));

        String yaml = new ComponentCatalogRenderer().renderRuntimeList(categories, definitions);
        Map<String, Object> output = mapping(new Yaml().load(yaml));
        List<Object> renderedCategories = list(output.get("categories"));

        assertThat(renderedCategories).hasSize(3);
        assertCategory(mapping(renderedCategories.get(0)), "post-processor", "Post Processors", 1,
                "example.Post", "Post");
        assertThat(mapping(renderedCategories.get(1)))
                .containsEntry("category", "assertion")
                .containsEntry("label", "Assertions")
                .containsEntry("component_count", Integer.valueOf(0))
                .containsEntry("components", Collections.emptyList());
        assertThat(yaml).contains("components: []");
        assertCategory(mapping(renderedCategories.get(2)), "local", "Local Components", 1,
                "plugin.ExampleGui", "Plugin Example");
        assertLeakFree(output);
    }

    private static void assertCategory(
            Map<String, Object> category, String id, String label, int count,
            String component, String componentLabel) {
        assertThat(category).containsEntry("category", id)
                .containsEntry("label", label)
                .containsEntry("component_count", Integer.valueOf(count))
                .containsOnlyKeys("category", "label", "component_count", "components");
        assertThat(list(category.get("components"))).containsExactly(
                componentRow(component, componentLabel));
    }

    private static Map<String, Object> componentRow(String component, String label) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("component", component);
        row.put("label", label);
        return row;
    }

    private static ComponentCatalog.ComponentDefinition definition(
            String category, String categoryLabel, String component, String label,
            List<ComponentCatalog.ComponentProperty> properties, String menuClass, String kind) {
        return new ComponentCatalog.ComponentDefinition(
                category, categoryLabel, component, label, properties, menuClass, kind);
    }
}
