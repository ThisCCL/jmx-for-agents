package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.validation.LocalJMeterEnvironmentException;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerClient;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResponse;
import java.util.Map;

final class ComponentCliCommand {
    private ComponentCliCommand() {
    }

    static int run(String[] args, Map<String, String> environment, boolean debug) {
        try {
            return runParsed(args, environment, debug);
        } catch (IllegalArgumentException exception) {
            String suggestedAction = "rerun components --help, or run categories ls and copy a listed lower-kebab id into components --category.";
            printUsageError(exception.getMessage(), suggestedAction, debug);
            return 2;
        } catch (LocalJMeterEnvironmentException exception) {
            printLocalRuntimeFailure(exception.getMessage(), debug);
            return 4;
        }
    }

    private static int runParsed(String[] args, Map<String, String> environment, boolean debug) {
        if (hasOption(args, "--format")) {
            throw new IllegalArgumentException("--format is not supported; components emits YAML by default.");
        }
        rejectUnknownOptions(args);
        String category = optionValue(args, "--category");
        String component = componentArgument(args);
        boolean details = trueMarker(args, "--details");
        boolean diagnostics = trueMarker(args, "--diagnostics");
        String limit = optionValue(args, "--limit");
        String maxBytes = optionValue(args, "--max-bytes");
        String cursor = optionValue(args, "--cursor");
        String componentToken = optionValue(args, "--component-token");
        if (componentToken != null && (componentToken.isEmpty() || componentToken.length() > 512)) {
            throw new IllegalArgumentException("--component-token must be a non-empty opaque token of at most 512 ASCII bytes");
        }
        if (componentToken != null && (component != null || category != null || details || diagnostics
                || limit != null || maxBytes != null || cursor != null)) {
            throw new IllegalArgumentException("--component-token is exclusive with component, --category, --details, --diagnostics, --limit, --max-bytes, and --cursor");
        }
        if (category != null && component != null) {
            throw new IllegalArgumentException("--category cannot be combined with a component");
        }
        if (details && component == null && category == null) {
            throw new IllegalArgumentException("--details requires a component");
        }
        if (category != null && diagnostics) {
            throw new IllegalArgumentException("--category cannot be combined with --diagnostics");
        }
        if (diagnostics && component == null) {
            throw new IllegalArgumentException("--diagnostics requires a component");
        }
        if (maxBytes != null && (category == null || !details)) {
            throw new IllegalArgumentException("--max-bytes requires --category with --details true");
        }
        if ((limit != null || cursor != null) && (category == null || !details)) {
            throw new IllegalArgumentException("--limit and --cursor require --category with --details true");
        }
        validateLimit(limit);
        validateMaxBytes(maxBytes);
        LocalJMeterRuntime runtime = LocalJMeterRuntime.fromArgs(args, environment);
        LocalJMeterWorkerRequest request = componentToken != null
                ? LocalJMeterWorkerRequest.componentDetailsByToken(runtime.home(), componentToken)
                : component == null
                ? LocalJMeterWorkerRequest.discoverComponents(
                        runtime.home(), category, details, limit, maxBytes, cursor)
                : LocalJMeterWorkerRequest.componentDetails(
                        runtime.home(), component, diagnostics);
        return runLocal(request, component, debug);
    }

    private static void rejectUnknownOptions(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--")
                    && !"--jmeter-home".equals(arg)
                    && !"--category".equals(arg)
                    && !"--details".equals(arg)
                    && !"--diagnostics".equals(arg)
                    && !"--limit".equals(arg)
                    && !"--max-bytes".equals(arg)
                    && !"--component-token".equals(arg)
                    && !"--cursor".equals(arg)) {
                throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
    }

    private static int runLocal(
            LocalJMeterWorkerRequest request, String component, boolean debug) {
        LocalJMeterWorkerResponse response = CliSupport.executeLocalWorker(request);
        if (response.success()) {
            System.out.print(response.payload());
            return 0;
        }
        if (component != null && "COMPONENT_IDENTITY_NOT_FOUND".equals(response.errorCode())) {
            String canonical = canonicalComponentId(response.message());
            printError(new CliError(
                    "COMPONENT_IDENTITY_NOT_FOUND",
                    "component",
                    identityNotFoundMessage(component, canonical),
                    null,
                    null,
                    null,
                    null,
                    component,
                    identityNotFoundSuggestion(canonical),
                    null), debug);
            return CliSupport.localWorkerExitCode(response);
        }
        if (component == null && "USAGE_ERROR".equals(response.errorCode())) {
            printUsageError(
                    response.message(),
                    response.suggestedAction(),
                    debug);
            return 2;
        }
        printLocalWorkerFailure(response, debug);
        return CliSupport.localWorkerExitCode(response);
    }

    private static String componentArgument(String[] args) {
        for (int index = 1; index < args.length; index++) {
            String arg = args[index];
            if ("--jmeter-home".equals(arg) || "--category".equals(arg)
                    || "--details".equals(arg) || "--diagnostics".equals(arg)
                    || "--limit".equals(arg)
                    || "--max-bytes".equals(arg)
                    || "--component-token".equals(arg)
                    || "--cursor".equals(arg)) {
                index++;
                continue;
            }
            if (!arg.startsWith("--")) {
                return arg;
            }
        }
        return null;
    }

    private static boolean hasOption(String[] args, String option) {
        for (String arg : args) {
            if (option.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean trueMarker(String[] args, String option) {
        if (!hasOption(args, option)) {
            return false;
        }
        for (int index = 0; index < args.length; index++) {
            if (!option.equals(args[index])) {
                continue;
            }
            if (index == args.length - 1 || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires the value true");
            }
            if (!"true".equals(args[index + 1])) {
                throw new IllegalArgumentException(option + " must be true");
            }
        }
        return true;
    }

    private static String optionValue(String[] args, String option) {
        for (int index = 0; index < args.length; index++) {
            if (option.equals(args[index])) {
                if (index == args.length - 1 || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException(option + " requires a value");
                }
                return args[index + 1];
            }
        }
        return null;
    }

    private static void validateLimit(String value) {
        if (value == null) {
            return;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--limit must be an integer", exception);
        }
        if (parsed < 1 || parsed > 50) {
            throw new IllegalArgumentException("--limit must be between 1 and 50");
        }
    }

    private static void validateMaxBytes(String value) {
        if (value == null) {
            return;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--max-bytes must be an integer", exception);
        }
        if (parsed < 4096 || parsed > 65536) {
            throw new IllegalArgumentException("--max-bytes must be between 4096 and 65536");
        }
    }

    private static void printUsageError(String message, String suggestedAction, boolean debug) {
        printError(new CliError(
                "USAGE_ERROR",
                "usage",
                message,
                null,
                null,
                null,
                null,
                null,
                suggestedAction,
                null), debug);
    }

    private static void printLocalRuntimeFailure(String message, boolean debug) {
        printError(new CliError(
                "LOCAL_JMETER_RUNTIME_ERROR",
                "runtime",
                message,
                null,
                null,
                null,
                null,
                "local JMeter runtime",
                "pass --jmeter-home or set JMX_AGENT_JMETER_HOME/JMETER_HOME to a valid local JMeter home containing bin, lib, lib/ext, and JMeter property files.",
                null), debug);
    }

    private static void printLocalWorkerFailure(LocalJMeterWorkerResponse response, boolean debug) {
        printError(new CliError(
                response.errorCode() == null ? "LOCAL_JMETER_RUNTIME_ERROR" : response.errorCode(),
                response.category() == null ? "runtime" : response.category(),
                response.message(),
                null,
                null,
                null,
                null,
                "local JMeter runtime",
                response.suggestedAction() == null
                        ? "pass --jmeter-home or set JMX_AGENT_JMETER_HOME/JMETER_HOME to a valid local JMeter home containing bin, lib, lib/ext, and JMeter property files."
                        : response.suggestedAction(),
                null), debug);
    }

    private static String canonicalComponentId(String message) {
        String marker = "canonical component id: ";
        int index = message.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String canonical = message.substring(index + marker.length()).trim();
        int separator = canonical.indexOf(';');
        if (separator >= 0) {
            canonical = canonical.substring(0, separator).trim();
        }
        return canonical.isEmpty() ? null : canonical;
    }

    private static String identityNotFoundMessage(String component, String canonical) {
        if (canonical == null) {
            return "Component identity not found: " + component;
        }
        return "Component identity not found: " + component + "; runtime component id: " + canonical;
    }

    private static String identityNotFoundSuggestion(String canonical) {
        if (canonical == null) {
            return "rerun components to list runtime-observed component identities, then retry with one listed identity.";
        }
        return "rerun components " + canonical
                + " --diagnostics true to inspect runtime-observed property metadata, then retry with that component identity.";
    }

    private static void printError(CliError error, boolean debug) {
        CliErrorFormatter.print(error, debug, System.err);
    }

}
