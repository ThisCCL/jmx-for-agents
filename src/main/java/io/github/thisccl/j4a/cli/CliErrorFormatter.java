package io.github.thisccl.j4a.cli;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

final class CliErrorFormatter {
    private CliErrorFormatter() {
    }

    static void print(CliError error, boolean debug, PrintStream stream) {
        stream.println("Error code: " + error.code());
        stream.println("Category: " + error.category());
        stream.println("Message: " + error.message());
        if (error.affectedFile() != null) {
            stream.println("Affected file: " + error.affectedFile());
        }
        if (error.locator() != null) {
            stream.println("Locator: " + error.locator());
        }
        if (error.propertyPath() != null) {
            stream.println("Property path: " + error.propertyPath());
        }
        if (error.unresolvedSegment() != null) {
            stream.println("Unresolved segment: " + error.unresolvedSegment());
        }
        if (error.component() != null) {
            stream.println("Component: " + error.component());
        }
        if (error.failureDiagnostic() != null) {
            printFailure(error.failureDiagnostic(), stream);
        }
        stream.println("Suggested next action: " + error.suggestedNextAction());
        if (debug && error.debugCause() != null) {
            stream.println("Debug stack trace:");
            error.debugCause().printStackTrace(stream);
        }
    }

    private static void printFailure(
            io.github.thisccl.j4a.apply.ApplyFailureDiagnostic failure, PrintStream stream) {
        stream.println("Failure class: " + failure.failureClass().name().toLowerCase(java.util.Locale.ROOT));
        stream.println("Phase: " + failure.phase());
        if (failure.change().isPresent()) {
            stream.println("Change index: " + failure.change().get().index());
            stream.println("Operation: " + failure.change().get().operation());
            stream.println("Change context: " + flowMapping(failure.change().get().context().toMap()));
        }
        for (int index = 0; index < failure.causes().size(); index++) {
            io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.SourceCause cause = failure.causes().get(index);
            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("type", cause.type());
            source.put("message", cause.message());
            stream.println("Source cause[" + index + "]: " + flowMapping(source));
        }
    }

    private static String flowMapping(Map<String, Object> value) {
        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.FLOW);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        return new Yaml(options).dump(value).trim();
    }

    static void print(CliError error, Map<String, Object> recovery, boolean debug, PrintStream stream) {
        Map<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("Error code", error.code());
        document.put("Category", error.category());
        document.put("Message", error.message());
        if (error.affectedFile() != null) document.put("Affected file", error.affectedFile().toString());
        if (error.locator() != null) document.put("Locator", error.locator());
        if (error.propertyPath() != null) document.put("Property path", error.propertyPath());
        if (error.unresolvedSegment() != null) document.put("Unresolved segment", error.unresolvedSegment());
        if (error.component() != null) document.put("Component", error.component());
        document.put("Suggested next action", error.suggestedNextAction());
        document.put("recovery", recovery);
        stream.print(new Yaml().dump(document));
        if (debug && error.debugCause() != null) error.debugCause().printStackTrace(stream);
    }
}
