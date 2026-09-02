package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.jmx.JmxTestPlan;
import org.apache.jmeter.testelement.TestElement;

final class LocalCandidatePreservation {
    private LocalCandidatePreservation() {
    }

    static void requirePreserved(JmxTestPlan reloaded, MutationImpact impact) {
        for (MutationImpact.AffectedElement affected : impact.affectedElements()) {
            TestElement actual;
            try {
                actual = LocalWorkerTreeAddress.resolve(reloaded, affected.address());
            } catch (IllegalArgumentException exception) {
                throw failure(affected, "address missing after SaveService reload", exception);
            }
            if (affected.receipt() != null) {
                try {
                    impact.propertyGraph().project(actual, affected.receipt());
                } catch (RuntimeException exception) {
                    throw failure(affected, exception.getMessage(), exception);
                }
            }
            MutationImpact.ElementSignature actualSignature = MutationImpact.ElementSignature.of(
                    actual, affected.projectedProperties(), impact.identityResolver());
            if (!affected.signature().matches(actualSignature)) {
                throw failure(affected, "projection mismatch. Expected: " + affected.signature()
                        + ". Actual: " + actualSignature, null);
            }
        }
    }

    private static CandidatePreservationException failure(
            MutationImpact.AffectedElement affected, String detail, Throwable cause) {
        return new CandidatePreservationException("Candidate preservation failed. Phase: project. Address: "
                + affected.address() + ". Component: " + affected.signature().componentIdentity()
                + ". Parent address: " + parentAddress(affected.address()) + ". " + detail, cause);
    }

    private static java.util.List<Integer> parentAddress(java.util.List<Integer> address) {
        return address.size() <= 1 ? java.util.Collections.<Integer>emptyList()
                : address.subList(0, address.size() - 1);
    }
}
