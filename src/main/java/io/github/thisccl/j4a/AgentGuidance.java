package io.github.thisccl.j4a;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public final class AgentGuidance {
    private static final String RESOURCE =
            "io/github/thisccl/j4a/guidance/agent-guidance.json";
    private static final Map<String, Object> VALUES = load();

    private AgentGuidance() {
    }

    public static String cliReadPropertyModes() {
        return modes("cli_read_properties");
    }

    public static String mcpReadPropertyModes() {
        return modes("mcp_read_properties");
    }

    public static String genericCollectionExample() {
        return string("generic_collection_example");
    }

    public static String semanticRowExample() {
        return string("semantic_row_example");
    }

    public static String recoveryTemplate(String name) {
        return String.valueOf(recoveryTemplates().get(name));
    }

    public static String mcpToolGuidance(String toolName) {
        if ("read".equals(toolName)) {
            return String.join("\n", readGuidance(), referenceGuidance(), staleRefGuidance());
        }
        if ("components".equals(toolName)) {
            return "runtime_metadata_status: metadata-source availability, not GUI completeness.";
        }
        if ("apply".equals(toolName) || "set".equals(toolName)) {
            return String.join("\n",
                    referenceGuidance(),
                    staleRefGuidance(),
                    "Generic: " + genericCollectionExample(),
                    "Semantic rows: " + semanticRowExample(),
                    "Invalid shape: " + recoveryTemplate("invalid_collection_shape"),
                    "Same path: " + recoveryTemplate("same_path_write"),
                    "Existing target: " + recoveryTemplate("existing_target"));
        }
        return "";
    }

    private static String readGuidance() {
        return "MCP properties: " + mcpReadPropertyModes()
                + "; writable is the graph-capability subset.";
    }

    private static String referenceGuidance() {
        return "ref_scope: source-bound|target-bound|none; emitted opaque refs only.";
    }

    private static String staleRefGuidance() {
        return String.join("\n",
                "MCP_REF_NOT_FOUND: structuredContent.recovery is one future read with original file/path spelling and explicit jmeter_home only; no automatic retry or mutation replay.",
                "Stale ref: " + recoveryTemplate("stale_mcp_ref"));
    }

    private static String modes(String key) {
        List<String> modes = new ArrayList<String>();
        for (Object value : list(key)) {
            modes.add(String.valueOf(value));
        }
        return String.join("|", modes);
    }

    private static String string(String key) {
        Object value = VALUES.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalStateException("Missing canonical agent guidance: " + key);
        }
        return (String) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(String key) {
        Object value = VALUES.get(key);
        if (!(value instanceof List)) {
            throw new IllegalStateException("Missing canonical agent guidance list: " + key);
        }
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> recoveryTemplates() {
        Object value = VALUES.get("recovery_templates");
        if (!(value instanceof Map)) {
            throw new IllegalStateException("Missing canonical recovery templates");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        InputStream input = AgentGuidance.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new IllegalStateException("Missing canonical agent guidance resource: " + RESOURCE);
        }
        try {
            Object parsed = new Yaml().load(input);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException("Invalid canonical agent guidance resource: " + RESOURCE);
            }
            return Collections.unmodifiableMap(
                    new LinkedHashMap<String, Object>((Map<String, Object>) parsed));
        } finally {
            try {
                input.close();
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Unable to close canonical agent guidance resource: " + RESOURCE, exception);
            }
        }
    }
}
