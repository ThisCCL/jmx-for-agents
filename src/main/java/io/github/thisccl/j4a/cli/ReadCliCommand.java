package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.read.ReadOptions;
import io.github.thisccl.j4a.validation.LocalJMeterEnvironmentException;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

final class ReadCliCommand {
    private ReadCliCommand() {
    }

    static int run(String[] args, Map<String, String> environment, boolean debug) {
        CommandResult result = execute(args, environment, debug);
        CliCommandResultAdapter.emit(result, debug, System.out, System.err);
        return result.exitCode();
    }

    static CommandResult execute(String[] args, Map<String, String> environment, boolean debug) {
        if (args.length < 2) {
            return failure(2, new CliError(
                    "USAGE_ERROR",
                    "usage",
                    "A JMX file is required.",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "pass the .jmx file path after read.",
                    null));
        }
        Path input = Paths.get(args[1]);
        try {
            ReadOptions options = ReadCliOptions.options(args);
            LocalJMeterRuntime runtime = LocalJMeterRuntime.fromArgs(args, environment);
            return executeLocal(input, options, runtime);
        } catch (IllegalArgumentException exception) {
            return failure(2, new CliError(
                    "USAGE_ERROR",
                    "usage",
                    exception.getMessage(),
                    input,
                    null,
                    null,
                    null,
                    null,
                    "rerun read --help and use supported read flags.",
                    exception));
        } catch (LocalJMeterEnvironmentException exception) {
            return failure(4, CliSupport.localEnvironmentError(input, exception));
        }
    }

    private static CommandResult executeLocal(
            Path input, ReadOptions options, LocalJMeterRuntime runtime) {
        LocalJMeterWorkerResponse response = CliSupport.executeLocalWorker(LocalJMeterWorkerRequest.renderReadData(
                input,
                runtime.home(),
                options.depth() == null ? null : String.valueOf(options.depth()),
                options.ref(),
                options.propertyMode().name(),
                String.valueOf(options.includeDisabledDetails())));
        if (!response.success()) {
            return failure(CliSupport.localWorkerExitCode(response), localWorkerError(input, response));
        }
        return DefaultCommandResult.success(response.payload(), structuredData(input));
    }

    private static CommandResult failure(int exitCode, CliError error) {
        return DefaultCommandResult.failure(exitCode, error);
    }

    private static Map<String, Object> structuredData(Path input) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("command", "read");
        data.put("format", "yaml");
        data.put("file", input.toString());
        return data;
    }

    private static CliError localWorkerError(Path input, LocalJMeterWorkerResponse response) {
        return new CliError(
                response.errorCode() == null ? "LOCAL_JMETER_RUNTIME_ERROR" : response.errorCode(),
                response.category() == null ? "runtime" : response.category(),
                response.message(),
                input,
                null,
                null,
                null,
                "local",
                response.suggestedAction(),
                null);
    }

}
