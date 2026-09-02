package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.locator.AmbiguousLocatorException;
import io.github.thisccl.j4a.validation.*;
import io.github.thisccl.j4a.jmx.JmxLoadException;
import io.github.thisccl.j4a.path.PropertyPathException;
import io.github.thisccl.j4a.path.PropertyPathResolutionException;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;

import java.nio.file.Path;
import java.util.function.Supplier;

final class CliSupport {
    private static final ThreadLocal<LocalJMeterWorkerClient> WORKER_CLIENT =
            new ThreadLocal<LocalJMeterWorkerClient>();
    private CliSupport() {
    }

    static void printUsageError(String message, Path affectedFile, String suggestedNextAction, boolean debug) {
        printError(new CliError(
                "USAGE_ERROR",
                "usage",
                message,
                affectedFile,
                null,
                null,
                null,
                null,
                suggestedNextAction,
                null), debug);
    }

    static LocalJMeterWorkerResponse executeLocalWorker(LocalJMeterWorkerRequest request) {
        return workerClient().execute(request).response();
    }

    static <T> T withWorkerClient(LocalJMeterWorkerClient client, Supplier<T> operation) {
        LocalJMeterWorkerClient previous = WORKER_CLIENT.get();
        WORKER_CLIENT.set(client);
        try {
            return operation.get();
        } finally {
            if (previous == null) {
                WORKER_CLIENT.remove();
            } else {
                WORKER_CLIENT.set(previous);
            }
        }
    }

    private static LocalJMeterWorkerClient workerClient() {
        LocalJMeterWorkerClient client = WORKER_CLIENT.get();
        return client == null ? new LocalJMeterWorkerClient() : client;
    }

    static int localWorkerExitCode(LocalJMeterWorkerResponse response) {
        if (response.failureDiagnostic().isPresent()) {
            switch (response.failureDiagnostic().get().failureClass()) {
                case USAGE:
                    return 2;
                case SEMANTIC:
                    return 3;
                case INFRASTRUCTURE:
                case FATAL:
                    return 4;
                default:
                    throw new IllegalStateException("Unsupported apply failure class");
            }
        }
        if ("USAGE_ERROR".equals(response.errorCode())) {
            return 2;
        }
        if ("LOCATOR_NOT_FOUND".equals(response.errorCode())
                || "MISSING_PROPERTY".equals(response.errorCode())
                || "COMPONENT_IDENTITY_NOT_FOUND".equals(response.errorCode())
                || "JMETER_ADD_DISABLED".equals(response.errorCode())
                || "JMETER_PLACEMENT_REJECTED".equals(response.errorCode())
                || "INVALID_PLACEMENT".equals(response.errorCode())
                || "CANDIDATE_PRESERVATION_FAILED".equals(response.errorCode())) {
            return 3;
        }
        if ("JMETER_MENU_REGISTRY_INCOMPATIBLE".equals(response.errorCode())
                || "JMX_READ_ERROR".equals(response.errorCode())) {
            return 4;
        }
        if ("PLUGIN_CLASS_MISSING".equals(response.errorCode())
                || "SEMANTIC_LOAD_ERROR".equals(response.errorCode())
                || "XML_PARSE_ERROR".equals(response.errorCode())) {
            return 1;
        }
        return 4;
    }

    static void printLocalWorkerFailure(Path affectedFile, LocalJMeterWorkerResponse response, boolean debug) {
        printError(new CliError(
                response.errorCode() == null ? "LOCAL_JMETER_RUNTIME_ERROR" : response.errorCode(),
                response.category() == null ? "runtime" : response.category(),
                response.message(),
                affectedFile,
                null,
                null,
                null,
                "local",
                response.suggestedAction(),
                null,
                response.failureDiagnostic().orElse(null)), debug);
    }

    static void printLocalEnvironmentFailure(Path jmxPath, LocalJMeterEnvironmentException exception, boolean debug) {
        printError(localEnvironmentError(jmxPath, exception), debug);
    }

    static CliError localEnvironmentError(Path jmxPath, LocalJMeterEnvironmentException exception) {
        String suggestedNextAction =
                "pass --jmeter-home or set JMX_AGENT_JMETER_HOME/JMETER_HOME to a valid local JMeter home containing bin, lib, lib/ext, and JMeter property files.";
        return new CliError(
                "LOCAL_JMETER_RUNTIME_ERROR",
                "runtime",
                exception.getMessage(),
                jmxPath,
                null,
                null,
                null,
                "local JMeter runtime",
                suggestedNextAction,
                exception);
    }

    static void printPropertyPathError(Path input, String locator, PropertyPathException exception, boolean debug) {
        String unresolvedSegment = null;
        if (exception instanceof PropertyPathResolutionException) {
            unresolvedSegment = ((PropertyPathResolutionException) exception).segment();
        }
        printError(new CliError(
                exception.errorCode().name(),
                "property-path",
                exception.getMessage(),
                input,
                locator,
                exception.path(),
                unresolvedSegment,
                null,
                "rerun read --properties all to discover valid property paths, then retry set with a listed property path.",
                exception), debug);
    }

    static void printLocatorError(Path input, String locator, RuntimeException exception, boolean debug) {
        printError(new CliError(
                exception instanceof AmbiguousLocatorException
                        ? "LOCATOR_AMBIGUOUS" : "LOCATOR_NOT_FOUND",
                "locator",
                exception.getMessage(),
                input,
                locator,
                null,
                null,
                null,
                "rerun read to refresh locators, then retry set with the current locator value.",
                exception), debug);
    }

    static boolean hasCauseNamed(Throwable throwable, String simpleName) {
        Throwable current = throwable;
        while (current != null) {
            if (simpleName.equals(current.getClass().getSimpleName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static void printError(CliError error, boolean debug) {
        CliErrorFormatter.print(error, debug, System.err);
    }

    static void printError(CliError error, java.util.Map<String, Object> recovery, boolean debug) {
        CliErrorFormatter.print(error, recovery, debug, System.err);
    }

}
