package io.github.thisccl.j4a.cli;

import java.util.ArrayList;
import java.util.List;

final class CliInvocation {
    private final String[] args;
    private final boolean debug;

    private CliInvocation(String[] args, boolean debug) {
        this.args = args;
        this.debug = debug;
    }

    static CliInvocation from(String[] rawArgs) {
        List<String> stripped = new ArrayList<>();
        boolean debug = false;
        for (String arg : rawArgs) {
            if ("--debug".equals(arg)) {
                debug = true;
            } else {
                stripped.add(arg);
            }
        }
        return new CliInvocation(stripped.toArray(new String[0]), debug);
    }

    String[] args() {
        return args;
    }

    boolean debug() {
        return debug;
    }
}
