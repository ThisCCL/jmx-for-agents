package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.validation.LocalJMeterEnvironmentException;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

final class ValidateCliCommand {
    private ValidateCliCommand() {
    }

    static int run(String[] args, Map<String, String> environment, boolean debug) {
        if (args.length < 2) {
            CliSupport.printUsageError("A JMX file is required.", null, "pass the .jmx file path after validate.", debug);
            return 2;
        }

        Path jmxPath = Paths.get(args[1]);
        try {
            LocalJMeterRuntime runtime = LocalJMeterRuntime.fromArgs(args, environment);
            LocalJMeterWorkerResponse response = CliSupport.executeLocalWorker(
                    LocalJMeterWorkerRequest.validate(jmxPath, runtime.home()));
            if (response.success()) {
                System.out.println("Validation passed: " + jmxPath);
                return 0;
            }
            CliSupport.printLocalWorkerFailure(jmxPath, response, debug);
            return CliSupport.localWorkerExitCode(response);
        } catch (LocalJMeterEnvironmentException exception) {
            CliSupport.printLocalEnvironmentFailure(jmxPath, exception, debug);
            return 4;
        } catch (IllegalArgumentException exception) {
            CliSupport.printUsageError(exception.getMessage(), jmxPath,
                    "rerun validate --help and use supported validate flags.", debug);
            return 2;
        }
    }
}
