package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.read.ReadOptions;

final class ReadCliOptions {
    private ReadCliOptions() {
    }

    static ReadOptions options(String[] args) {
        if (hasOption(args, "--verbose")) {
            throw new IllegalArgumentException("--verbose is not supported; use --properties key, all, or writable.");
        }
        rejectUnknownOptions(args);
        String focusRef = optionValue(args, "--ref");
        return new ReadOptions(
                false,
                hasOption(args, "--include-disabled-details"),
                depth(args, focusRef != null),
                focusRef,
                propertyMode(args));
    }

    private static void rejectUnknownOptions(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--")
                    && !"--depth".equals(arg)
                    && !"--ref".equals(arg)
                    && !"--properties".equals(arg)
                    && !"--include-disabled-details".equals(arg)
                    && !"--jmeter-home".equals(arg)) {
                throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
    }

    private static Integer depth(String[] args, boolean focused) {
        String depthValue = optionValue(args, "--depth");
        if (depthValue == null) {
            return focused ? null : 1;
        }
        try {
            int depth = Integer.parseInt(depthValue);
            if (depth < 0) {
                throw new IllegalArgumentException("--depth must be zero or greater");
            }
            return depth;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--depth must be an integer", exception);
        }
    }

    private static ReadOptions.PropertyMode propertyMode(String[] args) {
        String properties = optionValue(args, "--properties");
        if (properties == null) {
            return ReadOptions.PropertyMode.NONE;
        }
        switch (properties) {
            case "key":
                return ReadOptions.PropertyMode.KEY;
            case "all":
                return ReadOptions.PropertyMode.ALL;
            case "writable":
                return ReadOptions.PropertyMode.WRITABLE;
            default:
                throw new IllegalArgumentException("--properties must be key, all, or writable");
        }
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
