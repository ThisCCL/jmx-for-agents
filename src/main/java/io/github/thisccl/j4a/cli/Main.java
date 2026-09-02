package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.VersionInfo;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        configureUtf8Console();
        System.exit(run(args));
    }

    private static void configureUtf8Console() {
        System.setOut(utf8PrintStream(System.out));
        System.setErr(utf8PrintStream(System.err));
    }

    private static PrintStream utf8PrintStream(PrintStream stream) {
        try {
            return new PrintStream(stream, true, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException("UTF-8 output encoding is unavailable.", exception);
        }
    }

    public static int run(String[] args) {
        return run(args, System.getenv());
    }

    static int run(String[] args, Map<String, String> environment) {
        return run(args, environment, System.in);
    }

    static int run(String[] args, Map<String, String> environment, InputStream stdin) {
        if (containsVersionFlag(args)) {
            if (args.length == 1 && "--version".equals(args[0])) {
                System.out.println(VersionInfo.version());
                return 0;
            }
            CliSupport.printUsageError(
                    "--version does not accept additional arguments.",
                    null,
                    "run j4a --version by itself.",
                    false);
            return 2;
        }
        CliInvocation invocation = CliInvocation.from(args);
        try {
            return run(invocation.args(), environment, invocation.debug(), stdin);
        } catch (RuntimeException exception) {
            CliSupport.printError(new CliError(
                    "INTERNAL_ERROR",
                    "internal",
                    "Unexpected internal error while running the CLI.",
                    null,
                    null,
                    null,
                    null,
                    null,
                    "rerun with --debug and report this command with the affected JMX file.",
                    exception), invocation.debug());
            return 10;
        }
    }

    private static int run(String[] args, Map<String, String> environment, boolean debug, InputStream stdin) {
        if (args.length == 1 && isHelpFlag(args[0])) {
            CliHelp.print();
            return 0;
        }
        if (args.length == 2 && isHelpFlag(args[1])) {
            return CliHelp.printCommand(args[0]);
        }
        if (args.length == 0) {
            CliSupport.printUsageError(
                    "A command is required.",
                    null,
                    "pass one of: read, set, validate, components, categories, apply, init, or --help.",
                    debug);
            return 2;
        }
        if ("read".equals(args[0])) {
            return ReadCliCommand.run(args, environment, debug);
        }
        if ("set".equals(args[0])) {
            return SetCliCommand.run(args, environment, debug);
        }
        if ("validate".equals(args[0])) {
            return ValidateCliCommand.run(args, environment, debug);
        }
        if ("components".equals(args[0])) {
            return ComponentCliCommand.run(args, environment, debug);
        }
        if ("categories".equals(args[0])) {
            return CategoryCliCommand.run(args, environment, debug);
        }
        if ("apply".equals(args[0])) {
            return ApplyCliCommand.run(args, environment, debug, stdin);
        }
        if ("init".equals(args[0])) {
            return InitCliCommand.run(args, environment, debug);
        }
        CliHelp.print();
        return 2;
    }

    private static boolean isHelpFlag(String arg) {
        return "--help".equals(arg) || "-h".equals(arg);
    }

    private static boolean containsVersionFlag(String[] args) {
        for (String arg : args) {
            if ("--version".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
