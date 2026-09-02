package io.github.thisccl.j4a.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.thisccl.j4a.components.ComponentCatalog.ComponentDefinition;
import io.github.thisccl.j4a.components.ComponentCatalogRenderer;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RemovedAddabilityApiContractTest {
    @Test
    void productionApiContainsOnlyRuntimeCatalogOperations() {
        Set<String> definitionMethods = methodNames(ComponentDefinition.class);
        Set<String> rendererMethods = methodNames(ComponentCatalogRenderer.class);
        Set<String> requestMethods = methodNames(LocalJMeterWorkerRequest.class);

        assertThat(definitionMethods).doesNotContain("status", "parentHints", "orderingHints", "failure");
        assertThat(rendererMethods).doesNotContain(
                "renderList", "renderCategory", "renderCategories", "renderComponent");
        assertThat(requestMethods).doesNotContain("probeAddability");
    }

    private static Set<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).map(Method::getName).collect(Collectors.toSet());
    }
}
