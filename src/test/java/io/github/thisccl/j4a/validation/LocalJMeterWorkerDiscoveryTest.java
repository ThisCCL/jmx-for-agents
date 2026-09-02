package io.github.thisccl.j4a.validation;

import static io.github.thisccl.j4a.validation.LocalJMeterWorkerTestSupport.assertClassNotLoadableInMainProcess;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.components.ComponentCatalog;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalJMeterWorkerDiscoveryTest {
    private final DefaultLocalProfileQaFixtures fixtures = new DefaultLocalProfileQaFixtures();

    @Test
    void freshLocalProfileFixturesUseInstanceScopedHomesToAvoidCrossTestDeletion() throws IOException {
        Path firstHome = DefaultLocalProfileQaFixtures.fresh().localHome();
        Path secondHome = DefaultLocalProfileQaFixtures.fresh().localHome();

        assertThat(secondHome).isNotEqualTo(firstHome);
    }

    @Test
    void workerLoadsLibExtAndSearchPathPluginJmxWithoutLeakingPluginClasses() throws IOException {
        DefaultLocalProfileQaFixtures isolationFixtures = DefaultLocalProfileQaFixtures.fresh();
        String previousReuse = System.getProperty("j4a.worker.reuse");
        System.setProperty("j4a.worker.reuse", "false");
        LocalJMeterWorkerResult extResult;
        LocalJMeterWorkerResult searchPathResult;
        try {
            LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                    Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

            extResult = client.execute(LocalJMeterWorkerRequest.validate(
                    isolationFixtures.pluginBackedJmx(), isolationFixtures.localHome()));
            searchPathResult = client.execute(LocalJMeterWorkerRequest.validate(
                    isolationFixtures.searchPathBackedJmx(), isolationFixtures.localHome()));
        } finally {
            if (previousReuse == null) {
                System.clearProperty("j4a.worker.reuse");
            } else {
                System.setProperty("j4a.worker.reuse", previousReuse);
            }
        }

        assertThat(extResult.response().success()).as(extResult.response().toJsonLine()).isTrue();
        assertThat(extResult.response().stdout()).contains("SYNTHETIC_STDOUT_ExtOnlySampler");
        assertThat(extResult.response().stderr()).contains("SYNTHETIC_STDERR_ExtOnlySampler");
        assertThat(searchPathResult.response().success()).as(searchPathResult.response().toJsonLine()).isTrue();
        assertThat(searchPathResult.response().stdout()).contains("SYNTHETIC_STDOUT_SearchPathOnlySampler");
        assertThat(searchPathResult.response().stderr()).contains("SYNTHETIC_STDERR_SearchPathOnlySampler");
        assertClassNotLoadableInMainProcess(DefaultLocalProfileQaFixtures.EXT_PLUGIN_CLASS);
        assertClassNotLoadableInMainProcess(DefaultLocalProfileQaFixtures.SEARCH_PLUGIN_CLASS);
        assertThat(extResult.workerExited()).isTrue();
        assertThat(searchPathResult.workerExited()).isTrue();
    }

    @Test
    void workerRendersReadYamlForLoadedJmx() throws IOException {
        DefaultLocalProfileQaFixtures workerFixtures = DefaultLocalProfileQaFixtures.fresh();
        String previousReuse = System.getProperty("j4a.worker.reuse");
        System.setProperty("j4a.worker.reuse", "false");
        LocalJMeterWorkerResult result;
        try {
            LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                    Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

            result = client.execute(LocalJMeterWorkerRequest.renderReadData(
                    workerFixtures.pluginBackedJmx(), workerFixtures.localHome()));
        } finally {
            if (previousReuse == null) {
                System.clearProperty("j4a.worker.reuse");
            } else {
                System.setProperty("j4a.worker.reuse", previousReuse);
            }
        }

        assertThat(result.response().success()).as(result.response().toJsonLine()).isTrue();
        assertThat(result.response().payload()).contains("root:", "Synthetic Plugin Plan", "Ext Plugin Sampler");
        assertThat(result.response().stdout()).contains("SYNTHETIC_STDOUT_ExtOnlySampler");
        assertThat(result.workerExited()).isTrue();
    }

    @Test
    void workerDiscoversLocalComponentsWithoutFalseAddability() throws IOException {
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

        LocalJMeterWorkerResult discovery = client.execute(LocalJMeterWorkerRequest.discoverComponents(fixtures.localHome()));
        LocalJMeterWorkerResult extPluginDetails = client.execute(LocalJMeterWorkerRequest.componentDetails(
                fixtures.localHome(), DefaultLocalProfileQaFixtures.EXT_PLUGIN_CLASS + "Gui"));

        assertThat(discovery.response().success()).as(discovery.response().toJsonLine()).isTrue();
        assertThat(discovery.response().payload()).contains(
                "category: pre-processor",
                "component: org.apache.jmeter.modifiers.JSR223PreProcessor",
                "category: post-processor",
                "component: org.apache.jmeter.extractor.JSR223PostProcessor",
                "component: " + DefaultLocalProfileQaFixtures.EXT_PLUGIN_CLASS + "Gui");
        assertThat(discovery.response().payload()).doesNotContain(
                "category: menu_",
                "profile:", "status:", "allowed_parents:", "placements:",
                DefaultLocalProfilePluginFixtures.USER_CLASSPATH_SCAN_ONLY_PLUGIN_CLASS,
                DefaultLocalProfilePluginFixtures.PLUGIN_DEPENDENCY_SCAN_ONLY_PLUGIN_CLASS);
        assertThat(extPluginDetails.response().success()).as(extPluginDetails.response().toJsonLine()).isTrue();
        assertThat(extPluginDetails.response().payload()).contains(
                "component: " + DefaultLocalProfileQaFixtures.EXT_PLUGIN_CLASS + "Gui",
                "category: sampler", "label: Ext Only Sampler", "properties:");
        assertThat(extPluginDetails.response().payload()).doesNotContain(
                "group:", "\nclass:", "kind:",
                "status:", "failure:", "allowed_parents:", "placements:",
                "support_level:", "customizable:");
    }

    @Test
    void workerFiltersByPublicCategoryAndApprovedAliasWithoutChangingFqcn() throws IOException {
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

        LocalJMeterWorkerResult publicResult = client.execute(LocalJMeterWorkerRequest.discoverComponents(
                fixtures.localHome(), "sampler"));
        LocalJMeterWorkerResult aliasResult = client.execute(LocalJMeterWorkerRequest.discoverComponents(
                fixtures.localHome(), "menu_generative_controller"));

        assertThat(publicResult.response().success()).as(publicResult.response().toJsonLine()).isTrue();
        assertThat(aliasResult.response().success()).as(aliasResult.response().toJsonLine()).isTrue();
        assertThat(aliasResult.response().payload()).isEqualTo(publicResult.response().payload());
        assertThat(publicResult.response().payload()).contains(
                "category: sampler",
                "component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui");
        assertThat(publicResult.response().payload()).doesNotContain("menu_generative_controller");
    }

    @Test
    void workerReturnsOneEmptyBlockForKnownCategoryAndAggregatesUnmappedComponentsUnderLocal()
            throws Exception {
        Path emptyHome = Paths.get("build", "qa", "task-5-empty-home").toAbsolutePath().normalize();
        Path localHome = Paths.get("build", "qa", "task-5-local-home").toAbsolutePath().normalize();
        Map<Path, List<ComponentCatalog.ComponentDefinition>> definitions = definitionCache();
        Map<Path, Map<String, String>> categoryLabels = categoryLabelCache();
        definitions.put(emptyHome, Collections.<ComponentCatalog.ComponentDefinition>emptyList());
        definitions.put(localHome, Collections.singletonList(definition(
                "local", "Local Components", "example.PluginGui")));
        categoryLabels.put(emptyHome, fallbackCategoryLabels());
        categoryLabels.put(localHome, fallbackCategoryLabels());
        try {
            String empty = LocalJMeterWorkerComponents.discoverComponents(
                    LocalJMeterWorkerRequest.discoverComponents(emptyHome, "timer"));
            String local = LocalJMeterWorkerComponents.discoverComponents(
                    LocalJMeterWorkerRequest.discoverComponents(localHome, "local"));

            assertThat(empty).contains(
                    "categories:", "category: timer", "label: Timers", "components: []");
            assertThat(empty).doesNotContain("component:");
            assertThat(local).contains(
                    "category: local", "label: Local Components", "component: example.PluginGui");
        } finally {
            definitions.remove(emptyHome);
            definitions.remove(localHome);
            categoryLabels.remove(emptyHome);
            categoryLabels.remove(localHome);
        }
    }

    @Test
    void selectedZhCnLocaleLabelsKnownEmptyCategoryInCategoriesAndFilteredComponents() throws Exception {
        Path emptyHome = Paths.get("build", "qa", "task-3-zh-empty-home").toAbsolutePath().normalize();
        Map<Path, List<ComponentCatalog.ComponentDefinition>> definitions = definitionCache();
        Map<Path, Map<String, String>> categoryLabels = categoryLabelCache();
        Locale previousLocale = JMeterUtils.getLocale();
        Object previousResources = staticField(JMeterUtils.class, "resources").get(null);
        boolean previousIgnoreResources = staticField(JMeterUtils.class, "ignoreResources").getBoolean(null);
        definitions.put(emptyHome, Collections.<ComponentCatalog.ComponentDefinition>emptyList());
        JMeterUtils.setLocale(Locale.SIMPLIFIED_CHINESE);
        Map<String, String> selectedLabels = new LinkedHashMap<>();
        for (String rawGroup : LocalComponentCategoryCatalog.knownRawGroups()) {
            selectedLabels.put(rawGroup, JMeterUtils.getResString(rawGroup));
        }
        categoryLabels.put(emptyHome, selectedLabels);
        try {
            String categories = LocalJMeterWorkerComponents.listCategories(
                    LocalJMeterWorkerRequest.listCategories(emptyHome));
            String filtered = LocalJMeterWorkerComponents.discoverComponents(
                    LocalJMeterWorkerRequest.discoverComponents(emptyHome, "timer"));

            assertThat(categories).contains(
                    "category: timer", "label: 定时器", "component_count: 0");
            assertThat(filtered).contains(
                    "category: timer", "label: 定时器", "components: []");
            assertThat(categories).doesNotContain("label: Timers", "[res_key=");
            assertThat(filtered).doesNotContain("label: Timers", "[res_key=", "fallback");
        } finally {
            definitions.remove(emptyHome);
            categoryLabels.remove(emptyHome);
            staticField(JMeterUtils.class, "locale").set(null, previousLocale);
            staticField(JMeterUtils.class, "resources").set(null, previousResources);
            staticField(JMeterUtils.class, "ignoreResources").setBoolean(null, previousIgnoreResources);
        }
    }

    @Test
    void workerMapsUnknownCategoriesToTypedUsageErrorsWithoutRuntimeJargon() throws IOException {
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

        for (String category : new String[] {
                "menu_plugin_x", "not-a-category", " sampler ", "../sampler"}) {
            LocalJMeterWorkerResponse response = client.execute(LocalJMeterWorkerRequest.discoverComponents(
                    fixtures.localHome(), category)).response();

            assertThat(response.success()).as(response.toJsonLine()).isFalse();
            assertThat(response.errorCode()).isEqualTo("USAGE_ERROR");
            assertThat(response.category()).isEqualTo("usage");
            assertThat(response.suggestedAction()).isEqualTo(
                    "rerun categories ls to list valid category ids, then retry components --category.");
            assertThat(response.message()).contains("Unknown component category: " + category);
            assertThat(response.message().toLowerCase()).doesNotContain(
                    "cause chain", "runtime menu", "unavailable");
        }
    }

    @Test
    void workerCatchPathKeepsRuntimeContextForUnrelatedFailure() throws IOException {
        LocalJMeterWorkerClient client = LocalJMeterWorkerClient.withTimeouts(
                Duration.ofSeconds(15), Duration.ofSeconds(60), Duration.ofSeconds(60));

        LocalJMeterWorkerResponse response = client.execute(LocalJMeterWorkerRequest.componentDetails(
                fixtures.localHome(), "not.supported")).response();

        assertThat(response.success()).as(response.toJsonLine()).isFalse();
        assertThat(response.errorCode()).isEqualTo("COMPONENT_IDENTITY_NOT_FOUND");
        assertThat(response.message()).contains(
                "Phase: componentDetails",
                "Parent: unavailable",
                "JMeter home:");
    }

    @Test
    void workerResponsePreservesTypedUnknownMessageAcrossRecoveryActionPresentation() {
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.discoverComponents(
                Paths.get("build", "qa", "task-5-response-home"), "menu_plugin_x");
        String message = "Unknown component category: menu_plugin_x (localized detail)";
        String contractAction =
                "rerun categories ls to list valid category ids, then retry components --category.";
        String localizedAction =
                "relancez categories ls, puis réessayez components --category.";

        LocalJMeterWorkerResponse contract = LocalJMeterWorkerResponse.fromJsonLine(
                LocalJMeterWorkerResponse.failure(
                        request, "USAGE_ERROR", "usage", null, message, contractAction, "", "")
                        .toJsonLine());
        LocalJMeterWorkerResponse localized = LocalJMeterWorkerResponse.fromJsonLine(
                LocalJMeterWorkerResponse.failure(
                        request, "USAGE_ERROR", "usage", null, message, localizedAction, "", "")
                        .toJsonLine());

        assertThat(contract.errorCode()).isEqualTo("USAGE_ERROR");
        assertThat(contract.category()).isEqualTo("usage");
        assertThat(contract.message()).isEqualTo(message);
        assertThat(contract.suggestedAction()).isEqualTo(contractAction);
        assertThat(localized.errorCode()).isEqualTo("USAGE_ERROR");
        assertThat(localized.category()).isEqualTo("usage");
        assertThat(localized.message()).isEqualTo(message);
        assertThat(localized.suggestedAction()).isEqualTo(localizedAction);
    }

    @Test
    void workerResponseDoesNotInferUnknownCategoryFromSharedRecoveryAction() {
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.discoverComponents(
                Paths.get("build", "qa", "task-5-response-home"), "collection-row");
        String message = "Registered collection row is invalid.";

        LocalJMeterWorkerResponse response = LocalJMeterWorkerResponse.fromJsonLine(
                LocalJMeterWorkerResponse.failure(
                        request,
                        "USAGE_ERROR",
                        "usage",
                        null,
                        message,
                        "rerun categories ls to list valid category ids, then retry components --category.",
                        "",
                        "")
                        .toJsonLine());

        assertThat(response.errorCode()).isEqualTo("USAGE_ERROR");
        assertThat(response.category()).isEqualTo("usage");
        assertThat(response.message()).isEqualTo(message);
    }

    @SuppressWarnings("unchecked")
    private static Map<Path, List<ComponentCatalog.ComponentDefinition>> definitionCache() throws Exception {
        Field field = LocalJMeterWorkerComponents.class.getDeclaredField("DEFINITIONS");
        field.setAccessible(true);
        return (Map<Path, List<ComponentCatalog.ComponentDefinition>>) field.get(null);
    }

    private static Field staticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<Path, Map<String, String>> categoryLabelCache() throws Exception {
        Field field = LocalJMeterWorkerComponents.class.getDeclaredField("CATEGORY_LABELS");
        field.setAccessible(true);
        return (Map<Path, Map<String, String>>) field.get(null);
    }

    private static Map<String, String> fallbackCategoryLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String rawGroup : LocalComponentCategoryCatalog.knownRawGroups()) {
            labels.put(rawGroup, LocalComponentCategoryCatalog.fallbackLabel(rawGroup).get());
        }
        return labels;
    }

    private static ComponentCatalog.ComponentDefinition definition(
            String category, String categoryLabel, String component) {
        return new ComponentCatalog.ComponentDefinition(
                category, categoryLabel, component, component,
                Collections.<ComponentCatalog.ComponentProperty>emptyList(), component, "GUI_COMPONENT");
    }

}
