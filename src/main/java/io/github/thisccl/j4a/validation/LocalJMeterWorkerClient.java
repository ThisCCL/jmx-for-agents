package io.github.thisccl.j4a.validation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalJMeterWorkerClient implements AutoCloseable {
    static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(60);
    static final Duration DEFAULT_PREFLIGHT_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofSeconds(60);
    static final String REFERENCE_MODE_PROPERTY = "j4a.worker.reference.mode";
    static final String SESSION_REFERENCE_MODE = "session";
    static final String READY_MARKER = "JMX_AGENT_LOCAL_WORKER_READY";
    private static final String PREFLIGHT_READY_MARKER_PREFIX =
            "JMX_AGENT_LOCAL_WORKER_PREFLIGHT_READY:";
    private static final ThreadLocal<WorkerProtocolInvocationObserver> WORKER_PROTOCOL_INVOCATION_OBSERVER =
            new ThreadLocal<WorkerProtocolInvocationObserver>();
    private final String javaExecutable;
    private final Duration startupTimeout;
    private final Duration preflightTimeout;
    private final Duration operationTimeout;
    private final List<String> jvmArgs;
    private final LocalJMeterSharedWorkers sharedWorkers;
    private final String workerMainClass;

    public LocalJMeterWorkerClient() {
        this(LocalJMeterWorkerProcess.javaExecutable(), DEFAULT_STARTUP_TIMEOUT, DEFAULT_PREFLIGHT_TIMEOUT,
                DEFAULT_OPERATION_TIMEOUT,
                Collections.emptyList(), null);
    }

    private LocalJMeterWorkerClient(
            String javaExecutable, Duration startupTimeout, Duration preflightTimeout, Duration operationTimeout) {
        this(javaExecutable, startupTimeout, preflightTimeout, operationTimeout, Collections.emptyList(), null);
    }

    private LocalJMeterWorkerClient(
            String javaExecutable, Duration startupTimeout, Duration preflightTimeout, Duration operationTimeout,
            List<String> jvmArgs) {
        this(javaExecutable, startupTimeout, preflightTimeout, operationTimeout, jvmArgs, null);
    }

    private LocalJMeterWorkerClient(
            String javaExecutable, Duration startupTimeout, Duration preflightTimeout, Duration operationTimeout,
            List<String> jvmArgs,
            LocalJMeterSharedWorkers sharedWorkers) {
        this.javaExecutable = javaExecutable;
        this.startupTimeout = startupTimeout;
        this.preflightTimeout = preflightTimeout;
        this.operationTimeout = operationTimeout;
        this.jvmArgs = Collections.unmodifiableList(new ArrayList<>(jvmArgs));
        this.sharedWorkers = sharedWorkers;
        this.workerMainClass = LocalJMeterValidationWorker.class.getName();
    }

    static LocalJMeterWorkerClient forWorkerMain(String workerMainClass) {
        return new LocalJMeterWorkerClient(LocalJMeterWorkerProcess.javaExecutable(),
                DEFAULT_STARTUP_TIMEOUT, DEFAULT_PREFLIGHT_TIMEOUT, DEFAULT_OPERATION_TIMEOUT,
                Collections.<String>emptyList(), null,
                workerMainClass);
    }

    private LocalJMeterWorkerClient(
            String javaExecutable, Duration startupTimeout, Duration preflightTimeout, Duration operationTimeout,
            List<String> jvmArgs,
            LocalJMeterSharedWorkers sharedWorkers, String workerMainClass) {
        this.javaExecutable = javaExecutable;
        this.startupTimeout = startupTimeout;
        this.preflightTimeout = preflightTimeout;
        this.operationTimeout = operationTimeout;
        this.jvmArgs = Collections.unmodifiableList(new ArrayList<>(jvmArgs));
        this.sharedWorkers = sharedWorkers;
        this.workerMainClass = workerMainClass;
    }

    static LocalJMeterWorkerClient forJavaExecutable(String javaExecutable) {
        return new LocalJMeterWorkerClient(
                javaExecutable, DEFAULT_STARTUP_TIMEOUT, DEFAULT_PREFLIGHT_TIMEOUT, DEFAULT_OPERATION_TIMEOUT);
    }

    static LocalJMeterWorkerClient withTimeouts(
            Duration startupTimeout, Duration preflightTimeout, Duration operationTimeout) {
        return new LocalJMeterWorkerClient(
                LocalJMeterWorkerProcess.javaExecutable(), startupTimeout, preflightTimeout, operationTimeout);
    }

    public static LocalJMeterWorkerClient reusable() {
        return reusable(DEFAULT_STARTUP_TIMEOUT, DEFAULT_PREFLIGHT_TIMEOUT, DEFAULT_OPERATION_TIMEOUT);
    }

    static LocalJMeterWorkerClient reusable(
            Duration startupTimeout, Duration preflightTimeout, Duration operationTimeout) {
        return new LocalJMeterWorkerClient(LocalJMeterWorkerProcess.javaExecutable(), startupTimeout,
                preflightTimeout, operationTimeout, sessionModeJvmArguments(), new LocalJMeterSharedWorkers());
    }

    static LocalJMeterWorkerClient reusable(LocalJMeterSharedWorkers.ProcessTerminator processTerminator) {
        return new LocalJMeterWorkerClient(LocalJMeterWorkerProcess.javaExecutable(), DEFAULT_STARTUP_TIMEOUT,
                DEFAULT_PREFLIGHT_TIMEOUT, DEFAULT_OPERATION_TIMEOUT, sessionModeJvmArguments(),
                new LocalJMeterSharedWorkers(processTerminator));
    }

    private static List<String> sessionModeJvmArguments() {
        return Collections.singletonList(
                "-D" + REFERENCE_MODE_PROPERTY + "=" + SESSION_REFERENCE_MODE);
    }

    public LocalJMeterWorkerResult execute(LocalJMeterWorkerRequest request) {
        if (sharedWorkers != null) {
            return sharedWorkers.execute(this, request);
        }
        return executeOneShot(request);
    }

    static WorkerProtocolInvocationCapture captureWorkerProtocolInvocations(
            WorkerProtocolInvocationObserver observer) {
        WorkerProtocolInvocationObserver previous = WORKER_PROTOCOL_INVOCATION_OBSERVER.get();
        WORKER_PROTOCOL_INVOCATION_OBSERVER.set(observer);
        return new WorkerProtocolInvocationCapture(previous);
    }

    static void recordWorkerProtocolInvocation(LocalJMeterWorkerRequest request) {
        WorkerProtocolInvocationObserver observer = WORKER_PROTOCOL_INVOCATION_OBSERVER.get();
        if (observer != null) {
            observer.invoked(request);
        }
    }

    interface WorkerProtocolInvocationObserver {
        void invoked(LocalJMeterWorkerRequest request);
    }

    static final class WorkerProtocolInvocationCapture implements AutoCloseable {
        private final WorkerProtocolInvocationObserver previous;
        private final AtomicBoolean closed = new AtomicBoolean();

        private WorkerProtocolInvocationCapture(WorkerProtocolInvocationObserver previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                if (previous == null) {
                    WORKER_PROTOCOL_INVOCATION_OBSERVER.remove();
                } else {
                    WORKER_PROTOCOL_INVOCATION_OBSERVER.set(previous);
                }
            }
        }
    }

    @Override
    public void close() {
        if (sharedWorkers != null) {
            sharedWorkers.close();
        }
    }

    private LocalJMeterWorkerResult executeOneShot(LocalJMeterWorkerRequest request) {
        Process process = null;
        LocalJMeterWorkerStreamCollector stdout = null;
        LocalJMeterWorkerStreamCollector stderr = null;
        String workerId = UUID.randomUUID().toString();
        Path dryRunCandidateDirectory = null;
        LocalJMeterWorkerRequest workerRequest = request.withRequestId(UUID.randomUUID().toString());
        try {
            dryRunCandidateDirectory = createDryRunCandidateDirectory(workerRequest);
            if (dryRunCandidateDirectory != null) {
                workerRequest = workerRequest.withDryRunCandidateDirectory(dryRunCandidateDirectory);
            }
            ProcessBuilder processBuilder = new ProcessBuilder(command(workerRequest, workerId));
            processBuilder.directory(Paths.get(workerRequest.jmeterHome()).resolve("bin").toFile());
            processBuilder.redirectErrorStream(false);
            process = processBuilder.start();
            LocalJMeterWorkerProcess.recordProcessId(process, workerId);
            stdout = LocalJMeterWorkerStreamCollector.start(process.getInputStream());
            stderr = LocalJMeterWorkerStreamCollector.start(process.getErrorStream());
            if (!awaitStartup(process, stdout, stderr)) {
                boolean exited = LocalJMeterWorkerProcess.terminateProcessTree(process, workerId);
                if (!exited) {
                    throw new IllegalStateException("Unable to confirm local JMeter worker process-tree termination.");
                }
                return result(startupTimeout(workerRequest, stdout.text(), stderr.text()), true,
                        exitCode(process));
            }
            writeRequest(process.getOutputStream(), workerRequest);
            PreflightWait preflight = awaitPreflight(process, stdout, stderr, workerRequest);
            if (preflight == PreflightWait.TIMED_OUT) {
                boolean exited = LocalJMeterWorkerProcess.terminateProcessTree(process, workerId);
                if (!exited) {
                    throw new IllegalStateException("Unable to confirm local JMeter worker process-tree termination.");
                }
                return result(preflightTimeout(workerRequest, stdout.text(), stderr.text()), true,
                        exitCode(process));
            }
            if (preflight == PreflightWait.EXITED) {
                int exitCode = process.exitValue();
                return result(parseResponse(workerRequest, stdout.text(), stderr.text(), exitCode), true, exitCode);
            }
            boolean finished = process.waitFor(timeoutFor(workerRequest).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                boolean exited = LocalJMeterWorkerProcess.terminateProcessTree(process, workerId);
                if (!exited) {
                    throw new IllegalStateException("Unable to confirm local JMeter worker process-tree termination.");
                }
                return result(timeout(workerRequest, stdout.text(), stderr.text()), true, exitCode(process));
            }
            int exitCode = process.exitValue();
            String stdoutText = stdout.text();
            String stderrText = stderr.text();
            LocalJMeterWorkerResponse response = parseResponse(workerRequest, stdoutText, stderrText, exitCode);
            return result(response, true, exitCode);
        } catch (IOException exception) {
            LocalJMeterWorkerResponse response = startupFailure(workerRequest, exception);
            if (process != null && stdout != null && stderr != null) {
                String stdoutText = stdout.text();
                String stderrText = stderr.text();
                if (!stdoutText.trim().isEmpty() || !stderrText.trim().isEmpty()) {
                    response = parseResponse(workerRequest, stdoutText, stderrText, exitCode(process));
                }
            }
            if (process == null) {
                return result(response, true, LocalJMeterWorkerResult.EXIT_CODE_UNKNOWN);
            }
            try {
                if (!LocalJMeterWorkerProcess.terminateProcessTree(process, workerId)) {
                    throw new IllegalStateException(
                            "Unable to confirm local JMeter worker process-tree termination.", exception);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted before local JMeter worker process-tree termination was confirmed.", interrupted);
            }
            return result(response, true, exitCode(process));
        } catch (InterruptedException exception) {
            try {
                if (process != null
                        && !LocalJMeterWorkerProcess.terminateProcessTreeQuietly(process, workerId)) {
                    throw new IllegalStateException(
                            "Unable to confirm local JMeter worker process-tree termination.", exception);
                }
                return result(interrupted(workerRequest, exception), true,
                        LocalJMeterWorkerResult.EXIT_CODE_UNKNOWN);
            } finally {
                Thread.currentThread().interrupt();
            }
        } finally {
            LocalJMeterWorkerDryRunDirectories.deleteRecursively(dryRunCandidateDirectory);
        }
    }

    static Path createDryRunCandidateDirectory(LocalJMeterWorkerRequest request) throws IOException {
        if (!"applyPatch".equals(request.operation()) || request.targetPath() != null) {
            return null;
        }
        return LocalJMeterWorkerDryRunDirectories.create();
    }

    List<String> command(LocalJMeterWorkerRequest request, String workerId) {
        List<String> command = new ArrayList<>();
        Path jmeterHome = Paths.get(request.jmeterHome());
        command.add(javaExecutable);
        command.addAll(jvmArgs);
        LocalJMeterWorkerProcess.addRequiredJvmArguments(command);
        command.add("-Duser.home=" + System.getProperty("user.home", ""));
        command.add("-Dj4a.worker.id=" + workerId);
        String semanticEvidenceDisabled = System.getProperty(
                LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY);
        if (semanticEvidenceDisabled != null) {
            command.add("-D" + LocalJMeterGuiSemanticMetadata.DISABLED_PROPERTY
                    + "=" + semanticEvidenceDisabled);
        }
        command.add("-cp");
        command.add(LocalJMeterClasspath.localFirstClasspath(jmeterHome));
        command.add(workerMainClass);
        return command;
    }

    Duration timeoutFor(LocalJMeterWorkerRequest request) {
        return operationTimeout;
    }

    private static void writeRequest(OutputStream output, LocalJMeterWorkerRequest request) throws IOException {
        output.write((request.toJsonLine() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        output.flush();
        output.close();
    }

    private boolean awaitStartup(
            Process process, LocalJMeterWorkerStreamCollector stdout, LocalJMeterWorkerStreamCollector stderr)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupTimeout.toMillis());
        while (System.nanoTime() < deadline) {
            if (stdout.contains(READY_MARKER) || stderr.contains(READY_MARKER)) {
                return true;
            }
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException ignored) {
            }
            Thread.sleep(25L);
        }
        return stdout.contains(READY_MARKER) || stderr.contains(READY_MARKER);
    }

    private PreflightWait awaitPreflight(
            Process process,
            LocalJMeterWorkerStreamCollector stdout,
            LocalJMeterWorkerStreamCollector stderr,
            LocalJMeterWorkerRequest request) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(preflightTimeout.toMillis());
        String marker = preflightReadyMarker(request);
        while (System.nanoTime() < deadline) {
            if (stdout.contains(marker) || stderr.contains(marker)) {
                return PreflightWait.READY;
            }
            if (workerExited(process)) {
                return PreflightWait.EXITED;
            }
            Thread.sleep(25L);
        }
        return stdout.contains(marker) || stderr.contains(marker)
                ? PreflightWait.READY : PreflightWait.TIMED_OUT;
    }

    static String preflightReadyMarker(LocalJMeterWorkerRequest request) {
        return PREFLIGHT_READY_MARKER_PREFIX + request.requestId();
    }

    static boolean isPreflightReadyMarker(String line, LocalJMeterWorkerRequest request) {
        return preflightReadyMarker(request).equals(line);
    }

    private static LocalJMeterWorkerResponse parseResponse(
            LocalJMeterWorkerRequest request, String stdout, String stderr, int exitCode) {
        for (String line : stdout.split("\\r?\\n")) {
            if (line.trim().startsWith("{")) {
                try {
                    LocalJMeterWorkerResponse response = LocalJMeterWorkerResponse.fromJsonLine(line.trim());
                    response.requireMatches(request);
                    return response;
                } catch (IllegalArgumentException exception) {
                    return malformedProtocolResponse(request, stdout, stderr, exception);
                }
            }
        }
        return localRuntimeFailure(request,
                "Local JMeter worker exited without a protocol response. Exit code: " + exitCode + ".",
                "rerun with --debug and verify the selected local JMeter home.", stdout, stderr);
    }

    static LocalJMeterWorkerResponse parseResponseForTesting(
            LocalJMeterWorkerRequest request, String stdout, String stderr, int exitCode) {
        return parseResponse(request, stdout, stderr, exitCode);
    }

    static LocalJMeterWorkerResponse malformedProtocolResponse(
            LocalJMeterWorkerRequest request, String stdout, String stderr, IllegalArgumentException exception) {
        return localRuntimeFailure(request,
                "Local JMeter worker returned a malformed protocol response: " + exception.getMessage(),
                "rerun with --debug and verify the selected local JMeter home.", stdout, stderr);
    }

    static int exitCode(Process process) {
        if (process == null) {
            return LocalJMeterWorkerResult.EXIT_CODE_UNKNOWN;
        }
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException exception) {
            return LocalJMeterWorkerResult.EXIT_CODE_UNKNOWN;
        }
    }

    private static boolean workerExited(Process process) {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException exception) {
            return false;
        }
    }

    static LocalJMeterWorkerResponse startupFailure(LocalJMeterWorkerRequest request, IOException exception) {
        return localRuntimeFailure(request, "Unable to start isolated local JMeter worker: " + exception.getMessage(),
                "pass --jmeter-home or set JMX_AGENT_JMETER_HOME/JMETER_HOME to a valid local JMeter home.",
                "", exception.toString());
    }

    static LocalJMeterWorkerResponse timeout(
            LocalJMeterWorkerRequest request, String stdout, String stderr) {
        return localRuntimeFailure(request, "Local JMeter worker timed out while running " + request.operation() + ".",
                "retry with a local JMeter home whose plugins initialize promptly.",
                stdout, stderr);
    }

    static LocalJMeterWorkerResponse preflightTimeout(
            LocalJMeterWorkerRequest request, String stdout, String stderr) {
        return localRuntimeFailure(request, "Local JMeter worker runtime preflight timed out before operation dispatch.",
                "retry with a local JMeter home whose libraries can be fingerprinted promptly.",
                stdout, stderr);
    }

    static LocalJMeterWorkerResponse startupTimeout(
            LocalJMeterWorkerRequest request, String stdout, String stderr) {
        return localRuntimeFailure(request, "Local JMeter worker startup timed out before protocol readiness.",
                "retry with a local JMeter home whose plugins initialize promptly.",
                stdout, stderr);
    }

    static LocalJMeterWorkerResponse interrupted(
            LocalJMeterWorkerRequest request, InterruptedException exception) {
        return localRuntimeFailure(request, "Interrupted while waiting for isolated local JMeter worker.",
                "rerun the command after the interruption.", "", exception.toString());
    }

    private static LocalJMeterWorkerResponse localRuntimeFailure(
            LocalJMeterWorkerRequest request, String message, String suggestedAction, String stdout, String stderr) {
        return LocalJMeterWorkerResponse.fatalFailure(
                request,
                "LOCAL_JMETER_RUNTIME_ERROR",
                "runtime",
                null,
                message,
                suggestedAction,
                stdout,
                stderr);
    }

    static LocalJMeterWorkerResult result(LocalJMeterWorkerResponse response, boolean exited, int exitCode) {
        return new LocalJMeterWorkerResult(response, exited, exitCode);
    }

    String javaExecutable() {
        return javaExecutable;
    }

    Duration startupTimeoutDuration() {
        return startupTimeout;
    }

    Duration preflightTimeoutDuration() {
        return preflightTimeout;
    }

    Duration operationTimeoutDuration() {
        return operationTimeout;
    }

    private enum PreflightWait {
        READY,
        EXITED,
        TIMED_OUT
    }

}
