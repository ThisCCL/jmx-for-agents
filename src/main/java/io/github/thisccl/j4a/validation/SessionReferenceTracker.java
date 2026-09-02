package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.reference.ResolvedNodeHandle;

import io.github.thisccl.j4a.apply.MutationOutcome;
import io.github.thisccl.j4a.jmx.JmxTestPlan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jmeter.testelement.TestElement;

final class SessionReferenceTracker {
    private final SessionReferenceRegistry.PreparedState preparedState;
    private final DocumentIdentity documentIdentity;
    private final String fingerprint;
    private final SessionPlanIndex inputIndex;
    private final Map<TestElement, String> trackedReferences;
    private final boolean proofLost;

    SessionReferenceTracker(
            SessionReferenceRegistry.PreparedState preparedState,
            DocumentIdentity documentIdentity,
            String fingerprint,
            SessionPlanIndex inputIndex) {
        this.preparedState = preparedState;
        this.documentIdentity = documentIdentity;
        this.fingerprint = fingerprint;
        this.inputIndex = inputIndex;
        Map<TestElement, String> tracked = new IdentityHashMap<TestElement, String>();
        boolean lost = false;
        for (Map.Entry<String, SessionReferenceRegistry.ReferenceRecord> entry
                : preparedState.retainedRecords(documentIdentity, fingerprint).entrySet()) {
            SessionReferenceRegistry.ReferenceRecord record = entry.getValue();
            SessionPlanIndex.LocatedElement located = inputIndex.find(record.locator());
            if (located == null
                    || !located.element().getClass().getName().equals(record.expectedClass())
                    || tracked.put(located.element(), entry.getKey()) != null) {
                lost = true;
            }
        }
        this.trackedReferences = tracked;
        this.proofLost = lost;
    }

    void requireProven() {
        if (proofLost) {
            throw new SessionReferenceSpace.RetainedReferenceProofException();
        }
    }

    PreparedReferenceState reconcile(JmxTestPlan mutatedPlan, MutationOutcome outcome) {
        requireProven();
        SessionPlanIndex finalIndex = SessionPlanIndex.create(mutatedPlan);
        List<PreparedReferenceState.TrackedReference> survivors = new ArrayList<>();
        Set<TestElement> survivingIdentities =
                Collections.newSetFromMap(new IdentityHashMap<TestElement, Boolean>());
        for (SessionPlanIndex.LocatedElement located : finalIndex.orderedElements()) {
            survivingIdentities.add(located.element());
            String token = trackedReferences.get(located.element());
            if (token != null) {
                SessionReferenceRegistry.ReferenceRecord record =
                        preparedState.resolveRecord(documentIdentity, fingerprint, token);
                survivors.add(PreparedReferenceState.TrackedReference.of(
                        token, located.locator(), record.expectedClass(), ExactNodeHandle.of(located.element())));
            }
        }
        List<String> deleted = new ArrayList<>();
        for (SessionPlanIndex.LocatedElement input : inputIndex.orderedElements()) {
            String token = trackedReferences.get(input.element());
            if (token != null && !survivingIdentities.contains(input.element())) {
                deleted.add(token);
            }
        }
        List<PreparedReferenceState.CreatedAlias> aliases = new ArrayList<>();
        for (MutationOutcome.CreatedNode created : outcome.createdNodes()) {
            SessionPlanIndex.LocatedElement located = finalIndex.find(ExactNodeHandles.element(created.handle()));
            if (located != null) {
                aliases.add(PreparedReferenceState.CreatedAlias.of(
                        created.alias(), located.locator(), located.element().getClass().getName(), created.handle()));
            }
        }
        return PreparedReferenceState.tracking(survivors, deleted, aliases);
    }
}
