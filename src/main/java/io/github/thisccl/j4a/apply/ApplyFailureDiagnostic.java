package io.github.thisccl.j4a.apply;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ApplyFailureDiagnostic {
    public enum FailureClass { USAGE, SEMANTIC, INFRASTRUCTURE, FATAL }

    private final FailureClass failureClass;
    private final String phase;
    private final Change change;
    private final List<SourceCause> causes;

    public ApplyFailureDiagnostic(
            FailureClass failureClass,
            String phase,
            Integer index,
            String operation,
            MutationChangeContext context,
            List<SourceCause> causes) {
        this.failureClass = Objects.requireNonNull(failureClass, "failureClass");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.change = index == null ? null : new Change(index.intValue(), operation, context);
        this.causes = immutableCauses(causes);
    }

    public FailureClass failureClass() { return failureClass; }
    public String phase() { return phase; }
    public Optional<Change> change() { return Optional.ofNullable(change); }
    public List<SourceCause> causes() { return causes; }

    public String recovery() {
        if (failureClass == FailureClass.FATAL) {
            return "inspect or read the target before retry; the failed request was not replayed.";
        }
        if (failureClass == FailureClass.SEMANTIC) {
            if ("locator".equals(phase)) {
                return "run read to refresh target refs, rebuild the patch, then submit a new request.";
            }
            return "correct the reported apply card, then submit a new request.";
        }
        if (failureClass == FailureClass.USAGE) {
            return "correct the patch schema, then submit a new request.";
        }
        return "resolve the runtime or filesystem failure, then submit a new request.";
    }

    private static List<SourceCause> immutableCauses(List<SourceCause> causes) {
        if (causes == null || causes.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<SourceCause>(causes));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ApplyFailureDiagnostic)) return false;
        ApplyFailureDiagnostic value = (ApplyFailureDiagnostic) other;
        return failureClass == value.failureClass && phase.equals(value.phase)
                && Objects.equals(change, value.change) && causes.equals(value.causes);
    }

    @Override
    public int hashCode() { return Objects.hash(failureClass, phase, change, causes); }

    public static final class Change {
        private final int index;
        private final String operation;
        private final MutationChangeContext context;

        private Change(int index, String operation, MutationChangeContext context) {
            if (index < 0) throw new IllegalArgumentException("change index must be nonnegative");
            this.index = index;
            this.operation = Objects.requireNonNull(operation, "operation");
            this.context = Objects.requireNonNull(context, "context");
            requireAllowedContext(this.operation, this.context.toMap());
        }

        public int index() { return index; }
        public String operation() { return operation; }
        public MutationChangeContext context() { return context; }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("index", Integer.valueOf(index));
            value.put("operation", operation);
            value.put("context", context.toMap());
            return Collections.unmodifiableMap(value);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Change && toMap().equals(((Change) other).toMap());
        }

        @Override
        public int hashCode() { return toMap().hashCode(); }

        private static void requireAllowedContext(String operation, Map<String, Object> context) {
            Set<String> allowed;
            if ("set".equals(operation)) {
                allowed = setOf("ref", "component", "properties");
            } else if ("add".equals(operation)) {
                allowed = setOf("alias", "component", "parent", "before", "after", "position", "properties");
            } else if ("move".equals(operation)) {
                allowed = setOf("ref", "component", "parent", "before", "after", "position");
            } else if ("delete".equals(operation)) {
                allowed = setOf("ref", "component");
            } else if ("append".equals(operation)) {
                allowed = setOf("ref", "property");
            } else if ("insert".equals(operation) || "remove".equals(operation)) {
                allowed = setOf("ref", "property", "index");
            } else {
                throw new IllegalArgumentException("unsupported failure operation: " + operation);
            }
            if (!allowed.containsAll(context.keySet())) {
                throw new IllegalArgumentException("failure context contains a non-identifier field");
            }
        }

        private static Set<String> setOf(String... values) {
            return new LinkedHashSet<String>(Arrays.asList(values));
        }
    }

    public static final class SourceCause {
        private final String type;
        private final String message;

        public SourceCause(String type, String message) {
            this.type = bounded(singleLine(Objects.requireNonNull(type, "type")), 256);
            this.message = bounded(singleLine(Objects.requireNonNull(message, "message")), 512);
        }

        public String type() { return type; }
        public String message() { return message; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SourceCause)) return false;
            SourceCause value = (SourceCause) other;
            return type.equals(value.type) && message.equals(value.message);
        }

        @Override
        public int hashCode() { return Objects.hash(type, message); }

        private static String bounded(String value, int limit) {
            return value.length() <= limit ? value : value.substring(0, limit);
        }

        private static String singleLine(String value) {
            return value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
        }
    }
}
