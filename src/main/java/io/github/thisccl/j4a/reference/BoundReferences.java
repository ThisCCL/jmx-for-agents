package io.github.thisccl.j4a.reference;

public interface BoundReferences {
    /**
     * Exposes the public reference for one immutable address in this bound request.
     */
    String expose(String structuralAddress, String componentClass);

    /**
     * Tests whether an opaque handle denotes the component at an immutable address.
     */
    boolean matches(ResolvedNodeHandle handle, String structuralAddress, String componentClass);

    /**
     * Resolves an exact public reference from this bound request.
     *
     * <p>Implementations must treat the input as opaque and return {@link ReferenceResolution.Status#UNAVAILABLE}
     * for blank, malformed, unknown, or out-of-request values. They must never guess from class, name, properties,
     * or tree similarity.</p>
     */
    ReferenceResolution resolve(String publicReference);
}
