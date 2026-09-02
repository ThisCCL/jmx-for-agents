package io.github.thisccl.j4a.mcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpPropertyGraphSchema {
    private static final List<String> GRAPH_TYPES = Arrays.asList(
            "string", "boolean", "int", "long", "float", "double", "null",
            "collection", "map", "element", "opaque");

    private McpPropertyGraphSchema() {
    }

    static void applyTo(Map<String, Object> inputSchema) {
        inputSchema.put("$defs", definitions());
        applyValueConstraints(inputSchema);
    }

    private static Map<String, Object> definitions() {
        Map<String, Object> definitions = new LinkedHashMap<String, Object>();
        List<Object> recursiveAlternatives = new ArrayList<Object>();
        for (String type : GRAPH_TYPES) {
            definitions.put(type + "Value", graphValue(type, false));
            recursiveAlternatives.add(graphValue(type, true));
        }
        Map<String, Object> recursive = new LinkedHashMap<String, Object>();
        recursive.put("oneOf", recursiveAlternatives);
        definitions.put("recursiveValue", recursive);
        definitions.put("mapKey", mapKeySchema());
        definitions.put("scalarAddress", scalarAddressSchema());
        definitions.put("rowsValue", rowsValueSchema());
        definitions.put("propertyDocument", propertyDocumentSchema());
        return definitions;
    }

    @SuppressWarnings("unchecked")
    private static void applyValueConstraints(Map<String, Object> inputSchema) {
        List<Object> allOf = (List<Object>) inputSchema.get("allOf");
        for (String type : Arrays.asList(
                "string", "boolean", "int", "long", "float", "double", "null", "raw",
                "collection", "map", "element", "rows", "opaque")) {
            Map<String, Object> condition = new LinkedHashMap<String, Object>();
            condition.put("if", propertyConst("type", type));
            Map<String, Object> thenProperties = new LinkedHashMap<String, Object>();
            thenProperties.put("value", topLevelValue(type));
            Map<String, Object> then = new LinkedHashMap<String, Object>();
            then.put("properties", thenProperties);
            condition.put("then", then);
            allOf.add(condition);
        }
    }

    private static Map<String, Object> topLevelValue(String type) {
        if ("string".equals(type) || "raw".equals(type)) return typeSchema("string");
        if ("boolean".equals(type)) return typeSchema("boolean");
        if ("int".equals(type) || "long".equals(type)) return typeSchema("integer");
        if ("float".equals(type) || "double".equals(type)) return typeSchema("number");
        if ("null".equals(type)) return typeSchema("null");
        if ("rows".equals(type)) return ref("#/$defs/rowsValue");
        return ref("#/$defs/" + type + "Value");
    }

    private static Map<String, Object> graphValue(String type, boolean recursive) {
        Map<String, Object> schema = typeSchema("object");
        schema.put("additionalProperties", Boolean.FALSE);
        List<String> required = new ArrayList<String>();
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        if (recursive) {
            required.add("type");
            properties.put("type", constSchema(type));
        }
        required.add("presence");
        required.add("property_class");
        properties.put("presence", enumSchema("present", "absent"));
        properties.put("property_class", typeSchema("string"));
        List<String> payloadFields = payload(type, properties);
        schema.put("required", required);
        schema.put("properties", properties);
        if (!payloadFields.isEmpty()) schema.put("allOf", Arrays.<Object>asList(presenceConstraint(payloadFields)));
        return schema;
    }

    private static Map<String, Object> presenceConstraint(List<String> payloadFields) {
        Map<String, Object> then = new LinkedHashMap<String, Object>();
        then.put("required", payloadFields);
        List<Object> forbidden = new ArrayList<Object>();
        for (String field : payloadFields) forbidden.add(requiredProperties(field));
        Map<String, Object> anyOf = new LinkedHashMap<String, Object>();
        anyOf.put("anyOf", forbidden);
        Map<String, Object> otherwise = new LinkedHashMap<String, Object>();
        otherwise.put("not", anyOf);
        Map<String, Object> condition = new LinkedHashMap<String, Object>();
        condition.put("if", propertyConst("presence", "present"));
        condition.put("then", then);
        condition.put("else", otherwise);
        return condition;
    }

    private static List<String> payload(String type, Map<String, Object> properties) {
        if (Arrays.asList("string", "boolean", "int", "long", "float", "double", "null").contains(type)) {
            properties.put("value", topLevelValue(type));
            return Arrays.asList("value");
        }
        if ("collection".equals(type)) {
            properties.put("items", arrayOf(ref("#/$defs/recursiveValue")));
            return Arrays.asList("items");
        }
        if ("map".equals(type)) {
            Map<String, Object> entry = typeSchema("object");
            entry.put("additionalProperties", Boolean.FALSE);
            entry.put("required", Arrays.asList("key", "value"));
            Map<String, Object> entryProperties = new LinkedHashMap<String, Object>();
            entryProperties.put("key", ref("#/$defs/mapKey"));
            entryProperties.put("value", ref("#/$defs/recursiveValue"));
            entry.put("properties", entryProperties);
            properties.put("entries", arrayOf(entry));
            return Arrays.asList("entries");
        }
        if ("element".equals(type)) {
            properties.put("element_class", typeSchema("string"));
            properties.put("properties", arrayOf(ref("#/$defs/propertyDocument")));
            return Arrays.asList("element_class", "properties");
        }
        properties.put("format", constSchema("jmeter-save-element-xml-v1"));
        properties.put("base_digest", stringPattern("^[0-9a-f]{64}$"));
        properties.put("outer_property_class", typeSchema("string"));
        properties.put("runtime_fingerprint", typeSchema("string"));
        properties.put("payload", typeSchema("string"));
        return Arrays.asList("format", "base_digest", "outer_property_class", "runtime_fingerprint", "payload");
    }

    private static Map<String, Object> rowsValueSchema() {
        Map<String, Object> scalar = new LinkedHashMap<String, Object>();
        scalar.put("oneOf", Arrays.<Object>asList(
                typeSchema("string"), typeSchema("boolean"), typeSchema("integer"),
                typeSchema("number"), typeSchema("null")));
        Map<String, Object> row = typeSchema("object");
        row.put("additionalProperties", scalar);
        Map<String, Object> rows = arrayOf(row);
        Map<String, Object> wrapped = typeSchema("object");
        wrapped.put("additionalProperties", Boolean.FALSE);
        wrapped.put("required", Arrays.asList("rows"));
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("row_type", typeSchema("string"));
        fields.put("rows", rows);
        wrapped.put("properties", fields);
        Map<String, Object> alternatives = new LinkedHashMap<String, Object>();
        alternatives.put("oneOf", Arrays.<Object>asList(rows, wrapped));
        return alternatives;
    }

    private static Map<String, Object> scalarAddressSchema() {
        List<Object> segmentTypes = new ArrayList<Object>();
        segmentTypes.add(typeSchema("string"));
        Map<String, Object> index = typeSchema("integer");
        index.put("minimum", Integer.valueOf(0));
        index.put("maximum", Integer.valueOf(Integer.MAX_VALUE));
        segmentTypes.add(index);
        Map<String, Object> segment = new LinkedHashMap<String, Object>();
        segment.put("oneOf", segmentTypes);
        Map<String, Object> address = arrayOf(segment);
        address.put("minItems", Integer.valueOf(1));
        return address;
    }

    private static Map<String, Object> propertyDocumentSchema() {
        Map<String, Object> schema = typeSchema("object");
        schema.put("additionalProperties", Boolean.FALSE);
        schema.put("required", Arrays.asList("property", "type", "value"));
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("property", ref("#/$defs/scalarAddress"));
        properties.put("type", enumSchema(
                "string", "boolean", "int", "long", "float", "double", "null", "raw",
                "collection", "map", "element", "rows", "opaque"));
        properties.put("value", new LinkedHashMap<String, Object>());
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> mapKeySchema() {
        Map<String, Object> schema = typeSchema("object");
        schema.put("additionalProperties", Boolean.FALSE);
        schema.put("required", Arrays.asList("type", "value"));
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("type", enumSchema("string", "boolean", "int", "long", "float", "double"));
        properties.put("value", new LinkedHashMap<String, Object>());
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> propertyConst(String property, String value) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put(property, constSchema(value));
        Map<String, Object> schema = requiredProperties(property);
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> requiredProperties(String name) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("required", Arrays.asList(name));
        return schema;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> item) {
        Map<String, Object> schema = typeSchema("array");
        schema.put("items", item);
        return schema;
    }

    private static Map<String, Object> typeSchema(String type) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", type);
        return schema;
    }

    private static Map<String, Object> enumSchema(String... values) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("enum", Arrays.asList(values));
        return schema;
    }

    private static Map<String, Object> constSchema(String value) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("const", value);
        return schema;
    }

    private static Map<String, Object> stringPattern(String pattern) {
        Map<String, Object> schema = typeSchema("string");
        schema.put("pattern", pattern);
        return schema;
    }

    private static Map<String, Object> ref(String value) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("$ref", value);
        return schema;
    }
}
