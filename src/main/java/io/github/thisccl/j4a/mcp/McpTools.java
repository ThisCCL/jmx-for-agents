package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.AgentGuidance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class McpTools {
    private static final String JMETER_HOME_DESCRIPTION = "Optional trusted local JMeter home override for this call; "
            + "otherwise the server uses JMX_AGENT_JMETER_HOME or JMETER_HOME. Its plugins run as the current OS user "
            + "without a sandbox. Restart the server after plugin/JAR, properties, or classpath changes; runtimes are "
            + "not hot-reloaded.";
    private static final List<String> TOOL_NAMES = Collections.unmodifiableList(Arrays.asList(
            "read", "validate", "components", "categories", "apply", "init", "set"));
    private static final Set<String> TOOL_NAME_SET = Collections.unmodifiableSet(new HashSet<String>(TOOL_NAMES));

    private McpTools() {}

    static List<Object> list() {
        List<Object> tools = new ArrayList<Object>();
        for (String name : TOOL_NAMES) {
            tools.add(tool(name));
        }
        return tools;
    }

    static boolean publicTool(String name) { return TOOL_NAME_SET.contains(name); }

    private static Map<String, Object> tool(String name) {
        Map<String, Object> tool = new LinkedHashMap<String, Object>(); tool.put("name", name);
        tool.put("description", McpToolDescriptions.description(name));
        Map<String, Object> inputSchema = schema(name);
        McpToolArgumentValidator.assertSchemaKeys(name, propertiesOf(inputSchema).keySet());
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    private static Map<String, Object> schema(String name) {
        Map<String, Object> inputSchema = new LinkedHashMap<String, Object>();
        inputSchema.put("type", "object");
        inputSchema.put("additionalProperties", Boolean.FALSE);
        if ("read".equals(name)) {
            requireFileOrPath(inputSchema);
            inputSchema.put("properties", properties(Arrays.asList(
                    property("file", "string", "Required unless path is supplied. JMX file path to inspect."),
                    property("path", "string", "Alias for file. Required unless file is supplied; points to the JMX file path."),
                    property("jmeter_home", "string", JMETER_HOME_DESCRIPTION),
                    McpToolArgumentValidator.readDepthSchema(property("depth", "integer",
                            "Maximum JMX tree depth to render before focusing with ref. Defaults to 1.")),
                    property("ref", "string", "Opaque component handle from MCP read output. ref_scope.source marks emitted read refs source-bound. After MCP_REF_NOT_FOUND, structuredContent.recovery describes one future read with original caller arguments; it is never automatic."),
                    McpToolArgumentValidator.readPropertiesSchema(property("properties", "string",
                            "Property rendering mode: " + AgentGuidance.mcpReadPropertyModes()
                                    + "; writable is the ordered reusable writable graph-capability subset of all.")),
                    McpToolArgumentValidator.trueMarkerSchema(property("includeDisabledDetails", "boolean",
                            "Include details for disabled JMX elements.")))));
            return inputSchema;
        }
        if ("validate".equals(name)) {
            requireFileOrPath(inputSchema);
            inputSchema.put("properties", properties(Arrays.asList(
                    property("file", "string", "Required unless path is supplied. JMX file path to validate."),
                    property("path", "string", "Alias for file. Required unless file is supplied; points to the JMX file path."),
                    property("jmeter_home", "string", JMETER_HOME_DESCRIPTION))));
            return inputSchema;
        }
        if ("components".equals(name)) {
            inputSchema.put("required", Collections.emptyList());
            McpToolArgumentValidator.applyComponentSchema(inputSchema);
            inputSchema.put("properties", properties(Arrays.asList(
                    property("jmeter_home", "string", JMETER_HOME_DESCRIPTION),
                    property("category", "string", "Public category id in lower-kebab form, copied from the MCP categories tool and used to filter catalog results."),
                    property("component", "string", "Exact runtime FQCN component id to inspect in detail."),
                    property("kind", "string", "Input-only compatibility alias for component; canonical output still uses component."),
                    property("componentToken", "string", "Opaque signed recovery token from a COMPONENT_DETAIL_TOO_LARGE category entry. Exclusive with every component paging or diagnostics selector."),
                    McpToolArgumentValidator.trueMarkerSchema(property("details", "boolean",
                            "Use true with component or kind for one compact authoring detail, or with category for a bounded authoring-detail page.")),
                    McpToolArgumentValidator.trueMarkerSchema(property("diagnostics", "boolean",
                            "Use true with component or kind for the full capability projection. runtime_metadata_status reports metadata-source availability, not GUI completeness.")),
                    integerProperty("limit", "Maximum category detail entries per page; defaults to 20 and cannot exceed 50.", 1, 50, 20),
                    integerProperty("maxBytes", "Maximum UTF-8 bytes in canonical category-detail YAML; defaults to 16384 and accepts 4096 through 65536.", 4096, 65536, 16384),
                    property("cursor", "string", "Opaque continuation cursor returned by an earlier category detail page."))));
            return inputSchema;
        }
        if ("categories".equals(name)) {
            inputSchema.put("required", Collections.emptyList());
            inputSchema.put("properties", properties(Arrays.asList(
                    property("jmeter_home", "string", JMETER_HOME_DESCRIPTION))));
            return inputSchema;
        }
        if ("apply".equals(name)) {
            requireFileOrPath(inputSchema);
            requireExactlyOneGroup(inputSchema, "patchFile", "patchYaml");
            applyWriteModes(inputSchema);
            inputSchema.put("properties", properties(Arrays.asList(
                    property("file", "string", "Required unless path is supplied. JMX file path to patch."),
                    property("path", "string", "Alias for file. Required unless file is supplied; points to the JMX file path."),
                    property("patchFile", "string", "Required unless patchYaml is supplied. Use for a YAML patch that already exists on the MCP server filesystem or is intentionally retained for review, reuse, or version control. It uses the same YAML patch grammar and 4 MiB limit as patchYaml."),
                    property("patchYaml", "string", "Required unless patchFile is supplied. Preferred for an agent-generated one-off patch; no temporary file is needed. It uses the same YAML patch grammar and 4 MiB limit as patchFile."),
                    McpToolArgumentValidator.trueMarkerSchema(property("dryRun", "boolean",
                            "Preview without writing. May include out, which is ignored; cannot combine with override or forceOut.")),
                    applyOutProperty(),
                    McpToolArgumentValidator.trueMarkerSchema(property("override", "boolean",
                            "Select IN_PLACE mode to write back to the input file. Cannot combine with out, dryRun, or forceOut.")),
                    McpToolArgumentValidator.trueMarkerSchema(property("forceOut", "boolean",
                            "COPY only: overwrite an existing different-path output. Never authorizes out equal to input.")),
                    property("jmeter_home", "string", JMETER_HOME_DESCRIPTION))));
            return inputSchema;
        }
        if ("init".equals(name)) {
            inputSchema.put("required", Arrays.asList("out"));
            inputSchema.put("properties", properties(Arrays.asList(
                    property("out", "string", "Required destination output path for the new JMX file."),
                    McpToolArgumentValidator.trueMarkerSchema(property("forceOut", "boolean",
                            "Permit overwrite of an existing output target.")),
                    property("jmeter_home", "string", JMETER_HOME_DESCRIPTION),
                    property("name", "string", "Test Plan element name for the created JMX file."),
                    property("threadGroupName", "string", "Thread Group element name for the created JMX file."))));
            return inputSchema;
        }
        if ("set".equals(name)) {
            requireFileOrPath(inputSchema, "locator", "property", "value");
            requireExactlyOneGroup(inputSchema, "out", "override");
            inputSchema.put("properties", properties(Arrays.asList(
                    property("file", "string", "Required unless path is supplied. JMX file path to edit."),
                    property("path", "string", "Alias for file. Required unless file is supplied; points to the JMX file path."),
                    property("locator", "string", "Required opaque component handle copied from MCP read output. On MCP_REF_NOT_FOUND, structuredContent.recovery preserves original file/path spelling for one future read; no mutation is replayed."),
                    scalarAddressInput(),
                    structuredSetValueProperty(),
                    setTypeProperty(),
                    property("out", "string", "Destination output path for the written JMX file."),
                    McpToolArgumentValidator.trueMarkerSchema(property("override", "boolean",
                            "Write changes back to the input file when in-place mutation is intentional.")),
                    McpToolArgumentValidator.trueMarkerSchema(property("forceOut", "boolean",
                            "Only meaningful with out: permits overwrite of a distinct existing output; never authorizes the input path.")),
                    property("jmeter_home", "string", JMETER_HOME_DESCRIPTION))));
            McpPropertyGraphSchema.applyTo(inputSchema);
            return inputSchema;
        }
        inputSchema.put("required", Collections.emptyList());
        inputSchema.put("properties", new LinkedHashMap<String, Object>());
        return inputSchema;
    }

    private static void requireFileOrPath(Map<String, Object> inputSchema, String... additionalRequired) {
        inputSchema.put("required", Arrays.asList(additionalRequired));
        requireExactlyOneGroup(inputSchema, "file", "path");
    }

    @SuppressWarnings("unchecked")
    private static void requireExactlyOneGroup(Map<String, Object> inputSchema, String first, String second) {
        List<Object> allOf = (List<Object>) inputSchema.get("allOf");
        if (allOf == null) {
            allOf = new ArrayList<Object>();
            inputSchema.put("allOf", allOf);
        }
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        List<Object> oneOf = new ArrayList<Object>();
        oneOf.add(requiredProperties(first));
        oneOf.add(requiredProperties(second));
        group.put("oneOf", oneOf);
        allOf.add(group);
    }

    @SuppressWarnings("unchecked")
    private static void applyWriteModes(Map<String, Object> inputSchema) {
        List<Object> allOf = (List<Object>) inputSchema.get("allOf");
        if (allOf == null) {
            allOf = new ArrayList<Object>();
            inputSchema.put("allOf", allOf);
        }
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        List<Object> oneOf = new ArrayList<Object>();
        oneOf.add(modeSchema("dryRun", "override", "forceOut"));
        oneOf.add(modeSchema("override", "dryRun", "out", "forceOut"));
        oneOf.add(modeSchema("out", "dryRun", "override"));
        group.put("oneOf", oneOf);
        allOf.add(group);
    }

    private static Map<String, Object> modeSchema(String requiredTrue, String... forbidden) {
        Map<String, Object> schema = requiredProperties(requiredTrue);
        if (!"out".equals(requiredTrue)) {
            Map<String, Object> properties = new LinkedHashMap<String, Object>();
            Map<String, Object> trueValue = new LinkedHashMap<String, Object>();
            trueValue.put("const", Boolean.TRUE);
            properties.put(requiredTrue, trueValue);
            schema.put("properties", properties);
        }
        List<Object> allOf = new ArrayList<Object>();
        for (String name : forbidden) {
            Map<String, Object> forbiddenSchema = requiredProperties(name);
            if (!"out".equals(name)) {
                Map<String, Object> forbiddenProperties = new LinkedHashMap<String, Object>();
                Map<String, Object> forbiddenTrue = new LinkedHashMap<String, Object>();
                forbiddenTrue.put("const", Boolean.TRUE);
                forbiddenProperties.put(name, forbiddenTrue);
                forbiddenSchema.put("properties", forbiddenProperties);
            }
            Map<String, Object> not = new LinkedHashMap<String, Object>();
            not.put("not", forbiddenSchema);
            allOf.add(not);
        }
        schema.put("allOf", allOf);
        return schema;
    }

    private static Map<String, Object> requiredProperties(String name) { Map<String, Object> schema = new LinkedHashMap<String, Object>(); schema.put("required", Arrays.asList(name)); return schema; }

    private static Map<String, Object> properties(List<Map<String, Object>> propertyMaps) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        for (Map<String, Object> property : propertyMaps) {
            properties.put(String.valueOf(property.remove("name")), property);
        }
        return properties;
    }

    private static Map<String, Object> property(String name, String type, String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("name", name);
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> integerProperty(
            String name, String description, int minimum, int maximum, int defaultValue) {
        Map<String, Object> property = property(name, "integer", description);
        property.put("minimum", Integer.valueOf(minimum));
        property.put("maximum", Integer.valueOf(maximum));
        property.put("default", Integer.valueOf(defaultValue));
        return property;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(Map<String, Object> inputSchema) { return (Map<String, Object>) inputSchema.get("properties"); }

    private static Map<String, Object> applyOutProperty() {
        Map<String, Object> property = property("out", "string",
                "Non-blank destination output path for COPY mode. Must differ from input; ignored only in DRY_RUN mode.");
        property.put("minLength", Integer.valueOf(1));
        property.put("pattern", ".*\\S.*");
        return property;
    }

    private static Map<String, Object> structuredSetValueProperty() {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("name", "value");
        property.put("description", "Required native scalar, object, or list value. Structured values require an explicit type. Generic collection/map/element/opaque values copy the exact focused-read envelope; rows accepts either a bare row list or {row_type, rows}.");
        List<Object> alternatives = new ArrayList<Object>();
        for (String type : Arrays.asList("string", "number", "boolean", "object", "array")) {
            Map<String, Object> alternative = new LinkedHashMap<String, Object>();
            alternative.put("type", type);
            alternatives.add(alternative);
        }
        property.put("oneOf", alternatives);
        return property;
    }

    private static String setTypeDescription() {
        return "Exact value type: string, boolean, int, long, float, double, null, raw, collection, map, element, rows, or opaque. Copy structured type/value fragments from focused read unchanged; row_type is an optional target-bound assertion, never class authorization.";
    }

    private static Map<String, Object> setTypeProperty() {
        Map<String, Object> property = property("type", "string", setTypeDescription());
        property.put("enum", Arrays.asList(
                "string", "boolean", "int", "long", "float", "double", "null", "raw",
                "collection", "map", "element", "rows", "opaque"));
        return property;
    }

    private static Map<String, Object> scalarAddressInput() {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("name", "property");
        property.put("$ref", "#/$defs/scalarAddress");
        property.put("description", "Required non-empty native array copied from read output. Each segment is a string property/map key or an integer collection index from 0 through 2147483647.");
        return property;
    }
}
