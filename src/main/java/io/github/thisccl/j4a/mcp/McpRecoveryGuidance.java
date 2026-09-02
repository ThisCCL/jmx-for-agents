package io.github.thisccl.j4a.mcp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class McpRecoveryGuidance {
    private McpRecoveryGuidance() {
    }

    static String nativeAction(String guidance) {
        if (guidance == null || guidance.trim().isEmpty()) {
            return "Call the read MCP tool with file and jmeter_home to inspect the JMX plan before the next MCP request.";
        }
        String lower = guidance.toLowerCase(Locale.ROOT);
        if (lower.contains(" call the read mcp tool with the corrected arguments.")) {
            return correctedArguments(guidance, lower, "read",
                    "file or path, jmeter_home, ref, depth, properties, or includeDisabledDetails");
        }
        if (lower.contains(" call the validate mcp tool with the corrected arguments.")) {
            return correctedArguments(guidance, lower, "validate", "file or path and jmeter_home");
        }
        if (lower.contains(" call the components mcp tool with the corrected arguments.")) {
            return correctedArguments(guidance, lower, "components",
                    "jmeter_home, category, component, kind, details, or diagnostics");
        }
        if (lower.contains(" call the categories mcp tool with the corrected arguments.")) {
            return correctedArguments(guidance, lower, "categories", "jmeter_home");
        }
        if (lower.contains(" call the apply mcp tool with the corrected arguments.")) {
            return correctedArguments(guidance, lower, "apply",
                    "file or path, patchYaml or patchFile, dryRun, out, override, forceOut, and jmeter_home");
        }
        if (lower.contains(" call the init mcp tool with the corrected arguments.")) {
            return correctedArguments(guidance, lower, "init", "out, forceOut, jmeter_home, name, and threadGroupName");
        }
        if (lower.contains(" call the set mcp tool with the corrected arguments.")) {
            return correctedArguments(guidance, lower, "set",
                    "file or path, locator, property, value, out, override, forceOut, and jmeter_home");
        }
        if (lower.contains("pass a readable .jmx file and configure a local jmeter home")) {
            return "Call the validate MCP tool with file and jmeter_home after selecting a readable JMX file.";
        }
        if (lower.contains("pass a valid patch and a safe output target")) {
            return "Call the apply MCP tool with file, patchYaml or patchFile, and one output mode: dryRun, out, or override.";
        }
        if (lower.contains("pass a writable output .jmx path")) {
            return "Call the init MCP tool with out and use forceOut only when replacing an existing output file.";
        }
        if (lower.contains("rerun read to refresh locators and property paths")) {
            return "Call the read MCP tool with file, jmeter_home, and properties to obtain current locator and property values, "
                    + "then call the set MCP tool with file, locator, property, value, and out or override.";
        }
        if (lower.contains("rerun read --help and use supported read flags")) {
            return "Call the read MCP tool with file and jmeter_home; use ref, depth, properties, or includeDisabledDetails when needed.";
        }
        if (lower.contains("rerun categories ls with")) {
            return "Call the categories MCP tool with jmeter_home to list category ids for the selected local JMeter home.";
        }
        if (lower.contains("rerun the command with supported options")) {
            return "Call the read MCP tool with file and jmeter_home to inspect the JMX plan before the next MCP request.";
        }
        if (lower.contains("dry-run") || lower.contains("force-out") || lower.contains("retry apply")) {
            return "Call the set or apply MCP tool with file and one output mode: out or override; "
                    + "apply may instead use dryRun with patchYaml or patchFile, and forceOut is valid only with out.";
        }
        if (lower.contains("categories ls")) {
            return "Call the categories MCP tool to list valid category ids, then call the components MCP tool "
                    + "with the selected category field.";
        }
        if (lower.contains("value_template") || lower.contains("focused read")) {
            return "Call the components MCP tool with component for value_template, or call the read MCP tool with file, "
                    + "jmeter_home, ref, and properties to copy an exact focused value.";
        }
        if (lower.contains("conforming jmeter runtime/home") || lower.contains("stateless cli")
                || lower.contains("fresh ref") || lower.contains("new refs")
                || lower.contains("rerun read") || lower.contains("read the same file")) {
            return "Call the read MCP tool with the same file and same JMeter home (jmeter_home) to obtain fresh refs, then "
                    + "use the returned ref field in a new MCP tool call.";
        }
        if (lower.contains("check that the file exists")) {
            return "Please check that the file exists and is readable, then call the read MCP tool with file and jmeter_home.";
        }
        return "Correct the reported condition, then call the read MCP tool with file and jmeter_home to inspect the JMX plan.";
    }

    static Map<String, Object> nextRead(McpInvocationContext context) {
        Map<String, Object> recovery = new LinkedHashMap<String, Object>();
        recovery.put("tool", "read");
        recovery.put("arguments", context.readArguments());
        return recovery;
    }

    static Map<String, Object> inspectMutationTarget(McpInvocationContext context) {
        Map<String, Object> recovery = new LinkedHashMap<String, Object>();
        recovery.put("tool", "read");
        recovery.put("arguments", context.mutationInspectionArguments());
        return recovery;
    }

    private static String correctedArguments(String guidance, String lower, String tool, String fields) {
        int actionStart = lower.indexOf(" call the " + tool + " mcp tool with the corrected arguments.");
        return guidance.substring(0, actionStart) + " Call the " + tool + " MCP tool with " + fields + ".";
    }
}
