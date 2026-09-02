package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalJMeterWorkerSemanticCacheTest {
    @Test
    void concurrentFirstObservationRunsOneLoaderAndSharesTheImmutableResult() throws Exception {
        LocalJMeterWorkerSemanticCache cache = new LocalJMeterWorkerSemanticCache();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        LocalJMeterGuiSemanticMetadata.Observation expected = provenObservation();
        LocalJMeterWorkerSemanticCache.Loader loader = () -> {
            attempts.incrementAndGet();
            loaderEntered.countDown();
            await(releaseLoader);
            return expected;
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LocalJMeterGuiSemanticMetadata.Observation> first =
                    executor.submit(() -> cache.get("component", loader));
            loaderEntered.await();
            Future<LocalJMeterGuiSemanticMetadata.Observation> second =
                    executor.submit(() -> cache.get("component", loader));
            releaseLoader.countDown();

            assertThat(first.get()).isSameAs(expected);
            assertThat(second.get()).isSameAs(expected);
            assertThat(attempts).hasValue(1);
            assertThat(cache.size()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void everyNegativeObservationClassIsAttemptedOnceUntilWorkerReplacement() throws Exception {
        for (LocalJMeterGuiSemanticMetadata.FailureReason reason
                : LocalJMeterGuiSemanticMetadata.FailureReason.values()) {
            AtomicInteger attempts = new AtomicInteger();
            LocalJMeterWorkerSemanticCache.Loader loader = () -> {
                attempts.incrementAndGet();
                return failedObservation(reason);
            };
            LocalJMeterWorkerSemanticCache firstWorker = new LocalJMeterWorkerSemanticCache();

            LocalJMeterGuiSemanticMetadata.Observation first = firstWorker.get("component", loader);
            LocalJMeterGuiSemanticMetadata.Observation reused = firstWorker.get("component", loader);
            LocalJMeterWorkerSemanticCache replacementWorker = new LocalJMeterWorkerSemanticCache();
            LocalJMeterGuiSemanticMetadata.Observation replacement =
                    replacementWorker.get("component", loader);

            assertThat(reused).as(reason.name()).isSameAs(first);
            assertThat(replacement).as(reason.name()).isNotSameAs(first);
            assertThat(attempts).as(reason.name()).hasValue(2);
            assertThat(firstWorker.size()).isEqualTo(1);
            assertThat(replacementWorker.size()).isEqualTo(1);
        }
    }

    private static LocalJMeterGuiSemanticMetadata.Observation provenObservation() {
        return new LocalJMeterGuiSemanticMetadata.Observation(
                Collections.singletonList(new LocalJMeterGuiSemanticMetadata.ScalarDescriptor(
                        "qa.property", "string", "")),
                Collections.<LocalJMeterGuiSemanticMetadata.Failure>emptyList(),
                new LocalJMeterGuiSemanticMetadata.Stats(1, 1, 1, 1, 0, 1L));
    }

    private static LocalJMeterGuiSemanticMetadata.Observation failedObservation(
            LocalJMeterGuiSemanticMetadata.FailureReason reason) {
        return new LocalJMeterGuiSemanticMetadata.Observation(
                Collections.<LocalJMeterGuiSemanticMetadata.ScalarDescriptor>emptyList(),
                Collections.singletonList(new LocalJMeterGuiSemanticMetadata.Failure(reason, reason.name())),
                new LocalJMeterGuiSemanticMetadata.Stats(0, 0, 0, 0, 0, 1L));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
