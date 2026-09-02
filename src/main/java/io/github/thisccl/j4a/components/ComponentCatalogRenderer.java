package io.github.thisccl.j4a.components;

import io.github.thisccl.j4a.components.ComponentCatalog.ComponentDefinition;
import io.github.thisccl.j4a.components.ComponentCatalog.ComponentCategory;
import io.github.thisccl.j4a.components.ComponentCatalog.ComponentProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public final class ComponentCatalogRenderer {
    public String renderRuntimeComponent(ComponentDefinition definition) {
        return renderRuntimeComponent(definition, false);
    }

    public String renderRuntimeComponent(ComponentDefinition definition, boolean diagnostics) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("component", definition.component());
        output.put("label", definition.label());
        output.put("category", definition.category());
        if (diagnostics && definition.runtimeMetadataStatus() != null) {
            output.put("runtime_metadata_status", definition.runtimeMetadataStatus());
        }
        output.put("properties", definition.properties().stream()
                .filter(property -> diagnostics || property.writable())
                .map(property -> diagnostics
                        ? runtimeProperty(property) : authoringProperty(property))
                .collect(Collectors.toList()));
        return yaml().dump(output);
    }

    public String renderRuntimeList(
            List<ComponentCategory> categoryDefinitions, List<ComponentDefinition> definitions) {
        Map<String, Object> output = new LinkedHashMap<>();
        List<Map<String, Object>> categories = new ArrayList<>();
        for (ComponentCategory categoryDefinition : categoryDefinitions) {
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("category", categoryDefinition.category());
            category.put("label", categoryDefinition.label());
            category.put("component_count", categoryDefinition.componentCount());
            category.put("components", components(categoryDefinition.category(), definitions));
            categories.add(category);
        }
        output.put("categories", categories);
        return yaml().dump(output);
    }

    public String renderRuntimeCategories(List<ComponentCategory> categoryDefinitions) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("categories", runtimeCategorySummaries(categoryDefinitions));
        return yaml().dump(output);
    }

    private static void putIfPresent(Map<String, Object> output, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            output.put(key, value);
        }
    }

    private static List<Map<String, Object>> components(
            String category, List<ComponentDefinition> definitions) {
        return definitions.stream()
                .filter(definition -> category.equals(definition.category()))
                .map(ComponentCatalogRenderer::component)
                .collect(Collectors.toList());
    }

    private static Map<String, Object> component(ComponentDefinition definition) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("component", definition.component());
        output.put("label", definition.label());
        return output;
    }

    private static Map<String, Object> runtimeProperty(ComponentProperty property) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("property", property.address());
        output.put("type", property.type());
        output.put("key", property.key());
        output.put("writable", property.writable());
        putIfPresent(output, "reason", property.reason());
        putIfPresent(output, "ownership", property.ownership());
        putIfPresent(output, "representation_source", property.representationSource());
        if (property.value() != null) output.put("value", property.value());
        if (property.defaultValue() != null) output.put("default", property.defaultValue());
        putIfPresent(output, "value_shape", property.valueShape());
        putIfPresent(output, "required_property_class", property.requiredPropertyClass());
        putIfPresent(output, "required_value_class", property.requiredValueClass());
        putIfPresent(output, "row_type", property.rowType());
        if (!property.rowProperties().isEmpty()) output.put("row_properties", property.rowProperties());
        if (property.valueTemplate() != null) output.put("value_template", property.valueTemplate());
        return output;
    }

    private static Map<String, Object> authoringProperty(ComponentProperty property) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("property", property.address());
        output.put("type", property.type());
        if ("opaque".equals(property.type())) {
            output.put("format", "jmeter-save-element-xml-v1");
            output.put("value_source", "focused-read");
            output.put("write_mode", "replace-whole");
        }
        if (property.defaultValue() != null) output.put("default", property.defaultValue());
        putIfPresent(output, "value_shape", property.valueShape());
        putIfPresent(output, "row_type", property.rowType());
        if (!property.rowProperties().isEmpty()) output.put("row_properties", property.rowProperties());
        if (property.valueTemplate() != null) output.put("value_template", property.valueTemplate());
        return output;
    }

    private static List<Map<String, Object>> runtimeCategorySummaries(
            List<ComponentCategory> categoryDefinitions) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (ComponentCategory categoryDefinition : categoryDefinitions) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("category", categoryDefinition.category());
            summary.put("label", categoryDefinition.label());
            summary.put("component_count", categoryDefinition.componentCount());
            summaries.add(summary);
        }
        return summaries;
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(false);
        return new Yaml(options);
    }
}
