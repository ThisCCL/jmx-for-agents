package io.github.thisccl.j4a.cli;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

final class ParsedCliError {
    private final Map<String, String> fields;
    private final String raw;

    private ParsedCliError(Map<String, String> fields, String raw) {
        this.fields = fields;
        this.raw = raw == null ? "" : raw;
    }

    static ParsedCliError parse(String diagnostics) {
        ParsedCliError structured = structured(diagnostics);
        if (structured != null) return structured;
        Map<String, String> fields = new LinkedHashMap<String, String>();
        StringBuilder message = new StringBuilder();
        List<String> lines = diagnostics == null
                ? Collections.<String>emptyList()
                : java.util.Arrays.asList(diagnostics.split("\\r?\\n"));
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (capturedField(key)) {
                    fields.put(key, value);
                    continue;
                }
            }
            if (!line.trim().isEmpty()) {
                if (message.length() > 0) {
                    message.append(System.lineSeparator());
                }
                message.append(line.trim());
            }
        }
        if (message.length() > 0) {
            fields.put("Message", message.toString());
        }
        return new ParsedCliError(fields, diagnostics);
    }

    private static ParsedCliError structured(String diagnostics) {
        if (diagnostics == null || !diagnostics.contains("\nrecovery:")) return null;
        try {
            Object parsed = new Yaml().load(diagnostics);
            if (!(parsed instanceof Map<?, ?>)) return null;
            Map<String, String> fields = new LinkedHashMap<String, String>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (capturedField(key) && entry.getValue() != null) {
                    fields.put(key, String.valueOf(entry.getValue()));
                }
            }
            return fields.containsKey("Error code") ? new ParsedCliError(fields, diagnostics) : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean capturedField(String key) {
        return "Error code".equals(key)
                || "Category".equals(key)
                || "Message".equals(key)
                || "Affected file".equals(key)
                || "Locator".equals(key)
                || "Property path".equals(key)
                || "Property address".equals(key)
                || "Unresolved segment".equals(key)
                || "Component".equals(key)
                || "Failure class".equals(key)
                || "Phase".equals(key)
                || "Change index".equals(key)
                || "Operation".equals(key)
                || "Change context".equals(key)
                || (key.startsWith("Source cause[") && key.endsWith("]"))
                || "Suggested next action".equals(key);
    }

    String code() {
        return fields.get("Error code");
    }

    String category() {
        return fields.get("Category");
    }

    String message() {
        String message = fields.get("Message");
        return message == null ? raw.trim() : message;
    }

    Path affectedFile() {
        String affectedFile = fields.get("Affected file");
        return affectedFile == null ? null : Paths.get(affectedFile);
    }

    String locator() {
        return fields.get("Locator");
    }

    String propertyPath() {
        String propertyPath = fields.get("Property path");
        return propertyPath == null ? fields.get("Property address") : propertyPath;
    }

    String unresolvedSegment() {
        return fields.get("Unresolved segment");
    }

    String component() {
        return fields.get("Component");
    }

    String suggestedNextAction() {
        return fields.get("Suggested next action");
    }

    io.github.thisccl.j4a.apply.ApplyFailureDiagnostic failureDiagnostic() {
        String failureClass = fields.get("Failure class");
        String phase = fields.get("Phase");
        if (failureClass == null || phase == null) return null;
        String rawIndex = fields.get("Change index");
        io.github.thisccl.j4a.apply.MutationChangeContext context = null;
        if (rawIndex != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) new Yaml().load(fields.get("Change context"));
            context = io.github.thisccl.j4a.apply.MutationChangeContext.fromMap(parsed);
        }
        java.util.List<io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.SourceCause> causes =
                new java.util.ArrayList<io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.SourceCause>();
        for (int index = 0; index < 4; index++) {
            String sourceCause = fields.get("Source cause[" + index + "]");
            if (sourceCause == null) continue;
            Object parsedCause = new Yaml().load(sourceCause);
            if (!(parsedCause instanceof Map<?, ?>)) continue;
            Map<?, ?> source = (Map<?, ?>) parsedCause;
            causes.add(new io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.SourceCause(
                    String.valueOf(source.get("type")), String.valueOf(source.get("message"))));
        }
        return new io.github.thisccl.j4a.apply.ApplyFailureDiagnostic(
                io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.FailureClass.valueOf(
                        failureClass.toUpperCase(java.util.Locale.ROOT)),
                phase, rawIndex == null ? null : Integer.valueOf(rawIndex),
                fields.get("Operation"), context, causes);
    }
}
