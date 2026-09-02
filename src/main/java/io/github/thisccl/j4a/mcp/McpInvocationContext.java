package io.github.thisccl.j4a.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

final class McpInvocationContext {
    private static final McpInvocationContext EMPTY =
            new McpInvocationContext(null, null, null, null, java.util.Collections.<String, Object>emptyMap());

    private final String documentKey;
    private final String documentValue;
    private final String jmeterHome;
    private final String toolName;
    private final Map<String, Object> originalArguments;

    private McpInvocationContext(String documentKey, String documentValue, String jmeterHome,
            String toolName, Map<String, Object> originalArguments) {
        this.documentKey = documentKey;
        this.documentValue = documentValue;
        this.jmeterHome = jmeterHome;
        this.toolName = toolName;
        this.originalArguments = originalArguments;
    }

    static McpInvocationContext empty() {
        return EMPTY;
    }

    static McpInvocationContext fromValidatedArguments(String toolName, Map<String, Object> arguments) {
        String documentKey = arguments.containsKey("file") ? "file" : arguments.containsKey("path") ? "path" : null;
        String documentValue = documentKey == null ? null : (String) arguments.get(documentKey);
        String jmeterHome = arguments.containsKey("jmeter_home") ? (String) arguments.get("jmeter_home") : null;
        return new McpInvocationContext(documentKey, documentValue, jmeterHome, toolName,
                new LinkedHashMap<String, Object>(arguments));
    }

    boolean hasDocument() {
        return documentKey != null;
    }

    Map<String, Object> readArguments() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put(documentKey, documentValue);
        if (jmeterHome != null) {
            arguments.put("jmeter_home", jmeterHome);
        }
        return arguments;
    }

    Map<String, Object> mutationInspectionArguments() {
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        Object output = originalArguments.get("out");
        if (output instanceof String && !((String) output).trim().isEmpty()) {
            arguments.put("file", output);
        } else {
            arguments.put(documentKey, documentValue);
        }
        if (jmeterHome != null) {
            arguments.put("jmeter_home", jmeterHome);
        }
        return arguments;
    }

    boolean mutationTool() {
        return "set".equals(toolName) || "apply".equals(toolName);
    }

    boolean initTool() {
        return "init".equals(toolName);
    }

    Map<String, Object> initRecovery() {
        Map<String, Object> overwriteArguments = new LinkedHashMap<String, Object>(originalArguments);
        overwriteArguments.put("forceOut", Boolean.TRUE);
        Map<String, Object> chooseArguments = new LinkedHashMap<String, Object>(originalArguments);
        chooseArguments.remove("forceOut");
        chooseArguments.put("out", "<different-output.jmx>");
        java.util.List<Object> choices = new java.util.ArrayList<Object>();
        choices.add(initChoice("overwrite", overwriteArguments));
        choices.add(initChoice("choose-output", chooseArguments));
        Map<String, Object> recovery = new LinkedHashMap<String, Object>();
        recovery.put("choices", choices);
        return recovery;
    }

    private static Map<String, Object> initChoice(String action, Map<String, Object> arguments) {
        Map<String, Object> choice = new LinkedHashMap<String, Object>();
        choice.put("action", action);
        choice.put("tool", "init");
        choice.put("arguments", arguments);
        return choice;
    }
}
