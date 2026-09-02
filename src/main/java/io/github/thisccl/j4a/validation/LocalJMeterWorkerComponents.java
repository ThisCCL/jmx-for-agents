package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.components.ComponentCatalogRenderer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

final class LocalJMeterWorkerComponents {
    private static final java.util.Map<Path, List<ComponentCatalog.ComponentDefinition>> DEFINITIONS =
            new java.util.LinkedHashMap<>();
    private static final java.util.Map<Path, java.util.Map<String, String>> CATEGORY_LABELS =
            new java.util.LinkedHashMap<>();
    private static final java.util.Map<Path, LocalJMeterMenuRegistry> REGISTRIES =
            new java.util.LinkedHashMap<>();
    private static final LocalJMeterWorkerSemanticCache SEMANTIC_CACHE =
            new LocalJMeterWorkerSemanticCache();
    private static final ConcurrentMap<String, FutureTask<ComponentCatalog.ComponentDefinition>> DETAILS =
            new ConcurrentHashMap<String, FutureTask<ComponentCatalog.ComponentDefinition>>();
    private LocalJMeterWorkerComponents() {
    }

    static String discoverComponents(LocalJMeterWorkerRequest request) throws Exception {
        Path jmeterHome = Paths.get(request.jmeterHome());
        List<ComponentCatalog.ComponentDefinition> definitions = localDefinitions(jmeterHome);
        List<ComponentCatalog.ComponentCategory> categories =
                LocalComponentCategoryCatalog.categories(
                        definitions, localCategoryLabels(jmeterHome));
        ComponentCatalogRenderer renderer = new ComponentCatalogRenderer();
        if (request.category() != null) {
            Optional<String> category = LocalComponentCategoryCatalog.resolveCategoryId(
                    request.category(), definitions);
            if (!category.isPresent()) {
                throw new UnknownComponentCategoryException(request.category());
            }
            ComponentCatalog.ComponentCategory selectedCategory = findCategory(categories, category.get())
                    .orElseThrow(() -> new UnknownComponentCategoryException(request.category()));
            definitions = filterCategory(definitions, category.get());
            categories = Collections.singletonList(selectedCategory);
            if (request.details()) {
                return ComponentCategoryDetailsPage.render(
                        request, jmeterHome, category.get(), definitions,
                        LocalPropertyGraphRuntimeContext.selected(jmeterHome));
            }
        }
        return renderer.renderRuntimeList(categories, definitions);
    }

    static String componentDetails(LocalJMeterWorkerRequest request) throws Exception {
        Path jmeterHome = Paths.get(request.jmeterHome());
        List<ComponentCatalog.ComponentDefinition> definitions = localDefinitions(jmeterHome);
        io.github.thisccl.j4a.jmx.property.RuntimeContext runtimeContext =
                LocalPropertyGraphRuntimeContext.selected(jmeterHome);
        String component = request.componentToken() == null
                ? request.component()
                : ComponentDetailToken.resolve(
                        request.componentToken(), runtimeContext.fingerprint(), definitions);
        ComponentCatalog.ComponentDefinition definition = findLocalDefinition(definitions, component)
                .orElseThrow(() -> new ComponentIdentityNotFoundException(request.component()));
        return new ComponentCatalogRenderer().renderRuntimeComponent(
                discoverDetails(jmeterHome, definition, runtimeContext),
                request.diagnostics());
    }

    static String listCategories(LocalJMeterWorkerRequest request) throws Exception {
        Path jmeterHome = Paths.get(request.jmeterHome());
        return new ComponentCatalogRenderer().renderRuntimeCategories(
                LocalComponentCategoryCatalog.categories(
                        localDefinitions(jmeterHome), localCategoryLabels(jmeterHome)));
    }

    static java.util.Optional<ComponentCatalog.ComponentDefinition> findLocalDefinition(
            Path jmeterHome, String component) throws Exception {
        java.util.Optional<ComponentCatalog.ComponentDefinition> definition =
                findLocalDefinition(localDefinitions(jmeterHome), component);
        return definition.isPresent()
                ? java.util.Optional.of(discoverDetails(
                        jmeterHome, definition.get(), LocalPropertyGraphRuntimeContext.selected(jmeterHome)))
                : java.util.Optional.empty();
    }

    static LocalJMeterMenuRegistry.Entry requireLocalEntry(Path jmeterHome, String component) {
        return localRegistry(jmeterHome).resolve(component)
                .orElseThrow(() -> new ComponentIdentityNotFoundException(component));
    }

    static void prewarmRegistry(Path jmeterHome) {
        localRegistry(jmeterHome);
    }

    private static java.util.Optional<ComponentCatalog.ComponentDefinition> findLocalDefinition(
            List<ComponentCatalog.ComponentDefinition> definitions, String component) {
        List<ComponentCatalog.ComponentDefinition> matches = new ArrayList<>();
        for (ComponentCatalog.ComponentDefinition definition : definitions) {
            if (definition.component().equals(component)) {
                matches.add(definition);
            }
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(ambiguousComponentMessage(component, matches));
        }
        if (!matches.isEmpty()) {
            return java.util.Optional.of(matches.get(0));
        }
        return java.util.Optional.empty();
    }

    private static List<ComponentCatalog.ComponentDefinition> localDefinitions(Path jmeterHome) throws Exception {
        Path key = jmeterHome.toAbsolutePath().normalize();
        synchronized (DEFINITIONS) {
            List<ComponentCatalog.ComponentDefinition> cached = DEFINITIONS.get(key);
            if (cached != null) return cached;
        }
        LocalJMeterMenuRegistry registry = localRegistry(jmeterHome);
        List<ComponentCatalog.ComponentDefinition> discovered = LocalComponentDiscovery.discover(registry);
        synchronized (DEFINITIONS) {
            DEFINITIONS.put(key, discovered);
        }
        synchronized (CATEGORY_LABELS) {
            CATEGORY_LABELS.put(key, registry.categoryLabels());
        }
        return discovered;
    }

    private static java.util.Map<String, String> localCategoryLabels(Path jmeterHome) {
        Path key = jmeterHome.toAbsolutePath().normalize();
        synchronized (CATEGORY_LABELS) {
            java.util.Map<String, String> cached = CATEGORY_LABELS.get(key);
            if (cached != null) return cached;
        }
        java.util.Map<String, String> labels = localRegistry(jmeterHome).categoryLabels();
        synchronized (CATEGORY_LABELS) {
            CATEGORY_LABELS.put(key, labels);
        }
        return labels;
    }

    private static LocalJMeterMenuRegistry localRegistry(Path jmeterHome) {
        Path key = jmeterHome.toAbsolutePath().normalize();
        synchronized (REGISTRIES) {
            LocalJMeterMenuRegistry cached = REGISTRIES.get(key);
            if (cached != null) return cached;
            LocalJMeterMenuRegistry registry = LocalJMeterMenuRegistry.current();
            REGISTRIES.put(key, registry);
            return registry;
        }
    }

    static ComponentCatalog.ComponentDefinition discoverDetails(
            Path jmeterHome,
            ComponentCatalog.ComponentDefinition definition,
            io.github.thisccl.j4a.jmx.property.RuntimeContext runtimeContext) throws Exception {
        String key = jmeterHome.toAbsolutePath().normalize() + "\n" + definition.component();
        FutureTask<ComponentCatalog.ComponentDefinition> candidate =
                new FutureTask<ComponentCatalog.ComponentDefinition>(() -> LocalComponentDiscovery.withProperties(
                        definition, requireLocalEntry(jmeterHome, definition.component()), runtimeContext));
        FutureTask<ComponentCatalog.ComponentDefinition> selected = DETAILS.putIfAbsent(key, candidate);
        if (selected == null) {
            selected = candidate;
            candidate.run();
        }
        try {
            return selected.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        }
    }

    static LocalJMeterGuiSemanticMetadata.Observation semanticMetadata(
            LocalJMeterMenuRegistry.Entry entry,
            io.github.thisccl.j4a.jmx.property.RuntimeContext runtimeContext) {
        return SEMANTIC_CACHE.get(entry.menuClassName(), () -> {
            LocalJMeterGuiSemanticInstrumentation.observationAttempted();
            return LocalJMeterGuiSemanticCorrelation.observe(
                    entry.menuClassName(), org.apache.jmeter.util.JMeterUtils.getJMeterVersion(), runtimeContext);
        });
    }

    static int semanticCacheSize() {
        return SEMANTIC_CACHE.size();
    }

    static int detailCacheSize() {
        return DETAILS.size();
    }

    static void resetWorkerGenerationForTests() {
        SEMANTIC_CACHE.clear();
        DETAILS.clear();
        LocalJMeterGuiSemanticInstrumentation.reset();
    }

    private static List<ComponentCatalog.ComponentDefinition> filterCategory(
            List<ComponentCatalog.ComponentDefinition> definitions, String category) {
        List<ComponentCatalog.ComponentDefinition> selected = new ArrayList<>();
        for (ComponentCatalog.ComponentDefinition definition : definitions) {
            if (definition.category().equals(category)) {
                selected.add(definition);
            }
        }
        return selected;
    }

    private static Optional<ComponentCatalog.ComponentCategory> findCategory(
            List<ComponentCatalog.ComponentCategory> categories, String category) {
        for (ComponentCatalog.ComponentCategory candidate : categories) {
            if (candidate.category().equals(category)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String ambiguousComponentMessage(
            String component, List<ComponentCatalog.ComponentDefinition> definitions) {
        StringBuilder message = new StringBuilder("Ambiguous component: ").append(component).append(" matches ");
        for (int index = 0; index < definitions.size(); index++) {
            if (index > 0) {
                message.append(", ");
            }
            message.append(definitions.get(index).menuClassName());
        }
        return message.toString();
    }
}
