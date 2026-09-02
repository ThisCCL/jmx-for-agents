package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.apply.ApplyFailureDiagnostic;
import io.github.thisccl.j4a.apply.ApplyFailureException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LocalJMeterValidationWorker {
    private static final String READY_MARKER = "JMX_AGENT_LOCAL_WORKER_READY";
    private static final int CAPTURE_LIMIT_BYTES = 16 * 1024;

    private LocalJMeterValidationWorker() {
    }

    public static void main(String[] args) {
        configureUtf8Console();
        if (args.length == 2) {
            runLegacyValidation(args);
            return;
        }
        try {
            LocalJMeterWorkerOperations operations = LocalJMeterWorkerOperations.forStartupMode(
                    System.getProperty(LocalJMeterWorkerClient.REFERENCE_MODE_PROPERTY));
            runProtocol(operations);
            System.exit(0);
        } catch (Throwable throwable) {
            System.err.println("Local worker protocol failure: " + throwable.getMessage());
            System.exit(5);
        }
    }

    private static void runLegacyValidation(String[] args) {
        try {
            Path jmeterHome = Paths.get(args[1]);
            LocalJMeterWorkerRuntime.initialize(jmeterHome);
            LocalJMeterWorkerOperations.validate(Paths.get(args[0]), jmeterHome);
            System.exit(0);
        } catch (Throwable throwable) {
            Throwable root = unwrap(throwable);
            System.err.println(LocalJMeterWorkerJmx.semanticMessage(root));
            System.exit("PLUGIN_CLASS_MISSING".equals(LocalJMeterWorkerJmx.errorCode(root)) ? 5 : 1);
        }
    }

    private static void runProtocol(LocalJMeterWorkerOperations operations) throws Exception {
        System.out.println(READY_MARKER);
        System.out.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            LocalJMeterWorkerRequest request = LocalJMeterWorkerRequest.fromJsonLine(line);
            LocalJMeterWorkerResponse response = executeCapturingOutput(operations, request);
            System.out.println(response.toJsonLine());
            System.out.flush();
        }
    }

    private static LocalJMeterWorkerResponse executeCapturingOutput(
            LocalJMeterWorkerOperations operations, LocalJMeterWorkerRequest request) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        BoundedOutputCapture stdout = new BoundedOutputCapture("stdout", CAPTURE_LIMIT_BYTES);
        BoundedOutputCapture stderr = new BoundedOutputCapture("stderr", CAPTURE_LIMIT_BYTES);
        try {
            System.setOut(utf8PrintStream(stdout));
            System.setErr(utf8PrintStream(stderr));
            operations.preflight(request);
            originalOut.println(LocalJMeterWorkerClient.preflightReadyMarker(request));
            originalOut.flush();
            String payload = operations.execute(request);
            return LocalJMeterWorkerResponse.success(
                    request,
                    "Local worker operation completed: " + request.operation(),
                    payload,
                    stdout.text(),
                    stderr.text());
        } catch (Throwable throwable) {
            Throwable root = unwrap(throwable);
            ReferenceFailure referenceFailure = cause(root, ReferenceFailure.class);
            if (referenceFailure != null) {
                ApplyFailureDiagnostic diagnostic = applyDiagnostic(
                        request, root, referenceFailure.code(), false);
                return diagnostic == null ? LocalJMeterWorkerResponse.failure(
                        request,
                        referenceFailure.code(),
                        referenceFailure.category(),
                        null,
                        referenceFailure.getMessage(),
                        referenceFailure.suggestedAction(),
                        stdout.text(), stderr.text())
                        : LocalJMeterWorkerResponse.failure(
                        request, referenceFailure.code(), referenceFailure.category(), null,
                        referenceFailure.getMessage(), referenceFailure.suggestedAction(),
                        stdout.text(), stderr.text(), diagnostic);
            }
            String errorCode = LocalJMeterWorkerJmx.errorCode(root);
            String semanticMessage = LocalJMeterWorkerJmx.semanticMessage(root);
            String message = "applyPatch".equals(request.operation())
                    ? ("FILESYSTEM_WRITE_ERROR".equals(errorCode)
                            ? "Local JMeter apply could not access its authorized target directory."
                            : semanticMessage)
                    : LocalJMeterWorkerJmx.isUnknownComponentCategory(root)
                    ? semanticMessage
                    : semanticMessage
                            + ". Phase: " + request.operation()
                            + ". Component: " + value(request.component())
                            + ". Parent: unavailable"
                            + ". JMeter home: " + value(request.jmeterHome())
                            + ". File: " + value(request.jmxPath());
            if (root instanceof LocalJMeterWorkerRuntime.InitializationException
                    || root instanceof LocalPropertyGraphRuntimeContext.InitializationException
                    || "JMETER_MENU_REGISTRY_INCOMPATIBLE".equals(errorCode)) {
                ApplyFailureDiagnostic diagnostic = applyDiagnostic(request, root, errorCode, true);
                return LocalJMeterWorkerResponse.fatalFailure(
                        request, errorCode, LocalJMeterWorkerJmx.category(errorCode, request.operation()),
                        LocalJMeterWorkerJmx.unresolvedClassName(root), message,
                        recovery(diagnostic, errorCode, root), stdout.text(), stderr.text(), diagnostic);
            }
            ApplyFailureDiagnostic diagnostic = applyDiagnostic(request, root, errorCode, false);
            return diagnostic == null ? LocalJMeterWorkerResponse.failure(
                    request, errorCode, LocalJMeterWorkerJmx.category(errorCode, request.operation()),
                    LocalJMeterWorkerJmx.unresolvedClassName(root), message,
                    LocalJMeterWorkerJmx.suggestedAction(errorCode, root), stdout.text(), stderr.text())
                    : LocalJMeterWorkerResponse.failure(
                    request, errorCode, LocalJMeterWorkerJmx.category(errorCode, request.operation()),
                    LocalJMeterWorkerJmx.unresolvedClassName(root), message,
                    recovery(diagnostic, errorCode, root), stdout.text(), stderr.text(), diagnostic);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static String recovery(
            ApplyFailureDiagnostic diagnostic, String errorCode, Throwable root) {
        if (diagnostic != null && diagnostic.failureClass() == ApplyFailureDiagnostic.FailureClass.FATAL) {
            return diagnostic.recovery();
        }
        return LocalJMeterWorkerJmx.suggestedAction(errorCode, root);
    }

    private static ApplyFailureDiagnostic applyDiagnostic(
            LocalJMeterWorkerRequest request, Throwable root, String errorCode, boolean fatal) {
        if (!"applyPatch".equals(request.operation())) return null;
        ApplyFailureException card = cause(root, ApplyFailureException.class);
        if (card == null && "SEMANTIC_LOAD_ERROR".equals(errorCode)) return null;
        ApplyFailureDiagnostic.FailureClass failureClass = fatal
                ? ApplyFailureDiagnostic.FailureClass.FATAL
                : applyFailureClass(root, errorCode);
        return new ApplyFailureDiagnostic(
                failureClass,
                card == null ? applyPhase(root, errorCode) : card.phase(),
                card == null ? null : Integer.valueOf(card.changeIndex()),
                card == null ? null : card.operation(),
                card == null ? null : card.context(),
                sourceCauses(root));
    }

    private static ApplyFailureDiagnostic.FailureClass applyFailureClass(Throwable root, String errorCode) {
        if (root instanceof io.github.thisccl.j4a.apply.ApplyPatchParseException
                || "USAGE_ERROR".equals(errorCode)) {
            return ApplyFailureDiagnostic.FailureClass.USAGE;
        }
        ApplyFailureException card = cause(root, ApplyFailureException.class);
        if (card != null && "atomic-write".equals(card.phase())) {
            return ApplyFailureDiagnostic.FailureClass.INFRASTRUCTURE;
        }
        if (card != null && "candidate-reload".equals(card.phase())
                && ("JMX_READ_ERROR".equals(errorCode) || "FILESYSTEM_WRITE_ERROR".equals(errorCode))) {
            return ApplyFailureDiagnostic.FailureClass.INFRASTRUCTURE;
        }
        if (card != null || "SEMANTIC_LOAD_ERROR".equals(errorCode)
                || "LOCATOR_NOT_FOUND".equals(errorCode)
                || "MISSING_PROPERTY".equals(errorCode)
                || "INVALID_PLACEMENT".equals(errorCode)
                || "JMETER_PLACEMENT_REJECTED".equals(errorCode)
                || "CANDIDATE_PRESERVATION_FAILED".equals(errorCode)) {
            return ApplyFailureDiagnostic.FailureClass.SEMANTIC;
        }
        return ApplyFailureDiagnostic.FailureClass.INFRASTRUCTURE;
    }

    private static String applyPhase(Throwable root, String errorCode) {
        if (root instanceof io.github.thisccl.j4a.apply.ApplyPatchParseException) return "schema";
        if ("SEMANTIC_LOAD_ERROR".equals(errorCode)) return "initial-source-load";
        if (root instanceof java.io.IOException) return "filesystem";
        return "worker";
    }

    private static java.util.List<ApplyFailureDiagnostic.SourceCause> sourceCauses(Throwable throwable) {
        java.util.List<ApplyFailureDiagnostic.SourceCause> causes =
                new java.util.ArrayList<ApplyFailureDiagnostic.SourceCause>();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        Throwable current = throwable;
        while (current != null && causes.size() < 4 && seen.add(current)) {
            causes.add(new ApplyFailureDiagnostic.SourceCause(
                    current.getClass().getName(), sourceMessage(current)));
            current = current.getCause();
        }
        return causes;
    }

    private static String sourceMessage(Throwable throwable) {
        if (throwable instanceof java.nio.file.AccessDeniedException) {
            return "Access denied while preparing the atomic candidate.";
        }
        return bounded(throwable.getMessage());
    }

    private static String bounded(String message) {
        String value = message == null ? "" : message;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static String value(String value) {
        return value == null || value.isEmpty() ? "unavailable" : value;
    }

    private static void configureUtf8Console() {
        System.setOut(utf8PrintStream(System.out));
        System.setErr(utf8PrintStream(System.err));
    }

    private static PrintStream utf8PrintStream(OutputStream stream) {
        try {
            return new PrintStream(stream, true, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException exception) {
            throw new IllegalStateException("UTF-8 output encoding is unavailable.", exception);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException && ((InvocationTargetException) current).getTargetException() != null) {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current;
    }

    private static <T extends Throwable> T cause(Throwable throwable, Class<T> type) {
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<Throwable>();
        pending.add(throwable);
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) pending.addLast(suppressed);
        }
        return null;
    }

    private static final class BoundedOutputCapture extends OutputStream {
        private final String streamName;
        private final int limit;
        private final byte[] tail;
        private int size;
        private boolean truncated;

        private BoundedOutputCapture(String streamName, int limit) {
            this.streamName = streamName;
            this.limit = limit;
            this.tail = new byte[limit];
        }

        @Override
        public synchronized void write(int value) {
            if (size < limit) {
                tail[size++] = (byte) value;
                return;
            }
            System.arraycopy(tail, 1, tail, 0, limit - 1);
            tail[limit - 1] = (byte) value;
            truncated = true;
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            if (length <= 0) {
                return;
            }
            if (length >= limit) {
                System.arraycopy(bytes, offset + length - limit, tail, 0, limit);
                size = limit;
                truncated = true;
                return;
            }
            int retained = Math.max(0, size + length - limit);
            if (retained > 0) {
                System.arraycopy(tail, retained, tail, 0, size - retained);
                size -= retained;
                truncated = true;
            }
            System.arraycopy(bytes, offset, tail, size, length);
            size += length;
        }

        private synchronized String text() {
            String text = new String(tail, 0, size, StandardCharsets.UTF_8);
            if (!truncated) {
                return text;
            }
            return "[" + streamName + " truncated; retained last " + limit + " bytes]\n" + text;
        }
    }
}
