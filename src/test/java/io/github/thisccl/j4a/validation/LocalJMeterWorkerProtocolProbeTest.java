package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalJMeterWorkerProtocolProbeTest {
    @Test
    void probeIsThreadLocalAndRestoresThePriorCaptureOnClose() throws Exception {
        LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.componentDetails(
                Paths.get("probe.jmx"), "org.example.Component");

        try (LocalJMeterWorkerProtocolProbe outer = LocalJMeterWorkerProtocolProbe.start()) {
            LocalJMeterWorkerClient.recordWorkerProtocolInvocation(request);
            assertThat(outer.invocations()).isEqualTo(1);

            AtomicInteger childInvocations = new AtomicInteger();
            Thread child = new Thread(() -> {
                try (LocalJMeterWorkerProtocolProbe nested = LocalJMeterWorkerProtocolProbe.start()) {
                    LocalJMeterWorkerClient.recordWorkerProtocolInvocation(request);
                    childInvocations.set(nested.invocations());
                }
            });
            child.start();
            child.join();
            assertThat(childInvocations).hasValue(1);
            assertThat(outer.invocations()).isEqualTo(1);

            try (LocalJMeterWorkerProtocolProbe nested = LocalJMeterWorkerProtocolProbe.start()) {
                LocalJMeterWorkerClient.recordWorkerProtocolInvocation(request);
                assertThat(nested.invocations()).isEqualTo(1);
                assertThat(outer.invocations()).isEqualTo(1);
            }
            LocalJMeterWorkerClient.recordWorkerProtocolInvocation(request);
            assertThat(outer.invocations()).isEqualTo(2);
        }
    }
}
