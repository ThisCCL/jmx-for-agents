package io.github.thisccl.j4a.validation;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Objects;

final class LocalJMeterSharedWorkerKey {
    private final String javaExecutable;
    private final String jmeterHome;
    private final Duration startupTimeout;
    private final Duration operationTimeout;

    LocalJMeterSharedWorkerKey(LocalJMeterWorkerClient client, LocalJMeterWorkerRequest request) {
        this.javaExecutable = client.javaExecutable();
        this.jmeterHome = canonicalHome(request.jmeterHome());
        this.startupTimeout = client.startupTimeoutDuration();
        this.operationTimeout = client.operationTimeoutDuration();
    }

    @Override
    public boolean equals(Object value) {
        if (!(value instanceof LocalJMeterSharedWorkerKey)) {
            return false;
        }
        LocalJMeterSharedWorkerKey other = (LocalJMeterSharedWorkerKey) value;
        return Objects.equals(javaExecutable, other.javaExecutable)
                && Objects.equals(jmeterHome, other.jmeterHome)
                && Objects.equals(startupTimeout, other.startupTimeout)
                && Objects.equals(operationTimeout, other.operationTimeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(javaExecutable, jmeterHome, startupTimeout, operationTimeout);
    }

    @Override
    public String toString() {
        return "JMeter home " + jmeterHome;
    }

    private static String canonicalHome(String value) {
        Path home = Paths.get(value);
        try {
            return home.toRealPath().toString();
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("JMeter home must resolve before worker pooling: " + home, exception);
        }
    }
}
