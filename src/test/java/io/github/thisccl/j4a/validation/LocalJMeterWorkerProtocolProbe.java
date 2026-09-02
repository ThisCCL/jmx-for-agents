package io.github.thisccl.j4a.validation;

import java.util.concurrent.atomic.AtomicInteger;

public final class LocalJMeterWorkerProtocolProbe implements AutoCloseable {
    private final AtomicInteger invocations = new AtomicInteger();
    private final LocalJMeterWorkerClient.WorkerProtocolInvocationCapture capture;

    private LocalJMeterWorkerProtocolProbe() {
        this.capture = LocalJMeterWorkerClient.captureWorkerProtocolInvocations(request -> invocations.incrementAndGet());
    }

    public static LocalJMeterWorkerProtocolProbe start() {
        return new LocalJMeterWorkerProtocolProbe();
    }

    public int invocations() {
        return invocations.get();
    }

    @Override
    public void close() {
        capture.close();
    }
}
