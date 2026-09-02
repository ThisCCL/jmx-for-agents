package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyPatchParseException;
import io.github.thisccl.j4a.apply.ApplyPatchParser;
import io.github.thisccl.j4a.apply.ApplyWriteModeResolver;
import io.github.thisccl.j4a.jmx.JmxLoadException;
import io.github.thisccl.j4a.validation.LocalJMeterEnvironmentException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ApplyCliCommand {
    static final int MAX_PATCH_BYTES = 4 * 1024 * 1024;
    private ApplyCliCommand() {
    }

    static int run(String[] args, Map<String, String> environment, boolean debug, InputStream stdin) {
        if (args.length < 2) {
            CliSupport.printUsageError("A JMX file is required.", null, "pass the .jmx file path after apply.", debug);
            return 2;
        }
        Path input = Paths.get(args[1]);
        String patchSource = optionValue(args, "--patch");
        if (patchSource == null) {
            CliSupport.printUsageError("--patch is required.", input, "pass --patch <file|-> with a YAML patch.", debug);
            return 2;
        }
        Path affectedPatch = "-".equals(patchSource) ? null : Paths.get(patchSource);
        try {
            return runParsed(args, environment, debug, stdin, input, patchSource);
        } catch (StopCommandException exception) {
            return exception.exitCode();
        } catch (ApplyWriteModeResolver.UsageException exception) {
            CliSupport.printUsageError(exception.getMessage(), input, exception.suggestedNextAction(), debug);
            return 2;
        } catch (ApplyPatchParseException exception) {
            printPatchParseFailure(input, affectedPatch, exception, debug);
            return 2;
        } catch (IOException exception) {
            printPatchReadFailure(input, affectedPatch, exception, debug);
            return 4;
        } catch (LocalJMeterEnvironmentException exception) {
            CliSupport.printLocalEnvironmentFailure(input, exception, debug);
            return 4;
        } catch (JmxLoadException exception) {
            CliSupport.printError(new CliError(
                    "JMX_READ_WRITE_ERROR",
                    "filesystem",
                    exception.getMessage(),
                    input,
                    null,
                    null,
                    null,
                    null,
                    "check that the input and output paths are accessible, then rerun apply.",
                    exception), debug);
            return 4;
        }
    }

    private static int runParsed(
            String[] args, Map<String, String> environment, boolean debug,
            InputStream stdin, Path input, String patchSource) throws IOException {
        boolean dryRun = hasOption(args, "--dry-run");
        boolean override = hasOption(args, "--override");
        boolean forceOut = hasOption(args, "--force-out");
        String out = optionValue(args, "--out");
        ApplyWriteModeResolver.Resolution writeMode;
        try {
            writeMode = ApplyWriteModeResolver.resolve(input, dryRun, override, out, forceOut);
        } catch (ApplyWriteModeResolver.UsageException exception) {
            CliSupport.printUsageError(exception.getMessage(), input, exception.suggestedNextAction(), debug);
            return 2;
        }
        LocalJMeterRuntime runtime = runtime(args, environment, input, debug);
        int writeTargetExit = validateWriteTarget(writeMode, forceOut, debug);
        if (writeTargetExit != 0) {
            return writeTargetExit;
        }
        String yaml = readPatch(patchSource, stdin);
        ApplyPatch patch = new ApplyPatchParser().parse(yaml);
        return LocalApplyCliCommand.run(input, patchSource, yaml, patch, runtime,
                writeMode, out, debug);
    }

    private static LocalJMeterRuntime runtime(
            String[] args, Map<String, String> environment, Path input, boolean debug) {
        try {
            return LocalJMeterRuntime.fromArgs(args, environment);
        } catch (IllegalArgumentException exception) {
            CliSupport.printUsageError(exception.getMessage(), input,
                    "rerun apply --help and use supported apply flags.", debug);
            throw new StopCommandException(2);
        }
    }

    private static int validateWriteTarget(
            ApplyWriteModeResolver.Resolution writeMode, boolean forceOut, boolean debug) {
        if (writeMode.mode() == ApplyWriteModeResolver.Mode.COPY
                && Files.exists(writeMode.target()) && !forceOut) {
            Path target = writeMode.target();
            CliSupport.printError(new CliError("OUTPUT_FILE_EXISTS", "filesystem", "Output file already exists: " + target + ".",
                    target, null, null, null, null,
                    "pass --force-out to overwrite the output file, or choose a different --out path.", null), debug);
            return 4;
        }
        return 0;
    }

    private static String readPatch(String patchSource, InputStream stdin) throws IOException {
        if ("-".equals(patchSource)) {
            return new String(readAllBytes(stdin, MAX_PATCH_BYTES), StandardCharsets.UTF_8);
        }
        Path path = Paths.get(patchSource);
        if (Files.size(path) > MAX_PATCH_BYTES) {
            throw new ApplyPatchParseException("PATCH_INPUT_TOO_LARGE: YAML patch exceeds the 4 MiB maximum.");
        }
        try (InputStream patchInput = Files.newInputStream(path)) {
            return new String(readAllBytes(patchInput, MAX_PATCH_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAllBytes(InputStream inputStream, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            if (output.size() > maximumBytes - read) {
                throw new ApplyPatchParseException("PATCH_INPUT_TOO_LARGE: YAML patch exceeds the 4 MiB maximum.");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static void printDryRun(ApplyPatch patch) {
        System.out.println("Patch applicable: " + patch.changes().size() + " change(s)");
        System.out.println("Operations: " + operationNames(patch));
        System.out.println("No JMX changes were written.");
    }

    private static String operationNames(ApplyPatch patch) {
        List<String> names = new ArrayList<>();
        for (ApplyPatch.Change change : patch.changes()) {
            names.add(change.operation().name());
        }
        return String.join(", ", names);
    }

    private static void printPatchParseFailure(
            Path input, Path affectedPatch, ApplyPatchParseException exception, boolean debug) {
        io.github.thisccl.j4a.apply.ApplyFailureDiagnostic failure =
                new io.github.thisccl.j4a.apply.ApplyFailureDiagnostic(
                        io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.FailureClass.USAGE,
                        "schema", exception.changeIndex().orElse(null), exception.operation().orElse(null),
                        exception.context().orElse(null),
                        java.util.Collections.<io.github.thisccl.j4a.apply.ApplyFailureDiagnostic.SourceCause>emptyList());
        CliSupport.printError(new CliError(
                "PATCH_PARSE_ERROR",
                "usage",
                exception.getMessage(),
                affectedPatch == null ? input : affectedPatch,
                null,
                null,
                null,
                null,
                "fix the YAML patch so it uses operation cards accepted by apply.",
                exception,
                failure), debug);
    }

    private static void printPatchReadFailure(Path input, Path affectedPatch, IOException exception, boolean debug) {
        CliSupport.printError(new CliError(
                "PATCH_READ_ERROR",
                "filesystem",
                "Could not read patch input: " + exception.getMessage(),
                affectedPatch == null ? input : affectedPatch,
                null,
                null,
                null,
                null,
                "check that the patch path is readable, or pass --patch - with YAML on stdin.",
                exception), debug);
    }

    private static boolean hasOption(String[] args, String option) {
        for (String arg : args) {
            if (option.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String optionValue(String[] args, String option) {
        for (int index = 0; index < args.length - 1; index++) {
            if (option.equals(args[index])) {
                return args[index + 1];
            }
        }
        return null;
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
