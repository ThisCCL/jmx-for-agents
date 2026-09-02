package io.github.thisccl.j4a.apply;

import java.util.Objects;
import java.util.Optional;

public final class MutationChangeResult {
    public enum Status {
        APPLIED("applied"),
        VALIDATED("validated"),
        COMMITTED("committed");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    private final int index;
    private final String operation;
    private final Status status;
    private final MutationChangeContext context;
    private final String resultRef;

    private MutationChangeResult(
            int index,
            String operation,
            Status status,
            MutationChangeContext context,
            String resultRef) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        this.index = index;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.status = Objects.requireNonNull(status, "status");
        this.context = Objects.requireNonNull(context, "context");
        this.resultRef = resultRef;
    }

    public static MutationChangeResult applied(
            int index, String operation, MutationChangeContext context) {
        return new MutationChangeResult(index, operation, Status.APPLIED, context, null);
    }

    public MutationChangeResult published(Status publishedStatus, String publishedResultRef) {
        if (publishedStatus == Status.APPLIED) {
            throw new IllegalArgumentException("published status must cross the transaction boundary");
        }
        if (publishedResultRef != null && (publishedStatus != Status.COMMITTED
                || !"add".equals(operation) || !context.alias().isPresent())) {
            throw new IllegalArgumentException(
                    "resultRef requires a committed aliased add outcome");
        }
        return new MutationChangeResult(index, operation, publishedStatus, context, publishedResultRef);
    }

    public int index() {
        return index;
    }

    public String operation() {
        return operation;
    }

    public Status status() {
        return status;
    }

    public MutationChangeContext context() {
        return context;
    }

    public Optional<String> resultRef() {
        return Optional.ofNullable(resultRef);
    }
}
