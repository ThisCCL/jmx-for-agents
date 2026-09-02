package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.components.ComponentCatalog;
import io.github.thisccl.j4a.components.ComponentCatalogRenderer;
import io.github.thisccl.j4a.jmx.property.RuntimeContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.yaml.snakeyaml.Yaml;

final class ComponentCategoryDetailsPage {
    static final int DEFAULT_MAX_BYTES = 16384;

    private ComponentCategoryDetailsPage() {
    }

    static String render(
            LocalJMeterWorkerRequest request,
            Path jmeterHome,
            String category,
            List<ComponentCatalog.ComponentDefinition> definitions,
            RuntimeContext runtimeContext) {
        int limit = request.limit() == null ? 20 : Integer.parseInt(request.limit());
        int maxBytes = request.maxBytes() == null
                ? DEFAULT_MAX_BYTES : Integer.parseInt(request.maxBytes());
        String projection = ComponentCategoryCursor.PROJECTION;
        List<ComponentCatalog.ComponentDefinition> ordered = uniqueOrdered(definitions);
        String boundaryDigest = ComponentCategoryCursor.requireLastComponent(
                request.cursor(), runtimeContext.fingerprint(), category, projection, limit, maxBytes);
        int start = startIndex(ordered, boundaryDigest);
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        boolean partial = false;
        int nextIndex = start;
        ComponentCatalogRenderer renderer = new ComponentCatalogRenderer();
        Yaml yaml = new Yaml();

        while (nextIndex < ordered.size() && entries.size() < limit) {
            ComponentCatalog.ComponentDefinition definition = ordered.get(nextIndex);
            Map<String, Object> entry;
            boolean failed = false;
            try {
                ComponentCatalog.ComponentDefinition discovered =
                        LocalJMeterWorkerComponents.discoverDetails(jmeterHome, definition, runtimeContext);
                entry = mapping(yaml.load(renderer.renderRuntimeComponent(discovered, false)));
            } catch (Exception | LinkageError exception) {
                entry = failure(definition.component(), exception);
                failed = true;
            }

            entries.add(entry);
            String candidate = renderPage(yaml, category, projection, limit, maxBytes, entries,
                    partial || failed, nextIndex + 1, ordered, runtimeContext);
            if (utf8Bytes(candidate) <= maxBytes) {
                partial = partial || failed;
                nextIndex++;
                continue;
            }
            entries.remove(entries.size() - 1);
            if (!entries.isEmpty()) {
                break;
            }

            entries.add(oversized(ComponentDetailToken.encode(
                    runtimeContext.fingerprint(), category, projection, definition.component())));
            partial = true;
            nextIndex++;
            String recoveryPage = renderPage(yaml, category, projection, limit, maxBytes,
                    entries, true, nextIndex, ordered, runtimeContext);
            if (utf8Bytes(recoveryPage) > maxBytes) {
                throw new IllegalStateException("Bounded component recovery page exceeds max_bytes");
            }
        }

        return renderPage(yaml, category, projection, limit, maxBytes, entries,
                partial, nextIndex, ordered, runtimeContext);
    }

    private static String renderPage(
            Yaml yaml,
            String category,
            String projection,
            int limit,
            int maxBytes,
            List<Map<String, Object>> entries,
            boolean partial,
            int nextIndex,
            List<ComponentCatalog.ComponentDefinition> ordered,
            RuntimeContext runtimeContext) {
        Map<String, Object> page = new LinkedHashMap<String, Object>();
        page.put("category", category);
        page.put("projection", projection);
        page.put("limit", Integer.valueOf(limit));
        page.put("max_bytes", Integer.valueOf(maxBytes));
        page.put("components", new ArrayList<Map<String, Object>>(entries));
        page.put("partial", Boolean.valueOf(partial));
        if (nextIndex < ordered.size() && nextIndex > 0) {
            page.put("next_cursor", ComponentCategoryCursor.encode(
                    runtimeContext.fingerprint(), category, projection, limit, maxBytes,
                    ordered.get(nextIndex - 1).component()));
        }
        return yaml.dump(page);
    }

    private static List<ComponentCatalog.ComponentDefinition> uniqueOrdered(
            List<ComponentCatalog.ComponentDefinition> definitions) {
        Map<String, ComponentCatalog.ComponentDefinition> byComponent =
                new TreeMap<String, ComponentCatalog.ComponentDefinition>();
        for (ComponentCatalog.ComponentDefinition definition : definitions) {
            if (!byComponent.containsKey(definition.component())) {
                byComponent.put(definition.component(), definition);
            }
        }
        return new ArrayList<ComponentCatalog.ComponentDefinition>(byComponent.values());
    }

    private static int startIndex(
            List<ComponentCatalog.ComponentDefinition> definitions, String boundaryDigest) {
        if (boundaryDigest == null) {
            return 0;
        }
        int match = -1;
        for (int index = 0; index < definitions.size(); index++) {
            if (boundaryDigest.equals(ComponentCategoryCursor.componentDigest(
                    definitions.get(index).component()))) {
                if (match >= 0) {
                    throw new ComponentsCursorException(
                            "Components cursor boundary is not unique in this runtime");
                }
                match = index;
            }
        }
        if (match < 0) {
            throw new ComponentsCursorException(
                    "Components cursor boundary is unavailable in this runtime");
        }
        return match + 1;
    }

    private static Map<String, Object> oversized(String componentToken) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", "COMPONENT_DETAIL_TOO_LARGE");
        error.put("phase", "serialization");
        error.put("message", "Complete component detail exceeds max_bytes; recover it with componentToken.");
        entry.put("error", error);
        Map<String, Object> recovery = new LinkedHashMap<String, Object>();
        recovery.put("componentToken", componentToken);
        entry.put("recovery", recovery);
        return entry;
    }

    private static Map<String, Object> failure(String component, Throwable failure) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("component", component);
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", "COMPONENT_DETAIL_FAILED");
        error.put("phase", "materialization");
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = "Component detail discovery failed";
        }
        error.put("message", BoundedYamlMessage.scalar(message, 512));
        entry.put("error", error);
        return entry;
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }
}
