package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PropertyGraphInventoryGeneratorTest {
    @Test
    void currentRuntimeInventoryIsClassifiedAndRoundTrips() throws Exception {
        PropertyGraphInventorySupport.Comparison result = PropertyGraphInventorySupport.generateAndValidate();

        assertThat(result.unclassified).isZero();
        assertThat(result.persistedUserWithProof).isEqualTo(result.persistedUserTotal);
        assertThat(result.systemOwned).isGreaterThan(0);
        System.out.println("TODO15_COUNTS registry=" + result.registryEntries
                + " materialized=" + result.materializedEntries
                + " materialization_failed=" + result.materializationFailed
                + " observed_rows=" + result.observedRows
                + " persisted_user=" + result.persistedUserTotal
                + " persisted_proof=" + result.persistedUserWithProof
                + " system_owned=" + result.systemOwned
                + " transient_runtime_only=" + result.transientRuntimeOnly
                + " unclassified=" + result.unclassified);
    }
}
