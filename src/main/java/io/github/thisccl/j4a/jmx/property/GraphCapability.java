package io.github.thisccl.j4a.jmx.property;

import java.util.Objects;
import java.util.Optional;

public final class GraphCapability {
    private final StorageKeyStatus storageKeyStatus;
    private final WritableState writableState;
    private final String reason;
    private final GraphOwnership ownership;
    private final RepresentationSource representationSource;
    private final RuntimeClassConstraint runtimeClassConstraint;

    public GraphCapability(
            StorageKeyStatus storageKeyStatus,
            WritableState writableState,
            String reason,
            GraphOwnership ownership,
            RepresentationSource representationSource,
            RuntimeClassConstraint runtimeClassConstraint) {
        this.storageKeyStatus = Objects.requireNonNull(storageKeyStatus, "storage key status is required");
        this.writableState = Objects.requireNonNull(writableState, "writable state is required");
        this.ownership = Objects.requireNonNull(ownership, "ownership is required");
        this.representationSource = Objects.requireNonNull(
                representationSource, "representation source is required");
        this.runtimeClassConstraint = Objects.requireNonNull(
                runtimeClassConstraint, "runtime class constraint is required");
        if (writableState.isWritable() && reason != null) {
            throw new IllegalArgumentException("writable capability must not have a reason");
        }
        if (!writableState.isWritable() && (reason == null || reason.isEmpty())) {
            throw new IllegalArgumentException("read-only capability requires a reason");
        }
        this.reason = reason;
    }

    public StorageKeyStatus storageKeyStatus() {
        return storageKeyStatus;
    }

    public WritableState writableState() {
        return writableState;
    }

    public boolean writable() {
        return writableState.isWritable();
    }

    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    public GraphOwnership ownership() {
        return ownership;
    }

    public RepresentationSource representationSource() {
        return representationSource;
    }

    public RuntimeClassConstraint runtimeClassConstraint() {
        return runtimeClassConstraint;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GraphCapability)) {
            return false;
        }
        GraphCapability that = (GraphCapability) other;
        return storageKeyStatus == that.storageKeyStatus
                && writableState == that.writableState
                && Objects.equals(reason, that.reason)
                && ownership == that.ownership
                && representationSource == that.representationSource
                && runtimeClassConstraint.equals(that.runtimeClassConstraint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                storageKeyStatus,
                writableState,
                reason,
                ownership,
                representationSource,
                runtimeClassConstraint);
    }
}
