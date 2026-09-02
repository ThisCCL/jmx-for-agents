package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.apply.ApplyPatchCompiler;
import io.github.thisccl.j4a.apply.ApplyFailureException;
import io.github.thisccl.j4a.apply.MutationChangeContext;
import io.github.thisccl.j4a.apply.ApplyWriteModeResolver;
import io.github.thisccl.j4a.apply.MutationOutcome;
import io.github.thisccl.j4a.apply.MutationChangeResult;
import io.github.thisccl.j4a.apply.ResolvedApplyPlan;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import io.github.thisccl.j4a.locator.LocatorNotFoundException;
import io.github.thisccl.j4a.reference.BoundReferences;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.jmeter.testelement.TestElement;

final class LocalMutationTransaction {
    private final java.util.function.Consumer<Path> candidateReloadHandshake;

    LocalMutationTransaction() {
        this(new java.util.function.Consumer<Path>() {
            @Override
            public void accept(Path candidate) { }
        });
    }

    LocalMutationTransaction(java.util.function.Consumer<Path> candidateReloadHandshake) {
        this.candidateReloadHandshake = java.util.Objects.requireNonNull(
                candidateReloadHandshake, "candidateReloadHandshake");
    }

    MutationResult execute(MutationRequest request) throws Exception {
        return new Lifecycle(request, candidateReloadHandshake).complete();
    }

    private static final class Lifecycle {
        private final MutationRequest request;
        private final java.util.function.Consumer<Path> candidateReloadHandshake;
        private SessionReferenceRegistry.PreparedState prepared;
        private Path candidate;
        private boolean published;

        private Lifecycle(
                MutationRequest request,
                java.util.function.Consumer<Path> candidateReloadHandshake) {
            this.request = java.util.Objects.requireNonNull(request, "request");
            this.candidateReloadHandshake = candidateReloadHandshake;
        }

        private MutationResult complete() throws Exception {
            SourceSnapshot<JmxTestPlan> sourceSnapshot = sourceSnapshot();
            DocumentIdentity sourceIdentity = DocumentIdentity.of(
                    request.jmeterHome(), request.source());
            DocumentIdentity targetIdentity = request.dryRun()
                    ? null : DocumentIdentity.of(request.jmeterHome(), request.target());
            boolean copy = targetIdentity != null && !sourceIdentity.equals(targetIdentity);
            if (copy) {
                ApplyWriteModeResolver.requireDistinctCopyTarget(
                        request.source(), request.target());
            }
            SessionReferenceRegistry registry = request.sessionRegistry();
            Throwable primaryFailure = null;
            try {
                requireRegistryStatus(registry, sourceIdentity, sourceSnapshot.fingerprint());
                LocalMutationState.Bound bound = bind(
                        sourceIdentity, targetIdentity, sourceSnapshot, copy);
                ResolvedApplyPlan resolvedPlan = compile(
                        request.patch(), bound.references(), registry != null);
                MutationOutcome outcome = mutate(sourceSnapshot.parsed(), resolvedPlan);
                SnapshotCommitProposal snapshotProposal = registry == null && !request.dryRun()
                        ? snapshotProposal(bound.references(), sourceSnapshot.parsed(), outcome, copy)
                        : null;
                PreparedReferenceState identityProposal = registry == null
                        ? null : SessionReferenceSpace.reconcile(
                                bound.references(), sourceSnapshot.parsed(), outcome);
                candidate = request.dryRun()
                        ? LocalJMeterWorkerMutations.dryRunCandidate(
                                request.dryRunCandidateDirectory())
                        : LocalJMeterWorkerMutations.targetCandidate(request.target());
                LocalJMeterWorkerJmx.save(sourceSnapshot.parsed(), candidate);
                candidateReloadHandshake.accept(candidate);
                SourceSnapshot<JmxTestPlan> candidateSnapshot;
                try {
                    candidateSnapshot = candidateSnapshot();
                } catch (Exception exception) {
                    addCardContext(resolvedPlan, "candidate-reload", exception);
                    throw exception;
                }
                MutationImpact impact = LocalJMeterWorkerMutations.mutationImpact(
                        sourceSnapshot.parsed(), outcome);
                try {
                    LocalCandidatePreservation.requirePreserved(candidateSnapshot.parsed(), impact);
                } catch (RuntimeException exception) {
                    addCardContext(resolvedPlan, "preservation", exception);
                    throw exception;
                }
                if (registry != null) {
                    SessionCandidateIdentityProof.requireProven(
                            sourceSnapshot.parsed(), candidateSnapshot.parsed(),
                            identityProposal);
                }
                if (request.dryRun()) {
                    return MutationResult.dryRunValidated(
                            sourceSnapshot.fingerprint(), candidateSnapshot.fingerprint(),
                            outcome.appliedCount(), outcome.auxiliaryResults(),
                            publishChanges(outcome.changeResults(),
                                    MutationChangeResult.Status.VALIDATED,
                                    Collections.<MutationResult.CreatedReference>emptyList()));
                }
                LocalMutationState.Commit commit = prepareCommit(
                        bound, identityProposal, candidateSnapshot, outcome, copy, snapshotProposal);
                requireUnchangedSource(
                        sourceSnapshot.fingerprint(), registry, sourceIdentity);
                try {
                    LocalJMeterFileCommitter.commit(candidate, request.target(), request.replaceExisting());
                } catch (RuntimeException exception) {
                    addCardContext(resolvedPlan, "atomic-write", exception);
                    throw exception;
                }
                candidate = null;
                commit.publish();
                published = true;
                return MutationResult.committed(
                        sourceSnapshot.fingerprint(), candidateSnapshot.fingerprint(),
                        outcome.appliedCount(), commit.created(), commit.deleted(),
                        outcome.auxiliaryResults(),
                        publishChanges(outcome.changeResults(),
                                MutationChangeResult.Status.COMMITTED, commit.created()),
                        registry == null
                                ? MutationResult.ReferenceScope.TARGET_SNAPSHOT
                                : MutationResult.ReferenceScope.SESSION);
            } catch (SessionReferenceRegistry.CapacityExceededException exception) {
                ReferenceFailure failure = ReferenceFailure.capacityExceeded(exception);
                primaryFailure = failure;
                throw failure;
            } catch (Exception exception) {
                primaryFailure = exception;
                throw exception;
            } catch (Error error) {
                primaryFailure = error;
                throw error;
            } finally {
                cleanup(primaryFailure);
            }
        }

        private static void addCardContext(
                ResolvedApplyPlan plan, String phase, Throwable cause) {
            if (plan.changes().isEmpty()) {
                throw new IllegalStateException("card failure requires an accepted apply card", cause);
            }
            ResolvedApplyPlan.Change change = plan.changes().get(plan.changes().size() - 1);
            cause.addSuppressed(ApplyFailureException.contextOnly(
                    phase, change.changeIndex(), change.operation().name(), change.context()));
        }

    private SourceSnapshot<JmxTestPlan> sourceSnapshot() throws Exception {
        try {
            return LocalJMeterWorkerMutations.sourceSnapshot(
                    request.source(), request.jmeterHome());
        } catch (IOException exception) {
            invalidateSource();
            throw request.sessionRegistry() == null ? exception : ReferenceFailure.notFound();
        }
    }

    private SourceSnapshot<JmxTestPlan> candidateSnapshot() throws Exception {
        try {
            return LocalJMeterWorkerMutations.sourceSnapshot(
                    candidate, request.jmeterHome());
        } catch (IOException exception) {
            if (request.sessionRegistry() != null) {
                throw ReferenceFailure.reconciliationFailed(exception);
            }
            throw exception;
        }
    }

    private void requireRegistryStatus(
            SessionReferenceRegistry registry,
            DocumentIdentity sourceIdentity,
            String fingerprint) throws ReferenceFailure {
        if (registry == null) {
            return;
        }
        SessionReferenceRegistry.DocumentStatus status =
                registry.documentStatus(sourceIdentity, fingerprint);
        if (status == SessionReferenceRegistry.DocumentStatus.MISMATCH) {
            LocalJMeterWorkerMutations.invalidate(registry, sourceIdentity);
            throw ReferenceFailure.notFound();
        }
    }

    private LocalMutationState.Bound bind(
            DocumentIdentity sourceIdentity,
            DocumentIdentity targetIdentity,
            SourceSnapshot<JmxTestPlan> sourceSnapshot,
            boolean copy) throws ReferenceFailure {
        SessionReferenceRegistry registry = request.sessionRegistry();
        if (registry == null) {
            return new LocalMutationState.Bound(
                    new SnapshotReferenceSpace().bind(sourceSnapshot.parsed()), null);
        }
        prepared = copy
                ? registry.prepareCopy(sourceIdentity, targetIdentity)
                : registry.prepare(sourceIdentity);
        SessionReferenceSpace space = new SessionReferenceSpace(
                prepared, sourceIdentity, sourceSnapshot);
        BoundReferences references = space.bind(sourceSnapshot.parsed());
        try {
            SessionReferenceSpace.requireRetainedRecords(references);
        } catch (SessionReferenceSpace.RetainedReferenceProofException exception) {
            LocalJMeterWorkerMutations.invalidate(registry, sourceIdentity);
            throw ReferenceFailure.notFound();
        }
        return new LocalMutationState.Bound(references, space);
    }

        private MutationOutcome mutate(
                JmxTestPlan testPlan, ResolvedApplyPlan resolvedPlan) throws Exception {
            return new LocalMutationProgram(request.jmeterHome())
                    .apply(testPlan, resolvedPlan);
        }

    private LocalMutationState.Commit prepareCommit(
            LocalMutationState.Bound bound,
            PreparedReferenceState identityProposal,
            SourceSnapshot<JmxTestPlan> candidateSnapshot,
            MutationOutcome outcome,
            boolean copy,
            SnapshotCommitProposal snapshotProposal) {
        if (request.sessionRegistry() == null) {
            ExactBoundReferences candidateReferences = (ExactBoundReferences)
                    new SnapshotReferenceSpace().bind(candidateSnapshot.parsed());
            List<MutationResult.CreatedReference> created =
                    new ArrayList<MutationResult.CreatedReference>();
            for (SnapshotCreatedAlias alias : snapshotProposal.created) {
                String reference = candidateReferences.expose(alias.address, alias.expectedClass);
                created.add(new MutationResult.CreatedReference(alias.alias, reference));
            }
            return new LocalMutationState.Commit(
                    Collections.unmodifiableList(created), snapshotProposal.deleted, null);
        }
        SessionApplyReceipt receipt;
        if (copy) {
            SessionReferenceSpace targetSpace = new SessionReferenceSpace(
                    prepared,
                    identityUnchecked(request.target()),
                    candidateSnapshot);
            targetSpace.bind(candidateSnapshot.parsed());
            receipt = targetSpace.prepareCopyCommit(
                    identityProposal, outcome.appliedCount(), outcome.auxiliaryResults());
        } else {
            receipt = bound.sessionSpace().prepareApplyCommit(
                    identityProposal, candidateSnapshot.fingerprint(),
                    outcome.appliedCount(), outcome.auxiliaryResults());
        }
        SessionReferenceRegistry.PreparedPublication publication =
                request.sessionRegistry().preparePublication(
                        bound.sessionSpace().prepareSuccessfulUse());
        return new LocalMutationState.Commit(
                LocalMutationState.Commit.created(receipt.createdRefs()),
                receipt.deletedRefs(), publication);
    }

    private static SnapshotCommitProposal snapshotProposal(
            BoundReferences inputReferences,
            JmxTestPlan mutatedPlan,
            MutationOutcome outcome,
            boolean copy) {
        ExactBoundReferences mutatedReferences = (ExactBoundReferences)
                new SnapshotReferenceSpace().bind(mutatedPlan);
        List<SnapshotCreatedAlias> created = new ArrayList<SnapshotCreatedAlias>();
        for (MutationOutcome.CreatedNode node : outcome.createdNodes()) {
            TestElement element = element(node.handle());
            try {
                created.add(new SnapshotCreatedAlias(
                        node.alias(), mutatedReferences.expose(element), element.getClass().getName()));
            } catch (IllegalArgumentException deletedBeforePublication) {
                continue;
            }
        }
        List<String> deleted = new ArrayList<String>();
        if (!copy) {
            ExactBoundReferences exactInput = (ExactBoundReferences) inputReferences;
            for (io.github.thisccl.j4a.reference.ResolvedNodeHandle handle : outcome.deletedNodes()) {
                deleted.add(exactInput.expose(element(handle)));
            }
        }
        return new SnapshotCommitProposal(
                Collections.unmodifiableList(created), Collections.unmodifiableList(deleted));
    }

    private static List<MutationChangeResult> publishChanges(
            List<MutationChangeResult> changes,
            MutationChangeResult.Status status,
            List<MutationResult.CreatedReference> createdReferences) {
        Map<String, String> referencesByAlias = new LinkedHashMap<String, String>();
        for (MutationResult.CreatedReference created : createdReferences) {
            referencesByAlias.put(created.alias(), created.publicReference());
        }
        List<MutationChangeResult> published = new ArrayList<MutationChangeResult>(changes.size());
        for (MutationChangeResult change : changes) {
            String resultRef = "add".equals(change.operation()) && change.context().alias().isPresent()
                    ? referencesByAlias.get(change.context().alias().get()) : null;
            published.add(change.published(status, resultRef));
        }
        return Collections.unmodifiableList(published);
    }

    private static TestElement element(
            io.github.thisccl.j4a.reference.ResolvedNodeHandle handle) {
        if (!(handle instanceof ExactNodeHandle)) {
            throw new IllegalArgumentException("Resolved node handle is not owned by this reference module.");
        }
        return ((ExactNodeHandle) handle).element();
    }

    private static final class SnapshotCommitProposal {
        private final List<SnapshotCreatedAlias> created;
        private final List<String> deleted;

        private SnapshotCommitProposal(
                List<SnapshotCreatedAlias> created, List<String> deleted) {
            this.created = created;
            this.deleted = deleted;
        }
    }

    private static final class SnapshotCreatedAlias {
        private final String alias;
        private final String address;
        private final String expectedClass;

        private SnapshotCreatedAlias(String alias, String address, String expectedClass) {
            this.alias = alias;
            this.address = address;
            this.expectedClass = expectedClass;
        }
    }

    private DocumentIdentity identityUnchecked(Path target) {
        try {
            return DocumentIdentity.of(request.jmeterHome(), target);
        } catch (IOException exception) {
            throw new IllegalStateException("Target identity changed during transaction", exception);
        }
    }

    private void requireUnchangedSource(
            String expected,
            SessionReferenceRegistry registry,
            DocumentIdentity sourceIdentity) throws Exception {
        String actual;
        try {
            actual = SourceSnapshot.read(request.source(), ignored -> null).fingerprint();
        } catch (IOException exception) {
            if (registry != null) {
                LocalJMeterWorkerMutations.invalidate(registry, sourceIdentity);
                throw ReferenceFailure.notFound();
            }
            throw exception;
        }
        if (!expected.equals(actual)) {
            if (registry != null) {
                LocalJMeterWorkerMutations.invalidate(registry, sourceIdentity);
                throw ReferenceFailure.notFound();
            }
            throw new IOException("Source changed before mutation commit: " + request.source());
        }
    }

    private void invalidateSource() throws Exception {
        if (request.sessionRegistry() == null) {
            return;
        }
        LocalJMeterWorkerMutations.invalidate(
                request.sessionRegistry(),
                DocumentIdentity.of(request.jmeterHome(), request.source()));
    }

    private void cleanup(Throwable primaryFailure) throws IOException {
        try {
            if (candidate != null) {
                Files.deleteIfExists(candidate);
            }
        } catch (IOException | SecurityException cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            } else if (cleanupFailure instanceof IOException) {
                throw (IOException) cleanupFailure;
            } else {
                throw (SecurityException) cleanupFailure;
            }
        } finally {
            if (prepared != null && !published) {
                request.sessionRegistry().discard(prepared);
            }
        }
    }

        private static ResolvedApplyPlan compile(
            io.github.thisccl.j4a.apply.ApplyPatch patch,
            BoundReferences references,
            boolean session) throws Exception {
            try {
                return new ApplyPatchCompiler().compile(patch, references);
            } catch (ApplyPatchCompiler.CompilationException exception) {
                Throwable cause = exception;
                if (exception.reason() == ApplyPatchCompiler.Reason.REFERENCE_UNAVAILABLE) {
                    if (session) {
                        cause = ReferenceFailure.notFound();
                    } else {
                        cause = new LocatorNotFoundException(exception.expression());
                    }
                }
                io.github.thisccl.j4a.apply.ApplyPatch.Operation operation =
                        patch.changes().get(exception.changeIndex()).operation();
                cause.addSuppressed(ApplyFailureException.contextOnly(
                        exception.reason() == ApplyPatchCompiler.Reason.REFERENCE_UNAVAILABLE
                                ? "locator" : "schema",
                        exception.changeIndex(), exception.operation(),
                        MutationChangeContext.from(operation)));
                if (cause instanceof Exception) throw (Exception) cause;
                throw exception;
            }
        }
    }

}
