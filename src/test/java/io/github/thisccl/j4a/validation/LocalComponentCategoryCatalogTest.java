package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.components.ComponentCatalog;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalComponentCategoryCatalogTest {
    @Test
    void coreCategoriesAreAlwaysPresentInPublicOrderWhenEveryCategoryIsEmpty() {
        assertThat(LocalComponentCategoryCatalog.categories(
                Collections.<ComponentCatalog.ComponentDefinition>emptyList()))
                .extracting(
                        ComponentCatalog.ComponentCategory::category,
                        ComponentCatalog.ComponentCategory::label,
                        ComponentCatalog.ComponentCategory::componentCount)
                .containsExactly(
                        tuple("post-processor", "Post Processors", 0),
                        tuple("assertion", "Assertions", 0),
                        tuple("listener", "Listeners", 0),
                        tuple("pre-processor", "Pre Processors", 0),
                        tuple("logic-controller", "Logic Controllers", 0),
                        tuple("test-fragment", "Test Fragments", 0),
                        tuple("non-test-element", "Non-Test Elements", 0),
                        tuple("sampler", "Samplers", 0),
                        tuple("thread-group", "Thread Groups", 0),
                        tuple("timer", "Timers", 0),
                        tuple("config-element", "Config Elements", 0));
    }

    @Test
    void completeRuntimeLabelsLocalizeEmptyCategoriesAndFallbackOnlyForUnreadableValues() {
        Map<String, String> runtimeLabels = new LinkedHashMap<>();
        runtimeLabels.put("menu_assertions", "断言");
        runtimeLabels.put("menu_timer", "[res_key=menu_timer]");
        runtimeLabels.put("menu_config_element", "  ");
        List<ComponentCatalog.ComponentCategory> categories = LocalComponentCategoryCatalog.categories(
                Collections.singletonList(definition(
                        "menu_generative_controller", "Populated Samplers", "example.Sampler")),
                runtimeLabels);

        assertThat(categories)
                .filteredOn(category -> Arrays.asList(
                        "assertion", "sampler", "timer", "config-element").contains(category.category()))
                .extracting(
                        ComponentCatalog.ComponentCategory::category,
                        ComponentCatalog.ComponentCategory::label,
                        ComponentCatalog.ComponentCategory::componentCount)
                .containsExactly(
                        tuple("assertion", "断言", 0),
                        tuple("sampler", "Populated Samplers", 1),
                        tuple("timer", "Timers", 0),
                        tuple("config-element", "Config Elements", 0));
        assertThat(categories).extracting(ComponentCatalog.ComponentCategory::label)
                .allSatisfy(label -> assertThat(label).isNotBlank().doesNotStartWith("[res_key="));
    }

    @Test
    void visibleDefinitionsUseExactMappingsAndAggregateOnlyUnmappedEntriesUnderLocal() {
        List<ComponentCatalog.ComponentDefinition> definitions = Arrays.asList(
                definition("menu_post_processors", "Runtime Post Processors", "example.Post"),
                definition("menu_assertions", "Runtime Assertions", "example.Assertion"),
                definition("menu_listener", "Runtime Listeners", "example.Listener"),
                definition("menu_pre_processors", "Runtime Pre Processors", "example.Pre"),
                definition("menu_logic_controller", "Runtime Logic Controllers", "example.Logic"),
                definition("menu_fragments", "Runtime Test Fragments", "example.Fragment"),
                definition("menu_non_test_elements", "Runtime Non-Test Elements", "example.NonTest"),
                definition("menu_generative_controller", "Runtime Samplers", "example.Sampler"),
                definition("menu_threads", "Runtime Thread Groups", "example.ThreadGroup"),
                definition("menu_timer", "Runtime Timers", "example.Timer"),
                definition("menu_config_element", "Runtime Config Elements", "example.Config"),
                definition("menu_plugin_x", "Plugin Internals", "example.Plugin"));

        assertThat(LocalComponentCategoryCatalog.categories(definitions))
                .extracting(
                        ComponentCatalog.ComponentCategory::category,
                        ComponentCatalog.ComponentCategory::label,
                        ComponentCatalog.ComponentCategory::componentCount)
                .containsExactly(
                        tuple("post-processor", "Runtime Post Processors", 1),
                        tuple("assertion", "Runtime Assertions", 1),
                        tuple("listener", "Runtime Listeners", 1),
                        tuple("pre-processor", "Runtime Pre Processors", 1),
                        tuple("logic-controller", "Runtime Logic Controllers", 1),
                        tuple("test-fragment", "Runtime Test Fragments", 1),
                        tuple("non-test-element", "Runtime Non-Test Elements", 1),
                        tuple("sampler", "Runtime Samplers", 1),
                        tuple("thread-group", "Runtime Thread Groups", 1),
                        tuple("timer", "Runtime Timers", 1),
                        tuple("config-element", "Runtime Config Elements", 1),
                        tuple("local", "Local Components", 1));

        assertThat(definitions).extracting(ComponentCatalog.ComponentDefinition::category)
                .containsExactly(
                        "menu_post_processors", "menu_assertions", "menu_listener",
                        "menu_pre_processors", "menu_logic_controller", "menu_fragments",
                        "menu_non_test_elements", "menu_generative_controller", "menu_threads",
                        "menu_timer", "menu_config_element", "menu_plugin_x");
        assertThat(definitions).extracting(ComponentCatalog.ComponentDefinition::component)
                .containsExactly(
                        "example.Post", "example.Assertion", "example.Listener", "example.Pre",
                        "example.Logic", "example.Fragment", "example.NonTest", "example.Sampler",
                        "example.ThreadGroup", "example.Timer", "example.Config", "example.Plugin");
    }

    @Test
    void localIsConditionalAndProjectionIsRecomputedFromEachVisibleDefinitionList() {
        List<ComponentCatalog.ComponentCategory> withPlugin = LocalComponentCategoryCatalog.categories(
                Collections.singletonList(definition("menu_plugin_x", "Plugin", "example.Plugin")));
        List<ComponentCatalog.ComponentCategory> withoutPlugin = LocalComponentCategoryCatalog.categories(
                Collections.singletonList(definition(
                        "menu_post_processors", "Runtime Post Processors", "example.Post")));

        assertThat(withPlugin).extracting(ComponentCatalog.ComponentCategory::category)
                .endsWith("local");
        assertThat(withoutPlugin).extracting(ComponentCatalog.ComponentCategory::category)
                .doesNotContain("local", "menu_plugin_x");
        assertThat(withoutPlugin.get(0).componentCount()).isEqualTo(1);
    }

    @Test
    void canonicalPublicDefinitionGroupsCountInTheirCoreCategoriesWithoutCreatingLocal() {
        List<ComponentCatalog.ComponentCategory> categories = LocalComponentCategoryCatalog.categories(
                Arrays.asList(
                        definition("sampler", "Projected Samplers", "example.Sampler"),
                        definition("config-element", "Projected Config Elements", "example.Config")));

        assertThat(categories)
                .filteredOn(category -> category.componentCount() > 0)
                .extracting(
                        ComponentCatalog.ComponentCategory::category,
                        ComponentCatalog.ComponentCategory::label,
                        ComponentCatalog.ComponentCategory::componentCount)
                .containsExactly(
                        tuple("sampler", "Projected Samplers", 1),
                        tuple("config-element", "Projected Config Elements", 1));
        assertThat(categories).extracting(ComponentCatalog.ComponentCategory::category)
                .doesNotContain("local");
    }

    @Test
    void onlyElevenRawAliasesResolveAndAliasesAreNeverEmitted() {
        List<ComponentCatalog.ComponentDefinition> visible = Collections.singletonList(
                definition("menu_plugin_x", "Plugin", "example.Plugin"));

        assertThat(Arrays.asList(
                LocalComponentCategoryCatalog.resolveCategoryId("menu_post_processors", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_assertions", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_listener", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_pre_processors", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_logic_controller", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_fragments", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_non_test_elements", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_generative_controller", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_threads", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_timer", visible),
                LocalComponentCategoryCatalog.resolveCategoryId("menu_config_element", visible)))
                .extracting(java.util.Optional::get)
                .containsExactly(
                        "post-processor", "assertion", "listener", "pre-processor",
                        "logic-controller", "test-fragment", "non-test-element", "sampler",
                        "thread-group", "timer", "config-element");
        assertThat(LocalComponentCategoryCatalog.resolveCategoryId("local", visible))
                .contains("local");
        assertThat(LocalComponentCategoryCatalog.resolveCategoryId(
                "local", Collections.<ComponentCatalog.ComponentDefinition>emptyList())).isEmpty();
        assertThat(LocalComponentCategoryCatalog.resolveCategoryId("menu_plugin_x", visible)).isEmpty();
        assertThat(LocalComponentCategoryCatalog.resolveCategoryId("random-id", visible)).isEmpty();
        assertThat(LocalComponentCategoryCatalog.resolveCategoryId(null, visible)).isEmpty();
        assertThat(LocalComponentCategoryCatalog.resolveCategoryId("  ", visible)).isEmpty();
        assertThat(LocalComponentCategoryCatalog.categories(visible))
                .extracting(ComponentCatalog.ComponentCategory::category)
                .noneMatch(category -> ((String) category).contains("menu_"));
    }

    @Test
    void approvedRawGroupsExposeTheirStableFallbackLabels() {
        assertThat(Arrays.asList(
                "menu_post_processors", "menu_assertions", "menu_listener",
                "menu_pre_processors", "menu_logic_controller", "menu_fragments",
                "menu_non_test_elements", "menu_generative_controller", "menu_threads",
                "menu_timer", "menu_config_element"))
                .extracting(rawGroup -> LocalComponentCategoryCatalog.fallbackLabel(rawGroup).get())
                .containsExactly(
                        "Post Processors", "Assertions", "Listeners", "Pre Processors",
                        "Logic Controllers", "Test Fragments", "Non-Test Elements", "Samplers",
                        "Thread Groups", "Timers", "Config Elements");
        assertThat(LocalComponentCategoryCatalog.fallbackLabel("menu_plugin_x")).isEmpty();
        assertThat(LocalComponentCategoryCatalog.fallbackLabel(null)).isEmpty();
    }

    private static ComponentCatalog.ComponentDefinition definition(
            String rawGroup, String categoryLabel, String component) {
        return new ComponentCatalog.ComponentDefinition(rawGroup, categoryLabel, component, component,
                Collections.<ComponentCatalog.ComponentProperty>emptyList(), component,
                "METADATA_TEST_ELEMENT");
    }

    private static org.assertj.core.groups.Tuple tuple(String category, String label, int count) {
        return org.assertj.core.groups.Tuple.tuple(category, label, count);
    }
}
