package io.github.thisccl.j4a.validation;

import java.util.Objects;

final class ReferenceFailure extends Exception {
    private static final long serialVersionUID = 1L;

    enum Reason {
        NOT_FOUND,
        CAPACITY_EXCEEDED,
        ORDER_UNPROVEN,
        RECONCILIATION_FAILED
    }

    private final Reason reason;
    private final String code;
    private final String category;
    private final String suggestedAction;

    private ReferenceFailure(
            Reason reason,
            String code,
            String category,
            String message,
            String suggestedAction,
            Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.code = Objects.requireNonNull(code, "code");
        this.category = Objects.requireNonNull(category, "category");
        this.suggestedAction = Objects.requireNonNull(suggestedAction, "suggestedAction");
    }

    static ReferenceFailure notFound() {
        return new ReferenceFailure(
                Reason.NOT_FOUND,
                "MCP_REF_NOT_FOUND",
                "usage",
                "The session component ref is unavailable. It may be mistyped, deleted, refreshed after an external change, evicted, or lost after MCP/runtime restart.",
                "read the same file with the same JMeter home and retry with the fresh ref.",
                null);
    }

    static ReferenceFailure capacityExceeded() {
        return capacityExceeded(null);
    }

    static ReferenceFailure capacityExceeded(Throwable cause) {
        return new ReferenceFailure(
                Reason.CAPACITY_EXCEEDED,
                "MCP_REF_CAPACITY_EXCEEDED",
                "runtime",
                "Session component-reference capacity was exceeded.",
                "reduce rendered scope where supported or work with a smaller document.",
                cause);
    }

    static ReferenceFailure orderUnproven(Throwable cause) {
        return new ReferenceFailure(
                Reason.ORDER_UNPROVEN,
                "MCP_REF_ORDER_UNPROVEN",
                "runtime",
                "The selected JMeter runtime did not prove exact SaveService sibling/subtree encounter order"
                        + detail(cause) + ".",
                "select or fix a conforming JMeter runtime/home, read there for new refs, and retry; otherwise use stateless CLI write/read recovery.",
                cause);
    }

    static ReferenceFailure reconciliationFailed(Throwable cause) {
        return new ReferenceFailure(
                Reason.RECONCILIATION_FAILED,
                "MCP_REF_RECONCILIATION_FAILED",
                "runtime",
                "The candidate component-reference remap could not be proven" + detail(cause) + ".",
                "inspect or simplify the mutation before retrying.",
                cause);
    }

    Reason reason() {
        return reason;
    }

    String code() {
        return code;
    }

    String category() {
        return category;
    }

    String suggestedAction() {
        return suggestedAction;
    }

    private static String detail(Throwable cause) {
        if (cause == null || cause.getMessage() == null || cause.getMessage().trim().isEmpty()) {
            return "";
        }
        return ": " + cause.getMessage().trim();
    }
}
