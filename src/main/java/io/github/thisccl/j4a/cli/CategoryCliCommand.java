package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.validation.LocalJMeterEnvironmentException;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResponse;
import java.util.Map;

final class CategoryCliCommand {
    private CategoryCliCommand() {
    }

    static int run(String[] args, Map<String, String> environment, boolean debug) {
        if (isHelp(args)) {
            return CliHelp.printCommand("categories");
        }
        try {
            return runParsed(args, environment, debug);
        } catch (IllegalArgumentException exception) {
            CliSupport.printUsageError(exception.getMessage(), null,
                    "run categories ls --help for usage, then copy a listed lower-kebab category id into components --category.",
                    debug);
            return 2;
        } catch (LocalJMeterEnvironmentException exception) {
            CliSupport.printLocalEnvironmentFailure(null, exception, debug);
            return 4;
        }
    }

    private static int runParsed(String[] args, Map<String, String> environment, boolean debug) {
        if (args.length < 2 || !"ls".equals(args[1])) {
            throw new IllegalArgumentException("categories supports only the ls subcommand.");
        }
        validateLsArgs(args);
        LocalJMeterRuntime runtime = LocalJMeterRuntime.fromArgs(args, environment);
        return runLocal(runtime, debug);
    }

    private static int runLocal(LocalJMeterRuntime runtime, boolean debug) {
        LocalJMeterWorkerResponse response = CliSupport.executeLocalWorker(
                LocalJMeterWorkerRequest.listCategories(runtime.home()));
        if (response.success()) {
            System.out.print(response.payload());
            return 0;
        }
        CliSupport.printLocalWorkerFailure(null, response, debug);
        return CliSupport.localWorkerExitCode(response);
    }

    private static void validateLsArgs(String[] args) {
        LocalJMeterRuntime.rejectRemovedOptions(args);
        for (int index = 2; index < args.length; index++) {
            String arg = args[index];
            if ("--jmeter-home".equals(arg)) {
                if (index == args.length - 1 || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException(arg + " requires a value");
                }
                index++;
            } else if ("--category".equals(arg)) {
                throw new IllegalArgumentException("--category is not supported by categories ls; "
                        + "use components --category after choosing a listed category.");
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("Unsupported categories ls option: " + arg);
            } else {
                throw new IllegalArgumentException("Unsupported categories ls operand: " + arg);
            }
        }
    }

    private static boolean isHelp(String[] args) {
        return args.length == 2 && isHelpFlag(args[1])
                || args.length == 3 && "ls".equals(args[1]) && isHelpFlag(args[2]);
    }

    private static boolean isHelpFlag(String arg) {
        return "--help".equals(arg) || "-h".equals(arg);
    }
}
