package io.github.thisccl.j4a.apply;

public final class ApplyPatchParseException extends RuntimeException {
    private final Integer changeIndex;
    private final String operation;
    private final MutationChangeContext context;

    public ApplyPatchParseException(String message) {
        this(message, null, null, null, null);
    }

    public ApplyPatchParseException(String message, Throwable cause) {
        this(message, cause, null, null, null);
    }

    private ApplyPatchParseException(
            String message, Throwable cause, Integer changeIndex,
            String operation, MutationChangeContext context) {
        super(message, cause);
        this.changeIndex = changeIndex;
        this.operation = operation;
        this.context = context;
    }

    ApplyPatchParseException atChange(
            int index, String operation, MutationChangeContext context) {
        return new ApplyPatchParseException(getMessage(), this, Integer.valueOf(index), operation, context);
    }

    public java.util.Optional<Integer> changeIndex() { return java.util.Optional.ofNullable(changeIndex); }
    public java.util.Optional<String> operation() { return java.util.Optional.ofNullable(operation); }
    public java.util.Optional<MutationChangeContext> context() { return java.util.Optional.ofNullable(context); }
}
