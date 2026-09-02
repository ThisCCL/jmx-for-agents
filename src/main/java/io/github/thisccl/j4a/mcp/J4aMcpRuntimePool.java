package io.github.thisccl.j4a.mcp;

import io.github.thisccl.j4a.validation.LocalJMeterWorkerClient;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResult;
import io.github.thisccl.j4a.validation.LocalJMeterHomeResolver;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

final class J4aMcpRuntimePool implements AutoCloseable {
    private final LocalJMeterWorkerClient workerClient = LocalJMeterWorkerClient.reusable();
    private final ExecutorService warmupExecutor;
    private final PrintStream diagnostics;
    private final AtomicBoolean warmupScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Future<?> warmup;

    J4aMcpRuntimePool() {
        this(System.err);
    }

    J4aMcpRuntimePool(PrintStream diagnostics) {
        this.diagnostics = diagnostics;
        this.warmupExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "j4a-mcp-runtime-warmup");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    LocalJMeterWorkerClient workerClient() {
        return workerClient;
    }

    LocalJMeterWorkerResult execute(LocalJMeterWorkerRequest request) {
        return workerClient.execute(request);
    }

    void warmUpDefault(Map<String, String> environment) {
        if (closed.get() || !warmupScheduled.compareAndSet(false, true)) {
            return;
        }
        final Map<String, String> startupEnvironment = new LinkedHashMap<String, String>(environment);
        warmup = warmupExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    Optional<Path> home = new LocalJMeterHomeResolver().resolve(null, startupEnvironment);
                    if (home.isPresent() && !closed.get()) {
                        LocalJMeterWorkerResult result = execute(LocalJMeterWorkerRequest.discoverComponents(home.get()));
                        if (!result.response().success() && !closed.get()) {
                            diagnostics.println("J4A MCP JMeter warm-up failed: " + result.response().message());
                        }
                    }
                } catch (RuntimeException exception) {
                    if (!closed.get()) {
                        diagnostics.println("J4A MCP JMeter warm-up failed: " + exception.getMessage());
                    }
                }
            }
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Future<?> activeWarmup = warmup;
        if (activeWarmup != null) {
            activeWarmup.cancel(true);
        }
        warmupExecutor.shutdownNow();
        workerClient.close();
    }
}
