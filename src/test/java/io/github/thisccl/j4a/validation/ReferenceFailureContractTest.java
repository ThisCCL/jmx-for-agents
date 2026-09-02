package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReferenceFailureContractTest {
    @Test
    void stableReferenceFailuresHaveDistinctCategoriesAndRecoveryActions() {
        ReferenceFailure notFound = ReferenceFailure.notFound();
        ReferenceFailure reconciliation = ReferenceFailure.reconciliationFailed(null);
        ReferenceFailure order = ReferenceFailure.orderUnproven(null);
        ReferenceFailure capacity = ReferenceFailure.capacityExceeded();

        assertThat(notFound.code()).isEqualTo("MCP_REF_NOT_FOUND");
        assertThat(notFound.category()).isEqualTo("usage");
        assertThat(notFound.suggestedAction()).contains("same file", "same JMeter home", "fresh ref");
        assertThat(reconciliation.code()).isEqualTo("MCP_REF_RECONCILIATION_FAILED");
        assertThat(reconciliation.category()).isEqualTo("runtime");
        assertThat(reconciliation.suggestedAction()).contains("inspect", "simplify").doesNotContain("read");
        assertThat(order.code()).isEqualTo("MCP_REF_ORDER_UNPROVEN");
        assertThat(order.category()).isEqualTo("runtime");
        assertThat(order.suggestedAction()).contains("conforming", "runtime/home", "new refs", "stateless CLI");
        assertThat(capacity.code()).isEqualTo("MCP_REF_CAPACITY_EXCEEDED");
        assertThat(capacity.category()).isEqualTo("runtime");
    }
}
