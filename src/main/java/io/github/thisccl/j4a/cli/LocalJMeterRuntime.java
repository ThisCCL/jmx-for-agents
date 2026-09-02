package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.validation.LocalJMeterEnvironmentException;
import io.github.thisccl.j4a.validation.LocalJMeterHomeResolver;
import java.nio.file.Path;
import java.util.Map;

final class LocalJMeterRuntime {
    private static final String HOME_GUIDANCE =
            "No valid local JMeter home resolved from --jmeter-home, JMX_AGENT_JMETER_HOME, or JMETER_HOME.";

    private final Path home;

    private LocalJMeterRuntime(Path home) {
        this.home = home;
    }

    static LocalJMeterRuntime ofHome(Path home) {
        return new LocalJMeterRuntime(home);
    }

    static LocalJMeterRuntime fromArgs(String[] args, Map<String, String> environment) {
        rejectRemovedOptions(args);
        Path home = new LocalJMeterHomeResolver()
                .resolve(optionValue(args, "--jmeter-home"), environment)
                .orElseThrow(() -> new LocalJMeterEnvironmentException(HOME_GUIDANCE));
        return new LocalJMeterRuntime(home);
    }

    Path home() {
        return home;
    }

    static void rejectRemovedOptions(String[] args) {
        for (String arg : args) {
            if ("--profile".equals(arg) || "--validation-mode".equals(arg)) {
                throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
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
}
