package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.components.ComponentCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LocalComponentCategoryCatalog {
    private static final List<CoreCategory> CORE_CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            new CoreCategory("menu_post_processors", "post-processor", "Post Processors"),
            new CoreCategory("menu_assertions", "assertion", "Assertions"),
            new CoreCategory("menu_listener", "listener", "Listeners"),
            new CoreCategory("menu_pre_processors", "pre-processor", "Pre Processors"),
            new CoreCategory("menu_logic_controller", "logic-controller", "Logic Controllers"),
            new CoreCategory("menu_fragments", "test-fragment", "Test Fragments"),
            new CoreCategory("menu_non_test_elements", "non-test-element", "Non-Test Elements"),
            new CoreCategory("menu_generative_controller", "sampler", "Samplers"),
            new CoreCategory("menu_threads", "thread-group", "Thread Groups"),
            new CoreCategory("menu_timer", "timer", "Timers"),
            new CoreCategory("menu_config_element", "config-element", "Config Elements")));
    private static final Map<String, CoreCategory> CORE_BY_RAW_GROUP = coreByRawGroup();

    private LocalComponentCategoryCatalog() {
    }

    public static List<ComponentCatalog.ComponentCategory> categories(
            List<ComponentCatalog.ComponentDefinition> visibleDefinitions) {
        return categories(visibleDefinitions, Collections.<String, String>emptyMap());
    }

    public static List<ComponentCatalog.ComponentCategory> categories(
            List<ComponentCatalog.ComponentDefinition> visibleDefinitions,
            Map<String, String> runtimeLabels) {
        Map<CoreCategory, Integer> counts = new LinkedHashMap<>();
        Map<CoreCategory, String> labels = new LinkedHashMap<>();
        int localCount = 0;
        for (ComponentCatalog.ComponentDefinition definition : visibleDefinitions) {
            CoreCategory category = coreCategory(definition.category());
            if (category == null) {
                localCount++;
                continue;
            }
            Integer current = counts.get(category);
            counts.put(category, current == null ? 1 : current + 1);
            if (!labels.containsKey(category) && isReadable(definition.categoryLabel())) {
                labels.put(category, definition.categoryLabel());
            }
        }
        List<ComponentCatalog.ComponentCategory> categories = new ArrayList<>();
        for (CoreCategory category : CORE_CATEGORIES) {
            Integer count = counts.get(category);
            String runtimeLabel = runtimeLabels.get(category.rawGroup);
            String label = isReadable(runtimeLabel) ? runtimeLabel : labels.get(category);
            categories.add(new ComponentCatalog.ComponentCategory(
                    category.category, label == null ? category.label : label, count == null ? 0 : count));
        }
        if (localCount > 0) {
            categories.add(new ComponentCatalog.ComponentCategory("local", "Local Components", localCount));
        }
        return Collections.unmodifiableList(categories);
    }

    public static Optional<String> resolveCategoryId(
            String input, List<ComponentCatalog.ComponentDefinition> visibleDefinitions) {
        if (input == null || input.trim().isEmpty()) {
            return Optional.empty();
        }
        CoreCategory alias = CORE_BY_RAW_GROUP.get(input);
        if (alias != null) {
            return Optional.of(alias.category);
        }
        for (CoreCategory category : CORE_CATEGORIES) {
            if (category.category.equals(input)) {
                return Optional.of(category.category);
            }
        }
        if ("local".equals(input) && hasUnmappedDefinition(visibleDefinitions)) {
            return Optional.of("local");
        }
        return Optional.empty();
    }

    public static Optional<String> fallbackLabel(String rawGroup) {
        CoreCategory category = CORE_BY_RAW_GROUP.get(rawGroup);
        return category == null ? Optional.<String>empty() : Optional.of(category.label);
    }

    public static List<String> knownRawGroups() {
        return Collections.unmodifiableList(new ArrayList<>(CORE_BY_RAW_GROUP.keySet()));
    }

    private static Map<String, CoreCategory> coreByRawGroup() {
        Map<String, CoreCategory> categories = new LinkedHashMap<>();
        for (CoreCategory category : CORE_CATEGORIES) {
            categories.put(category.rawGroup, category);
        }
        return Collections.unmodifiableMap(categories);
    }

    private static boolean isReadable(String label) {
        return label != null && !label.trim().isEmpty() && !label.trim().startsWith("[res_key=");
    }

    private static boolean hasUnmappedDefinition(
            List<ComponentCatalog.ComponentDefinition> visibleDefinitions) {
        for (ComponentCatalog.ComponentDefinition definition : visibleDefinitions) {
            if (coreCategory(definition.category()) == null) {
                return true;
            }
        }
        return false;
    }

    private static CoreCategory coreCategory(String group) {
        CoreCategory rawCategory = CORE_BY_RAW_GROUP.get(group);
        if (rawCategory != null) {
            return rawCategory;
        }
        for (CoreCategory category : CORE_CATEGORIES) {
            if (category.category.equals(group)) {
                return category;
            }
        }
        return null;
    }

    private static final class CoreCategory {
        private final String rawGroup;
        private final String category;
        private final String label;

        private CoreCategory(String rawGroup, String category, String label) {
            this.rawGroup = rawGroup;
            this.category = category;
            this.label = label;
        }
    }
}
