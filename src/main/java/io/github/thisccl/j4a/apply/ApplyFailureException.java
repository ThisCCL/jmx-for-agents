package io.github.thisccl.j4a.apply;

import java.util.Objects;

public final class ApplyFailureException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String phase;
    private final int changeIndex;
    private final String operation;
    private final MutationChangeContext context;

    public static ApplyFailureException contextOnly(
            String phase, int changeIndex, String operation, MutationChangeContext context) {
        return new ApplyFailureException(phase, changeIndex, operation, context);
    }

    private ApplyFailureException(
            String phase, int changeIndex, String operation, MutationChangeContext context) {
        super("typed apply failure context");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.changeIndex = changeIndex;
        this.operation = Objects.requireNonNull(operation, "operation");
        this.context = Objects.requireNonNull(context, "context");
    }

    public String phase() { return phase; }
    public int changeIndex() { return changeIndex; }
    public String operation() { return operation; }
    public MutationChangeContext context() { return context; }
}
