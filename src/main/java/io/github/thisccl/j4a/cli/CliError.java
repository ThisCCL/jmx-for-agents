package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.apply.ApplyFailureDiagnostic;
import java.nio.file.Path;

final class CliError {
    private final String code;
    private final String category;
    private final String message;
    private final Path affectedFile;
    private final String locator;
    private final String propertyPath;
    private final String unresolvedSegment;
    private final String component;
    private final String suggestedNextAction;
    private final Throwable debugCause;
    private final ApplyFailureDiagnostic failureDiagnostic;

    CliError(
            String code,
            String category,
            String message,
            Path affectedFile,
            String locator,
            String propertyPath,
            String unresolvedSegment,
            String component,
            String suggestedNextAction,
            Throwable debugCause) {
        this(code, category, message, affectedFile, locator, propertyPath, unresolvedSegment,
                component, suggestedNextAction, debugCause, null);
    }

    CliError(
            String code, String category, String message, Path affectedFile, String locator,
            String propertyPath, String unresolvedSegment, String component,
            String suggestedNextAction, Throwable debugCause,
            ApplyFailureDiagnostic failureDiagnostic) {
        this.code = code;
        this.category = category;
        this.message = message;
        this.affectedFile = affectedFile;
        this.locator = locator;
        this.propertyPath = propertyPath;
        this.unresolvedSegment = unresolvedSegment;
        this.component = component;
        this.suggestedNextAction = suggestedNextAction;
        this.debugCause = debugCause;
        this.failureDiagnostic = failureDiagnostic;
    }

    String code() {
        return code;
    }

    String category() {
        return category;
    }

    String message() {
        return message;
    }

    Path affectedFile() {
        return affectedFile;
    }

    String locator() {
        return locator;
    }

    String propertyPath() {
        return propertyPath;
    }

    String unresolvedSegment() {
        return unresolvedSegment;
    }

    String component() {
        return component;
    }

    String suggestedNextAction() {
        return suggestedNextAction;
    }

    Throwable debugCause() {
        return debugCause;
    }

    ApplyFailureDiagnostic failureDiagnostic() { return failureDiagnostic; }

    CliError withDebugCause(Throwable cause) {
        return new CliError(code, category, message, affectedFile, locator, propertyPath, unresolvedSegment, component,
                suggestedNextAction, cause, failureDiagnostic);
    }
}
