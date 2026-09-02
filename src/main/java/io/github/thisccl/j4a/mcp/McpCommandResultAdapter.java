package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.cli.CommandDiagnostic;
import io.github.thisccl.j4a.cli.CommandResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpCommandResultAdapter {
    Map<String, Object> invalidArguments(String message, String suggestedNextAction) {
        String recovery = McpRecoveryGuidance.nativeAction(suggestedNextAction);
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("isError", Boolean.TRUE);
        List<Object> content = new ArrayList<Object>();
        Map<String, Object> text = new LinkedHashMap<String, Object>();
        text.put("type", "text");
        text.put("text", message);
        content.add(text);
        output.put("content", content);
        Map<String, Object> structured = new LinkedHashMap<String, Object>();
        structured.put("exitStatus", Integer.valueOf(2));
        structured.put("data", new LinkedHashMap<String, Object>());
        List<Object> diagnostics = new ArrayList<Object>();
        Map<String, Object> diagnostic = new LinkedHashMap<String, Object>();
        diagnostic.put("code", "USAGE_ERROR");
        diagnostic.put("category", "usage");
        diagnostic.put("message", message);
        diagnostic.put("suggestedNextAction", recovery);
        structured.put("recoveryGuidance", recovery);
        diagnostics.add(diagnostic);
        structured.put("diagnostics", diagnostics);
        output.put("structuredContent", structured);
        return output;
    }

    public Map<String, Object> adapt(CommandResult result) {
        return adapt(result, McpInvocationContext.empty());
    }

    Map<String, Object> adapt(CommandResult result, McpInvocationContext context) {
        Map<String, Object> output = new LinkedHashMap<String, Object>();
        output.put("isError", Boolean.valueOf(result.exitCode() != 0));
        output.put("content", textContent(result));
        output.put("structuredContent", structuredContent(result, context));
        return output;
    }

    private static List<Object> textContent(CommandResult result) {
        List<Object> content = new ArrayList<Object>();
        Map<String, Object> text = new LinkedHashMap<String, Object>();
        text.put("type", "text");
        if (result.exitCode() == 0) {
            text.put("text", result.textOutput());
        } else {
            text.put("text", errorText(result));
        }
        content.add(text);
        return content;
    }

    private static Map<String, Object> structuredContent(CommandResult result, McpInvocationContext context) {
        boolean unavailableReference = unavailableReference(result);
        boolean fatalRuntimeFailure = fatalRuntimeFailure(result);
        Map<String, Object> structured = new LinkedHashMap<String, Object>();
        structured.put("exitStatus", Integer.valueOf(result.exitCode()));
        structured.put("data", normalizedData(result));
        Map<String, Object> referenceScope = referenceScope(result);
        if (referenceScope != null) {
            structured.put("ref_scope", referenceScope);
        }
        structured.put("diagnostics", diagnostics(result, unavailableReference && !fatalRuntimeFailure));
        if (result.recoveryGuidance() != null) {
            structured.put("recoveryGuidance", typedApplyFailure(result)
                    && (!unavailableReference || fatalRuntimeFailure)
                    ? result.recoveryGuidance()
                    : McpRecoveryGuidance.nativeAction(result.recoveryGuidance()));
        }
        if (unavailableReference && context.hasDocument()) {
            structured.put("recovery", McpRecoveryGuidance.nextRead(context));
        }
        if (fatalRuntimeFailure && context.hasDocument() && context.mutationTool()) {
            structured.put("recovery", McpRecoveryGuidance.inspectMutationTarget(context));
        }
        if (context.initTool() && outputFileExists(result)) {
            structured.put("recovery", context.initRecovery());
        }
        return structured;
    }

    private static boolean outputFileExists(CommandResult result) {
        for (CommandDiagnostic diagnostic : result.diagnostics()) {
            if ("OUTPUT_FILE_EXISTS".equals(diagnostic.code())) return true;
        }
        return false;
    }

    private static boolean unavailableReference(CommandResult result) {
        for (CommandDiagnostic diagnostic : result.diagnostics()) {
            if ("MCP_REF_NOT_FOUND".equals(diagnostic.code())) {
                return true;
            }
        }
        return false;
    }

    private static boolean fatalRuntimeFailure(CommandResult result) {
        for (CommandDiagnostic diagnostic : result.diagnostics()) {
            if (diagnostic.failureDiagnostic().isPresent()
                    && diagnostic.failureDiagnostic().get().failureClass()
                    == io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.FailureClass.FATAL) {
                return true;
            }
            if ("LOCAL_JMETER_RUNTIME_ERROR".equals(diagnostic.code())) {
                return true;
            }
        }
        return false;
    }

    private static boolean typedApplyFailure(CommandResult result) {
        for (CommandDiagnostic diagnostic : result.diagnostics()) {
            if (diagnostic.failureDiagnostic().isPresent()) return true;
        }
        return false;
    }

    private static Map<String, Object> referenceScope(CommandResult result) {
        if (result.exitCode() != 0) {
            return null;
        }
        Object command = result.structuredData().get("command");
        if ("read".equals(command)) {
            return referenceScope("source-bound", "none", "none");
        }
        if ("init".equals(command)) {
            return referenceScope("none", "target-bound", "none");
        }
        if ("set".equals(command)) {
            return referenceScope("none", "none", "none");
        }
        if ("apply".equals(command)) {
            Object writeMode = result.structuredData().get("writeMode");
            if ("in-place".equals(writeMode)) {
                return referenceScope("none", "none", "target-bound");
            }
            if ("copy".equals(writeMode)) {
                return referenceScope("source-bound", "none", "target-bound");
            }
            return referenceScope("none", "none", "none");
        }
        return null;
    }

    private static Map<String, Object> referenceScope(String source, String target, String created) {
        Map<String, Object> scope = new LinkedHashMap<String, Object>();
        scope.put("source", source);
        scope.put("target", target);
        scope.put("created", created);
        return scope;
    }

    private static Map<String, Object> normalizedData(CommandResult result) {
        if (result.exitCode() != 0 || !yamlDocument(result)) {
            return normalizedSetData(result);
        }
        return McpYamlNormalizer.parseMapping(result.textOutput());
    }

    private static Map<String, Object> normalizedSetData(CommandResult result) {
        if (!"set".equals(result.structuredData().get("command"))) {
            return result.structuredData();
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>(result.structuredData());
        Object changed = data.get("changedProperty");
        if (!(changed instanceof Map)) {
            return data;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> changedProperty = new LinkedHashMap<String, Object>((Map<String, Object>) changed);
        Object property = changedProperty.get("property");
        if (property instanceof String) {
            changedProperty.put("property", McpYamlNormalizer.parseValue((String) property));
        }
        data.put("changedProperty", changedProperty);
        return data;
    }

    private static boolean yamlDocument(CommandResult result) {
        Object command = result.structuredData().get("command");
        return "read".equals(command)
                || result.structuredData().containsKey("categories")
                || result.structuredData().containsKey("component");
    }

    private static List<Object> diagnostics(CommandResult result, boolean unavailableReference) {
        List<Object> diagnostics = new ArrayList<Object>();
        for (CommandDiagnostic diagnostic : result.diagnostics()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("code", normalizedDiagnosticCode(diagnostic));
            item.put("category", diagnostic.category());
            item.put("message", diagnostic.message());
            if (diagnostic.affectedFile() != null) {
                item.put("affectedFile", diagnostic.affectedFile().toString());
            }
            if (diagnostic.locator() != null) {
                item.put("locator", diagnostic.locator());
            }
            if (diagnostic.propertyPath() != null) {
                item.put("property", McpYamlNormalizer.parseValue(diagnostic.propertyPath()));
            }
            if (diagnostic.unresolvedSegment() != null) {
                item.put("unresolvedSegment", diagnostic.unresolvedSegment());
            }
            if (diagnostic.component() != null) {
                item.put("component", diagnostic.component());
            }
            if (diagnostic.suggestedNextAction() != null) {
                item.put("suggestedNextAction",
                        diagnostic.failureDiagnostic().isPresent() && !unavailableReference
                                ? diagnostic.suggestedNextAction()
                                : McpRecoveryGuidance.nativeAction(diagnostic.suggestedNextAction()));
            }
            if (diagnostic.failureDiagnostic().isPresent()) {
                io.github.thisccl.j4a.apply.ApplyFailureDiagnostic failure =
                        diagnostic.failureDiagnostic().get();
                item.put("failureClass", failure.failureClass().name().toLowerCase(java.util.Locale.ROOT));
                item.put("phase", failure.phase());
                if (failure.change().isPresent()) item.put("change", failure.change().get().toMap());
                List<Object> causes = new ArrayList<Object>();
                for (io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.SourceCause cause : failure.causes()) {
                    Map<String, Object> source = new LinkedHashMap<String, Object>();
                    source.put("type", cause.type()); source.put("message", cause.message()); causes.add(source);
                }
                item.put("causes", causes);
            }
            diagnostics.add(item);
        }
        return diagnostics;
    }

    private static String normalizedDiagnosticCode(CommandDiagnostic diagnostic) {
        return "PATCH_PARSE_ERROR".equals(diagnostic.code()) ? "USAGE_ERROR" : diagnostic.code();
    }

    private static String errorText(CommandResult result) {
        if (result.diagnostics().isEmpty()) {
            return "Command failed with exit status " + result.exitCode() + ".";
        }
        CommandDiagnostic diagnostic = result.diagnostics().get(0);
        StringBuilder builder = new StringBuilder();
        builder.append(diagnostic.message());
        if (diagnostic.failureDiagnostic().isPresent()
                && diagnostic.failureDiagnostic().get().change().isPresent()) {
            io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.Change change =
                    diagnostic.failureDiagnostic().get().change().get();
            builder.append(System.lineSeparator()).append("changes[")
                    .append(change.index()).append("] ").append(change.operation())
                    .append(" failed during ").append(diagnostic.failureDiagnostic().get().phase()).append('.');
        }
        if (diagnostic.suggestedNextAction() != null) {
            builder.append(System.lineSeparator())
                    .append("Suggested next action: ")
                    .append(diagnostic.failureDiagnostic().isPresent()
                            ? diagnostic.suggestedNextAction()
                            : McpRecoveryGuidance.nativeAction(diagnostic.suggestedNextAction()));
        }
        return builder.toString();
    }
}
