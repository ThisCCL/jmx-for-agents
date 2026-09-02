package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.apply.ApplyFailureDiagnostic;
import java.nio.file.Path;

public final class CommandDiagnostic {
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

    private CommandDiagnostic(
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
        this.failureDiagnostic = null;
    }

    private CommandDiagnostic(CliError error) {
        this.code = error.code();
        this.category = error.category();
        this.message = error.message();
        this.affectedFile = error.affectedFile();
        this.locator = error.locator();
        this.propertyPath = error.propertyPath();
        this.unresolvedSegment = error.unresolvedSegment();
        this.component = error.component();
        this.suggestedNextAction = error.suggestedNextAction();
        this.debugCause = error.debugCause();
        this.failureDiagnostic = error.failureDiagnostic();
    }

    static CommandDiagnostic from(CliError error) {
        return new CommandDiagnostic(error);
    }

    public static CommandDiagnostic applyFailure(
            String code, String category, String message, String suggestedNextAction,
            ApplyFailureDiagnostic failureDiagnostic) {
        return CommandDiagnostic.from(new CliError(code, category, message, null, null, null, null,
                null, suggestedNextAction, null, failureDiagnostic));
    }

    CliError toCliError() {
        return new CliError(
                code,
                category,
                message,
                affectedFile,
                locator,
                propertyPath,
                unresolvedSegment,
                component,
                suggestedNextAction,
                debugCause,
                failureDiagnostic);
    }

    public String code() {
        return code;
    }

    public String category() {
        return category;
    }

    public String message() {
        return message;
    }

    public Path affectedFile() {
        return affectedFile;
    }

    public String locator() {
        return locator;
    }

    public String propertyPath() {
        return propertyPath;
    }

    public String unresolvedSegment() {
        return unresolvedSegment;
    }

    public String component() {
        return component;
    }

    public String suggestedNextAction() {
        return suggestedNextAction;
    }

    public java.util.Optional<ApplyFailureDiagnostic> failureDiagnostic() {
        return java.util.Optional.ofNullable(failureDiagnostic);
    }
}
