package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.apply.ApplyFailureDiagnostic;
import io.github.thisccl.j4a.apply.MutationChangeContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.yaml.snakeyaml.Yaml;

public final class LocalJMeterWorkerResponse {
    private final String requestId;
    private final String operation;
    private final String jmxPath;
    private final String jmeterHome;
    private final String component;
    private final String patchPath;
    private final boolean success;
    private final LocalJMeterWorkerDisposition disposition;
    private final String errorCode;
    private final String category;
    private final String affectedFile;
    private final String missingClass;
    private final String message;
    private final String suggestedAction;
    private final String payload;
    private final String stdout;
    private final String stderr;
    private final ApplyFailureDiagnostic failureDiagnostic;

    private LocalJMeterWorkerResponse(Builder builder) {
        this.requestId = builder.requestId;
        this.operation = builder.operation;
        this.jmxPath = builder.jmxPath;
        this.jmeterHome = builder.jmeterHome;
        this.component = builder.component;
        this.patchPath = builder.patchPath;
        this.success = builder.success;
        this.disposition = builder.disposition;
        this.errorCode = builder.errorCode;
        this.category = builder.category;
        this.affectedFile = builder.affectedFile;
        this.missingClass = builder.missingClass;
        this.message = builder.message;
        this.suggestedAction = builder.suggestedAction;
        this.payload = builder.payload;
        this.stdout = builder.stdout;
        this.stderr = builder.stderr;
        this.failureDiagnostic = builder.failureDiagnostic;
    }

    static LocalJMeterWorkerResponse success(
            LocalJMeterWorkerRequest request,
            String message,
            String payload,
            String stdout,
            String stderr) {
        return builder(request)
                .success(true)
                .disposition(LocalJMeterWorkerDisposition.SUCCESS)
                .message(message)
                .payload(payload)
                .stdout(stdout)
                .stderr(stderr)
                .build();
    }

    static LocalJMeterWorkerResponse failure(
            LocalJMeterWorkerRequest request,
            String errorCode,
            String category,
            String missingClass,
            String message,
            String suggestedAction,
            String stdout,
            String stderr) {
        return builder(request)
                .success(false)
                .disposition(LocalJMeterWorkerDisposition.DOMAIN_FAILURE)
                .errorCode(errorCode)
                .category(category)
                .missingClass(missingClass)
                .message(message)
                .suggestedAction(suggestedAction)
                .stdout(stdout)
                .stderr(stderr)
                .build();
    }

    public static LocalJMeterWorkerResponse failure(
            LocalJMeterWorkerRequest request, String errorCode, String category, String missingClass,
            String message, String suggestedAction, String stdout, String stderr,
            ApplyFailureDiagnostic failureDiagnostic) {
        return builder(request).success(false).disposition(LocalJMeterWorkerDisposition.DOMAIN_FAILURE)
                .errorCode(errorCode).category(category).missingClass(missingClass).message(message)
                .suggestedAction(suggestedAction).stdout(stdout).stderr(stderr)
                .failureDiagnostic(failureDiagnostic).build();
    }

    static LocalJMeterWorkerResponse fatalFailure(
            LocalJMeterWorkerRequest request,
            String errorCode,
            String category,
            String missingClass,
            String message,
            String suggestedAction,
            String stdout,
            String stderr) {
        ApplyFailureDiagnostic applyFailure = "applyPatch".equals(request.operation())
                ? new ApplyFailureDiagnostic(ApplyFailureDiagnostic.FailureClass.FATAL, "worker",
                        null, null, null, java.util.Collections.<ApplyFailureDiagnostic.SourceCause>emptyList())
                : null;
        return builder(request)
                .success(false)
                .disposition(LocalJMeterWorkerDisposition.FATAL_FAILURE)
                .errorCode(errorCode)
                .category(category)
                .missingClass(missingClass)
                .message(message)
                .suggestedAction(applyFailure == null ? suggestedAction : applyFailure.recovery())
                .stdout(stdout)
                .stderr(stderr)
                .failureDiagnostic(applyFailure)
                .build();
    }

    static LocalJMeterWorkerResponse fatalFailure(
            LocalJMeterWorkerRequest request, String errorCode, String category, String missingClass,
            String message, String suggestedAction, String stdout, String stderr,
            ApplyFailureDiagnostic failureDiagnostic) {
        return builder(request).success(false).disposition(LocalJMeterWorkerDisposition.FATAL_FAILURE)
                .errorCode(errorCode).category(category).missingClass(missingClass).message(message)
                .suggestedAction(suggestedAction).stdout(stdout).stderr(stderr)
                .failureDiagnostic(failureDiagnostic).build();
    }

    static LocalJMeterWorkerResponse fromJsonLine(String json) {
        LocalJMeterWorkerJson.ObjectFields fields = LocalJMeterWorkerJson.parseObject(json);
        Builder builder = new Builder();
        builder.requestId = fields.string("requestId");
        builder.operation = fields.string("operation");
        builder.jmxPath = fields.string("jmxPath");
        builder.jmeterHome = fields.string("jmeterHome");
        builder.component = fields.string("component");
        builder.patchPath = fields.string("patchPath");
        builder.success = fields.bool("success");
        String disposition = fields.string("disposition");
        builder.disposition = disposition == null
                ? (builder.success ? LocalJMeterWorkerDisposition.SUCCESS : LocalJMeterWorkerDisposition.FATAL_FAILURE)
                : LocalJMeterWorkerDisposition.valueOf(disposition);
        if (builder.success != (builder.disposition == LocalJMeterWorkerDisposition.SUCCESS)) {
            throw new IllegalArgumentException("protocol success/disposition contradiction");
        }
        builder.errorCode = fields.string("errorCode");
        builder.category = fields.string("category");
        builder.affectedFile = fields.string("affectedFile");
        builder.missingClass = fields.string("missingClass");
        builder.message = fields.string("message");
        builder.suggestedAction = fields.string("suggestedAction");
        builder.payload = fields.string("payload");
        builder.stdout = fields.string("stdout");
        builder.stderr = fields.string("stderr");
        builder.failureDiagnostic = parseFailureDiagnostic(fields);
        return builder.build();
    }

    public String toJsonLine() {
        LocalJMeterWorkerJson.Builder json = LocalJMeterWorkerJson.object();
        json.field("requestId", requestId);
        json.field("operation", operation);
        json.field("jmxPath", jmxPath);
        json.field("jmeterHome", jmeterHome);
        json.field("component", component);
        json.field("patchPath", patchPath);
        json.field("success", success);
        json.field("disposition", disposition.name());
        json.field("errorCode", errorCode);
        json.field("category", category);
        json.field("affectedFile", affectedFile);
        json.field("missingClass", missingClass);
        json.field("message", message);
        json.field("suggestedAction", suggestedAction);
        json.field("payload", payload);
        json.field("stdout", stdout);
        json.field("stderr", stderr);
        if (failureDiagnostic != null) {
            json.field("failureClass", failureDiagnostic.failureClass().name());
            json.field("failurePhase", failureDiagnostic.phase());
            json.field("failureChangeIndex", failureDiagnostic.change().isPresent()
                    ? String.valueOf(failureDiagnostic.change().get().index()) : null);
            json.field("failureOperation", failureDiagnostic.change().isPresent()
                    ? failureDiagnostic.change().get().operation() : null);
            json.field("failureContext", failureContextYaml(failureDiagnostic));
            json.field("failureCauses", failureCausesYaml(failureDiagnostic));
        }
        return json.build();
    }

    public boolean success() {
        return success;
    }

    public LocalJMeterWorkerDisposition disposition() {
        return disposition;
    }

    public String errorCode() {
        return errorCode;
    }

    public String category() {
        return category;
    }

    public String affectedFile() {
        return affectedFile;
    }

    public String message() {
        return message;
    }

    public String suggestedAction() {
        return suggestedAction;
    }

    public String payload() {
        return payload == null ? stdout() : payload;
    }

    public String stdout() {
        return stdout == null ? "" : stdout;
    }

    public String stderr() {
        return stderr == null ? "" : stderr;
    }

    public Optional<ApplyFailureDiagnostic> failureDiagnostic() {
        return Optional.ofNullable(failureDiagnostic);
    }

    String missingClass() {
        return missingClass;
    }

    void requireMatches(LocalJMeterWorkerRequest request) {
        requireEqual("requestId", request.requestId(), requestId);
        requireEqual("operation", request.operation(), operation);
        requireEqual("jmxPath", request.jmxPath(), jmxPath);
        requireEqual("jmeterHome", request.jmeterHome(), jmeterHome);
        requireEqual("component", request.component(), component);
        requireEqual("patchPath", request.patchPath(), patchPath);
    }

    private static void requireEqual(String field, String expected, String actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException("protocol " + field + " mismatch");
        }
    }

    private static Builder builder(LocalJMeterWorkerRequest request) {
        Builder builder = new Builder();
        builder.requestId = request.requestId();
        builder.operation = request.operation();
        builder.jmxPath = request.jmxPath();
        builder.jmeterHome = request.jmeterHome();
        builder.component = request.component();
        builder.patchPath = request.patchPath();
        builder.affectedFile = request.jmxPath();
        return builder;
    }

    private static String failureContextYaml(ApplyFailureDiagnostic diagnostic) {
        return diagnostic.change().isPresent()
                ? new Yaml().dump(diagnostic.change().get().context().toMap()) : null;
    }

    private static String failureCausesYaml(ApplyFailureDiagnostic diagnostic) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (ApplyFailureDiagnostic.SourceCause cause : diagnostic.causes()) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("type", cause.type());
            value.put("message", cause.message());
            values.add(value);
        }
        return new Yaml().dump(values);
    }

    @SuppressWarnings("unchecked")
    private static ApplyFailureDiagnostic parseFailureDiagnostic(LocalJMeterWorkerJson.ObjectFields fields) {
        String failureClass = fields.string("failureClass");
        if (failureClass == null) return null;
        String index = fields.string("failureChangeIndex");
        MutationChangeContext context = null;
        if (index != null) {
            context = MutationChangeContext.fromMap(
                    (Map<String, Object>) new Yaml().load(fields.string("failureContext")));
        }
        List<ApplyFailureDiagnostic.SourceCause> causes = new ArrayList<ApplyFailureDiagnostic.SourceCause>();
        Object parsedCauses = new Yaml().load(fields.string("failureCauses"));
        if (parsedCauses instanceof List<?>) {
            for (Object item : (List<?>) parsedCauses) {
                Map<String, Object> cause = (Map<String, Object>) item;
                causes.add(new ApplyFailureDiagnostic.SourceCause(
                        String.valueOf(cause.get("type")), String.valueOf(cause.get("message"))));
            }
        }
        return new ApplyFailureDiagnostic(ApplyFailureDiagnostic.FailureClass.valueOf(failureClass),
                fields.string("failurePhase"), index == null ? null : Integer.valueOf(index),
                fields.string("failureOperation"), context, causes);
    }

    private static final class Builder {
        private String requestId;
        private String operation;
        private String jmxPath;
        private String jmeterHome;
        private String component;
        private String patchPath;
        private boolean success;
        private LocalJMeterWorkerDisposition disposition;
        private String errorCode;
        private String category;
        private String affectedFile;
        private String missingClass;
        private String message;
        private String suggestedAction;
        private String payload;
        private String stdout;
        private String stderr;
        private ApplyFailureDiagnostic failureDiagnostic;

        private Builder success(boolean value) {
            this.success = value;
            return this;
        }

        private Builder disposition(LocalJMeterWorkerDisposition value) {
            this.disposition = value;
            return this;
        }

        private Builder errorCode(String value) {
            this.errorCode = value;
            return this;
        }

        private Builder category(String value) {
            this.category = value;
            return this;
        }

        private Builder missingClass(String value) {
            this.missingClass = value;
            return this;
        }

        private Builder message(String value) {
            this.message = value;
            return this;
        }

        private Builder suggestedAction(String value) {
            this.suggestedAction = value;
            return this;
        }

        private Builder payload(String value) {
            this.payload = value;
            return this;
        }

        private Builder stdout(String value) {
            this.stdout = value;
            return this;
        }

        private Builder stderr(String value) {
            this.stderr = value;
            return this;
        }

        private Builder failureDiagnostic(ApplyFailureDiagnostic value) {
            this.failureDiagnostic = value;
            return this;
        }

        private LocalJMeterWorkerResponse build() {
            return new LocalJMeterWorkerResponse(this);
        }
    }
}
