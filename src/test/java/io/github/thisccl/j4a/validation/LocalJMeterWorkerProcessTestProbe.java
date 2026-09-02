package io.github.thisccl.j4a.validation;

import java.util.LinkedHashSet;
import java.util.Set;

public final class LocalJMeterWorkerProcessTestProbe {
    private LocalJMeterWorkerProcessTestProbe() {
    }

    public static Set<Long> recordedProcessIds() {
        return new LinkedHashSet<Long>(LocalJMeterWorkerProcess.recordedProcessIdsForTesting());
    }
}
