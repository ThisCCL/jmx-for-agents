package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.apply.MutationChangeResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class MutationResult {
    enum Tag {
        DRY_RUN_VALIDATED,
        COMMITTED
    }

    enum ReferenceScope {
        NONE,
        TARGET_SNAPSHOT,
        SESSION
    }

    private final Tag tag;
    private final ValidatedReceipt receipt;
    private final PublishedReferences publishedReferences;
    private final ReferenceScope referenceScope;

    private MutationResult(
            Tag tag,
            ValidatedReceipt receipt,
            PublishedReferences publishedReferences,
            ReferenceScope referenceScope) {
        this.tag = Objects.requireNonNull(tag, "tag");
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.publishedReferences = publishedReferences;
        this.referenceScope = Objects.requireNonNull(referenceScope, "referenceScope");
        if ((tag == Tag.COMMITTED) != (publishedReferences != null)) {
            throw new IllegalArgumentException("Only committed results carry published references");
        }
    }

    static MutationResult dryRunValidated(
            String sourceFingerprint, String candidateFingerprint, int appliedCount) {
        return dryRunValidated(
                sourceFingerprint, candidateFingerprint, appliedCount,
                Collections.<String, Object>emptyMap());
    }

    static MutationResult dryRunValidated(
            String sourceFingerprint,
            String candidateFingerprint,
            int appliedCount,
            Map<String, ?> auxiliaryResults) {
        return new MutationResult(
                Tag.DRY_RUN_VALIDATED,
                new ValidatedReceipt(
                        sourceFingerprint, candidateFingerprint, appliedCount, auxiliaryResults,
                        Collections.<MutationChangeResult>emptyList()),
                null,
                ReferenceScope.NONE);
    }

    static MutationResult dryRunValidated(
            String sourceFingerprint,
            String candidateFingerprint,
            int appliedCount,
            Map<String, ?> auxiliaryResults,
            List<MutationChangeResult> changeResults) {
        return new MutationResult(
                Tag.DRY_RUN_VALIDATED,
                new ValidatedReceipt(
                        sourceFingerprint, candidateFingerprint, appliedCount, auxiliaryResults,
                        changeResults),
                null,
                ReferenceScope.NONE);
    }

    static MutationResult committed(
            String sourceFingerprint,
            String committedFingerprint,
            int appliedCount,
            List<CreatedReference> created,
            List<String> deleted) {
        return committed(
                sourceFingerprint, committedFingerprint, appliedCount, created, deleted,
                Collections.<String, Object>emptyMap(),
                Collections.<MutationChangeResult>emptyList(),
                ReferenceScope.SESSION);
    }

    static MutationResult committed(
            String sourceFingerprint,
            String committedFingerprint,
            int appliedCount,
            List<CreatedReference> created,
            List<String> deleted,
            Map<String, ?> auxiliaryResults) {
        return committed(
                sourceFingerprint, committedFingerprint, appliedCount, created, deleted,
                auxiliaryResults, Collections.<MutationChangeResult>emptyList(), ReferenceScope.SESSION);
    }

    static MutationResult committed(
            String sourceFingerprint,
            String committedFingerprint,
            int appliedCount,
            List<CreatedReference> created,
            List<String> deleted,
            Map<String, ?> auxiliaryResults,
            List<MutationChangeResult> changeResults,
            ReferenceScope referenceScope) {
        return new MutationResult(
                Tag.COMMITTED,
                new ValidatedReceipt(
                        sourceFingerprint, committedFingerprint, appliedCount, auxiliaryResults,
                        changeResults),
                new PublishedReferences(created, deleted),
                referenceScope);
    }

    Tag tag() {
        return tag;
    }

    ValidatedReceipt receipt() {
        return receipt;
    }

    Optional<PublishedReferences> publishedReferences() {
        return Optional.ofNullable(publishedReferences);
    }

    ReferenceScope referenceScope() {
        return referenceScope;
    }

    static final class ValidatedReceipt {
        private final String sourceFingerprint;
        private final String candidateFingerprint;
        private final int appliedCount;
        private final Map<String, Object> auxiliaryResults;
        private final List<MutationChangeResult> changeResults;

        private ValidatedReceipt(
                String sourceFingerprint,
                String candidateFingerprint,
                int appliedCount,
                Map<String, ?> auxiliaryResults,
                List<MutationChangeResult> changeResults) {
            if (appliedCount < 0) {
                throw new IllegalArgumentException("appliedCount must not be negative");
            }
            this.sourceFingerprint = Objects.requireNonNull(sourceFingerprint, "sourceFingerprint");
            this.candidateFingerprint = Objects.requireNonNull(candidateFingerprint, "candidateFingerprint");
            this.appliedCount = appliedCount;
            this.auxiliaryResults = immutableMap(auxiliaryResults);
            this.changeResults = immutableCopy(changeResults, "changeResults");
        }

        String sourceFingerprint() {
            return sourceFingerprint;
        }

        String candidateFingerprint() {
            return candidateFingerprint;
        }

        int appliedCount() {
            return appliedCount;
        }

        Map<String, Object> auxiliaryResults() {
            return auxiliaryResults;
        }

        List<MutationChangeResult> changeResults() {
            return changeResults;
        }
    }

    static final class PublishedReferences {
        private final List<CreatedReference> created;
        private final List<String> deleted;

        private PublishedReferences(List<CreatedReference> created, List<String> deleted) {
            this.created = immutableCopy(created, "created");
            this.deleted = immutableCopy(deleted, "deleted");
        }

        List<CreatedReference> created() {
            return created;
        }

        List<String> deleted() {
            return deleted;
        }
    }

    static final class CreatedReference {
        private final String alias;
        private final String publicReference;

        CreatedReference(String alias, String publicReference) {
            this.alias = Objects.requireNonNull(alias, "alias");
            this.publicReference = Objects.requireNonNull(publicReference, "publicReference");
        }

        String alias() {
            return alias;
        }

        String publicReference() {
            return publicReference;
        }
    }

    private static <T> List<T> immutableCopy(List<T> source, String name) {
        Objects.requireNonNull(source, name);
        List<T> copy = new ArrayList<T>(source.size());
        for (T item : source) {
            copy.add(Objects.requireNonNull(item, name + " entry"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        Objects.requireNonNull(source, "auxiliaryResults");
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "auxiliary result name"), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
