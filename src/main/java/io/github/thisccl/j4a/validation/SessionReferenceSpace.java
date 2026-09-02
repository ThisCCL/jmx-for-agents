package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.apply.MutationOutcome;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ReferenceResolution;
import io.github.thisccl.j4a.reference.ResolvedNodeHandle;
import java.util.Objects;
import java.util.Map;

final class SessionReferenceSpace {
    private final SessionReferenceRegistry.PreparedState preparedState;
    private final DocumentIdentity documentIdentity;
    private final String fingerprint;
    private final ReferenceTokenGenerator tokenGenerator;
    private boolean bound;
    private boolean successfulUsePrepared;

    SessionReferenceSpace(
            SessionReferenceRegistry.PreparedState preparedState,
            DocumentIdentity documentIdentity,
            SourceSnapshot<JmxTestPlan> sourceSnapshot) {
        this(preparedState, documentIdentity, sourceSnapshot, new ReferenceTokenGenerator());
    }

    SessionReferenceSpace(
            SessionReferenceRegistry.PreparedState preparedState,
            DocumentIdentity documentIdentity,
            SourceSnapshot<JmxTestPlan> sourceSnapshot,
            ReferenceTokenGenerator tokenGenerator) {
        this.preparedState = Objects.requireNonNull(preparedState, "preparedState");
        this.documentIdentity = Objects.requireNonNull(documentIdentity, "documentIdentity");
        this.fingerprint = Objects.requireNonNull(sourceSnapshot, "sourceSnapshot").fingerprint();
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator");
    }

    synchronized BoundReferences bind(JmxTestPlan loadedPlan) {
        Objects.requireNonNull(loadedPlan, "loadedPlan");
        if (bound) {
            throw new IllegalStateException("Session reference space is already bound");
        }
        SessionPlanIndex planIndex = SessionPlanIndex.create(loadedPlan);
        preparedState.bindDocument(documentIdentity, fingerprint);
        bound = true;
        return new SessionBoundReferences(
                preparedState, documentIdentity, fingerprint, tokenGenerator, planIndex);
    }

    synchronized SessionReferenceRegistry.PreparedState prepareSuccessfulUse() {
        if (!bound) {
            throw new IllegalStateException("Session reference space has not been bound");
        }
        if (!successfulUsePrepared) {
            preparedState.successfulUse(documentIdentity);
            successfulUsePrepared = true;
        }
        return preparedState;
    }

    synchronized SessionApplyReceipt prepareApplyCommit(
            PreparedReferenceState proposal, String committedFingerprint, int appliedCount) {
        return prepareApplyCommit(
                proposal, committedFingerprint, appliedCount, java.util.Collections.<String, Object>emptyMap());
    }

    synchronized SessionApplyReceipt prepareApplyCommit(
            PreparedReferenceState proposal,
            String committedFingerprint,
            int appliedCount,
            Map<String, ?> auxiliaryResults) {
        if (!bound) {
            throw new IllegalStateException("Session reference space has not been bound");
        }
        if (successfulUsePrepared) {
            throw new IllegalStateException("Successful use has already been prepared");
        }
        java.util.List<SessionApplyReceipt.CreatedRef> createdRefs = preparedState.reconcileDocument(
                documentIdentity, fingerprint, committedFingerprint, proposal, tokenGenerator);
        return new SessionApplyReceipt(
                appliedCount, createdRefs, proposal.deletedReferences(), auxiliaryResults);
    }

    synchronized SessionApplyReceipt prepareCopyCommit(
            PreparedReferenceState proposal, int appliedCount) {
        return prepareCopyCommit(
                proposal, appliedCount, java.util.Collections.<String, Object>emptyMap());
    }

    synchronized SessionApplyReceipt prepareCopyCommit(
            PreparedReferenceState proposal, int appliedCount, Map<String, ?> auxiliaryResults) {
        if (!bound) {
            throw new IllegalStateException("Session reference space has not been bound");
        }
        if (successfulUsePrepared) {
            throw new IllegalStateException("Successful use has already been prepared");
        }
        java.util.List<SessionApplyReceipt.CreatedRef> createdRefs = preparedState.createDocumentAliases(
                documentIdentity, fingerprint, proposal, tokenGenerator);
        return new SessionApplyReceipt(
                appliedCount, createdRefs, java.util.Collections.<String>emptyList(), auxiliaryResults);
    }

    static void requireRetainedRecords(BoundReferences references) {
        Objects.requireNonNull(references, "references");
        if (!(references instanceof SessionBoundReferences)) {
            throw new IllegalArgumentException("Retained records require session-bound references");
        }
        ((SessionBoundReferences) references).requireRetainedRecords();
    }

    private static final class SessionBoundReferences implements ExactBoundReferences {
        private final SessionReferenceRegistry.PreparedState preparedState;
        private final DocumentIdentity documentIdentity;
        private final String fingerprint;
        private final ReferenceTokenGenerator tokenGenerator;
        private final SessionPlanIndex planIndex;
        private final SessionReferenceTracker tracker;

        private SessionBoundReferences(
                SessionReferenceRegistry.PreparedState preparedState,
                DocumentIdentity documentIdentity,
                String fingerprint,
                ReferenceTokenGenerator tokenGenerator,
                SessionPlanIndex planIndex) {
            this.preparedState = preparedState;
            this.documentIdentity = documentIdentity;
            this.fingerprint = fingerprint;
            this.tokenGenerator = tokenGenerator;
            this.planIndex = planIndex;
            this.tracker = new SessionReferenceTracker(
                    preparedState, documentIdentity, fingerprint, planIndex);
        }

        @Override
        public String expose(String structuralAddress, String componentClass) {
            SessionPlanIndex.LocatedElement located = planIndex.find(structuralAddress);
            if (located == null || !componentClass.equals(located.element().getClass().getName())) {
                throw new IllegalArgumentException("Address is not uniquely part of this bound request");
            }
            return preparedState.expose(
                    documentIdentity,
                    fingerprint,
                    located.locator(),
                    componentClass,
                    tokenGenerator);
        }

        @Override
        public String expose(org.apache.jmeter.testelement.TestElement element) {
            SessionPlanIndex.LocatedElement located = planIndex.require(element);
            return expose(located.locator(), element.getClass().getName());
        }

        @Override
        public boolean matches(
                ResolvedNodeHandle handle, String structuralAddress, String componentClass) {
            if (!(handle instanceof ExactNodeHandle)) {
                return false;
            }
            SessionPlanIndex.LocatedElement located = planIndex.find(structuralAddress);
            return located != null
                    && componentClass.equals(located.element().getClass().getName())
                    && located.element() == ((ExactNodeHandle) handle).element();
        }

        private void requireRetainedRecords() {
            tracker.requireProven();
        }

        @Override
        public ReferenceResolution resolve(String publicReference) {
            SessionReferenceRegistry.ReferenceRecord record =
                    preparedState.resolveRecord(documentIdentity, fingerprint, publicReference);
            if (record == null) {
                return ReferenceResolution.unavailable();
            }
            SessionPlanIndex.LocatedElement located = planIndex.find(record.locator());
            if (located == null || !located.element().getClass().getName().equals(record.expectedClass())) {
                return ReferenceResolution.retainedRecordProofLoss();
            }
            return ReferenceResolution.resolved(ExactNodeHandle.of(located.element()));
        }

        private PreparedReferenceState reconcile(JmxTestPlan mutatedPlan, MutationOutcome outcome) {
            Objects.requireNonNull(mutatedPlan, "mutatedPlan");
            Objects.requireNonNull(outcome, "outcome");
            return tracker.reconcile(mutatedPlan, outcome);
        }
    }

    static PreparedReferenceState reconcile(
            BoundReferences references, JmxTestPlan mutatedPlan, MutationOutcome outcome) {
        Objects.requireNonNull(references, "references");
        if (!(references instanceof SessionBoundReferences)) {
            throw new IllegalArgumentException("Reconciliation requires session-bound references");
        }
        return ((SessionBoundReferences) references).reconcile(mutatedPlan, outcome);
    }

    static final class RetainedReferenceProofException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        RetainedReferenceProofException() {
            super("A retained component-reference record cannot be proven against the immutable input plan");
        }
    }
}
