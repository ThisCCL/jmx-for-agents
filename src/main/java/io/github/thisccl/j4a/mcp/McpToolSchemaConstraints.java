package io.github.thisccl.j4a.mcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpToolSchemaConstraints {
    private McpToolSchemaConstraints() {
    }

    static Map<String, Object> readDepth(Map<String, Object> property) {
        property.put("minimum", Integer.valueOf(0));
        property.put("default", Integer.valueOf(1));
        return property;
    }

    static Map<String, Object> readProperties(Map<String, Object> property) {
        property.put("enum", Arrays.asList("none", "key", "all", "writable"));
        return property;
    }

    static Map<String, Object> trueMarker(Map<String, Object> property) {
        property.put("const", Boolean.TRUE);
        return property;
    }

    static void componentModes(Map<String, Object> inputSchema) {
        forbidTogether(inputSchema, "component", "kind");
        forbidTogether(inputSchema, "category", "component");
        forbidTogether(inputSchema, "category", "kind");
        forbidTogether(inputSchema, "category", "diagnostics");
        requireDetailSelectorWhenPresent(inputSchema);
        requireSelectorWhenPresent(inputSchema, "diagnostics");
        requireCategoryDetailsWhenPresent(inputSchema, "limit");
        requireCategoryDetailsWhenPresent(inputSchema, "maxBytes");
        requireCategoryDetailsWhenPresent(inputSchema, "cursor");
        for (String field : Arrays.asList(
                "component", "kind", "category", "details", "diagnostics",
                "limit", "maxBytes", "cursor")) {
            forbidTogether(inputSchema, "componentToken", field);
        }
    }

    private static void forbidTogether(Map<String, Object> inputSchema, String first, String second) {
        Map<String, Object> forbidden = new LinkedHashMap<String, Object>();
        forbidden.put("required", Arrays.asList(first, second));
        Map<String, Object> clause = new LinkedHashMap<String, Object>();
        clause.put("not", forbidden);
        allOf(inputSchema).add(clause);
    }

    private static void requireSelectorWhenPresent(Map<String, Object> inputSchema, String marker) {
        Map<String, Object> clause = new LinkedHashMap<String, Object>();
        clause.put("if", required(marker));
        Map<String, Object> then = new LinkedHashMap<String, Object>();
        then.put("oneOf", Arrays.<Object>asList(required("component"), required("kind")));
        clause.put("then", then);
        allOf(inputSchema).add(clause);
    }

    private static void requireDetailSelectorWhenPresent(Map<String, Object> inputSchema) {
        Map<String, Object> clause = new LinkedHashMap<String, Object>();
        clause.put("if", required("details"));
        Map<String, Object> then = new LinkedHashMap<String, Object>();
        then.put("oneOf", Arrays.<Object>asList(required("component"), required("kind"), required("category")));
        clause.put("then", then);
        allOf(inputSchema).add(clause);
    }

    private static void requireCategoryDetailsWhenPresent(Map<String, Object> inputSchema, String field) {
        Map<String, Object> clause = new LinkedHashMap<String, Object>();
        clause.put("if", required(field));
        Map<String, Object> then = new LinkedHashMap<String, Object>();
        then.put("required", Arrays.asList("category", "details"));
        clause.put("then", then);
        allOf(inputSchema).add(clause);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> allOf(Map<String, Object> inputSchema) {
        List<Object> allOf = (List<Object>) inputSchema.get("allOf");
        if (allOf == null) {
            allOf = new ArrayList<Object>();
            inputSchema.put("allOf", allOf);
        }
        return allOf;
    }

    private static Map<String, Object> required(String property) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("required", Arrays.asList(property));
        return schema;
    }
}
