package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.reference.ResolvedNodeHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Opaque immutable result of reference reconciliation.
 *
 * <p>The type deliberately has no commit or publish operation. A successful filesystem commit and any subsequent
 * state publication remain separate responsibilities.</p>
 */
final class PreparedReferenceState {
    private static final PreparedReferenceState PREPARED = new PreparedReferenceState(
            Collections.<TrackedReference>emptyList(),
            Collections.<String>emptyList(),
            Collections.<CreatedAlias>emptyList());

    private final List<TrackedReference> survivingReferences;
    private final List<String> deletedReferences;
    private final List<CreatedAlias> createdAliases;

    private PreparedReferenceState(
            List<TrackedReference> survivingReferences,
            List<String> deletedReferences,
            List<CreatedAlias> createdAliases) {
        this.survivingReferences = immutableCopy(survivingReferences, "survivingReferences");
        this.deletedReferences = immutableCopy(deletedReferences, "deletedReferences");
        this.createdAliases = immutableCopy(createdAliases, "createdAliases");
    }

    static PreparedReferenceState prepared() {
        return PREPARED;
    }

    static PreparedReferenceState tracking(
            List<TrackedReference> survivingReferences,
            List<String> deletedReferences,
            List<CreatedAlias> createdAliases) {
        return new PreparedReferenceState(survivingReferences, deletedReferences, createdAliases);
    }

    List<TrackedReference> survivingReferences() {
        return survivingReferences;
    }

    List<String> deletedReferences() {
        return deletedReferences;
    }

    List<CreatedAlias> createdAliases() {
        return createdAliases;
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        List<T> copy = new ArrayList<T>(values.size());
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, name + " entry"));
        }
        return Collections.unmodifiableList(copy);
    }

    static final class TrackedReference {
        private final String publicReference;
        private final String locator;
        private final String expectedClass;
        private final ResolvedNodeHandle handle;

        private TrackedReference(
                String publicReference, String locator, String expectedClass, ResolvedNodeHandle handle) {
            this.publicReference = Objects.requireNonNull(publicReference, "publicReference");
            this.locator = Objects.requireNonNull(locator, "locator");
            this.expectedClass = Objects.requireNonNull(expectedClass, "expectedClass");
            this.handle = Objects.requireNonNull(handle, "handle");
        }

        static TrackedReference of(
                String publicReference, String locator, String expectedClass, ResolvedNodeHandle handle) {
            return new TrackedReference(publicReference, locator, expectedClass, handle);
        }

        String publicReference() {
            return publicReference;
        }

        String locator() {
            return locator;
        }

        String expectedClass() {
            return expectedClass;
        }

        ResolvedNodeHandle handle() {
            return handle;
        }
    }

    static final class CreatedAlias {
        private final String alias;
        private final String locator;
        private final String expectedClass;
        private final ResolvedNodeHandle handle;

        private CreatedAlias(String alias, String locator, String expectedClass, ResolvedNodeHandle handle) {
            this.alias = Objects.requireNonNull(alias, "alias");
            this.locator = Objects.requireNonNull(locator, "locator");
            this.expectedClass = Objects.requireNonNull(expectedClass, "expectedClass");
            this.handle = Objects.requireNonNull(handle, "handle");
        }

        static CreatedAlias of(String alias, String locator, String expectedClass, ResolvedNodeHandle handle) {
            return new CreatedAlias(alias, locator, expectedClass, handle);
        }

        String alias() {
            return alias;
        }

        String locator() {
            return locator;
        }

        String expectedClass() {
            return expectedClass;
        }

        ResolvedNodeHandle handle() {
            return handle;
        }
    }
}
