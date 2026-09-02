package io.github.thisccl.j4a.validation;

import java.nio.file.Path;

final class LocalJMeterWorkerRuntime {
    private static Path initializedHome;

    private LocalJMeterWorkerRuntime() {
    }

    static synchronized void initialize(Path requestedHome) throws Exception {
        Path home = requestedHome.toRealPath();
        if (initializedHome != null) {
            if (!initializedHome.equals(home)) {
                throw new IllegalStateException(
                        "Local JMeter worker cannot switch homes after initialization: " + initializedHome);
            }
            return;
        }
        try {
            LocalJMeterWorkerJmx.initializeJMeter(home);
            initializedHome = home;
        } catch (Exception exception) {
            throw new InitializationException(home, exception);
        }
    }

    static final class InitializationException extends Exception {
        private InitializationException(Path home, Exception cause) {
            super("Unable to initialize local JMeter runtime once for " + home, cause);
        }
    }
}
