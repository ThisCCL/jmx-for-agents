package io.github.thisccl.j4a.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.components.ComponentCatalogRenderer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MainCategoriesRuntimeAuthorityTest {
    @Test
    void categorySummariesUseTheExplicitFriendlyOrderIncludingEmptyAndLocalCategories() {
        List<ComponentCatalog.ComponentCategory> categories = Arrays.asList(
                category("post-processor", "Post Processors", 1),
                category("assertion", "Assertions", 0),
                category("local", "Local Components", 1));

        Map<String, Object> output = mapping(new Yaml().load(
                new ComponentCatalogRenderer().renderRuntimeCategories(categories)));

        assertThat(list(output.get("categories"))).containsExactly(
                row("post-processor", "Post Processors", 1),
                row("assertion", "Assertions", 0),
                row("local", "Local Components", 1));
        assertLeakFree(output);
    }

    private static ComponentCatalog.ComponentCategory category(String id, String label, int count) {
        return new ComponentCatalog.ComponentCategory(id, label, count);
    }

    private static Map<String, Object> row(String id, String label, int count) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("category", id);
        row.put("label", label);
        row.put("component_count", Integer.valueOf(count));
        return row;
    }

    static void assertLeakFree(Object value) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (String forbidden : Arrays.asList(
                    "group", "class", "kind", "source", "status", "failure", "allowed_parents",
                    "placements", "support_level", "customizable")) {
                assertThat(map.containsKey(forbidden)).as("forbidden key %s", forbidden).isFalse();
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                assertLeakFree(entry.getKey());
                assertLeakFree(entry.getValue());
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                assertLeakFree(item);
            }
        } else if (value instanceof String) {
            assertThat((String) value).doesNotContain("menu_", "[res_key=");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapping(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
