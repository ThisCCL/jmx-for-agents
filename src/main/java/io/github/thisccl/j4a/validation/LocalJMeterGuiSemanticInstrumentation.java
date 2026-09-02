package io.github.thisccl.j4a.validation;

import java.util.concurrent.atomic.AtomicLong;

final class LocalJMeterGuiSemanticInstrumentation {
    private static final AtomicLong OBSERVATION_ATTEMPTS = new AtomicLong();
    private static final AtomicLong GUI_CONSTRUCTIONS = new AtomicLong();
    private static final AtomicLong DIFFERENTIAL_PROBES = new AtomicLong();

    private LocalJMeterGuiSemanticInstrumentation() {
    }

    static void observationAttempted() {
        OBSERVATION_ATTEMPTS.incrementAndGet();
    }

    static void guiConstructed() {
        GUI_CONSTRUCTIONS.incrementAndGet();
    }

    static void differentialProbeStarted() {
        DIFFERENTIAL_PROBES.incrementAndGet();
    }

    static Snapshot snapshot() {
        return new Snapshot(
                OBSERVATION_ATTEMPTS.get(), GUI_CONSTRUCTIONS.get(), DIFFERENTIAL_PROBES.get());
    }

    static void reset() {
        OBSERVATION_ATTEMPTS.set(0L);
        GUI_CONSTRUCTIONS.set(0L);
        DIFFERENTIAL_PROBES.set(0L);
    }

    static final class Snapshot {
        private final long observationAttempts;
        private final long guiConstructions;
        private final long differentialProbes;

        private Snapshot(long observationAttempts, long guiConstructions, long differentialProbes) {
            this.observationAttempts = observationAttempts;
            this.guiConstructions = guiConstructions;
            this.differentialProbes = differentialProbes;
        }

        long observationAttempts() {
            return observationAttempts;
        }

        long guiConstructions() {
            return guiConstructions;
        }

        long differentialProbes() {
            return differentialProbes;
        }
    }
}
