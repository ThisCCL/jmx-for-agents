package io.github.thisccl.j4a.reference;

import io.github.thisccl.j4a.validation.ExactNodeHandle;
import java.util.Objects;
import java.util.Optional;

public final class ReferenceResolution {
    public enum Status {
        RESOLVED,
        UNAVAILABLE
    }

    public enum UnavailableReason {
        UNKNOWN_REFERENCE,
        RETAINED_RECORD_PROOF_LOSS
    }

    private static final ReferenceResolution UNAVAILABLE =
            new ReferenceResolution(Status.UNAVAILABLE, null, UnavailableReason.UNKNOWN_REFERENCE);
    private static final ReferenceResolution RETAINED_RECORD_PROOF_LOSS =
            new ReferenceResolution(Status.UNAVAILABLE, null, UnavailableReason.RETAINED_RECORD_PROOF_LOSS);

    private final Status status;
    private final ResolvedNodeHandle handle;
    private final UnavailableReason unavailableReason;

    private ReferenceResolution(
            Status status,
            ResolvedNodeHandle handle,
            UnavailableReason unavailableReason) {
        this.status = Objects.requireNonNull(status, "status");
        this.handle = handle;
        this.unavailableReason = unavailableReason;
    }

    public static ReferenceResolution resolved(ResolvedNodeHandle handle) {
        if (!(handle instanceof ExactNodeHandle)) {
            throw new IllegalArgumentException("Resolved node handle is not owned by this reference module");
        }
        return new ReferenceResolution(
                Status.RESOLVED, Objects.requireNonNull(handle, "handle"), null);
    }

    public static ReferenceResolution unavailable() {
        return UNAVAILABLE;
    }

    public static ReferenceResolution retainedRecordProofLoss() {
        return RETAINED_RECORD_PROOF_LOSS;
    }

    public Status status() {
        return status;
    }

    public Optional<ResolvedNodeHandle> handle() {
        return Optional.ofNullable(handle);
    }

    public Optional<UnavailableReason> unavailableReason() {
        return Optional.ofNullable(unavailableReason);
    }
}
