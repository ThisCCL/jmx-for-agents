package io.github.thisccl.j4a.validation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

final class LocalJMeterWorkerSemanticCache {
    private final ConcurrentMap<String, FutureTask<LocalJMeterGuiSemanticMetadata.Observation>> observations =
            new ConcurrentHashMap<String, FutureTask<LocalJMeterGuiSemanticMetadata.Observation>>();

    LocalJMeterGuiSemanticMetadata.Observation get(String component, Loader loader) {
        FutureTask<LocalJMeterGuiSemanticMetadata.Observation> candidate =
                new FutureTask<LocalJMeterGuiSemanticMetadata.Observation>(loader::load);
        FutureTask<LocalJMeterGuiSemanticMetadata.Observation> selected =
                observations.putIfAbsent(component, candidate);
        if (selected == null) {
            selected = candidate;
            candidate.run();
        }
        try {
            return selected.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting semantic observation", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("Semantic observation failed", cause);
        }
    }

    int size() {
        return observations.size();
    }

    void clear() {
        observations.clear();
    }

    interface Loader {
        LocalJMeterGuiSemanticMetadata.Observation load();
    }
}
