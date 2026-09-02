package io.github.thisccl.j4a.jmx.property;

public enum WritableState {
    WRITABLE(true),
    READ_ONLY(false);

    private final boolean writable;

    WritableState(boolean writable) {
        this.writable = writable;
    }

    public boolean isWritable() {
        return writable;
    }
}
