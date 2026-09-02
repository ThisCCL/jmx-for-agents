package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.jmx.JmxLoadException;
import io.github.thisccl.j4a.jmx.JmxInitialPlanFactory;
import io.github.thisccl.j4a.validation.LocalJMeterEnvironmentException;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class InitCliCommand {
    private InitCliCommand() {
    }

    static int run(String[] args, Map<String, String> environment, boolean debug) {
        if (args.length < 2) {
            CliSupport.printUsageError("An output JMX file is required.", null,
                    "pass the output .jmx file path after init.", debug);
            return 2;
        }
        if (args[1].startsWith("--")) {
            CliSupport.printUsageError("An output JMX file is required before init options.", null,
                    "pass the output .jmx file path before any init flags.", debug);
            return 2;
        }
        Path output = Paths.get(args[1]);
        try {
            LocalJMeterRuntime.rejectRemovedOptions(args);
            Options options = Options.parse(args, output, debug);
            if (Files.exists(output) && !options.forceOut()) {
                CliSupport.printError(new CliError("OUTPUT_FILE_EXISTS", "filesystem",
                        "Output file already exists: " + output + ".",
                        output, null, null, null, null,
                        "pass --force-out to overwrite the output file, or choose a different output path.",
                        null), recovery(args), debug);
                return 4;
            }
            LocalJMeterRuntime runtime = LocalJMeterRuntime.fromArgs(args, environment);
            return runLocal(output, options, runtime, debug);
        } catch (StopCommandException exception) {
            return exception.exitCode();
        } catch (IllegalArgumentException exception) {
            CliSupport.printUsageError(exception.getMessage(), output,
                    "rerun init --help and use supported init flags.", debug);
            return 2;
        } catch (LocalJMeterEnvironmentException exception) {
            CliSupport.printLocalEnvironmentFailure(output, exception, debug);
            return 4;
        } catch (JmxLoadException exception) {
            CliSupport.printError(new CliError(
                    "JMX_READ_WRITE_ERROR",
                    "filesystem",
                    exception.getMessage(),
                    output,
                    null,
                    null,
                    null,
                    null,
                    "check that the output path is writable, then rerun init.",
                    exception), debug);
            return 4;
        }
    }

    private static Map<String, Object> recovery(String[] originalArgs) {
        List<String> overwrite = new ArrayList<String>(Arrays.asList(originalArgs));
        if (!overwrite.contains("--force-out")) overwrite.add("--force-out");
        List<String> chooseOutput = new ArrayList<String>(Arrays.asList(originalArgs));
        chooseOutput.set(1, "<different-output.jmx>");
        chooseOutput.remove("--force-out");
        List<Object> choices = new ArrayList<Object>();
        choices.add(choice("overwrite", overwrite));
        choices.add(choice("choose-output", chooseOutput));
        Map<String, Object> recovery = new java.util.LinkedHashMap<String, Object>();
        recovery.put("choices", choices);
        return recovery;
    }

    private static Map<String, Object> choice(String action, List<String> argv) {
        Map<String, Object> choice = new java.util.LinkedHashMap<String, Object>();
        choice.put("action", action);
        choice.put("command", "init");
        choice.put("argv", argv);
        return choice;
    }

    private static int runLocal(Path output, Options options, LocalJMeterRuntime runtime, boolean debug) {
        LocalJMeterWorkerResponse response = CliSupport.executeLocalWorker(LocalJMeterWorkerRequest.initJmx(
                output,
                runtime.home(),
                options.testPlanName(),
                options.threadGroupName(),
                "2",
                "NONE"));
        if (!response.success()) {
            CliSupport.printLocalWorkerFailure(output, response, debug);
            return CliSupport.localWorkerExitCode(response);
        }
        System.out.print(response.payload());
        return 0;
    }

    private static final class Options {
        private final boolean forceOut;
        private final String testPlanName;
        private final String threadGroupName;

        private Options(boolean forceOut, String testPlanName, String threadGroupName) {
            this.forceOut = forceOut;
            this.testPlanName = testPlanName;
            this.threadGroupName = threadGroupName;
        }

        static Options parse(String[] args, Path output, boolean debug) {
            boolean forceOut = false;
            String testPlanName = JmxInitialPlanFactory.DEFAULT_TEST_PLAN_NAME;
            String threadGroupName = JmxInitialPlanFactory.DEFAULT_THREAD_GROUP_NAME;
            Set<String> seenOptions = new HashSet<>();
            for (int index = 2; index < args.length; index++) {
                String arg = args[index];
                if (!arg.startsWith("--")) {
                    CliSupport.printUsageError("Unsupported init operand: " + arg, output,
                            "rerun init --help and use supported init flags.", debug);
                    throw new StopCommandException(2);
                }
                if ("--force-out".equals(arg)) {
                    forceOut = true;
                    continue;
                }
                if (!isValueOption(arg)) {
                    CliSupport.printUsageError("Unsupported init option: " + arg, output,
                            "rerun init --help and use supported init flags.", debug);
                    throw new StopCommandException(2);
                }
                if (!seenOptions.add(arg)) {
                    CliSupport.printUsageError("Duplicate init option: " + arg, output,
                            "pass each init option at most once.", debug);
                    throw new StopCommandException(2);
                }
                String value = requireValue(args, index, arg, output, debug);
                if ("--name".equals(arg)) {
                    testPlanName = value;
                } else if ("--thread-group-name".equals(arg)) {
                    threadGroupName = value;
                }
                index++;
            }
            return new Options(forceOut, testPlanName, threadGroupName);
        }

        private static boolean isValueOption(String option) {
            return "--jmeter-home".equals(option)
                    || "--name".equals(option)
                    || "--thread-group-name".equals(option);
        }

        private static String requireValue(String[] args, int index, String option, Path output, boolean debug) {
            if (index == args.length - 1 || args[index + 1].startsWith("--")) {
                CliSupport.printUsageError(option + " requires a value", output,
                        "rerun init --help and pass a value after " + option + ".", debug);
                throw new StopCommandException(2);
            }
            return args[index + 1];
        }

        boolean forceOut() {
            return forceOut;
        }

        String testPlanName() {
            return testPlanName;
        }

        String threadGroupName() {
            return threadGroupName;
        }
    }

    private static final class StopCommandException extends RuntimeException {
        private final int exitCode;

        private StopCommandException(int exitCode) {
            this.exitCode = exitCode;
        }

        private int exitCode() {
            return exitCode;
        }
    }
}
