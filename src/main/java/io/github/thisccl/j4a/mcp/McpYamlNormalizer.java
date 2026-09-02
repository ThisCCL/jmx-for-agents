package io.github.thisccl.j4a.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class McpYamlNormalizer {
    private McpYamlNormalizer() {
    }

    static Map<String, Object> parseMapping(String yamlText) {
        Object document = parseValue(yamlText);
        if (!(document instanceof Map)) {
            throw new IllegalArgumentException("MCP YAML output must be a mapping document.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> mapping = (Map<String, Object>) document;
        return mapping;
    }

    static Object parseValue(String yamlText) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(100);
        options.setCodePointLimit(McpStdioServer.MAX_JSON_RPC_LINE_BYTES);
        Iterable<Object> documents = new Yaml(new SafeConstructor(options)).loadAll(yamlText);
        java.util.Iterator<Object> iterator = documents.iterator();
        if (!iterator.hasNext()) {
            throw new IllegalArgumentException("MCP YAML output must contain one mapping document.");
        }
        Object document = normalize(iterator.next(), "$");
        if (iterator.hasNext()) {
            throw new IllegalArgumentException("MCP YAML output must contain exactly one document.");
        }
        return document;
    }

    private static Object normalize(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            if (value instanceof Double && !Double.isFinite(((Double) value).doubleValue())) {
                throw new IllegalArgumentException("MCP YAML output contains a non-finite number at " + path + ".");
            }
            if (value instanceof Float && !Float.isFinite(((Float) value).floatValue())) {
                throw new IllegalArgumentException("MCP YAML output contains a non-finite number at " + path + ".");
            }
            return value;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> normalized = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("MCP YAML output has a non-string field at " + path + ".");
                }
                String key = (String) entry.getKey();
                normalized.put(key, normalize(entry.getValue(), path + "." + key));
            }
            return normalized;
        }
        if (value instanceof List<?>) {
            List<Object> normalized = new ArrayList<Object>();
            int index = 0;
            for (Object item : (List<?>) value) {
                normalized.add(normalize(item, path + "[" + index + "]"));
                index++;
            }
            return normalized;
        }
        throw new IllegalArgumentException(
                "MCP YAML output contains unsupported data at " + path + ": " + value.getClass().getName() + ".");
    }
}
