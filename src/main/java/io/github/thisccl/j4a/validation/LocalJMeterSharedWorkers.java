package io.github.thisccl.j4a.validation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class LocalJMeterSharedWorkers {
    private static final int STDOUT_QUEUE_CAPACITY = 512;
    private static final int RESPONSE_STDOUT_LIMIT = 64 * 1024;

    static void readStdoutLines(InputStream input, BoundedLineQueue lines) throws IOException {
        LocalJMeterWorkerStreamCollector.BoundedLineReader reader =
                new LocalJMeterWorkerStreamCollector.BoundedLineReader(input, RESPONSE_STDOUT_LIMIT);
        LocalJMeterWorkerStreamCollector.Line line;
        while ((line = reader.readLine()) != null) {
            lines.offer(line);
        }
    }

    private final Map<LocalJMeterSharedWorkerKey, Worker> workers =
            new ConcurrentHashMap<LocalJMeterSharedWorkerKey, Worker>();
    private final Set<LocalJMeterSharedWorkerKey> recovering = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ProcessTerminator processTerminator;
    private final WorkerSelectionObserver selectionObserver;

    LocalJMeterSharedWorkers() {
        this(new ProcessTerminator() {
            @Override
            public boolean terminate(Process process, String workerId) {
                return LocalJMeterWorkerProcess.terminateProcessTreeUninterruptibly(process, workerId);
            }
        });
    }

    LocalJMeterSharedWorkers(ProcessTerminator processTerminator) {
        this(processTerminator, request -> { });
    }

    LocalJMeterSharedWorkers(ProcessTerminator processTerminator, WorkerSelectionObserver selectionObserver) {
        this.processTerminator = processTerminator;
        this.selectionObserver = selectionObserver;
    }

    LocalJMeterWorkerResult execute(LocalJMeterWorkerClient client, LocalJMeterWorkerRequest request) {
        LocalJMeterSharedWorkerKey key = new LocalJMeterSharedWorkerKey(client, request);
        while (true) {
            if (closed.get()) {
                throw new IllegalStateException("Local JMeter runtime pool is closed.");
            }
            Worker worker = workers.computeIfAbsent(key,
                    ignored -> new Worker(client, request, recovering.remove(key), processTerminator));
            selectionObserver.selected(request);
            if (closed.get()) {
                workers.remove(key, worker);
                worker.close();
                throw new IllegalStateException("Local JMeter runtime pool is closed.");
            }
            final Worker selectedWorker = worker;
            Runnable evict = new Runnable() {
                @Override
                public void run() {
                    workers.remove(key, selectedWorker);
                }
            };
            Runnable evictForRecovery = new Runnable() {
                @Override
                public void run() {
                    workers.remove(key, selectedWorker);
                    recovering.add(key);
                }
            };
            LocalJMeterWorkerResult result = worker.execute(request, evict, evictForRecovery);
            if (result != null) {
                return result;
            }
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WorkerCloseException failure = null;
        try {
            for (Map.Entry<LocalJMeterSharedWorkerKey, Worker> entry : workers.entrySet()) {
                try {
                    entry.getValue().close();
                } catch (RuntimeException exception) {
                    WorkerCloseException contextual = new WorkerCloseException(
                            "Unable to close local JMeter worker owned by " + entry.getKey() + ".", exception);
                    if (failure == null) {
                        failure = new WorkerCloseException(
                                "One or more local JMeter workers could not be closed.", null);
                    }
                    failure.addSuppressed(contextual);
                }
            }
        } finally {
            workers.clear();
            recovering.clear();
        }
        if (failure != null) {
            throw failure;
        }
    }

    interface ProcessTerminator {
        boolean terminate(Process process, String workerId);
    }

    interface WorkerSelectionObserver {
        void selected(LocalJMeterWorkerRequest request);
    }

    static final class WorkerCloseException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private WorkerCloseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Worker {
        private final LocalJMeterWorkerClient client;
        private final String workerId = UUID.randomUUID().toString();
        private Process process;
        private BufferedWriter writer;
        private LocalJMeterWorkerStreamCollector stderr;
        private LocalJMeterWorkerStreamCollector.Capture stderrGeneration;
        private BoundedLineQueue stdoutLines;
        private BoundedLineQueue.Generation stdoutGeneration;
        private Thread stdoutReader;
        private boolean closed;
        private final boolean recoveryRequest;
        private final ProcessTerminator processTerminator;
        private LocalJMeterWorkerResult terminalFailure;
        private int dispatchedRequests;

        private Worker(LocalJMeterWorkerClient client, LocalJMeterWorkerRequest request, boolean recoveryRequest,
                ProcessTerminator processTerminator) {
            this.client = client;
            this.recoveryRequest = recoveryRequest;
            this.processTerminator = processTerminator;
            start(request);
        }

        private boolean isAlive() {
            if (process == null) {
                return false;
            }
            try {
                process.exitValue();
                return false;
            } catch (IllegalThreadStateException exception) {
                return true;
            }
        }

        private synchronized LocalJMeterWorkerResult execute(
                LocalJMeterWorkerRequest request, Runnable evict, Runnable evictForRecovery) {
            if (terminalFailure != null) {
                evict.run();
                return null;
            }
            if (closed && dispatchedRequests > 0) {
                evict.run();
                return null;
            }
            Path dryRunCandidateDirectory = null;
            LocalJMeterWorkerRequest workerRequest = request.withRequestId(UUID.randomUUID().toString());
            try {
                if (!isAlive()) {
                    int exitCode = LocalJMeterWorkerClient.exitCode(process);
                    String stderrOutput = stderrText();
                    markClosedAndEvict(evict);
                    close();
                    terminalFailure = LocalJMeterWorkerClient.result(
                            LocalJMeterWorkerClient.startupTimeout(workerRequest, "", stderrOutput),
                            true,
                            exitCode);
                    return terminalFailure;
                }
                dryRunCandidateDirectory = LocalJMeterWorkerClient.createDryRunCandidateDirectory(request);
                if (dryRunCandidateDirectory != null) {
                    workerRequest = workerRequest.withDryRunCandidateDirectory(dryRunCandidateDirectory);
                }
                beginCaptureGeneration(true);
                dispatchedRequests++;
                LocalJMeterWorkerClient.recordWorkerProtocolInvocation(workerRequest);
                writer.write(workerRequest.toJsonLine());
                writer.newLine();
                writer.flush();
                LocalJMeterWorkerResponse preflightFailure = awaitPreflight(workerRequest);
                if (preflightFailure != null) {
                    return closeForFailure(preflightFailure, evictForRecovery);
                }
                beginCaptureGeneration(false);
                LocalJMeterWorkerResponse response = awaitResponse(workerRequest);
                if (response.disposition() == LocalJMeterWorkerDisposition.FATAL_FAILURE) {
                    return closeForFailure(response, evictForRecovery);
                }
                if (process.waitFor(500L, TimeUnit.MILLISECONDS)) {
                    int exitCode = process.exitValue();
                    markClosedAndEvict(evict);
                    close();
                    return LocalJMeterWorkerClient.result(response, true, exitCode);
                }
                return LocalJMeterWorkerClient.result(
                        response, false, LocalJMeterWorkerResult.EXIT_CODE_UNKNOWN);
            } catch (IOException exception) {
                return closeForFailure(LocalJMeterWorkerClient.startupFailure(workerRequest, exception), evictForRecovery);
            } catch (InterruptedException exception) {
                LocalJMeterWorkerResponse response = LocalJMeterWorkerClient.interrupted(workerRequest, exception);
                try {
                    markClosedAndEvict(evictForRecovery);
                    close();
                    terminalFailure = LocalJMeterWorkerClient.result(
                            response, true, LocalJMeterWorkerResult.EXIT_CODE_UNKNOWN);
                    return terminalFailure;
                } finally {
                    Thread.currentThread().interrupt();
                }
            } finally {
                LocalJMeterWorkerDryRunDirectories.deleteRecursively(dryRunCandidateDirectory);
            }
        }

        private void start(LocalJMeterWorkerRequest request) {
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(client.command(request, workerId));
                processBuilder.directory(Paths.get(request.jmeterHome()).resolve("bin").toFile());
                processBuilder.redirectErrorStream(false);
                process = processBuilder.start();
                LocalJMeterWorkerProcess.recordProcessId(process, workerId);
                writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                stdoutLines = new BoundedLineQueue(STDOUT_QUEUE_CAPACITY);
                startStdoutReader();
                stderr = LocalJMeterWorkerStreamCollector.start(process.getErrorStream());
                beginCaptureGeneration(false);
                awaitReady();
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                close();
            }
        }

        private void startStdoutReader() {
            final BoundedLineQueue lines = stdoutLines;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (InputStream input = process.getInputStream()) {
                        readStdoutLines(input, lines);
                    } catch (IOException ignored) {
                    }
                }
            }, "local-jmeter-worker-stdout");
            thread.setDaemon(true);
            thread.start();
            stdoutReader = thread;
        }

        private void awaitReady() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(client.startupTimeoutDuration().toMillis());
            while (System.nanoTime() < deadline) {
                String line = stdoutLines.poll(25L, TimeUnit.MILLISECONDS);
                if (LocalJMeterWorkerClient.READY_MARKER.equals(line)) {
                    return;
                }
                if (!isAlive()) {
                    return;
                }
            }
            if (readyMarkerQueued(stdoutLines)) {
                return;
            }
            close();
        }

        private static boolean readyMarkerQueued(BoundedLineQueue lines) {
            String line;
            while ((line = lines.poll()) != null) {
                if (LocalJMeterWorkerClient.READY_MARKER.equals(line)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean readyMarkerQueued(BlockingQueue<String> lines) {
            String line;
            while ((line = lines.poll()) != null) {
                if (LocalJMeterWorkerClient.READY_MARKER.equals(line)) {
                    return true;
                }
            }
            return false;
        }

        private LocalJMeterWorkerResponse awaitResponse(LocalJMeterWorkerRequest request) throws InterruptedException {
            long timeoutMillis = timeoutMillis(request);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            BoundedText responseStdout = new BoundedText(RESPONSE_STDOUT_LIMIT);
            while (System.nanoTime() < deadline) {
                String line = stdoutLines.poll(25L, TimeUnit.MILLISECONDS);
                if (line != null) {
                    responseStdout.appendLine(line);
                }
                if (line != null && line.trim().startsWith("{")) {
                    try {
                        LocalJMeterWorkerResponse response = LocalJMeterWorkerResponse.fromJsonLine(line.trim());
                        response.requireMatches(request);
                        return response;
                    } catch (IllegalArgumentException exception) {
                        return LocalJMeterWorkerClient.malformedProtocolResponse(
                                request, responseStdout.text(stdoutTruncation()), stderrText(), exception);
                    }
                }
                if (!isAlive()) {
                    awaitStdoutReaderAfterExit();
                    if (stdoutLinesPending()) {
                        continue;
                    }
                    return LocalJMeterWorkerResponse.fatalFailure(
                            request,
                            "LOCAL_JMETER_RUNTIME_ERROR",
                            "runtime",
                            null,
                            "Local JMeter worker exited before returning a response. Exit code: "
                                    + LocalJMeterWorkerClient.exitCode(process) + ".",
                            "rerun with --debug and verify the selected local JMeter home.",
                            responseStdout.text(stdoutTruncation()),
                            stderrText());
                }
            }
            return LocalJMeterWorkerClient.timeout(request, responseStdout.text(stdoutTruncation()), stderrText());
        }

        private LocalJMeterWorkerResponse awaitPreflight(LocalJMeterWorkerRequest request)
                throws InterruptedException {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(client.preflightTimeoutDuration().toMillis());
            BoundedText responseStdout = new BoundedText(RESPONSE_STDOUT_LIMIT);
            while (System.nanoTime() < deadline) {
                String line = stdoutLines.poll(25L, TimeUnit.MILLISECONDS);
                if (line != null) {
                    responseStdout.appendLine(line);
                }
                if (line != null && LocalJMeterWorkerClient.isPreflightReadyMarker(line, request)) {
                    return null;
                }
                if (line != null && line.trim().startsWith("{")) {
                    try {
                        LocalJMeterWorkerResponse response = LocalJMeterWorkerResponse.fromJsonLine(line.trim());
                        response.requireMatches(request);
                        return response;
                    } catch (IllegalArgumentException exception) {
                        return LocalJMeterWorkerClient.malformedProtocolResponse(
                                request, responseStdout.text(stdoutTruncation()), stderrText(), exception);
                    }
                }
                if (!isAlive()) {
                    awaitStdoutReaderAfterExit();
                    if (stdoutLinesPending()) {
                        continue;
                    }
                    return LocalJMeterWorkerResponse.fatalFailure(
                            request,
                            "LOCAL_JMETER_RUNTIME_ERROR",
                            "runtime",
                            null,
                            "Local JMeter worker exited during runtime preflight. Exit code: "
                                    + LocalJMeterWorkerClient.exitCode(process) + ".",
                            "rerun with --debug and verify the selected local JMeter home.",
                            responseStdout.text(stdoutTruncation()),
                            stderrText());
                }
            }
            return LocalJMeterWorkerClient.preflightTimeout(
                    request, responseStdout.text(stdoutTruncation()), stderrText());
        }

        private long timeoutMillis(LocalJMeterWorkerRequest request) {
            long timeoutMillis = client.timeoutFor(request).toMillis();
            return recoveryRequest
                    ? Math.max(timeoutMillis, client.startupTimeoutDuration().toMillis()) : timeoutMillis;
        }

        private void beginCaptureGeneration(boolean discardQueuedOutput) {
            BoundedLineQueue lines = stdoutLines;
            if (lines != null) {
                stdoutGeneration = lines.beginGeneration(discardQueuedOutput);
            }
            if (stderr != null) {
                stderrGeneration = stderr.beginGeneration();
            }
        }

        private Truncation stdoutTruncation() {
            return stdoutGeneration == null ? Truncation.none() : stdoutGeneration.truncation();
        }

        private String stderrText() {
            return stderr == null || stderrGeneration == null ? "" : stderr.text(stderrGeneration, "stderr");
        }

        private void awaitStdoutReaderAfterExit() {
            Thread reader = stdoutReader;
            if (reader == null) {
                return;
            }
            try {
                reader.join(1000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean stdoutLinesPending() {
            BoundedLineQueue lines = stdoutLines;
            return lines != null && !lines.isEmpty();
        }

        private LocalJMeterWorkerResult closeForFailure(LocalJMeterWorkerResponse response, Runnable evict) {
            markClosedAndEvict(evict);
            Process currentProcess = process;
            int exitCode = LocalJMeterWorkerClient.exitCode(currentProcess);
            close();
            if (exitCode == LocalJMeterWorkerResult.EXIT_CODE_UNKNOWN) {
                exitCode = LocalJMeterWorkerClient.exitCode(currentProcess);
            }
            terminalFailure = LocalJMeterWorkerClient.result(response, true, exitCode);
            return terminalFailure;
        }

        private void markClosedAndEvict(Runnable evict) {
            closed = true;
            evict.run();
        }

        private synchronized void close() {
            if (closed && process == null) {
                return;
            }
            closed = true;
            Process currentProcess = process;
            process = null;
            BufferedWriter currentWriter = writer;
            writer = null;
            stderr = null;
            stderrGeneration = null;
            stdoutLines = null;
            stdoutGeneration = null;
            stdoutReader = null;
            boolean terminated = currentProcess == null || processTerminator.terminate(currentProcess, workerId);
            try {
                if (currentWriter != null) {
                    currentWriter.close();
                }
            } catch (IOException ignored) {
            }
            if (!terminated) {
                throw new IllegalStateException("Unable to confirm local JMeter worker process-tree termination.");
            }
        }
    }

    static final class BoundedLineQueue {
        private final BlockingQueue<CapturedLine> lines;
        private final Object generationLock = new Object();
        private Generation activeGeneration = new Generation();

        BoundedLineQueue(int capacity) {
            this.lines = new LinkedBlockingQueue<CapturedLine>(capacity);
        }

        void offer(String line) {
            offer(CapturedLine.from(line));
        }

        void offer(LocalJMeterWorkerStreamCollector.Line line) {
            if (line.dropped()) {
                synchronized (generationLock) {
                    activeGeneration.recordDropped(line.droppedBytes());
                }
                return;
            }
            offer(CapturedLine.from(line));
        }

        private void offer(CapturedLine captured) {
            synchronized (generationLock) {
                while (!lines.offer(captured)) {
                    CapturedLine dropped = lines.poll();
                    if (dropped != null) {
                        activeGeneration.record(dropped);
                    }
                }
            }
        }

        private String poll(long timeout, TimeUnit unit) throws InterruptedException {
            CapturedLine captured = lines.poll(timeout, unit);
            return captured == null ? null : captured.text;
        }

        String poll() {
            CapturedLine captured = lines.poll();
            return captured == null ? null : captured.text;
        }

        Generation beginGeneration(boolean discardQueuedOutput) {
            synchronized (generationLock) {
                if (discardQueuedOutput) {
                    lines.clear();
                }
                activeGeneration.retire();
                activeGeneration = new Generation();
                return activeGeneration;
            }
        }

        Generation beginGeneration() {
            return beginGeneration(true);
        }

        private boolean isEmpty() {
            return lines.isEmpty();
        }

        private boolean truncated() {
            return activeGeneration.truncation().truncated();
        }

        static final class Generation {
            private long droppedLines;
            private long droppedBytes;
            private boolean retired;

            private synchronized void record(CapturedLine line) {
                if (!retired) {
                    droppedLines++;
                    droppedBytes += line.bytes.length;
                }
            }

            private synchronized void recordDropped(long lineBytes) {
                if (!retired) {
                    droppedLines++;
                    droppedBytes += lineBytes;
                }
            }

            synchronized Truncation truncation() {
                return new Truncation(droppedLines, droppedBytes);
            }

            private synchronized void retire() {
                retired = true;
            }
        }
    }

    private static final class CapturedLine {
        private final String text;
        private final byte[] bytes;

        private CapturedLine(String text, byte[] bytes) {
            this.text = text;
            this.bytes = bytes;
        }

        private static CapturedLine from(String line) {
            String rendered = line + System.lineSeparator();
            return new CapturedLine(line, rendered.getBytes(StandardCharsets.UTF_8));
        }

        private static CapturedLine from(LocalJMeterWorkerStreamCollector.Line line) {
            return new CapturedLine(line.text(), line.bytes());
        }
    }

    static final class Truncation {
        private final long droppedLines;
        private final long droppedBytes;

        Truncation(long droppedLines, long droppedBytes) {
            this.droppedLines = droppedLines;
            this.droppedBytes = droppedBytes;
        }

        static Truncation none() {
            return new Truncation(0L, 0L);
        }

        private Truncation plus(Truncation other) {
            return new Truncation(droppedLines + other.droppedLines, droppedBytes + other.droppedBytes);
        }

        boolean truncated() {
            return droppedLines > 0L || droppedBytes > 0L;
        }

        String marker(String streamName) {
            return "[" + streamName + " truncated; dropped " + droppedLines + " lines, "
                    + droppedBytes + " bytes]" + System.lineSeparator();
        }
    }

    static final class BoundedText {
        private final int limit;
        private final java.util.ArrayDeque<CapturedLine> lines = new java.util.ArrayDeque<CapturedLine>();
        private int retainedBytes;
        private Truncation localTruncation = Truncation.none();

        BoundedText(int limit) {
            this.limit = limit;
        }

        void appendLine(String line) {
            CapturedLine captured = CapturedLine.from(line);
            lines.addLast(captured);
            retainedBytes += captured.bytes.length;
            trimBody();
        }

        String text(Truncation upstreamTruncation) {
            Truncation combined = localTruncation.plus(upstreamTruncation);
            if (!combined.truncated()) {
                return body();
            }
            String marker = combined.marker("stdout");
            while (marker.getBytes(StandardCharsets.UTF_8).length + retainedBytes > limit && !lines.isEmpty()) {
                recordDropped(lines.removeFirst());
                combined = localTruncation.plus(upstreamTruncation);
                marker = combined.marker("stdout");
            }
            return marker + body();
        }

        private void trimBody() {
            while (retainedBytes > limit && !lines.isEmpty()) {
                recordDropped(lines.removeFirst());
            }
        }

        private void recordDropped(CapturedLine line) {
            retainedBytes -= line.bytes.length;
            localTruncation = localTruncation.plus(new Truncation(1L, line.bytes.length));
        }

        private String body() {
            StringBuilder body = new StringBuilder();
            for (CapturedLine line : lines) {
                body.append(line.text).append(System.lineSeparator());
            }
            return body.toString();
        }
    }
}
